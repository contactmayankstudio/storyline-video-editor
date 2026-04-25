import { readFileSync } from "node:fs";
import process from "node:process";
import { execFile } from "node:child_process";
import { promisify } from "node:util";

import { cert, getApps, initializeApp, applicationDefault } from "firebase-admin/app";
import { Timestamp, getFirestore } from "firebase-admin/firestore";
import { Octokit } from "@octokit/rest";
import { McpServer, ResourceTemplate } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { z } from "zod";

const execFileAsync = promisify(execFile);
const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || "";
const FIREBASE_SERVICE_ACCOUNT_PATH = process.env.FIREBASE_SERVICE_ACCOUNT_PATH || "";
const FIREBASE_SERVICE_ACCOUNT_JSON = process.env.FIREBASE_SERVICE_ACCOUNT_JSON || "";
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || "";
const GITHUB_OWNER = process.env.GITHUB_OWNER || "";
const GITHUB_REPO = process.env.GITHUB_REPO || "";
const ADB_PATH = process.env.ADB_PATH || "adb";
const STORYLINE_APP_ID =
  process.env.STORYLINE_APP_ID || "com.storyline.app/com.video.engine.MainActivity";

function toPlainValue(value) {
  if (value instanceof Timestamp) {
    return value.toDate().toISOString();
  }
  if (Array.isArray(value)) {
    return value.map(toPlainValue);
  }
  if (value && typeof value === "object") {
    if (typeof value.path === "string" && typeof value.id === "string") {
      return { path: value.path, id: value.id };
    }
    return Object.fromEntries(
      Object.entries(value).map(([key, entry]) => [key, toPlainValue(entry)]),
    );
  }
  return value;
}

function toDocObject(doc) {
  return {
    id: doc.id,
    ...toPlainValue(doc.data()),
  };
}

function sortByRecent(items, fieldNames) {
  const fields = Array.isArray(fieldNames) ? fieldNames : [fieldNames];
  return [...items].sort((left, right) => {
    const leftValue = fields
      .map((field) => left?.[field])
      .find((value) => typeof value === "number" || typeof value === "string") ?? 0;
    const rightValue = fields
      .map((field) => right?.[field])
      .find((value) => typeof value === "number" || typeof value === "string") ?? 0;
    return String(rightValue).localeCompare(String(leftValue), undefined, { numeric: true });
  });
}

function textResult(payload) {
  return {
    content: [
      {
        type: "text",
        text: typeof payload === "string" ? payload : JSON.stringify(payload, null, 2),
      },
    ],
    structuredContent: typeof payload === "string" ? { message: payload } : payload,
  };
}

function errorResult(error) {
  const message = error instanceof Error ? error.message : String(error);
  return {
    content: [{ type: "text", text: `Error: ${message}` }],
    isError: true,
  };
}

function parseJson(value, fallback) {
  if (!value) return fallback;
  try {
    return JSON.parse(value);
  } catch {
    return fallback;
  }
}

function ensureFirebaseConfigured() {
  if (!FIREBASE_PROJECT_ID) {
    throw new Error("Missing FIREBASE_PROJECT_ID");
  }
}

function getFirebaseApp() {
  ensureFirebaseConfigured();
  const existing = getApps()[0];
  if (existing) return existing;

  const options = { projectId: FIREBASE_PROJECT_ID };
  if (FIREBASE_SERVICE_ACCOUNT_JSON) {
    return initializeApp({
      ...options,
      credential: cert(parseJson(FIREBASE_SERVICE_ACCOUNT_JSON, {})),
    });
  }
  if (FIREBASE_SERVICE_ACCOUNT_PATH) {
    return initializeApp({
      ...options,
      credential: cert(JSON.parse(readFileSync(FIREBASE_SERVICE_ACCOUNT_PATH, "utf8"))),
    });
  }
  return initializeApp({
    ...options,
    credential: applicationDefault(),
  });
}

function getDb() {
  return getFirestore(getFirebaseApp());
}

function getGitHub() {
  if (!GITHUB_TOKEN || !GITHUB_OWNER || !GITHUB_REPO) {
    throw new Error("Missing GITHUB_TOKEN, GITHUB_OWNER, or GITHUB_REPO");
  }
  return new Octokit({ auth: GITHUB_TOKEN });
}

async function runAdb(args, serial = "") {
  const finalArgs = [];
  if (serial) {
    finalArgs.push("-s", serial);
  }
  finalArgs.push(...args);
  const { stdout, stderr } = await execFileAsync(ADB_PATH, finalArgs, {
    maxBuffer: 8 * 1024 * 1024,
  });
  return { stdout: stdout.trim(), stderr: stderr.trim() };
}

