#!/usr/bin/env node
const http = require("http");
const { execFile } = require("child_process");
const { promisify } = require("util");

const execFileAsync = promisify(execFile);
const PORT = Number(process.env.STORYLINE_DEVICE_BRIDGE_PORT || 47831);
const TOKEN = (process.env.STORYLINE_DEVICE_BRIDGE_TOKEN || "").trim();
const ADB_PATH = process.env.ADB_PATH || "adb";
const APP_ID = process.env.STORYLINE_APP_ID || "com.storyline.app/com.video.engine.MainActivity";

function sendJson(res, status, payload) {
    res.writeHead(status, { "Content-Type": "application/json; charset=utf-8" });
    res.end(JSON.stringify(payload, null, 2));
}

function parseBody(req) {
    return new Promise((resolve, reject) => {
        let buffer = "";
        req.on("data", (chunk) => {
            buffer += chunk;
            if (buffer.length > 1024 * 1024) {
                reject(new Error("Body too large"));
                req.destroy();
            }
        });
        req.on("end", () => {
            if (!buffer.trim()) {
                resolve({});
                return;
            }
            try {
                resolve(JSON.parse(buffer));
            } catch (error) {
                reject(error);
            }
        });
        req.on("error", reject);
    });
}

function requireToken(req) {
    if (!TOKEN) {
        return true;
    }
    const auth = String(req.headers.authorization || "");
    return auth === `Bearer ${TOKEN}`;
}

async function runAdb(args, serial = "") {
    const finalArgs = [];
    if (serial) {
        finalArgs.push("-s", serial);
    }
    finalArgs.push(...args);
    const { stdout, stderr } = await execFileAsync(ADB_PATH, finalArgs, {
        maxBuffer: 8 * 1024 * 1024,
    });
    return {
        stdout: stdout.trim(),
        stderr: stderr.trim(),
    };
}

async function listDevices() {
    const { stdout } = await runAdb(["devices", "-l"]);
    return stdout
        .split("\n")
        .slice(1)
        .map((line) => line.trim())
        .filter(Boolean)
        .map((line) => {
            const [serial, state, ...rest] = line.split(/\s+/);
            return { serial, state, details: rest.join(" ") };
        });
}

async function resolveSerial(preferredSerial = "") {
    if (preferredSerial) {
        return preferredSerial;
    }
    const devices = await listDevices();
    return devices.find((device) => device.state === "device")?.serial || "";
}

async function getRuntime(serial = "") {
    const resolvedSerial = await resolveSerial(serial);
    if (!resolvedSerial) {
        throw new Error("No connected adb device found");
    }

    const [activities, windows, packageInfo] = await Promise.all([
        runAdb(["shell", "dumpsys", "activity", "activities"], resolvedSerial),
        runAdb(["shell", "dumpsys", "window", "windows"], resolvedSerial),
        runAdb(["shell", "dumpsys", "package", "com.storyline.app"], resolvedSerial),
    ]);

    return {
        serial: resolvedSerial,
        storylineAppId: APP_ID,
        resumedActivityLines: activities.stdout
            .split("\n")
            .filter((line) => line.includes("com.storyline.app") || line.includes("ResumedActivity"))
            .slice(0, 40),
        windowFocusLines: windows.stdout
            .split("\n")
            .filter((line) => line.includes("mCurrentFocus") || line.includes("mFocusedApp"))
            .slice(0, 20),
        packageInfoLines: packageInfo.stdout
            .split("\n")
            .filter((line) => line.includes("versionName=") || line.includes("versionCode="))
            .slice(0, 10),
    };
}

async function getLogcat(lines = 200, serial = "") {
    const resolvedSerial = await resolveSerial(serial);
    if (!resolvedSerial) {
        throw new Error("No connected adb device found");
    }
    const { stdout } = await runAdb(["logcat", "-d", "-t", String(lines)], resolvedSerial);
    return {
        serial: resolvedSerial,
        lines,
        logcat: stdout,
    };
}

const server = http.createServer(async (req, res) => {
    try {
        if (!requireToken(req)) {
            sendJson(res, 401, { error: "Unauthorized" });
            return;
        }

        if (req.method === "GET" && req.url === "/health") {
            sendJson(res, 200, { ok: true, adbPath: ADB_PATH, appId: APP_ID });
            return;
        }

        if (req.method === "GET" && req.url === "/devices") {
            sendJson(res, 200, { devices: await listDevices() });
            return;
        }

        if (req.method === "POST" && req.url === "/runtime") {
            const body = await parseBody(req);
            sendJson(res, 200, await getRuntime(String(body.serial || "").trim()));
            return;
        }

        if (req.method === "POST" && req.url === "/logcat") {
            const body = await parseBody(req);
            const lines = Math.max(20, Math.min(2000, Number(body.lines || 200)));
            sendJson(res, 200, await getLogcat(lines, String(body.serial || "").trim()));
            return;
        }

        sendJson(res, 404, { error: "Not found" });
    } catch (error) {
        sendJson(res, 500, { error: error.message });
    }
});

server.listen(PORT, () => {
    console.log(`Storyline device bridge listening on :${PORT}`);
});
