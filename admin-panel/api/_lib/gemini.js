const DEFAULT_GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";

function getGeminiApiKey() {
    return process.env.GEMINI_API_KEY || "";
}

function getGeminiConfig() {
    return {
        apiKey: getGeminiApiKey(),
        model: DEFAULT_GEMINI_MODEL,
    };
}

function getGeminiApiUrl(model) {
    return `https://generativelanguage.googleapis.com/v1beta/models/${model}:generateContent`;
}

function extractText(payload) {
    return (payload.candidates || [])
        .flatMap((candidate) => candidate.content?.parts || [])
        .map((part) => part.text || "")
        .join("\n")
        .trim();
}

function extractJson(text) {
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) {
        throw new Error("Gemini returned non-JSON output");
    }

    return JSON.parse(match[0]);
}

async function generateText(prompt) {
    const { apiKey, model } = getGeminiConfig();
    if (!apiKey) {
        throw new Error("GEMINI_API_KEY is not configured");
    }

    const response = await fetch(getGeminiApiUrl(model), {
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

    if (!text) {
        throw new Error("Gemini returned empty output");
    }

    return text;
}

async function generateJson(prompt) {
    return extractJson(await generateText(prompt));
}

module.exports = {
    generateText,
    generateJson,
    getGeminiConfig,
    getGeminiApiKey,
};
