const GITHUB_MODELS_API_URL = "https://models.github.ai/inference/chat/completions";
const DEFAULT_GITHUB_MODELS_MODEL = process.env.GITHUB_MODELS_MODEL || "openai/gpt-4.1";

function getGitHubModelsConfig() {
    return {
        token: process.env.GITHUB_MODELS_TOKEN || process.env.GITHUB_TOKEN || "",
        model: DEFAULT_GITHUB_MODELS_MODEL,
    };
}

function extractText(payload) {
    return payload.choices?.[0]?.message?.content?.trim() || "";
}

function extractJson(text) {
    const match = text.match(/\{[\s\S]*\}/);
    if (!match) {
        throw new Error("GitHub Models returned non-JSON output");
    }

    return JSON.parse(match[0]);
}

async function generateText(prompt) {
    const { token, model } = getGitHubModelsConfig();
    if (!token) {
        throw new Error("GITHUB_MODELS_TOKEN is not configured");
    }

    const response = await fetch(GITHUB_MODELS_API_URL, {
        method: "POST",
        headers: {
            Accept: "application/vnd.github+json",
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
            "X-GitHub-Api-Version": "2022-11-28",
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
        throw new Error(`GitHub Models API ${response.status}: ${text}`);
    }

    const payload = await response.json();
    const text = extractText(payload);

    if (!text) {
        throw new Error("GitHub Models returned empty output");
    }

    return text;
}

async function generateJson(prompt) {
    return extractJson(await generateText(prompt));
}

module.exports = {
    generateText,
    generateJson,
    getGitHubModelsConfig,
};
