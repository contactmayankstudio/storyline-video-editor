const fs = require("fs");
const path = require("path");
const { spawnSync } = require("child_process");

const ROOT = process.cwd();
const GITHUB_MODELS_API_URL = "https://models.github.ai/inference/chat/completions";
const DEFAULT_MODEL = "openai/gpt-4.1-mini";
const DEFAULT_BUILD_COMMAND = "cd android && chmod +x gradlew && ./gradlew assembleDebug --console=plain";
const MAX_FILE_SIZE = 120000;

const AUTOMATION_PROMPT = requiredEnv("AUTOMATION_PROMPT");
const AUTOMATION_GITHUB_MODELS_TOKEN = optionalEnv("AUTOMATION_GITHUB_MODELS_TOKEN");
const AUTOMATION_GITHUB_MODELS_MODEL = optionalEnv("AUTOMATION_GITHUB_MODELS_MODEL") || DEFAULT_MODEL;
const AUTOMATION_PUSH_TOKEN = optionalEnv("AUTOMATION_PUSH_TOKEN");
const AUTOMATION_TARGET_BRANCH = optionalEnv("AUTOMATION_TARGET_BRANCH") || "main";
const AUTOMATION_BUILD_COMMAND = optionalEnv("AUTOMATION_BUILD_COMMAND") || DEFAULT_BUILD_COMMAND;
const AUTOMATION_MAX_ATTEMPTS = Math.max(1, Number.parseInt(optionalEnv("AUTOMATION_MAX_ATTEMPTS") || "2", 10));
const AUTOMATION_MAX_FILES = Math.max(1, Math.min(6, Number.parseInt(optionalEnv("AUTOMATION_MAX_FILES") || "4", 10)));
const GITHUB_REPOSITORY = requiredEnv("GITHUB_REPOSITORY");
const STEP_SUMMARY_PATH = optionalEnv("GITHUB_STEP_SUMMARY");

const RELEVANT_EXTENSIONS = /\.(js|cjs|mjs|json|html|css|md|kt|kts|gradle|xml|cpp|c|h|hpp|java|properties|yml|yaml|txt|sh)$/i;
const EXCLUDED_PREFIXES = [
    ".git/",
    ".gradle/",
    ".idea/",
    ".vercel/",
    "android/.gradle/",
    "android/.idea/",
    "android/app/build/",
    "build/",
    "build_check/",
    "build_health/",
    "build_verify/",
    "crash-fix-server/node_modules/",
    "apk-host/public/",
    "docs/",
];

function requiredEnv(name) {
    const value = process.env[name];
    if (!value) {
        throw new Error(`Missing required environment variable ${name}`);
    }
    return value.trim();
}

function optionalEnv(name) {
    const value = process.env[name];
    return typeof value === "string" ? value.trim() : "";
}

function log(message) {
    console.log(`[automation] ${message}`);
}

function addSummary(text) {
    if (!STEP_SUMMARY_PATH) {
        return;
    }
    fs.appendFileSync(STEP_SUMMARY_PATH, `${text}\n`, "utf8");
}

function shellQuote(value) {
    return `'${String(value).replace(/'/g, `'\"'\"'`)}'`;
}

function stripCodeFences(text) {
    const trimmed = text.trim();
    const fenced = trimmed.match(/^```[a-zA-Z0-9_-]*\n([\s\S]*?)\n```$/);
    return fenced ? fenced[1] : trimmed;
}

function extractJson(text) {
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) {
        throw new Error(`Model returned non-JSON content:\n${text.slice(0, 1200)}`);
    }
    return JSON.parse(match[0]);
}

function run(command, options = {}) {
    const result = spawnSync("bash", ["-lc", command], {
        cwd: options.cwd || ROOT,
        encoding: "utf8",
        maxBuffer: options.maxBuffer || 64 * 1024 * 1024,
        timeout: options.timeoutMs || 25 * 60 * 1000,
        env: {
            ...process.env,
            FORCE_COLOR: "0",
        },
    });

    return {
        code: result.status ?? 1,
        stdout: result.stdout || "",
        stderr: result.stderr || "",
    };
}

function git(command, options = {}) {
    const result = run(`git ${command}`, options);
    if (result.code !== 0 && !options.allowFailure) {
        throw new Error(`git ${command} failed\n${result.stdout}\n${result.stderr}`);
    }
    return result;
}

function shouldIncludeFile(filePath) {
    if (!RELEVANT_EXTENSIONS.test(filePath)) {
        return false;
    }
    return !EXCLUDED_PREFIXES.some((prefix) => filePath.startsWith(prefix));
}

function listCandidateFiles() {
    const tracked = git("ls-files").stdout.trim().split("\n").filter(Boolean);
    return tracked
        .filter(shouldIncludeFile)
        .map((filePath) => {
            const absolutePath = path.join(ROOT, filePath);
            const stat = fs.statSync(absolutePath);
            return {
                path: filePath,
                size: stat.size,
            };
        })
        .filter((file) => file.size <= MAX_FILE_SIZE);
}

function readFile(filePath) {
    return fs.existsSync(filePath) ? fs.readFileSync(filePath, "utf8") : "";
}

