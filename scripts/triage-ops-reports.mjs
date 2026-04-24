#!/usr/bin/env node

const FIREBASE_PROJECT_ID = process.env.FIREBASE_PROJECT_ID || "storyline-cbd6a";
const FIRESTORE_ACCESS_TOKEN = process.env.FIRESTORE_ACCESS_TOKEN || "";
const GITHUB_TOKEN = process.env.GITHUB_TOKEN || "";
const GITHUB_REPOSITORY = process.env.GITHUB_REPOSITORY || "sarojshahu12-max/storyline";
const TRIAGED_BY = process.env.TRIAGED_BY || "github-actions";

if (!FIRESTORE_ACCESS_TOKEN) {
    throw new Error("FIRESTORE_ACCESS_TOKEN is required");
}

if (!GITHUB_TOKEN) {
    throw new Error("GITHUB_TOKEN is required");
}

const [GITHUB_OWNER, GITHUB_REPO] = GITHUB_REPOSITORY.split("/");

if (!GITHUB_OWNER || !GITHUB_REPO) {
    throw new Error(`Invalid GITHUB_REPOSITORY: ${GITHUB_REPOSITORY}`);
}

function firestoreApi(path, options = {}) {
    return fetch(`https://firestore.googleapis.com/v1/projects/${FIREBASE_PROJECT_ID}/databases/(default)/${path}`, {
        method: options.method || "GET",
        headers: {
            Authorization: `Bearer ${FIRESTORE_ACCESS_TOKEN}`,
            "Content-Type": "application/json",
            ...(options.headers || {}),
        },
        body: options.body,
    });
}

function githubApi(path, options = {}) {
    return fetch(`https://api.github.com${path}`, {
        method: options.method || "GET",
        headers: {
            Accept: "application/vnd.github+json",
            Authorization: `Bearer ${GITHUB_TOKEN}`,
            "User-Agent": "storyline-ops-triage",
            "X-GitHub-Api-Version": "2022-11-28",
            ...(options.headers || {}),
        },
        body: options.body,
    });
}

function decodeValue(value) {
    if (!value || typeof value !== "object") {
        return null;
    }
    if ("stringValue" in value) return value.stringValue;
    if ("booleanValue" in value) return value.booleanValue;
    if ("integerValue" in value) return Number(value.integerValue);
    if ("doubleValue" in value) return Number(value.doubleValue);
    if ("timestampValue" in value) return value.timestampValue;
    if ("nullValue" in value) return null;
    if ("mapValue" in value) {
        const fields = value.mapValue.fields || {};
        return Object.fromEntries(Object.entries(fields).map(([key, child]) => [key, decodeValue(child)]));
    }
    if ("arrayValue" in value) {
        return (value.arrayValue.values || []).map(decodeValue);
    }
    return null;
}

function encodeValue(value) {
    if (value === null || value === undefined) {
        return { nullValue: null };
    }
    if (typeof value === "string") {
        return { stringValue: value };
    }
    if (typeof value === "boolean") {
        return { booleanValue: value };
    }
    if (Number.isInteger(value)) {
        return { integerValue: String(value) };
    }
    if (typeof value === "number") {
        return { doubleValue: value };
    }
    if (Array.isArray(value)) {
        return { arrayValue: { values: value.map(encodeValue) } };
    }
    if (typeof value === "object") {
        return {
            mapValue: {
                fields: Object.fromEntries(Object.entries(value).map(([key, child]) => [key, encodeValue(child)])),
            },
        };
    }
    throw new Error(`Unsupported value type: ${typeof value}`);
}

function decodeDocument(document) {
    const fields = document.fields || {};
    return {
        id: document.name.split("/").pop(),
        name: document.name,
        data: Object.fromEntries(Object.entries(fields).map(([key, value]) => [key, decodeValue(value)])),
    };
}

function buildOpsReportIssueTitle(reportId, report) {
    return `[App Report] ${report.reportType || "problem"} ${report.currentScreen || "screen"} ${reportId}`.slice(0, 240);
}

