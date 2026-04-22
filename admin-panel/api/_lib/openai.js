const OPENAI_API_URL = "https://api.openai.com/v1/chat/completions";
const DEFAULT_OPENAI_MODEL = process.env.OPENAI_MODEL || "gpt-4.1-mini";

function getOpenAIConfig() {
    return {
        apiKey: process.env.OPENAI_API_KEY || "",
        model: DEFAULT_OPENAI_MODEL,
    };
}

function extractText(payload) {
    return payload.choices?.[0]?.message?.content?.trim() || "";
}

function extractJson(text) {
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) {
        throw new Error("OpenAI returned non-JSON output");
    }

    return JSON.parse(match[0]);
}

async function generateJson(prompt) {
    const { apiKey, model } = getOpenAIConfig();
    if (!apiKey) {
        throw new Error("OPENAI_API_KEY is not configured");
    }

    const response = await fetch(OPENAI_API_URL, {
        method: "POST",
        headers: {
            "Content-Type": "application/json",
            Authorization: `Bearer ${apiKey}`,
        },
        body: JSON.stringify({
            model,
            temperature: 0.2,
            messages: [
                {
                    role: "developer",
                    content: "Return only valid JSON. Do not include markdown or code fences.",
                },
                {
                    role: "user",
                    content: prompt,
                },
            ],
        }),
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`OpenAI API ${response.status}: ${text}`);
    }

    const payload = await response.json();
    return extractJson(extractText(payload));
}

module.exports = {
    generateJson,
    getOpenAIConfig,
};
