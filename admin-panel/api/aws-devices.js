const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { awsControlPlaneRequest } = require("./_lib/aws-control-plane");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        await requireAdmin(req);
        const data = await awsControlPlaneRequest("/admin/devices");
        return res.status(200).json(data);
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