async function callGitHubModel(messages) {
    if (!AUTOMATION_GITHUB_MODELS_TOKEN) {
        throw new Error("AUTOMATION_GITHUB_MODELS_TOKEN is not configured");
    }

    const response = await fetch(GITHUB_MODELS_API_URL, {
        method: "POST",
        headers: {
            Accept: "application/vnd.github+json",
            Authorization: `Bearer ${AUTOMATION_GITHUB_MODELS_TOKEN}`,
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2026-03-10",
        },
        body: JSON.stringify({
            model: AUTOMATION_GITHUB_MODELS_MODEL,
            temperature: 0.2,
            messages,
        }),
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`GitHub Models API ${response.status}: ${text}`);
    }

    const payload = await response.json();
    const text = payload.choices?.[0]?.message?.content?.trim() || "";

    if (!text) {
        throw new Error("GitHub Models returned empty output");
    }

    return text;
}

async function requestPlan(repoMap) {
    const prompt = [
        "You are an autonomous senior engineer working inside the Storyline repository.",
        "Pick the smallest realistic change set that advances the user's request.",
        `Choose at most ${AUTOMATION_MAX_FILES} files.`,
        "Return only valid JSON in this shape:",
        "{",
        '  "summary": "one sentence",',
        '  "commitMessage": "short git commit message",',
        '  "files": [{"path":"relative/path","action":"edit|create","reason":"why this file"}],',
        '  "successCriteria": ["criterion 1", "criterion 2"],',
        '  "notes": ["note 1", "note 2"]',
        "}",
        "",
        "Rules:",
        "- Only choose files from the repository map unless creating a clearly necessary new text file.",
        "- Prefer focused code changes over broad rewrites.",
        "- Do not choose generated files, build outputs, or docs-only files unless the task is explicitly documentation.",
        "- If the task is too large, choose the safest first milestone instead of pretending to finish everything.",
        "",
        "User request:",
        AUTOMATION_PROMPT,
        "",
        "Repository map:",
        repoMap,
    ].join("\n");

    const response = await callGitHubModel([
        {
            role: "system",
            content: "Return only JSON. No markdown fences. No prose before or after the JSON.",
        },
        {
            role: "user",
            content: prompt,
        },
    ]);

    const plan = extractJson(response);
    if (!Array.isArray(plan.files) || !plan.files.length) {
        throw new Error("Automation plan returned no files to edit");
    }
    return {
        summary: String(plan.summary || "AI automation change"),
        commitMessage: String(plan.commitMessage || "Automated storyline update"),
        files: plan.files.slice(0, AUTOMATION_MAX_FILES).map((file) => ({
            path: String(file.path || "").trim(),
            action: String(file.action || "edit").trim(),
            reason: String(file.reason || "").trim(),
        })).filter((file) => file.path),
        successCriteria: Array.isArray(plan.successCriteria) ? plan.successCriteria.map(String) : [],
        notes: Array.isArray(plan.notes) ? plan.notes.map(String) : [],
    };
}

async function requestFileContent({ filePath, fileReason, attempt, buildFailureContext }) {
    const absolutePath = path.join(ROOT, filePath);
    const existing = readFile(absolutePath);
    const prompt = [
        "You are editing exactly one file in the Storyline repository.",
        "Return the full final file contents only.",
        "Do not use markdown fences.",
        "",
        `Task: ${AUTOMATION_PROMPT}`,
        `File: ${filePath}`,
        `Reason: ${fileReason || "Selected by plan"}`,
        `Attempt: ${attempt}`,
        "",
        buildFailureContext ? `Build failure context:\n${buildFailureContext}\n` : "",
        existing
            ? `Existing file contents:\n${existing}`
            : "This file does not exist yet. Create it from scratch if needed.",
    ].join("\n");

    const response = await callGitHubModel([
        {
            role: "system",
            content: "Return only the raw file contents for the requested file.",
        },
        {
            role: "user",
            content: prompt,
        },
    ]);

    return stripCodeFences(response);
}

async function requestFixPlan(changedFiles, buildFailureContext) {
    const prompt = [
        "A local Android build failed after automated edits.",
        `Choose up to ${AUTOMATION_MAX_FILES} files from this changed-file list to correct.`,
        "Return only valid JSON in this shape:",
        "{",
        '  "summary": "one sentence",',
        '  "files": [{"path":"relative/path","reason":"why this file should change"}]',
        "}",
        "",
        `Original task: ${AUTOMATION_PROMPT}`,
        "",
        `Changed files: ${changedFiles.join(", ")}`,
        "",
        `Build failure excerpt:\n${buildFailureContext}`,
    ].join("\n");

    const response = await callGitHubModel([
        {
            role: "system",
            content: "Return only JSON. No markdown fences.",
        },
        {
            role: "user",
            content: prompt,
        },
    ]);

    const plan = extractJson(response);
    return {
        summary: String(plan.summary || "Fix build failure"),
        files: Array.isArray(plan.files)
            ? plan.files.slice(0, AUTOMATION_MAX_FILES).map((file) => ({
                path: String(file.path || "").trim(),
                reason: String(file.reason || "").trim(),
            })).filter((file) => file.path)
            : [],
    };
}

