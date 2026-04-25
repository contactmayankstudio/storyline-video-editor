const {
    dispatchWorkflow,
    getConfig: getGitHubConfig,
    getLatestRunSummary,
    getRepoFile,
    upsertRepoFile,
} = require("./_lib/github");
const { generateTextWithFallback } = require("./_lib/ai");
const { getCodebaseContext } = require("./_lib/codebase");
const { deviceBridgeRequest } = require("./_lib/device-bridge");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

function sanitizeMessages(messages) {
    if (!Array.isArray(messages)) {
        return [];
    }

    return messages
        .filter((message) => message && typeof message.content === "string")
        .map((message) => ({
            role: typeof message.role === "string" ? message.role : "user",
            content: message.content.trim(),
        }))
        .filter((message) => message.content)
        .slice(-12);
}

function buildPrompt(messages, build, codebaseContext) {
    const conversation = messages
        .map((message) => `${message.role.toUpperCase()}: ${message.content}`)
        .join("\n\n");

    return [
        "You are Storyline Control Copilot.",
        "You help with Android video-editor development, build debugging, feature design, deployment, and practical next steps.",
        "Answer in plain text, concise but useful.",
        "Prefer concrete actions over vague theory.",
        "If asked about CLI integration, explain whether it should run in browser, serverless, or a separate bridge service.",
        "",
        "Latest build context:",
        JSON.stringify(build || {}, null, 2),
        "",
        "Relevant codebase context:",
        JSON.stringify(codebaseContext || {}, null, 2),
        "",
        "Use the codebase context whenever the user is asking about bugs, architecture, automation, playback, import/export, Firebase, GitHub workflows, admin panel behavior, or MCP integration.",
        "Mention concrete file paths when they are relevant.",
        "",
        "Conversation:",
        conversation || "USER: Hello",
        "",
        "Reply as the assistant to the latest user message only.",
    ].join("\n");
}

function parseDirectCommand(message) {
    const raw = String(message || "").trim();
    if (!raw.startsWith("/")) {
        return null;
    }

    const [firstLine, ...restLines] = raw.split("\n");
    const [command, ...args] = firstLine.trim().split(/\s+/);
    const rest = restLines.join("\n").trim();

    return {
        command,
        args,
        rest,
        raw,
    };
}

async function handleDirectCommand(message) {
    const parsed = parseDirectCommand(message);
    if (!parsed) {
        return null;
    }

    if (parsed.command === "/read") {
        const filePath = parsed.args.join(" ").trim();
        if (!filePath) {
            throw new Error("Usage: /read path/to/file");
        }
        const file = await getRepoFile(filePath);
        if (!file) {
            throw new Error(`File not found: ${filePath}`);
        }
        return {
            reply: [
                `Read ${file.path}`,
                "",
                "```",
                file.content,
                "```",
            ].join("\n"),
            provider: "direct-control",
            providerLabel: "Direct Control",
            model: "repo-read",
            codebaseFiles: [file.path],
        };
    }

    if (parsed.command === "/write") {
        const filePath = parsed.args.join(" ").trim();
        if (!filePath || !parsed.rest) {
            throw new Error("Usage: /write path/to/file followed by the new file content");
        }
        const commitMessage =
            `Admin chat update ${filePath}`;
        await upsertRepoFile(filePath, parsed.rest, { message: commitMessage });
        return {
            reply: `Updated \`${filePath}\` directly on \`${getGitHubConfig().branch}\`.`,
            provider: "direct-control",
            providerLabel: "Direct Control",
            model: "repo-write",
            codebaseFiles: [filePath],
        };
    }

    if (parsed.command === "/automation") {
        const prompt = [parsed.args.join(" "), parsed.rest].filter(Boolean).join("\n").trim();
        if (!prompt) {
            throw new Error("Usage: /automation your prompt here");
        }
        const github = getGitHubConfig();
        await dispatchWorkflow(github.automationWorkflowName, {
            ref: github.branch,
            inputs: {
                prompt,
                target_branch: github.branch,
                max_attempts: "2",
            },
        });
        const latestRun = await getLatestRunSummary(undefined, github.automationWorkflowName).catch(() => null);
        return {
            reply: [
                "Cloud automation dispatched.",
                latestRun?.url ? `Run: ${latestRun.url}` : "",
            ].filter(Boolean).join("\n"),
            provider: "direct-control",
            providerLabel: "Direct Control",
            model: "automation-dispatch",
            codebaseFiles: [],
        };
    }

    if (parsed.command === "/adb-snapshot") {
        const data = await deviceBridgeRequest("/runtime", {
            method: "POST",
            body: {},
        });
        return {
            reply: [
                "Device runtime snapshot:",
                "```json",
                JSON.stringify(data, null, 2),
                "```",
            ].join("\n"),
            provider: "direct-control",
            providerLabel: "Direct Control",
            model: "device-runtime",
            codebaseFiles: [],
        };
    }

    if (parsed.command === "/adb-log") {
        const lines = Math.max(20, Math.min(2000, Number(parsed.args[0] || 200)));
        const data = await deviceBridgeRequest("/logcat", {
            method: "POST",
            body: { lines },
        });
        return {
            reply: [
                `Recent adb logcat (${lines} lines):`,
                "```",
                data.logcat || "",
                "```",
            ].join("\n"),
            provider: "direct-control",
            providerLabel: "Direct Control",
            model: "device-logcat",
            codebaseFiles: [],
        };
    }

    throw new Error(`Unknown direct command: ${parsed.command}`);
}

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        await requireAdmin(req);
        const { messages, providerPreference } = req.body || {};
        const sanitizedMessages = sanitizeMessages(messages);

        if (!sanitizedMessages.length) {
            return res.status(400).json({ error: "messages are required" });
        }

        const latestBuild = await getLatestRunSummary().catch(() => null);
        const latestUserMessage = sanitizedMessages[sanitizedMessages.length - 1]?.content || "";
        const directResult = await handleDirectCommand(latestUserMessage);
        if (directResult) {
            return res.status(200).json(directResult);
        }
        const codebaseContext = getCodebaseContext(latestUserMessage, { maxEntries: 5 });
        let deviceContext = null;
        if (/\badb\b|\blogcat\b|\bdevice\b|\bphone\b|\bruntime\b/i.test(latestUserMessage)) {
            deviceContext = await deviceBridgeRequest("/runtime", {
                method: "POST",
                body: {},
            }).catch(() => null);
        }
        const prompt = buildPrompt(sanitizedMessages, latestBuild, codebaseContext);
        const finalPrompt = deviceContext
            ? `${prompt}\n\nLive device context:\n${JSON.stringify(deviceContext, null, 2)}`
            : prompt;
        const aiResponse = await generateTextWithFallback(finalPrompt, {
            preferredProvider: providerPreference,
        });

        return res.status(200).json({
            reply: aiResponse.result,
            provider: aiResponse.provider,
            providerLabel: aiResponse.providerLabel,
            model: aiResponse.model,
            attempts: aiResponse.attempts,
            codebaseFiles: codebaseContext.selectedFiles.map((file) => file.path),
            deviceContextAttached: Boolean(deviceContext),
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
