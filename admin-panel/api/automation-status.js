const { getConfig, getLatestRunSummary } = require("./_lib/github");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    const config = getConfig();
    const workflowName = config.automationWorkflowName;

    try {
        await requireAdmin(req);
        const run = await getLatestRunSummary(undefined, workflowName);
        return res.status(200).json({
            workflowName,
            run,
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message, workflowName });
    }
};
