const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { getCodebaseContext } = require("./_lib/codebase");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        await requireAdmin(req);
        const query = String(req.query?.q || "").trim();
        return res.status(200).json(getCodebaseContext(query, { maxEntries: 6 }));
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
