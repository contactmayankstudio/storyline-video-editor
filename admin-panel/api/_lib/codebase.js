const fs = require("fs");
const path = require("path");

const REPO_ROOT = path.resolve(__dirname, "../../..");

const CURATED_FILES = [
    {
        path: "android/app/src/main/kotlin/com/video/engine/MainActivity.kt",
        summary: "Main editor activity. Wires playback, import, export, app health telemetry, tool sheets, automation intents, and timeline refresh logic.",
        tags: ["android", "editor", "timeline", "automation", "telemetry", "import", "export"],
        fallbackExcerpt: [
            "MainActivity owns the editor shell and most operator-facing automation hooks.",
            "It handles adb actions like reset_to_blank, import_media_path, quick_import_audio, set_playhead_ms, play, pause, and select_track_clip.",
            "It also clears editor shell state, refreshes timeline tracks, updates countdown text, and bridges preview playback with timeline UI.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/PlaybackController.kt",
        summary: "Playback engine controller for preview play/pause, preroll, timeline clock, countdown formatting, and UI frame scheduling.",
        tags: ["playback", "preview", "jank", "timeline", "countdown", "smoothness"],
        fallbackExcerpt: [
            "PlaybackController computes available duration, starts native playback with a small preroll delay, and formats the top countdown from total timeline duration.",
            "It updates the UI time display, timeline manager, and playhead while trying to avoid redundant UI work.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/PreviewAudioPlayer.kt",
        summary: "Preview audio path. Handles MediaPlayer playback, seek/resync thresholds, volume updates, and audio continuity during preview.",
        tags: ["audio", "preview", "resync", "stutter", "media", "sync"],
        fallbackExcerpt: [
            "PreviewAudioPlayer is the first place to inspect preview stutter that looks audio-related.",
            "Recent fixes reduced aggressive resync pressure and skipped redundant volume updates to make low-end playback smoother.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/PlaybackExportIssueDetector.kt",
        summary: "Automatic detector for playback stalls, playback jank, export stalls, export finalize hangs, and related ops reports.",
        tags: ["ops", "detector", "playback", "export", "issues", "reports"],
        fallbackExcerpt: [
            "This detector raises automatic ops reports when playback start stalls, playback jank repeats, export progress stops moving, or finalize takes too long.",
            "It feeds the admin panel and headless GitHub triage pipeline.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/ProblemReportManager.kt",
        summary: "Creates manual and automatic app problem reports, records recent UI taps, and writes reports into Firestore-backed ops collections.",
        tags: ["reports", "firestore", "ops", "manual", "automatic", "debugging"],
        fallbackExcerpt: [
            "ProblemReportManager is the bridge from in-app problem signals to Firestore ops_reports documents.",
            "Manual long-press reports and automatic detector-driven reports both go through this path.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/AppHealthReporter.kt",
        summary: "Live installation telemetry reporter for screen, app state, last action, playing state, and version/device metadata.",
        tags: ["health", "telemetry", "firestore", "ops_installations", "foreground", "screen"],
        fallbackExcerpt: [
            "AppHealthReporter updates ops_installations with current screen, app state, last action, version, and device metadata.",
            "Admin panel Live App Health reads this data to show whether the app is actually open on devices.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/ImportController.kt",
        summary: "Visual media import flow. Chooses track type, imports from quick sample or explicit path, and triggers timeline refresh.",
        tags: ["import", "video", "overlay", "layer", "track", "media"],
        fallbackExcerpt: [
            "ImportController handles video, overlay, and layer imports, including adb-driven path imports used by automation.",
            "If clips appear to auto-import or land on the wrong track, this is one of the first files to inspect.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/audio/AudioImportController.kt",
        summary: "Audio import path for quick sample and explicit file imports onto the audio track.",
        tags: ["audio", "import", "track", "music", "voice"],
        fallbackExcerpt: [
            "AudioImportController owns audio-only import and applies the current playhead as start time when importing clips.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/ExportController.kt",
        summary: "Export workflow orchestration, export presets, progress updates, and final file handling.",
        tags: ["export", "progress", "mux", "encoding", "presets"],
        fallbackExcerpt: [
            "ExportController is the main place to inspect export preset selection, progress state, finalize behavior, and file output paths.",
        ].join("\n"),
    },
    {
        path: "android/app/src/main/kotlin/com/video/engine/timeline/TimelineManager.kt",
        summary: "Recycler-backed timeline clip list used for total duration, selection state, scrub dispatch, and undo/redo snapshots.",
        tags: ["timeline", "duration", "selection", "undo", "scrub"],
        fallbackExcerpt: [
            "TimelineManager total duration is currently the sum of clip durations in its clip list.",
            "If countdown is wrong after blank resets or imports, compare TimelineManager state with native timeline cache and MainActivity refresh logic.",
        ].join("\n"),
    },
    {
        path: "admin-panel/api/ai-chat.js",
        summary: "Admin panel AI chat backend. Turns chat messages plus build and codebase context into a prompt for Gemini, GitHub AI, or OpenAI.",
        tags: ["admin", "ai", "chat", "prompt", "panel"],
        fallbackExcerpt: "ai-chat.js is the right place to make the admin chat repo-aware instead of generic.",
    },
    {
        path: "admin-panel/api/automation-dispatch.js",
        summary: "Admin trigger for cloud automation workflow dispatch from the panel.",
        tags: ["admin", "automation", "github", "dispatch", "workflow"],
        fallbackExcerpt: "automation-dispatch.js bridges panel prompts to GitHub Actions automation workers.",
    },
    {
        path: "functions/index.js",
        summary: "Firebase Functions side of crash and ops automation, including issue creation and triage helpers where deployment plan allows.",
        tags: ["firebase", "functions", "crash", "github", "triage"],
        fallbackExcerpt: "functions/index.js contains the cloud-side automation bridge for crash and issue workflows.",
    },
    {
        path: ".github/workflows/autopilot.yml",
        summary: "Continuous AI autopilot workflow that periodically wakes up and continues safe repository improvements.",
        tags: ["github", "workflow", "autopilot", "automation", "ci"],
        fallbackExcerpt: "autopilot.yml is the scheduled GitHub Actions entrypoint for continuous AI improvement cycles.",
    },
    {
        path: ".github/workflows/ops-report-triage.yml",
        summary: "Headless ops report triage workflow that converts Firestore ops reports into GitHub issues on a schedule.",
        tags: ["github", "workflow", "ops", "triage", "reports"],
        fallbackExcerpt: "ops-report-triage.yml is the backup headless path that triages app reports into GitHub issues even when the admin panel is not open.",
    },
    {
        path: "mcp/storyline-ops-server/src/index.js",
        summary: "Local MCP server for Storyline. Exposes Firebase ops data, GitHub workflow control, issue creation, and adb/device inspection tools.",
        tags: ["mcp", "firebase", "github", "adb", "ops", "server"],
        fallbackExcerpt: "The Storyline MCP server gives AI structured access to ops reports, live installations, GitHub runs, workflow dispatch, issue creation, and adb runtime snapshots.",
    },
];

function tokenize(value) {
    return String(value || "")
        .toLowerCase()
        .split(/[^a-z0-9_]+/)
        .map((token) => token.trim())
        .filter(Boolean);
}

function safeReadExcerpt(relativePath, maxChars = 1800) {
    const absolutePath = path.resolve(REPO_ROOT, relativePath);
    try {
        if (!fs.existsSync(absolutePath)) {
            return null;
        }
        const stat = fs.statSync(absolutePath);
        if (!stat.isFile()) {
            return null;
        }
        return fs.readFileSync(absolutePath, "utf8").slice(0, maxChars).trim();
    } catch {
        return null;
    }
}

function scoreEntry(entry, tokens) {
    if (!tokens.length) {
        return 1;
    }

    const haystack = [
        entry.path,
        entry.summary,
        entry.excerpt,
        ...(entry.tags || []),
    ]
        .join(" ")
        .toLowerCase();

    let score = 0;
    for (const token of tokens) {
        if (haystack.includes(token)) score += 3;
        if (entry.path.toLowerCase().includes(token)) score += 3;
        if ((entry.tags || []).some((tag) => tag.toLowerCase().includes(token))) score += 2;
    }

    if (entry.path.startsWith("android/app/src/main/kotlin/com/video/engine/")) {
        score += 1;
    }

    return score;
}

function getCodebaseEntries() {
    return CURATED_FILES.map((entry) => ({
        path: entry.path,
        summary: entry.summary,
        tags: entry.tags,
        excerpt: safeReadExcerpt(entry.path) || entry.fallbackExcerpt,
    }));
}

function getCodebaseContext(query, options = {}) {
    const maxEntries = Number(options.maxEntries || 5);
    const tokens = tokenize(query);
    const selectedFiles = getCodebaseEntries()
        .map((entry) => ({
            ...entry,
            score: scoreEntry(entry, tokens),
        }))
        .sort((left, right) => right.score - left.score || left.path.localeCompare(right.path))
        .filter((entry) => entry.score > 0 || !tokens.length)
        .slice(0, maxEntries)
        .map(({ score, ...entry }) => entry);

    return {
        summary: "Storyline codebase context includes Android editor engine, playback and export paths, ops telemetry, admin automation, GitHub workflows, and MCP tooling.",
        selectedFiles,
    };
}

module.exports = {
    getCodebaseContext,
};
