const DEFAULT_OWNER = process.env.GITHUB_OWNER || "sarojshahu12-max";
const DEFAULT_REPO = process.env.GITHUB_REPO || "storyline";
const DEFAULT_BRANCH = process.env.GITHUB_BRANCH || "main";
const DEFAULT_WORKFLOW = process.env.GITHUB_WORKFLOW_NAME || "Android CI Build";

function getConfig() {
    return {
        owner: DEFAULT_OWNER,
        repo: DEFAULT_REPO,
        branch: DEFAULT_BRANCH,
        workflowName: DEFAULT_WORKFLOW,
        token: process.env.GITHUB_TOKEN || "",
    };
}

async function githubRequest(pathname) {
    const config = getConfig();
    if (!config.token) {
        throw new Error("GITHUB_TOKEN is not configured");
    }

    const response = await fetch(`https://api.github.com${pathname}`, {
        headers: {
            Accept: "application/vnd.github+json",
            Authorization: `Bearer ${config.token}`,
            "User-Agent": "storyline-admin-panel",
            "X-GitHub-Api-Version": "2022-11-28",
        },
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`GitHub API ${response.status}: ${text}`);
    }

    return response.json();
}

async function getWorkflowId() {
    const { owner, repo, workflowName } = getConfig();
    const workflows = await githubRequest(`/repos/${owner}/${repo}/actions/workflows`);
    const workflow = (workflows.workflows || []).find((item) => item.name === workflowName);

    if (!workflow) {
        throw new Error(`Workflow not found: ${workflowName}`);
    }

    return workflow.id;
}

async function getLatestRunSummary(preferredConclusion) {
    const { owner, repo, branch, workflowName } = getConfig();
    const workflowId = await getWorkflowId();
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
        workflowName,
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

module.exports = {
    getConfig,
    getLatestRunSummary,
};

