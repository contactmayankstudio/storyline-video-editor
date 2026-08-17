const {
    dispatchWorkflow,
    getConfig: getGitHubConfig,
    getLatestRunSummary,
} = require("./github");

const DEFAULT_GCP_WORKFLOW_NAME = process.env.GCP_ANDROID_WORKFLOW_NAME || "Google Cloud Android CI";

function getGoogleCloudConfig() {
    return {
        workflowName: DEFAULT_GCP_WORKFLOW_NAME,
        mirroredProjectId: String(process.env.GCP_AUTOMATION_PROJECT_ID || "").trim(),
        mirroredResultsBucket: String(process.env.GCP_AUTOMATION_RESULTS_BUCKET || "").trim(),
        mirroredArtifactsBucket: String(process.env.GCP_AUTOMATION_ARTIFACTS_BUCKET || "").trim(),
        configured: true,
    };
}

function normalizeIntentMessage(message) {
    return String(message || "").trim().toLowerCase();
}

function inferGoogleCloudIntent(message) {
    const value = normalizeIntentMessage(message);
    if (!value) {
        return null;
    }

    const mentionsGoogleCloud = /\b(gcp|google cloud|cloud build|firebase test lab|test lab)\b/.test(value);
    const mentionsBuildArtifact = /\b(apk|aab|bundle|build|release|debug|robo)\b/.test(value);
    if (!mentionsGoogleCloud && !mentionsBuildArtifact) {
        return null;
    }

    if (/\b(status|state|latest|last run|pichla|abhi kya hua|kya chal raha)\b/.test(value)) {
        return {
            kind: "status",
            label: "status",
        };
    }

    if (/\b(robo|test lab|firebase test lab|device test|cloud test)\b/.test(value)) {
        return {
            kind: "robo-test",
            label: "Firebase Test Lab robo run",
        };
    }

    if (/\b(aab|bundle)\b/.test(value) || /\brelease\b/.test(value) && /\bbundle\b/.test(value)) {
        return {
            kind: "release-bundle",
            label: "release AAB build",
        };
    }

    if (/\brelease\b/.test(value)) {
        return {
            kind: "release-apk",
            label: "release APK build",
        };
    }

    if (/\b(debug|apk|build)\b/.test(value)) {
        return {
            kind: "debug-apk",
            label: "debug APK build",
        };
    }

    return null;
}

function buildRunSummary(run) {
    if (!run) {
        return "No Google Cloud Android workflow run found yet.";
    }

    return [
        `Workflow: ${run.workflowName}`,
        `Branch: ${run.branch || "n/a"}`,
        `Status: ${run.status || "n/a"}`,
        `Conclusion: ${run.conclusion || "in progress"}`,
        `Commit: ${run.headSha?.slice(0, 8) || "n/a"}`,
        `Updated: ${run.updatedAt ? new Date(run.updatedAt).toLocaleString() : "n/a"}`,
        run.url ? `GitHub: ${run.url}` : "",
    ].filter(Boolean).join("\n");
}

function getWorkflowInputsForIntent(intent) {
    switch (intent.kind) {
    case "robo-test":
        return {
            action_mode: "robo-test",
            device_model: "MediumPhone.arm",
            android_version: "34",
            locale: "en",
            orientation: "portrait",
            timeout: "5m",
        };
    case "release-apk":
        return {
            action_mode: "release-apk",
        };
    case "release-bundle":
        return {
            action_mode: "release-bundle",
        };
    case "debug-apk":
    default:
        return {
            action_mode: "debug-apk",
        };
    }
}

async function runGoogleCloudIntent(intent) {
    const github = getGitHubConfig();
    const workflowName = getGoogleCloudConfig().workflowName;

    if (intent.kind === "status") {
        const run = await getLatestRunSummary(undefined, workflowName).catch(() => null);
        return {
            reply: [
                "Google Cloud Android status:",
                buildRunSummary(run),
            ].join("\n\n"),
            provider: "google-cloud-control",
            providerLabel: "Google Cloud Control",
            model: "workflow-status",
            codebaseFiles: [],
            actionMode: "google-cloud-status",
        };
    }

    await dispatchWorkflow(workflowName, {
        ref: github.branch,
        inputs: getWorkflowInputsForIntent(intent),
    });

    await new Promise((resolve) => setTimeout(resolve, 1500));
    const run = await getLatestRunSummary(undefined, workflowName).catch(() => null);

    return {
        reply: [
            `Google Cloud action queued: ${intent.label}.`,
            run?.url ? `Run: ${run.url}` : "",
            "This stays cloud-side. The phone/admin panel can poll status while GitHub and Cloud Build do the work.",
        ].filter(Boolean).join("\n"),
        provider: "google-cloud-control",
        providerLabel: "Google Cloud Control",
        model: "workflow-dispatch",
        codebaseFiles: [],
        actionMode: "google-cloud-dispatch",
        googleCloudDispatched: true,
        googleCloudIntent: intent.kind,
    };
}

module.exports = {
    buildRunSummary,
    getGoogleCloudConfig,
    inferGoogleCloudIntent,
    runGoogleCloudIntent,
};
