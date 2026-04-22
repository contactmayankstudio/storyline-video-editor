const { getConfig, getLatestRunSummary } = require("./_lib/github");
const { getAiConfig } = require("./_lib/ai");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    const githubConfig = getConfig();
    const aiConfig = getAiConfig();
    const geminiConfigured = Boolean(aiConfig.providers.find((provider) => provider.id === "gemini")?.configured);

    try {
        let build = null;
        let message = "";

        if (githubConfig.token) {
            build = await getLatestRunSummary();
            if (!build) {
                message = "No workflow runs found yet.";
            }
        } else {
            message = "Set GITHUB_TOKEN in Vercel to read private GitHub Actions data.";
        }

        return res.status(200).json({
            config: {
                githubConfigured: Boolean(githubConfig.token),
                geminiConfigured,
                aiConfigured: aiConfig.anyConfigured,
                providerOrder: aiConfig.providerOrder,
                providers: aiConfig.providers,
                repo: `${githubConfig.owner}/${githubConfig.repo}`,
                workflowName: githubConfig.workflowName,
            },
            build,
            message,
        });
    } catch (error) {
        return res.status(500).json({
            error: error.message,
            config: {
                githubConfigured: Boolean(githubConfig.token),
                geminiConfigured,
                aiConfigured: aiConfig.anyConfigured,
                providerOrder: aiConfig.providerOrder,
                providers: aiConfig.providers,
            },
        });
    }
};
