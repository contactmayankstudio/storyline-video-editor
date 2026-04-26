const { requireAdmin, sendAdminError } = require("./_lib/admin");
const { getCodebaseContext } = require("./_lib/codebase");

/**
 * Hindi explanation added:
 * GitHub par build status dekhne ke liye, aap repository ke "Actions" tab me ja sakte hain.
 * Wahan aapko recent workflows aur unke build status (jaise success, failure, running) dikhai denge.
 * Iske alawa, kisi specific branch par build status dekhne ke liye us branch ko select karke workflows check kar sakte hain.
 *
 * "Open report trengal" ka matlab hai ek aisa report jo abhi tak close nahi hua hai,
 * yaani wo report abhi bhi active hai aur us par kaam chal raha hai.
 * Ye term aksar issue tracking ya bug reporting systems me use hoti hai,
 * jahan "open" ka matlab hai unresolved ya pending status.
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