function buildOpsReportIssueBody(reportId, report) {
    const recentTaps = Array.isArray(report.recentUiTaps) && report.recentUiTaps.length
        ? report.recentUiTaps.slice(-12).join("\n")
        : "No recent UI taps captured.";

    return [
        "## App Problem Report",
        "",
        `- Report ID: ${reportId}`,
        `- Type: ${report.reportType || "manual"}`,
        `- Source: ${report.source || "android-client"}`,
        `- Screen: ${report.currentScreen || "unknown"}`,
        `- Last action: ${report.lastAction || "unknown"}`,
        `- Version: ${report.versionName || "unknown"} (${report.versionCode || "?"})`,
        `- Device: ${report.deviceManufacturer || "Unknown"} ${report.deviceModel || "Device"}`,
        `- Android: ${report.androidVersion || "unknown"}`,
        `- Playing: ${report.isPlaying ? "true" : "false"}`,
        `- Project loaded: ${report.hasProjectContent ? "true" : "false"}`,
        report.stallMs ? `- Stall: ${report.stallMs}ms` : null,
        "",
        "## User Note",
        "",
        "```text",
        report.description || "No description provided.",
        "```",
        "",
        "## Recent UI Taps",
        "",
        "```text",
        recentTaps,
        "```",
        "",
        "> Created automatically from Storyline app telemetry.",
    ].filter(Boolean).join("\n");
}

async function runQuery(structuredQuery) {
    const response = await firestoreApi("documents:runQuery", {
        method: "POST",
        body: JSON.stringify({ structuredQuery }),
    });
    const text = await response.text();
    if (!response.ok) {
        throw new Error(`Firestore runQuery ${response.status}: ${text}`);
    }
    const rows = text.trim().split("\n").filter(Boolean).map((line) => JSON.parse(line));
    return rows
        .map((row) => row.document)
        .filter(Boolean)
        .map(decodeDocument);
}

async function findExistingIssue(reportId) {
    const query = [
        `repo:${GITHUB_OWNER}/${GITHUB_REPO}`,
        "is:issue",
        "in:title",
        `"${reportId}"`,
    ].join(" ");
    const response = await githubApi(`/search/issues?q=${encodeURIComponent(query)}&per_page=1`);
    const text = await response.text();
    if (!response.ok) {
        throw new Error(`GitHub search ${response.status}: ${text}`);
    }
    const payload = JSON.parse(text);
    const issue = payload.items?.[0];
    return issue
        ? { number: issue.number, url: issue.html_url, title: issue.title }
        : null;
}

async function createIssue(reportId, report) {
    const response = await githubApi(`/repos/${GITHUB_OWNER}/${GITHUB_REPO}/issues`, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify({
            title: buildOpsReportIssueTitle(reportId, report),
            body: buildOpsReportIssueBody(reportId, report),
            labels: [
                "app-report",
                report.reportType || "needs-triage",
                report.reportType === "freeze" ? "performance" : "user-report",
            ],
        }),
    });
    const text = await response.text();
    if (!response.ok) {
        throw new Error(`GitHub issue create ${response.status}: ${text}`);
    }
    const issue = JSON.parse(text);
    return { number: issue.number, url: issue.html_url, title: issue.title };
}

async function patchReport(reportId, issue) {
    const now = new Date().toISOString();
    const response = await firestoreApi(`documents/ops_reports/${encodeURIComponent(reportId)}?updateMask.fieldPaths=status&updateMask.fieldPaths=githubIssue&updateMask.fieldPaths=updatedAt&updateMask.fieldPaths=triagedAt&updateMask.fieldPaths=triagedBy`, {
        method: "PATCH",
        body: JSON.stringify({
            fields: {
                status: encodeValue("triaged"),
                githubIssue: encodeValue(issue),
                updatedAt: { timestampValue: now },
                triagedAt: { timestampValue: now },
                triagedBy: encodeValue(TRIAGED_BY),
            },
        }),
    });
    const text = await response.text();
    if (!response.ok) {
        throw new Error(`Firestore patch ${response.status}: ${text}`);
    }
}

async function loadOpenReports() {
    return runQuery({
        from: [{ collectionId: "ops_reports" }],
        where: {
            fieldFilter: {
                field: { fieldPath: "status" },
                op: "EQUAL",
                value: { stringValue: "open" },
            },
        },
        limit: 10,
    });
}

async function main() {
    const reports = await loadOpenReports();
    if (!reports.length) {
        console.log("No open ops_reports found.");
        return;
    }

    console.log(`Found ${reports.length} open ops_reports.`);
    for (const report of reports) {
        const reportId = report.id;
        const reportData = report.data;
        try {
            console.log(`Triage start: ${reportId}`);
            let issue = await findExistingIssue(reportId);
            if (!issue) {
                issue = await createIssue(reportId, reportData);
                console.log(`Created issue #${issue.number} for ${reportId}`);
            } else {
                console.log(`Found existing issue #${issue.number} for ${reportId}`);
            }
            await patchReport(reportId, issue);
            console.log(`Patched Firestore report ${reportId} -> triaged`);
        } catch (error) {
            console.error(`Failed to triage ${reportId}: ${error.message}`);
            process.exitCode = 1;
        }
    }
}

await main();