function ensureParentDirectory(filePath) {
    fs.mkdirSync(path.dirname(filePath), { recursive: true });
}

function writeFile(filePath, contents) {
    ensureParentDirectory(filePath);
    fs.writeFileSync(filePath, contents, "utf8");
}

function currentChangedFiles() {
    return git("diff --name-only").stdout.trim().split("\n").filter(Boolean);
}

function buildFailureExcerpt(output) {
    const trimmed = output.trim();
    return trimmed.length > 16000 ? trimmed.slice(-16000) : trimmed;
}

function configureGitIdentity() {
    git('config user.name "storyline-automation[bot]"');
    git('config user.email "storyline-automation[bot]@users.noreply.github.com"');
}

function pushChanges(commitMessage) {
    if (!AUTOMATION_PUSH_TOKEN) {
        throw new Error("AUTOMATION_PUSH_TOKEN is not configured");
    }

    const changedFiles = currentChangedFiles();
    if (!changedFiles.length) {
        log("No file changes detected after automation run");
        return null;
    }

    configureGitIdentity();
    git(`remote set-url origin https://x-access-token:${AUTOMATION_PUSH_TOKEN}@github.com/${GITHUB_REPOSITORY}.git`);
    git(`add ${changedFiles.map(shellQuote).join(" ")}`);
    const commitResult = git(`commit -m ${shellQuote(commitMessage)}`, { allowFailure: true });

    if (commitResult.code !== 0) {
        const status = git("status --short").stdout.trim();
        if (!status) {
            return null;
        }
        throw new Error(`git commit failed\n${commitResult.stdout}\n${commitResult.stderr}`);
    }

    git(`push origin HEAD:${AUTOMATION_TARGET_BRANCH}`);
    return git("rev-parse HEAD").stdout.trim();
}

async function main() {
    addSummary("## Storyline Automation");
    addSummary(`Task: ${AUTOMATION_PROMPT}`);
    addSummary(`Model: ${AUTOMATION_GITHUB_MODELS_MODEL}`);

    const candidateFiles = listCandidateFiles();
    const repoMap = candidateFiles
        .slice(0, 500)
        .map((file) => `${file.path} (${file.size} bytes)`)
        .join("\n");

    if (!repoMap) {
        throw new Error("No candidate files found for automation");
    }

    const plan = await requestPlan(repoMap);
    addSummary(`Plan: ${plan.summary}`);
    if (plan.notes.length) {
        addSummary("");
        addSummary("Notes:");
        for (const note of plan.notes) {
            addSummary(`- ${note}`);
        }
    }

    for (const file of plan.files) {
        const absolutePath = path.join(ROOT, file.path);
        const content = await requestFileContent({
            filePath: file.path,
            fileReason: file.reason,
            attempt: 1,
            buildFailureContext: "",
        });
        writeFile(absolutePath, content);
        log(`Updated ${file.path}`);
    }

    let lastBuildOutput = "";
    let buildSucceeded = false;

    for (let attempt = 1; attempt <= AUTOMATION_MAX_ATTEMPTS; attempt += 1) {
        log(`Running build attempt ${attempt}/${AUTOMATION_MAX_ATTEMPTS}`);
        const buildResult = run(AUTOMATION_BUILD_COMMAND, { timeoutMs: 35 * 60 * 1000 });
        lastBuildOutput = `${buildResult.stdout}\n${buildResult.stderr}`.trim();

        if (buildResult.code === 0) {
            buildSucceeded = true;
            addSummary("");
            addSummary(`Build: success on attempt ${attempt}`);
            break;
        }

        addSummary("");
        addSummary(`Build: failed on attempt ${attempt}`);

        if (attempt === AUTOMATION_MAX_ATTEMPTS) {
            break;
        }

        const changedFiles = currentChangedFiles();
        if (!changedFiles.length) {
            break;
        }

        const failureContext = buildFailureExcerpt(lastBuildOutput);
        const fixPlan = await requestFixPlan(changedFiles, failureContext);

        if (!fixPlan.files.length) {
            break;
        }

        for (const file of fixPlan.files) {
            const absolutePath = path.join(ROOT, file.path);
            const content = await requestFileContent({
                filePath: file.path,
                fileReason: file.reason,
                attempt: attempt + 1,
                buildFailureContext: failureContext,
            });
            writeFile(absolutePath, content);
            log(`Revised ${file.path} after failed build`);
        }
    }

    if (!buildSucceeded) {
        addSummary("");
        addSummary("Build failure excerpt:");
        addSummary("```text");
        addSummary(buildFailureExcerpt(lastBuildOutput));
        addSummary("```");
        throw new Error("Automation build did not pass");
    }

    const pushedSha = pushChanges(plan.commitMessage);
    if (pushedSha) {
        addSummary("");
        addSummary(`Pushed commit: ${pushedSha}`);
    } else {
        addSummary("");
        addSummary("No commit was created because no repository changes remained.");
    }
}

main().catch((error) => {
    addSummary("");
    addSummary(`Error: ${error.message}`);
    console.error(error);
    process.exit(1);
});
