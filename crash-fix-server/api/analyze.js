const { GoogleGenerativeAI } = require('@google/generative-ai');

const MODELS = ['gemini-2.0-flash', 'gemini-2.0-flash-lite', 'gemini-2.5-flash'];

async function tryGenerate(prompt, apiKey) {
  const genAI = new GoogleGenerativeAI(apiKey);
  for (const modelName of MODELS) {
    try {
      const model = genAI.getGenerativeModel({ model: modelName });
      const result = await model.generateContent(prompt);
      return result.response.text();
    } catch (e) {
      if (e.message?.includes('429') || e.message?.includes('quota')) continue;
      throw e;
    }
  }
  throw new Error('QUOTA_EXHAUSTED');
}

module.exports = async function handler(req, res) {
  if (req.method !== 'POST') return res.status(405).end();

  const { stackTrace, crashLog, packageName } = req.body;
  if (!stackTrace && !crashLog) return res.status(400).json({ error: 'stackTrace required' });

  const prompt = `You are an Android crash expert for app: ${packageName || 'com.video.engine'}.

Stack Trace:
${stackTrace || crashLog}

Respond ONLY with valid JSON:
{
  "rootCause": "one line",
  "affectedFile": "FileName.kt",
  "affectedLine": 0,
  "fixSuggestion": "exact fix",
  "severity": "critical|high|medium|low",
  "prTitle": "fix: short description"
}`;

  try {
    const text = await tryGenerate(prompt, process.env.GEMINI_API_KEY);
    const match = text.match(/\{[\s\S]*\}/);
    res.status(200).json(match ? JSON.parse(match[0]) : { raw: text });
  } catch (e) {
    if (e.message === 'QUOTA_EXHAUSTED') {
      // Queue mein save karo - retry baad mein hoga
      res.status(202).json({ status: 'queued', message: 'Quota exhausted, will retry automatically' });
    } else {
      res.status(500).json({ error: e.message });
    }
  }
};
