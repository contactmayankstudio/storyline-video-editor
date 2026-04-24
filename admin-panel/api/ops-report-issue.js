const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { getConfig } = require("./_lib/github");

function buildHeaders(extraHeaders = {}) {
    const config = getConfig();
    return {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${config.token}`,
        "User-Agent": "storyline-admin-panel",
        "X-GitHub-Api-Version": "2022-11-28",
        ...extraHeaders,
    };
}

async function githubRequest(pathname, options = {}) {
    const config = getConfig();
    if (!config.token) {
        const error = new Error("GITHUB_TOKEN is not configured");
        error.status = 503;
        throw error;
    }

    const response = await fetch(`https://api.github.com${pathname}`, {
        method: options.method || "GET",
        headers: buildHeaders(options.headers),
        body: options.body,
    });

    if (!response.ok) {
        const text = await response.text();
        const error = new Error(`GitHub API ${response.status}: ${text}`);
        error.status = response.status;
        throw error;
    }

    if (response.status === 204) {
        return null;
    }

    return response.json();
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

function normalizeIssue(issue) {
    return {
        number: issue.number,
        url: issue.html_url,
        title: issue.title,
    };
}

async function findExistingIssue(reportId) {
    const { owner, repo } = getConfig();
    const query = [
        `repo:${owner}/${repo}`,
        "is:issue",
        "in:title",
        `"${reportId}"`,
    ].join(" ");

    const data = await githubRequest(`/search/issues?q=${encodeURIComponent(query)}&per_page=1`);
    const issue = Array.isArray(data?.items) ? data.items[0] : null;
    return issue ? normalizeIssue(issue) : null;
}

async function createIssue(reportId, report) {
    const { owner, repo } = getConfig();
    const issue = await githubRequest(`/repos/${owner}/${repo}/issues`, {
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

    return normalizeIssue(issue);
}

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        const admin = await requireAdmin(req);
        const { reportId, report } = req.body || {};

        if (!reportId || typeof reportId !== "string") {
            return res.status(400).json({ error: "reportId is required" });
        }

        if (!report || typeof report !== "object") {
            return res.status(400).json({ error: "report payload is required" });
        }

        const existingIssue = await findExistingIssue(reportId);
        if (existingIssue) {
            return res.status(200).json({
                ok: true,
                issue: existingIssue,
                existing: true,
                triagedBy: admin.email || "",
            });
        }

        const issue = await createIssue(reportId, report);
        return res.status(200).json({
            ok: true,
            issue,
            existing: false,
            triagedBy: admin.email || "",
        });
    } catch (error) {
        if (error?.status === 401 || error?.status === 403 || error?.status === 503) {
            return sendAdminError(res, error);
        }

        return res.status(error?.status || 500).json({
            error: error?.message || "Issue triage failed",
        });
    }
};
