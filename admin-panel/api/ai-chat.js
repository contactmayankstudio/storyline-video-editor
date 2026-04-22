const { getLatestRunSummary } = require("./_lib/github");
const { generateTextWithFallback } = require("./_lib/ai");
const { requireAdmin, sendAdminError } = require("./_lib/admin");

function sanitizeMessages(messages) {
    if (!Array.isArray(messages)) {
        return [];
    }

    return messages
        .filter((message) => message && typeof message.content === "string")
        .map((message) => ({
            role: typeof message.role === "string" ? message.role : "user",
            content: message.content.trim(),
        }))
        .filter((message) => message.content)
        .slice(-12);
}

function buildPrompt(messages, build) {
    const conversation = messages
        .map((message) => `${message.role.toUpperCase()}: ${message.content}`)
        .join("\n\n");

    return [
        "You are Storyline Control Copilot.",
        "You help with Android video-editor development, build debugging, feature design, deployment, and practical next steps.",
        "Answer in plain text, concise but useful.",
        "Prefer concrete actions over vague theory.",
        "If asked about CLI integration, explain whether it should run in browser, serverless, or a separate bridge service.",
        "",
        "Latest build context:",
        JSON.stringify(build || {}, null, 2),
        "",
        "Conversation:",
        conversation || "USER: Hello",
        "",
        "Reply as the assistant to the latest user message only.",
    ].join("\n");
}

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        await requireAdmin(req);
        const { messages, providerPreference } = req.body || {};
        const sanitizedMessages = sanitizeMessages(messages);

        if (!sanitizedMessages.length) {
            return res.status(400).json({ error: "messages are required" });
        }

        const latestBuild = await getLatestRunSummary().catch(() => null);
        const prompt = buildPrompt(sanitizedMessages, latestBuild);
        const aiResponse = await generateTextWithFallback(prompt, {
            preferredProvider: providerPreference,
        });

        return res.status(200).json({
            reply: aiResponse.result,
            provider: aiResponse.provider,
            providerLabel: aiResponse.providerLabel,
            model: aiResponse.model,
            attempts: aiResponse.attempts,
        });
    } catch (error) {
        if (error.status) {
            return sendAdminError(res, error);
        }
        return res.status(500).json({ error: error.message });
    }
};
