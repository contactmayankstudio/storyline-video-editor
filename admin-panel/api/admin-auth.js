const { getAdminConfig, requireAdmin, sendAdminError } = require("./_lib/admin");

module.exports = async function handler(req, res) {
    if (req.method !== "GET") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        const admin = await requireAdmin(req);
        const config = getAdminConfig();

        return res.status(200).json({
            ok: true,
            admin: {
                email: admin.email,
                displayName: admin.displayName,
                photoUrl: admin.photoUrl,
                uid: admin.uid,
                providerIds: admin.providerIds,
            },
            config: {
                allowedEmailsConfigured: config.allowedEmails.length > 0,
                allowedDomainsConfigured: config.allowedDomains.length > 0,
            },
        });
    } catch (error) {
        return sendAdminError(res, error);
    }
};