async function listAdbDevices() {
  const { stdout } = await runAdb(["devices", "-l"]);
  return stdout
    .split("\n")
    .slice(1)
    .map((line) => line.trim())
    .filter(Boolean)
    .map((line) => {
      const [serial, state, ...rest] = line.split(/\s+/);
      return {
        serial,
        state,
        details: rest.join(" "),
      };
    });
}

async function getDefaultSerial() {
  const devices = await listAdbDevices();
  return devices.find((device) => device.state === "device")?.serial || "";
}

async function getRuntimeSnapshot(serial = "") {
  const resolvedSerial = serial || (await getDefaultSerial());
  if (!resolvedSerial) {
    throw new Error("No connected adb device found");
  }

  const [activities, windows, packageInfo] = await Promise.all([
    runAdb(["shell", "dumpsys", "activity", "activities"], resolvedSerial),
    runAdb(["shell", "dumpsys", "window", "windows"], resolvedSerial),
    runAdb(["shell", "dumpsys", "package", "com.storyline.app"], resolvedSerial),
  ]);

  const activityLines = activities.stdout
    .split("\n")
    .filter((line) => line.includes("com.storyline.app") || line.includes("ResumedActivity"))
    .slice(0, 40);
  const windowLines = windows.stdout
    .split("\n")
    .filter((line) => line.includes("mCurrentFocus") || line.includes("mFocusedApp"))
    .slice(0, 20);
  const packageLines = packageInfo.stdout
    .split("\n")
    .filter((line) => line.includes("versionName=") || line.includes("versionCode="))
    .slice(0, 10);

  return {
    serial: resolvedSerial,
    storylineAppId: STORYLINE_APP_ID,
    resumedActivityLines: activityLines,
    windowFocusLines: windowLines,
    packageInfoLines: packageLines,
  };
}

async function readStorylineLogcat(lines = 200, serial = "") {
  const resolvedSerial = serial || (await getDefaultSerial());
  if (!resolvedSerial) {
    throw new Error("No connected adb device found");
  }
  const { stdout } = await runAdb(["logcat", "-d", "-t", String(lines)], resolvedSerial);
  return stdout;
}

async function listOpsReports(limit = 10, status = "open") {
  const snapshot = await getDb().collection("ops_reports").get();
  let docs = snapshot.docs.map(toDocObject);
  if (status && status !== "any") {
    docs = docs.filter((doc) => doc.status === status);
  }
  return sortByRecent(docs, ["updatedAtMs", "createdAtMs", "triagedAt"]).slice(0, limit);
}

async function listLiveInstallations(limit = 10, maxAgeMinutes = 20) {
  const cutoffMs = Date.now() - maxAgeMinutes * 60 * 1000;
  const snapshot = await getDb().collection("ops_installations").get();
  return sortByRecent(snapshot.docs.map(toDocObject), ["updatedAtMs", "updatedAt"])
    .filter((doc) => Number(doc.updatedAtMs || 0) >= cutoffMs)
    .slice(0, limit);
}

async function getOpsReport(reportId) {
  const doc = await getDb().collection("ops_reports").doc(reportId).get();
  if (!doc.exists) {
    throw new Error(`ops_reports/${reportId} not found`);
  }
  return toDocObject(doc);
}

async function getLatestRuns(limit = 10, branch = "") {
  const octokit = getGitHub();
  const response = await octokit.actions.listWorkflowRunsForRepo({
    owner: GITHUB_OWNER,
    repo: GITHUB_REPO,
    per_page: limit,
    ...(branch ? { branch } : {}),
  });
  return response.data.workflow_runs.map((run) => ({
    id: run.id,
    name: run.name,
    workflow_id: run.workflow_id,
    status: run.status,
    conclusion: run.conclusion,
    head_branch: run.head_branch,
    head_sha: run.head_sha,
    event: run.event,
    html_url: run.html_url,
    created_at: run.created_at,
    updated_at: run.updated_at,
  }));
}

async function dispatchWorkflow(workflowId, ref, inputs = {}) {
  const octokit = getGitHub();
  await octokit.actions.createWorkflowDispatch({
    owner: GITHUB_OWNER,
    repo: GITHUB_REPO,
    workflow_id: workflowId,
    ref,
    inputs,
  });
  return {
    ok: true,
    workflowId,
    ref,
    inputs,
  };
}

