#!/bin/bash
# crash-autofix.sh — Firestore se pending fixes lo aur apply karo

set -e

PROJECT_ID="${FIREBASE_PROJECT_ID:-your-project-id}"
GEMINI_KEY="${GEMINI_API_KEY}"
REPO_ROOT="$(cd "$(dirname "$0")" && pwd)"

echo "🔍 Checking Firestore for pending crash fixes..."

# Firestore se pending fixes fetch karo
FIXES=$(curl -s \
  "https://firestore.googleapis.com/v1/projects/${PROJECT_ID}/databases/(default)/documents/crash_fixes?pageSize=10" \
  -H "Authorization: Bearer $(gcloud auth print-access-token 2>/dev/null || echo '')")

if echo "$FIXES" | grep -q '"documents"'; then
  echo "$FIXES" | python3 -c "
import sys, json
data = json.load(sys.stdin)
docs = data.get('documents', [])
for doc in docs:
  fields = doc.get('fields', {})
  status = fields.get('status', {}).get('stringValue', '')
  if status == 'pending':
    bash_fix = fields.get('fix', {}).get('mapValue', {}).get('fields', {}).get('bashFix', {}).get('stringValue', '')
    issue_id = fields.get('issueId', {}).get('stringValue', 'unknown')
    if bash_fix:
      print(f'ISSUE:{issue_id}|FIX:{bash_fix}')
" | while IFS='|' read -r issue fix_cmd; do
    ISSUE_ID="${issue#ISSUE:}"
    BASH_FIX="${fix_cmd#FIX:}"
    echo "🔧 Applying fix for: $ISSUE_ID"
    echo "   Command: $BASH_FIX"
    # Safety check - sirf sed commands allow karo
    if echo "$BASH_FIX" | grep -qE '^sed -i'; then
      cd "$REPO_ROOT/android"
      eval "$BASH_FIX" && echo "✅ Fix applied" || echo "❌ Fix failed"
    else
      echo "⚠️  Skipped (only sed commands allowed for safety)"
    fi
  done
else
  echo "No pending fixes found."
fi
