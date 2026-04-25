# Storyline MCP Server

This project now includes a local MCP server scaffold at:

- `mcp/storyline-ops-server`

It is intended for AI agents that need structured access to:

- Firestore `ops_reports`
- Firestore `ops_installations`
- GitHub issues and workflow runs
- GitHub workflow dispatch
- local Android device state through `adb`

## What It Gives You

Instead of making an AI scrape the admin panel manually, the MCP server exposes clean tools and resources such as:

- `list_ops_reports`
- `get_ops_report`
- `list_live_installations`
- `get_latest_github_runs`
- `dispatch_github_workflow`
- `create_github_issue`
- `triage_ops_report`
- `get_adb_devices`
- `get_storyline_runtime_snapshot`
- `read_storyline_logcat`

It also exposes resources:

- `storyline://ops/reports/open`
- `storyline://ops/installations/live`
- `storyline://github/runs/latest`
- `storyline://ops/reports/{reportId}`

## Setup

From the repo root:

```bash
cd /home/am/storyline/mcp/storyline-ops-server
npm install
```

Environment:

- `FIREBASE_PROJECT_ID`
- `FIREBASE_SERVICE_ACCOUNT_PATH` or `FIREBASE_SERVICE_ACCOUNT_JSON`
- `GITHUB_TOKEN`
- `GITHUB_OWNER`
- `GITHUB_REPO`
- optional `ADB_PATH`
- optional `STORYLINE_APP_ID`

See:

- `mcp/storyline-ops-server/.env.example`

## Start

```bash
cd /home/am/storyline/mcp/storyline-ops-server
npm start
```

This uses `stdio`, which is the right transport for local MCP clients.

## Generic MCP Client Config

Most MCP clients accept a config shaped like:

```json
{
  "mcpServers": {
    "storyline-ops": {
      "command": "node",
      "args": ["/home/am/storyline/mcp/storyline-ops-server/src/index.js"],
      "cwd": "/home/am/storyline/mcp/storyline-ops-server",
      "env": {
        "FIREBASE_PROJECT_ID": "storyline-cbd6a",
        "FIREBASE_SERVICE_ACCOUNT_PATH": "/absolute/path/to/service-account.json",
        "GITHUB_TOKEN": "ghp_xxx",
        "GITHUB_OWNER": "sarojshahu12-max",
        "GITHUB_REPO": "storyline"
      }
    }
  }
}
```

## Notes

- The MCP layer itself is free.
- Cost comes from the backing services:
  - GitHub private plan limits
  - Firebase usage
  - any cloud runners you trigger
- This server is local and does not replace GitHub Actions. It gives AI a better control surface over the systems already in use.
