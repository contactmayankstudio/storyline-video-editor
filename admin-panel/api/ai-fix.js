const { getLatestRunSummary } = require("./_lib/github");
const { generateJson } = require("./_lib/gemini");

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        const { build: clientBuild, errorText } = req.body || {};
        const latestFailure = await getLatestRunSummary("failure").catch(() => null);
        const build = latestFailure || clientBuild || null;

        const prompt = [
            "You are a senior Android CI engineer.",
            "Analyze the failure context and respond only with valid JSON in this shape:",
            '{',
            '  "summary": "one sentence",',
            '  "likelyCause": "one sentence",',
            '  "confidence": "low|medium|high",',
            '  "requiredAction": "single concrete action",',
            '  "nextSteps": ["step 1", "step 2", "step 3"]',
            '}',
            "",
            "Workflow context:",
            JSON.stringify(build || {}, null, 2),
            "",
            "User supplied error excerpt:",
            errorText || "No log excerpt provided.",
            "",
            "Important constraints:",
            "- Be specific and practical.",
            "- If logs are incomplete, say so and lower confidence.",
            "- If the problem is missing Firebase Android config, explicitly say that web firebaseConfig is not a replacement for google-services.json.",
        ].join("\n");

        const result = await generateJson(prompt);
        return res.status(200).json(result);
    } catch (error) {
        return res.status(500).json({ error: error.message });
    }
};

