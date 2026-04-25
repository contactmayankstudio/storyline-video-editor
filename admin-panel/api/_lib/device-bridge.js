function getDeviceBridgeConfig() {
    return {
        url: (process.env.STORYLINE_DEVICE_BRIDGE_URL || "").trim(),
        token: (process.env.STORYLINE_DEVICE_BRIDGE_TOKEN || "").trim(),
    };
}

async function deviceBridgeRequest(pathname, options = {}) {
    const config = getDeviceBridgeConfig();
    if (!config.url) {
        throw new Error("STORYLINE_DEVICE_BRIDGE_URL is not configured");
    }

    const baseUrl = config.url.endsWith("/") ? config.url.slice(0, -1) : config.url;
    const response = await fetch(`${baseUrl}${pathname}`, {
        method: options.method || "GET",
        headers: {
            Accept: "application/json",
            ...(config.token ? { Authorization: `Bearer ${config.token}` } : {}),
            ...(options.body ? { "Content-Type": "application/json" } : {}),
            ...(options.headers || {}),
        },
        body: options.body ? JSON.stringify(options.body) : undefined,
    });

    if (!response.ok) {
        const text = await response.text();
        throw new Error(`Device bridge ${response.status}: ${text}`);
    }

    return response.json();
}

module.exports = {
    deviceBridgeRequest,
    getDeviceBridgeConfig,
};
