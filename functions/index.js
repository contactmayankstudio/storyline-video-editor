const functions = require('firebase-functions');
const admin = require('firebase-admin');
const { genkit } = require('genkit');
const { googleAI, gemini20Flash } = require('@genkit-ai/googleai');
const { Octokit } = require('@octokit/rest');

admin.initializeApp();

const ai = genkit({
  plugins: [googleAI({ apiKey: process.env.GEMINI_API_KEY })],
  model: gemini20Flash,
});

// ── 1. Crashlytics trigger ─────────────────────────────────────────────────
exports.onCrashDetected = functions.crashlytics.issue().onNew(async (issue) => {
  const crashInfo = {
    issueId: issue.issueId,
    issueTitle: issue.issueTitle,
    appVersion: issue.appInfo?.latestAppVersion,
    stackTrace: issue.issueTitle,
  };

  functions.logger.info('New crash detected', crashInfo);

  // Gemini se fix lo
  const fix = await analyzeCrash(crashInfo);

  // Firestore mein save karo
  await admin.firestore().collection('crash_fixes').doc(issue.issueId).set({
    ...crashInfo,
    fix,
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
    status: 'pending',
  });

  const githubIssue = await createGitHubIssue(issue.issueId, crashInfo, fix);
  if (githubIssue) {
    await admin.firestore().collection('crash_fixes').doc(issue.issueId).set({
      githubIssue,
      status: 'triaged',
      updatedAt: admin.firestore.FieldValue.serverTimestamp(),
    }, { merge: true });
  }

  // GitHub PR banao only when explicitly enabled.
  if (
    process.env.GITHUB_AUTOFIX_PR_ENABLED === '1'
    && fix.affectedFile
    && fix.fixSuggestion
  ) {
    await createGitHubPR(issue.issueId, fix);
  }
});

// ── 2. Manual HTTP trigger (testing ke liye) ──────────────────────────────
exports.analyzeCrashHttp = functions.https.onRequest(async (req, res) => {
  if (req.method !== 'POST') return res.status(405).end();
  const { stackTrace, issueId } = req.body;
  if (!stackTrace) return res.status(400).json({ error: 'stackTrace required' });

  const fix = await analyzeCrash({ stackTrace, issueId: issueId || 'manual' });
  res.json(fix);
});

// ── Core: Gemini crash analysis ───────────────────────────────────────────
async function analyzeCrash(crashInfo) {
  const prompt = `You are an Android crash analysis expert for app: com.video.engine (video editor).

Crash: ${crashInfo.issueTitle || ''}
Stack Trace: ${crashInfo.stackTrace || ''}
App Version: ${crashInfo.appVersion || 'unknown'}

Respond ONLY with valid JSON:
{
  "rootCause": "one line explanation",
  "affectedFile": "ExactFileName.kt",
  "affectedLine": 42,
  "fixSuggestion": "exact code fix",
  "bashFix": "sed -i 's/old_code/new_code/' path/to/file.kt",
  "severity": "critical|high|medium|low",
  "prTitle": "fix: short description",
  "prBody": "what was wrong and how it was fixed"
}`;

  try {
    const { text } = await ai.generate(prompt);
    const match = text.match(/\{[\s\S]*\}/);
    return match ? JSON.parse(match[0]) : { rawResponse: text, severity: 'unknown' };
  } catch (e) {
    functions.logger.error('Gemini error', e);
    return { error: e.message };
  }
}

function getOctokitConfig() {
  const owner = process.env.GITHUB_OWNER;
  const repo = process.env.GITHUB_REPO;
  const token = process.env.GITHUB_TOKEN;

  if (!owner || !repo || !token) {
    functions.logger.warn('GitHub env vars missing, skipping GitHub automation');
    return null;
  }

  return {
    owner,
    repo,
    octokit: new Octokit({ auth: token }),
  };
}

function buildIssueBody(issueId, crashInfo, fix) {
  return [
    '## Crashlytics Auto Report',
    '',
    `- Crashlytics Issue ID: ${issueId}`,
    `- App Version: ${crashInfo.appVersion || 'unknown'}`,
    `- Title: ${crashInfo.issueTitle || 'unknown'}`,
    '',
    '## AI Triage',
    '',
    `- Severity: ${fix?.severity || 'unknown'}`,
    `- Root cause: ${fix?.rootCause || 'Unavailable'}`,
    `- Suggested file: ${fix?.affectedFile || 'Unavailable'}`,
    `- Suggested line: ${fix?.affectedLine || 'Unavailable'}`,
    '',
    '## Suggested Fix',
    '',
    '```text',
    fix?.fixSuggestion || 'No fix suggestion generated.',
    '```',
    '',
    '## Stack Summary',
    '',
    '```text',
    crashInfo.stackTrace || crashInfo.issueTitle || 'Unavailable',
    '```',
    '',
    '> Created automatically from Firebase Crashlytics.',
  ].join('\n');
}

async function createGitHubIssue(issueId, crashInfo, fix) {
  const github = getOctokitConfig();
  if (!github) {
    return null;
  }

  const { octokit, owner, repo } = github;
  try {
    const issue = await octokit.issues.create({
      owner,
      repo,
      title: `[Crashlytics] ${crashInfo.issueTitle || `Crash ${issueId}`}`.slice(0, 240),
      body: buildIssueBody(issueId, crashInfo, fix),
      labels: [
        'crashlytics',
        'ai-triage',
        fix?.severity || 'needs-triage',
      ],
    });

    functions.logger.info('GitHub issue created', {
      issueId,
      number: issue.data.number,
      url: issue.data.html_url,
    });

    return {
      number: issue.data.number,
      url: issue.data.html_url,
      title: issue.data.title,
    };
  } catch (e) {
    functions.logger.error('GitHub issue error', e.message);
    return null;
  }
}

// ── GitHub PR creator ─────────────────────────────────────────────────────
async function createGitHubPR(issueId, fix) {
  const github = getOctokitConfig();
  if (!github) {
    return;
  }

  const { octokit, owner, repo } = github;

  try {
    // Main branch ka latest SHA lo
    const { data: ref } = await octokit.git.getRef({ owner, repo, ref: 'heads/main' });
    const sha = ref.object.sha;

    // Naya branch banao
    const branchName = `autofix/crash-${issueId}-${Date.now()}`;
    await octokit.git.createRef({
      owner, repo,
      ref: `refs/heads/${branchName}`,
      sha,
    });

    // PR banao
    await octokit.pulls.create({
      owner, repo,
      title: fix.prTitle || `fix: auto-fix crash ${issueId}`,
      body: `## Auto-generated crash fix\n\n**Root Cause:** ${fix.rootCause}\n\n**Fix:**\n\`\`\`\n${fix.fixSuggestion}\n\`\`\`\n\n**Bash fix applied:**\n\`\`\`bash\n${fix.bashFix}\n\`\`\`\n\n> Generated by Gemini AI from Crashlytics issue: ${issueId}`,
      head: branchName,
      base: 'main',
    });

    functions.logger.info(`PR created: ${branchName}`);
  } catch (e) {
    functions.logger.error('GitHub PR error', e.message);
  }
}
