const {
    dispatchWorkflow,
    getConfig,
    getLatestRunSummary,
    getRepoFile,
    upsertRepoFile,
} = require("./_lib/github");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

const AUTOPILOT_CONFIG_PATH = "automation/autopilot.config.json";

function getDefaultAutopilotConfig() {
    return {
        enabled: false,
        prompt: "Keep Storyline improving continuously. Review the latest app, build, release portal, admin, and regression state. If crash or user-signal data is available, prioritize that. Pick one safe production fix or feature increment, verify it, push it, and continue until this autopilot is disabled.",
        targetBranch: "main",
        maxAttempts: 2,
        cadenceMinutes: 30,
        updatedAt: null,
        updatedBy: null,
    };
}

async function loadAutopilotConfig() {
    const file = await getRepoFile(AUTOPILOT_CONFIG_PATH);
    if (!file) {
        return getDefaultAutopilotConfig();
    }

    try {
        return {
            ...getDefaultAutopilotConfig(),
            ...JSON.parse(file.content),
        };
    } catch (error) {
        return getDefaultAutopilotConfig();
    }
}

function normalizePayload(body, email) {
    const defaults = getDefaultAutopilotConfig();
    const prompt = String(body?.prompt || defaults.prompt).trim();
    const targetBranch = String(body?.targetBranch || defaults.targetBranch).trim() || defaults.targetBranch;
    const maxAttempts = Math.max(1, Math.min(3, Number.parseInt(body?.maxAttempts || defaults.maxAttempts, 10) || defaults.maxAttempts));

    return {
        enabled: Boolean(body?.enabled),
        prompt,
        targetBranch,
        maxAttempts,
        cadenceMinutes: defaults.cadenceMinutes,
        updatedAt: new Date().toISOString(),
        updatedBy: email || "unknown",
    };
}

module.exports = async function handler(req, res) {
    if (!["GET", "POST"].includes(req.method)) {
        return res.status(405).json({ error: "Method not allowed" });
    }

    const githubConfig = getConfig();

    try {
        const admin = await requireAdmin(req);

        if (req.method === "GET") {
            const config = await loadAutopilotConfig();
            const [autopilotRun, automationRun] = await Promise.all([
                getLatestRunSummary(undefined, githubConfig.autopilotWorkflowName).catch(() => null),
                getLatestRunSummary(undefined, githubConfig.automationWorkflowName).catch(() => null),
            ]);

            return res.status(200).json({
                config,
                autopilotWorkflowName: githubConfig.autopilotWorkflowName,
                automationWorkflowName: githubConfig.automationWorkflowName,
                autopilotRun,
                automationRun,
            });
        }

        const config = normalizePayload(req.body || {}, admin.email);
        if (config.enabled && !config.prompt) {
            return res.status(400).json({ error: "prompt is required when autopilot is enabled" });
        }

        await upsertRepoFile(
            AUTOPILOT_CONFIG_PATH,
            `${JSON.stringify(config, null, 2)}\n`,
            {
                branch: config.targetBranch,
                message: config.enabled
                    ? "Enable continuous AI autopilot"
                    : "Disable continuous AI autopilot",
            }
        );

        let automationRun = null;
        const kickoffNow = req.body?.kickoffNow !== false;
        if (config.enabled && kickoffNow) {
            await dispatchWorkflow(githubConfig.automationWorkflowName, {
                ref: config.targetBranch,
                inputs: {
                    prompt: `[Continuous AI autopilot]\\n${config.prompt}`,
                    target_branch: config.targetBranch,
                    max_attempts: String(config.maxAttempts),
                },
            });
            await new Promise((resolve) => setTimeout(resolve, 1500));
            automationRun = await getLatestRunSummary(undefined, githubConfig.automationWorkflowName).catch(() => null);
        }

        const autopilotRun = await getLatestRunSummary(undefined, githubConfig.autopilotWorkflowName).catch(() => null);

        return res.status(200).json({
            saved: true,
            config,
            autopilotWorkflowName: githubConfig.autopilotWorkflowName,
            automationWorkflowName: githubConfig.automationWorkflowName,
            autopilotRun,
            automationRun,
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