async function createGitHubIssue(title, body, labels = []) {
  const octokit = getGitHub();
  const response = await octokit.issues.create({
    owner: GITHUB_OWNER,
    repo: GITHUB_REPO,
    title,
    body,
    labels,
  });
  return {
    number: response.data.number,
    title: response.data.title,
    url: response.data.html_url,
    state: response.data.state,
  };
}

async function triageOpsReport(reportId, extraLabels = []) {
  const octokit = getGitHub();
  const db = getDb();
  const report = await getOpsReport(reportId);
  const issueTitle = `[App Report] ${report.type || "manual"} ${report.currentScreen || "unknown"} ${reportId}`;
  const search = await octokit.search.issuesAndPullRequests({
    q: `repo:${GITHUB_OWNER}/${GITHUB_REPO} is:issue "${reportId}"`,
    per_page: 5,
  });
  const existing = search.data.items.find((item) => !item.pull_request);

  let issue;
  if (existing) {
    issue = {
      number: existing.number,
      title: existing.title,
      url: existing.html_url,
      state: existing.state,
    };
  } else {
    issue = await createGitHubIssue(
      issueTitle,
      [
        `Auto-triaged from \`ops_reports/${reportId}\`.`,
        "",
        "```json",
        JSON.stringify(report, null, 2),
        "```",
      ].join("\n"),
      ["bug", "ops-report", ...extraLabels].filter(Boolean),
    );
  }

  await db.collection("ops_reports").doc(reportId).set(
    {
      status: "triaged",
      triagedAt: Timestamp.now(),
      triagedBy: "mcp:storyline-ops-server",
      githubIssue: issue,
    },
    { merge: true },
  );

  return {
    reportId,
    issue,
    status: "triaged",
  };
}

async function readOpenReportsResource() {
  return {
    reports: await listOpsReports(25, "open"),
  };
}

async function readLiveInstallationsResource() {
  return {
    installations: await listLiveInstallations(25, 30),
  };
}

async function readLatestRunsResource() {
  return {
    runs: await getLatestRuns(10),
  };
}

const server = new McpServer(
  {
    name: "storyline-ops-server",
    version: "1.0.0",
  },
  {
    capabilities: {
      logging: {},
    },
  },
);

