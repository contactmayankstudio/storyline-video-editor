const { generateJson: generateGeminiJson, generateText: generateGeminiText, getGeminiConfig } = require("./gemini");
const { generateJson: generateGitHubModelsJson, generateText: generateGitHubModelsText, getGitHubModelsConfig } = require("./github-models");
const { generateJson: generateOpenAIJson, generateText: generateOpenAIText, getOpenAIConfig } = require("./openai");

const PROVIDERS = {
    gemini: {
        id: "gemini",
        label: "Gemini",
        getConfig: getGeminiConfig,
        generateText: generateGeminiText,
        generateJson: generateGeminiJson,
        note: "Primary Google AI path for fast build diagnosis.",
    },
    "github-models": {
        id: "github-models",
        label: "GitHub AI",
        getConfig: getGitHubModelsConfig,
        generateText: generateGitHubModelsText,
        generateJson: generateGitHubModelsJson,
        note: "Uses GitHub Models with xAI Grok or another configured model. Token must have model access.",
    },
    openai: {
        id: "openai",
        label: "OpenAI",
        getConfig: getOpenAIConfig,
        generateText: generateOpenAIText,
        generateJson: generateOpenAIJson,
        note: "Optional paid fallback for advanced coding analysis.",
    },
};

function getProviderOrder() {
    const defaults = ["gemini", "github-models", "openai"];
    const raw = process.env.AI_PROVIDER_ORDER || defaults.join(",");
    const requested = raw
        .split(",")
        .map((value) => value.trim().toLowerCase())
        .filter(Boolean)
        .filter((id) => PROVIDERS[id]);

    for (const id of defaults) {
        if (!requested.includes(id)) {
            requested.push(id);
        }
    }

    return requested;
}

function getAiConfig() {
    const providerOrder = getProviderOrder();
    const providers = Object.values(PROVIDERS).map((provider) => {
        const config = provider.getConfig();
        const token = config.apiKey || config.token || "";

        return {
            id: provider.id,
            label: provider.label,
            configured: Boolean(token),
            model: config.model,
            note: provider.note,
        };
    });

    return {
        providerOrder,
        providers,
        anyConfigured: providers.some((provider) => provider.configured),
    };
}

function resolveAttemptOrder(preferredProvider) {
    const providerOrder = getProviderOrder();
    if (!preferredProvider || preferredProvider === "auto") {
        return providerOrder;
    }

    if (!PROVIDERS[preferredProvider]) {
        return providerOrder;
    }

    return [preferredProvider, ...providerOrder.filter((id) => id !== preferredProvider)];
}

async function generateJsonWithFallback(prompt, options = {}) {
    const { preferredProvider } = options;
    return generateWithFallback("generateJson", prompt, preferredProvider);
}

async function generateTextWithFallback(prompt, options = {}) {
    const { preferredProvider } = options;
    return generateWithFallback("generateText", prompt, preferredProvider);
}

async function generateWithFallback(method, prompt, preferredProvider) {
    const aiConfig = getAiConfig();
    const providerState = new Map(aiConfig.providers.map((provider) => [provider.id, provider]));
    const attempts = [];

    for (const providerId of resolveAttemptOrder(preferredProvider)) {
        const provider = PROVIDERS[providerId];
        const state = providerState.get(providerId);

        if (!state?.configured) {
            attempts.push({
                provider: providerId,
                label: provider.label,
                model: state?.model || null,
                status: "skipped",
                error: "not configured",
            });
            continue;
        }

        try {
            const result = await provider[method](prompt);
            attempts.push({
                provider: providerId,
                label: provider.label,
                model: state.model,
                status: "success",
            });

            return {
                provider: providerId,
                providerLabel: provider.label,
                model: state.model,
                attempts,
                result,
            };
        } catch (error) {
            attempts.push({
                provider: providerId,
                label: provider.label,
                model: state.model,
                status: "failed",
                error: error.message,
            });
        }
    }

    const summary = attempts
        .map((attempt) => `${attempt.label}: ${attempt.error || attempt.status}`)
        .join(" | ");

    throw new Error(summary || "No AI provider is configured");
}

module.exports = {
    generateJsonWithFallback,
    generateTextWithFallback,
    getAiConfig,
};
