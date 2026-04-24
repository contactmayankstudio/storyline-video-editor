const DEFAULT_FIREBASE_WEB_API_KEY = "AIzaSyBTeJHjKC7Ft9H9oAvD525NC8NMBOI4n6w";

class AdminAuthError extends Error {
    constructor(status, message) {
        super(message);
        this.name = "AdminAuthError";
        this.status = status;
    }
}

function parseCsvList(value) {
    return Array.from(new Set(
        String(value || "")
            .split(",")
            .map((item) => item.trim().toLowerCase())
            .filter(Boolean)
    ));
}

function getAdminConfig() {
    const allowedEmails = parseCsvList(
        process.env.STORYLINE_ADMIN_EMAILS || process.env.ADMIN_EMAILS
    );
    const allowedDomains = parseCsvList(
        process.env.STORYLINE_ADMIN_DOMAINS
        || process.env.ADMIN_DOMAINS
        || process.env.STORYLINE_ADMIN_DOMAIN
        || process.env.ADMIN_DOMAIN
    );

    return {
        apiKey: process.env.FIREBASE_WEB_API_KEY || DEFAULT_FIREBASE_WEB_API_KEY,
        usingDefaultApiKey: !(process.env.FIREBASE_WEB_API_KEY),
        allowedEmails,
        allowedDomains,
        configured: allowedEmails.length > 0 || allowedDomains.length > 0,
    };
}

function getBearerToken(req) {
    const header = req.headers.authorization || req.headers.Authorization || "";
    const match = /^Bearer\s+(.+)$/i.exec(header);
    return match ? match[1].trim() : "";
}

function isAllowedAdminEmail(email, config) {
    const normalizedEmail = String(email || "").trim().toLowerCase();
    if (!normalizedEmail) {
        return false;
    }

    if (config.allowedEmails.includes(normalizedEmail)) {
        return true;
    }

    const domain = normalizedEmail.split("@")[1] || "";
    return Boolean(domain) && config.allowedDomains.includes(domain);
}

async function lookupFirebaseUser(idToken, apiKey) {
    const response = await fetch(
        `https://identitytoolkit.googleapis.com/v1/accounts:lookup?key=${encodeURIComponent(apiKey)}`,
        {
            method: "POST",
            headers: {
                "Content-Type": "application/json",
            },
            body: JSON.stringify({ idToken }),
        }
    );

    const data = await response.json().catch(() => null);
    if (!response.ok) {
        const message = data?.error?.message || "Firebase token verification failed";
        throw new AdminAuthError(401, message);
    }

    const user = data?.users?.[0];
    if (!user) {
        throw new AdminAuthError(401, "Firebase user lookup returned no account");
    }

    return {
        uid: user.localId || "",
        email: user.email || "",
        emailVerified: Boolean(user.emailVerified),
        displayName: user.displayName || "",
        photoUrl: user.photoUrl || "",
        providerIds: Array.isArray(user.providerUserInfo)
            ? user.providerUserInfo.map((provider) => provider.providerId).filter(Boolean)
            : [],
    };
}

async function requireAdmin(req) {
    const config = getAdminConfig();
    if (!config.configured) {
        throw new AdminAuthError(
            503,
            "Admin allowlist is not configured. Set STORYLINE_ADMIN_EMAILS or STORYLINE_ADMIN_DOMAIN(S) in Vercel."
        );
    }

    const idToken = getBearerToken(req);
    if (!idToken) {
        throw new AdminAuthError(401, "Missing admin auth token");
    }

    const user = await lookupFirebaseUser(idToken, config.apiKey);
    if (!user.email || !user.emailVerified) {
        throw new AdminAuthError(403, "Use a verified Google account for admin access");
    }

    if (!isAllowedAdminEmail(user.email, config)) {
        throw new AdminAuthError(403, `Admin access is not enabled for ${user.email}`);
    }

    return user;
}

function sendAdminError(res, error) {
    const config = getAdminConfig();
    const status = error instanceof AdminAuthError ? error.status : 500;
    return res.status(status).json({
        error: error.message || "Admin authorization failed",
        authRequired: true,
        authConfigured: config.configured,
        diagnostics: {
            allowedEmailsConfigured: config.allowedEmails.length > 0,
            allowedDomainsConfigured: config.allowedDomains.length > 0,
            usingDefaultFirebaseWebApiKey: config.usingDefaultApiKey,
            requiredEnv: [
                "STORYLINE_ADMIN_EMAILS or STORYLINE_ADMIN_DOMAINS",
                "FIREBASE_WEB_API_KEY",
            ],
            commonFixes: [
                "Enable Google sign-in in Firebase Authentication.",
                "Add the current Vercel domain to Firebase Authentication authorized domains.",
                "Allowlist the admin email or domain in Vercel env vars.",
            ],
        },
    });
}

module.exports = {
    getAdminConfig,
    requireAdmin,
    sendAdminError,
};