server.registerTool(
  "list_ops_reports",
  {
    title: "List Ops Reports",
    description: "List Storyline ops reports from Firestore, optionally filtered by status.",
    inputSchema: {
      limit: z.number().int().min(1).max(100).default(10),
      status: z.string().default("open"),
    },
  },
  async ({ limit = 10, status = "open" }) => {
    try {
      return textResult({ reports: await listOpsReports(limit, status) });
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "get_ops_report",
  {
    title: "Get Ops Report",
    description: "Fetch a single Storyline ops report document from Firestore.",
    inputSchema: {
      reportId: z.string().min(1),
    },
  },
  async ({ reportId }) => {
    try {
      return textResult(await getOpsReport(reportId));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "list_live_installations",
  {
    title: "List Live Installations",
    description: "List recent active Storyline installations from Firestore telemetry.",
    inputSchema: {
      limit: z.number().int().min(1).max(100).default(10),
      maxAgeMinutes: z.number().int().min(1).max(240).default(20),
    },
  },
  async ({ limit = 10, maxAgeMinutes = 20 }) => {
    try {
      return textResult({
        installations: await listLiveInstallations(limit, maxAgeMinutes),
      });
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "get_latest_github_runs",
  {
    title: "Get Latest GitHub Runs",
    description: "List latest GitHub Actions runs for the Storyline repository.",
    inputSchema: {
      limit: z.number().int().min(1).max(50).default(10),
      branch: z.string().optional(),
    },
  },
  async ({ limit = 10, branch }) => {
    try {
      return textResult({ runs: await getLatestRuns(limit, branch || "") });
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "dispatch_github_workflow",
  {
    title: "Dispatch GitHub Workflow",
    description: "Dispatch a GitHub Actions workflow by id or filename.",
    inputSchema: {
      workflowId: z.string().min(1),
      ref: z.string().min(1).default("main"),
      inputsJson: z.string().optional(),
    },
  },
  async ({ workflowId, ref = "main", inputsJson }) => {
    try {
      const inputs = parseJson(inputsJson, {});
      return textResult(await dispatchWorkflow(workflowId, ref, inputs));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "create_github_issue",
  {
    title: "Create GitHub Issue",
    description: "Create a GitHub issue in the Storyline repository.",
    inputSchema: {
      title: z.string().min(1),
      body: z.string().min(1),
      labelsJson: z.string().optional(),
    },
  },
  async ({ title, body, labelsJson }) => {
    try {
      const labels = parseJson(labelsJson, []);
      return textResult(await createGitHubIssue(title, body, Array.isArray(labels) ? labels : []));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "triage_ops_report",
  {
    title: "Triage Ops Report",
    description: "Create or reuse a GitHub issue for an ops report and mark it triaged in Firestore.",
    inputSchema: {
      reportId: z.string().min(1),
      labelsJson: z.string().optional(),
    },
  },
  async ({ reportId, labelsJson }) => {
    try {
      const labels = parseJson(labelsJson, []);
      return textResult(await triageOpsReport(reportId, Array.isArray(labels) ? labels : []));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "get_adb_devices",
  {
    title: "Get ADB Devices",
    description: "List currently connected adb devices.",
  },
  async () => {
    try {
      return textResult({ devices: await listAdbDevices() });
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "get_storyline_runtime_snapshot",
  {
    title: "Get Storyline Runtime Snapshot",
    description: "Read current device, activity, focus, and package info for the Storyline app over adb.",
    inputSchema: {
      serial: z.string().optional(),
    },
  },
  async ({ serial }) => {
    try {
      return textResult(await getRuntimeSnapshot(serial || ""));
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerTool(
  "read_storyline_logcat",
  {
    title: "Read Storyline Logcat",
    description: "Read recent logcat lines from the connected Android device.",
    inputSchema: {
      lines: z.number().int().min(20).max(2000).default(200),
      serial: z.string().optional(),
    },
  },
  async ({ lines = 200, serial }) => {
    try {
      return textResult({
        serial: serial || (await getDefaultSerial()),
        logcat: await readStorylineLogcat(lines, serial || ""),
      });
    } catch (error) {
      return errorResult(error);
    }
  },
);

server.registerResource(
  "open-ops-reports",
  "storyline://ops/reports/open",
  {
    title: "Open Ops Reports",
    description: "Open Storyline ops reports from Firestore.",
    mimeType: "application/json",
  },
  async (uri) => ({
    contents: [
      {
        uri: uri.href,
        text: JSON.stringify(await readOpenReportsResource(), null, 2),
      },
    ],
  }),
);

server.registerResource(
  "live-installations",
  "storyline://ops/installations/live",
  {
    title: "Live Installations",
    description: "Recent active Storyline installations from Firestore telemetry.",
    mimeType: "application/json",
  },
  async (uri) => ({
    contents: [
      {
        uri: uri.href,
        text: JSON.stringify(await readLiveInstallationsResource(), null, 2),
      },
    ],
  }),
);

server.registerResource(
  "latest-github-runs",
  "storyline://github/runs/latest",
  {
    title: "Latest GitHub Runs",
    description: "Latest Storyline GitHub Actions runs.",
    mimeType: "application/json",
  },
  async (uri) => ({
    contents: [
      {
        uri: uri.href,
        text: JSON.stringify(await readLatestRunsResource(), null, 2),
      },
    ],
  }),
);

server.registerResource(
  "ops-report",
  new ResourceTemplate("storyline://ops/reports/{reportId}", { list: undefined }),
  {
    title: "Ops Report",
    description: "Single Storyline ops report document.",
    mimeType: "application/json",
  },
  async (uri, { reportId }) => ({
    contents: [
      {
        uri: uri.href,
        text: JSON.stringify(await getOpsReport(reportId), null, 2),
      },
    ],
  }),
);

server.registerPrompt(
  "triage-storyline-report",
  {
    title: "Triage Storyline Report",
    description: "Generate a focused debugging prompt for a specific ops report.",
    argsSchema: {
      reportId: z.string().min(1),
    },
  },
  async ({ reportId }) => {
    const report = await getOpsReport(reportId);
    return {
      messages: [
        {
          role: "user",
          content: {
            type: "text",
            text: [
              "Triage this Storyline ops report.",
              "",
              "Focus on:",
              "1. likely root cause",
              "2. files/modules to inspect first",
              "3. whether GitHub build/device evidence is needed",
              "4. the smallest safe patch to try next",
              "",
              "Report JSON:",
              "```json",
              JSON.stringify(report, null, 2),
              "```",
            ].join("\n"),
          },
        },
      ],
    };
  },
);

const transport = new StdioServerTransport();

server
  .connect(transport)
  .catch((error) => {
    console.error("Storyline MCP server failed to start:", error);
    process.exit(1);
  });
