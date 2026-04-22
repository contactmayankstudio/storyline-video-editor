const GEMINI_API_URL = "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent";

function getGeminiApiKey() {
    return process.env.GEMINI_API_KEY || "";
}

function extractText(payload) {
    return (payload.candidates || [])
        .flatMap((candidate) => candidate.content?.parts || [])
        .map((part) => part.text || "")
        .join("\n")
        .trim();
}

async function generateJson(prompt) {
    const apiKey = getGeminiApiKey();
    if (!apiKey) {
        throw new Error("GEMINI_API_KEY is not configured");
    }

    const response = await fetch(GEMINI_API_URL, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            "x-goog-api-key": apiKey,
        },
        body: JSON.stringify({
            contents: [
                {
                    parts: [
                        {
                            text: prompt,
                        },
                    ],
                },
            ],
            generationConfig: {
                temperature: 0.2,
            },
        }),
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`Gemini API ${response.status}: ${text}`);
    }

    const payload = await response.json();
    const text = extractText(payload);
    const match = text.match(/\{[\s\S]*\}/);

    if (!match) {
        throw new Error("Gemini returned non-JSON output");
    }

    return JSON.parse(match[0]);
}

module.exports = {
    generateJson,
    getGeminiApiKey,
};

