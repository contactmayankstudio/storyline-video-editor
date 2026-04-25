const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { awsControlPlaneRequest } = require("./_lib/aws-control-plane");

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        const user = await requireAdmin(req);
        const payload = req.body || {};
        const data = await awsControlPlaneRequest("/admin/commands", {
            method: "POST",
            body: {
                ...payload,
                issuedBy: user.email || "admin-panel",
                source: "admin-panel",
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
