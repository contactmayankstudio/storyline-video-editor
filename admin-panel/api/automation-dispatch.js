const { dispatchWorkflow, getConfig, getLatestRunSummary } = require("./_lib/github");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    const config = getConfig();
    const workflowName = config.automationWorkflowName;

    try {
        await requireAdmin(req);
        const { prompt, targetBranch, maxAttempts } = req.body || {};
        const trimmedPrompt = String(prompt || "").trim();

        if (!trimmedPrompt) {
            return res.status(400).json({ error: "prompt is required" });
        }

        await dispatchWorkflow(workflowName, {
            ref: targetBranch || config.branch,
            inputs: {
                prompt: trimmedPrompt,
                target_branch: targetBranch || config.branch,
                max_attempts: String(maxAttempts || "2"),
            },
        });

        await new Promise((resolve) => setTimeout(resolve, 1500));
        const latestRun = await getLatestRunSummary(undefined, workflowName).catch(() => null);

        return res.status(200).json({
            accepted: true,
            workflowName,
            run: latestRun,
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
