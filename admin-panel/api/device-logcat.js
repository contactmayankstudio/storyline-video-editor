const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { deviceBridgeRequest } = require("./_lib/device-bridge");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        await requireAdmin(req);
        const lines = Math.max(20, Math.min(2000, Number(req.query?.lines || 200)));
        const serial = String(req.query?.serial || "").trim();
        const data = await deviceBridgeRequest("/logcat", {
            method: "POST",
            body: {
                lines,
                ...(serial ? { serial } : {}),
            },
        });
        return res.status(200).json(data);
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
