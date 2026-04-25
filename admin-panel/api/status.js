const { getConfig, getLatestRunSummary } = require("./_lib/github");
const { getAiConfig } = require("./_lib/ai");
const { getAwsControlPlaneConfig, hasAwsControlPlaneConfig } = require("./_lib/aws-control-plane");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

const DEFAULT_RELEASE_PORTAL_URL = (process.env.RELEASE_PORTAL_URL || "https://apk-host-swart.vercel.app").replace(/\/$/, "");

function getApkDownloadUrl() {
    const configured = String(process.env.APK_DOWNLOAD_URL || "").trim();
    if (configured) {
        return configured;
    }
    return `${DEFAULT_RELEASE_PORTAL_URL}/app.apk`;
}

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    const githubConfig = getConfig();
    const aiConfig = getAiConfig();
    const awsConfig = getAwsControlPlaneConfig();
    const geminiConfigured = Boolean(aiConfig.providers.find((provider) => provider.id === "gemini")?.configured);

    try {
        await requireAdmin(req);
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
                releasePortalUrl: DEFAULT_RELEASE_PORTAL_URL,
                apkDownloadUrl: getApkDownloadUrl(),
                deviceBridgeConfigured: Boolean(process.env.STORYLINE_DEVICE_BRIDGE_URL && process.env.STORYLINE_DEVICE_BRIDGE_TOKEN),
                awsControlPlaneConfigured: hasAwsControlPlaneConfig(),
                awsControlPlaneUrl: awsConfig.url || "",
            },
            build,
            message,
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({
            error: error.message,
            config: {
                githubConfigured: Boolean(githubConfig.token),
                geminiConfigured,
                aiConfigured: aiConfig.anyConfigured,
                providerOrder: aiConfig.providerOrder,
                providers: aiConfig.providers,
                releasePortalUrl: DEFAULT_RELEASE_PORTAL_URL,
                apkDownloadUrl: getApkDownloadUrl(),
                deviceBridgeConfigured: Boolean(process.env.STORYLINE_DEVICE_BRIDGE_URL && process.env.STORYLINE_DEVICE_BRIDGE_TOKEN),
                awsControlPlaneConfigured: hasAwsControlPlaneConfig(),
                awsControlPlaneUrl: awsConfig.url || "",
            },
        });
    }
};
