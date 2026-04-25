function getAwsControlPlaneConfig() {
    return {
        url: String(process.env.AWS_CONTROL_PLANE_URL || "").trim().replace(/\/$/, ""),
        adminToken: String(process.env.AWS_CONTROL_PLANE_TOKEN || "").trim(),
        deviceKey: String(process.env.AWS_CONTROL_PLANE_DEVICE_KEY || "").trim(),
    };
}

function hasAwsControlPlaneConfig() {
    const config = getAwsControlPlaneConfig();
    return Boolean(config.url && config.adminToken);
}

async function awsControlPlaneRequest(pathname, options = {}) {
    const config = getAwsControlPlaneConfig();
    if (!config.url || !config.adminToken) {
        throw new Error("AWS control plane is not configured");
    }

    const response = await fetch(`${config.url}${pathname}`, {
        method: options.method || "GET",
        headers: {
            Authorization: `Bearer ${config.adminToken}`,
            "Content-Type": "application/json",
            ...(options.headers || {}),
        },
        body: options.body ? JSON.stringify(options.body) : undefined,
    });

    const text = await response.text();
    const data = text ? JSON.parse(text) : {};
    if (!response.ok) {
        const error = new Error(data?.error || `AWS control plane ${response.status}`);
        error.status = response.status;
        throw error;
    }
    return data;
}

module.exports = {
    awsControlPlaneRequest,
    getAwsControlPlaneConfig,
    hasAwsControlPlaneConfig,
};
