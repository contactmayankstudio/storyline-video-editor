const DEFAULT_OWNER = process.env.GITHUB_OWNER || "sarojshahu12-max";
const DEFAULT_REPO = process.env.GITHUB_REPO || "storyline";
const DEFAULT_BRANCH = process.env.GITHUB_BRANCH || "main";
const DEFAULT_WORKFLOW = process.env.GITHUB_WORKFLOW_NAME || "Android CI Build";
const DEFAULT_AUTOMATION_WORKFLOW = process.env.GITHUB_AUTOMATION_WORKFLOW_NAME || "Storyline AI Automation";
const DEFAULT_AUTOPILOT_WORKFLOW = process.env.GITHUB_AUTOPILOT_WORKFLOW_NAME || "Storyline Continuous Autopilot";

function getConfig() {
    return {
        owner: DEFAULT_OWNER,
        repo: DEFAULT_REPO,
        branch: DEFAULT_BRANCH,
        workflowName: DEFAULT_WORKFLOW,
        automationWorkflowName: DEFAULT_AUTOMATION_WORKFLOW,
        autopilotWorkflowName: DEFAULT_AUTOPILOT_WORKFLOW,
        token: process.env.GITHUB_TOKEN || "",
    };
}

function buildHeaders(extraHeaders = {}) {
    return {
        Accept: "application/vnd.github+json",
        Authorization: `Bearer ${getConfig().token}`,
        "User-Agent": "storyline-admin-panel",
        "X-GitHub-Api-Version": "2022-11-28",
        ...extraHeaders,
    };
}

async function githubRequest(pathname, options = {}) {
    const config = getConfig();
    if (!config.token) {
        throw new Error("GITHUB_TOKEN is not configured");
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

    return response.json();
}

function encodeRepoPath(filePath) {
    return String(filePath || "")
        .split("/")
        .filter(Boolean)
        .map((segment) => encodeURIComponent(segment))
        .join("/");
}

async function getWorkflowId(workflowName = getConfig().workflowName) {
    const { owner, repo } = getConfig();
    const workflows = await githubRequest(`/repos/${owner}/${repo}/actions/workflows`);
    const workflow = (workflows.workflows || []).find((item) => item.name === workflowName);

    if (!workflow) {
        throw new Error(`Workflow not found: ${workflowName}`);
    }

    return workflow.id;
}

async function getLatestRunSummary(preferredConclusion, workflowNameOverride) {
    const { owner, repo, branch, workflowName } = getConfig();
    const selectedWorkflowName = workflowNameOverride || workflowName;
    const workflowId = await getWorkflowId(selectedWorkflowName);
    const runs = await githubRequest(`/repos/${owner}/${repo}/actions/workflows/${workflowId}/runs?branch=${encodeURIComponent(branch)}&per_page=10`);
    const allRuns = runs.workflow_runs || [];
    const selectedRun = allRuns.find((run) => run.conclusion === preferredConclusion) || allRuns[0];

    if (!selectedRun) {
        return null;
    }

    const jobsResponse = await githubRequest(`/repos/${owner}/${repo}/actions/runs/${selectedRun.id}/jobs?per_page=100`);
    const jobs = jobsResponse.jobs || [];
    const failedJob = jobs.find((job) => job.conclusion === "failure") || null;
    const failedStep = failedJob?.steps?.find((step) => step.conclusion === "failure") || null;

    return {
        workflowName: selectedWorkflowName,
        branch: selectedRun.head_branch,
        status: selectedRun.status,
        conclusion: selectedRun.conclusion,
        headSha: selectedRun.head_sha,
        url: selectedRun.html_url,
        createdAt: selectedRun.created_at,
        updatedAt: selectedRun.updated_at,
        failedJob: failedJob?.name || null,
        failedStep: failedStep?.name || null,
    };
}

async function dispatchWorkflow(workflowName, options = {}) {
    const { owner, repo, branch } = getConfig();
    const workflowId = await getWorkflowId(workflowName);
    const response = await fetch(`https://api.github.com/repos/${owner}/${repo}/actions/workflows/${workflowId}/dispatches`, {
        method: "POST",
        headers: buildHeaders({
            "Content-Type": "application/json",
        }),
        body: JSON.stringify({
            ref: options.ref || branch,
            inputs: options.inputs || {},
        }),
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`GitHub API ${response.status}: ${text}`);
    }

    return true;
}

async function getRepoFile(filePath, branchOverride) {
    const { owner, repo, branch } = getConfig();
    const ref = branchOverride || branch;
    const encodedPath = encodeRepoPath(filePath);
    try {
        const data = await githubRequest(`/repos/${owner}/${repo}/contents/${encodedPath}?ref=${encodeURIComponent(ref)}`);
        const content = Buffer.from(data.content || "", "base64").toString("utf8");
        return {
            path: data.path,
            sha: data.sha,
            content,
        };
    } catch (error) {
        if (error.status === 404) {
            return null;
        }
        throw error;
    }
}

async function upsertRepoFile(filePath, content, options = {}) {
    const { owner, repo, branch } = getConfig();
    const targetBranch = options.branch || branch;
    const existing = await getRepoFile(filePath, targetBranch);
    const encodedPath = encodeRepoPath(filePath);
    const payload = {
        message: options.message || `Update ${filePath}`,
        content: Buffer.from(content, "utf8").toString("base64"),
        branch: targetBranch,
    };

    if (existing?.sha) {
        payload.sha = existing.sha;
    }

    return githubRequest(`/repos/${owner}/${repo}/contents/${encodedPath}`, {
        method: "PUT",
        headers: {
            "Content-Type": "application/json",
        },
        body: JSON.stringify(payload),
    });
}

module.exports = {
    dispatchWorkflow,
    getConfig,
    getLatestRunSummary,
    getRepoFile,
    upsertRepoFile,
};
