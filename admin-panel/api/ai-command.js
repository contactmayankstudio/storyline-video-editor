const { getLatestRunSummary } = require("./_lib/github");
const { generateJsonWithFallback } = require("./_lib/ai");

module.exports = async function handler(req, res) {
    if (req.method !== "POST") {
        return res.status(405).json({ error: "Method not allowed" });
    }

    try {
        const { requestText, providerPreference, build: clientBuild } = req.body || {};
        const brief = (requestText || "").trim();

        if (!brief) {
            return res.status(400).json({ error: "requestText is required" });
        }

        const latestBuild = clientBuild || await getLatestRunSummary().catch(() => null);
        const prompt = [
            "You are a principal architect for a mobile video editor called Storyline.",
            "Respond only with valid JSON in this shape:",
            "{",
            '  "summary": "one sentence",',
            '  "architectureMoves": ["move 1", "move 2", "move 3"],',
            '  "repoChanges": ["concrete change 1", "concrete change 2", "concrete change 3"],',
            '  "workflowChanges": ["ci/deploy step 1", "ci/deploy step 2"],',
            '  "risks": ["risk 1", "risk 2", "risk 3"],',
            '  "nextCommand": "one high-signal operator command for the next implementation pass"',
            "}",
            "",
            "Product context:",
            "- Android video editor with native preview/export pipeline.",
            "- Current focus: multi-clip timeline, OpenGL preview, master audio, and resilient build automation.",
            "- Prefer incremental, shippable phases instead of unrealistic big-bang rewrites.",
            "",
            "Latest build context:",
            JSON.stringify(latestBuild || {}, null, 2),
            "",
            "Operator request:",
            brief,
            "",
            "Important constraints:",
            "- Recommend phases that fit a real repository, not greenfield theory.",
            "- Call out any risky engine rewrites separately from safe admin-panel upgrades.",
            "- If the request is too large, break it into milestones.",
        ].join("\n");

        const aiResponse = await generateJsonWithFallback(prompt, {
            preferredProvider: providerPreference,
        });

        return res.status(200).json({
            ...aiResponse.result,
            provider: aiResponse.provider,
            providerLabel: aiResponse.providerLabel,
            model: aiResponse.model,
            attempts: aiResponse.attempts,
        });
    } catch (error) {
        return res.status(500).json({ error: error.message });
    }
};
