const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { getCodebaseContext } = require("./_lib/codebase");

/**
 * Hindi explanation added:
 * GitHub par build status dekhne ke liye, aap repository ke "Actions" tab me ja sakte hain.
 * Wahan aapko recent workflows aur unke build status (jaise success, failure, running) dikhai denge.
 * Iske alawa, kisi specific branch par build status dekhne ke liye us branch ko select karke workflows check kar sakte hain.
 */

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