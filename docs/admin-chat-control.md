# Admin Chat Control

The admin AI chat console now supports direct control commands and optional device bridge integration.

## Direct Chat Commands

Inside the admin chat console, the latest user message can be one of these direct commands:

- `/read path/to/file`
- `/write path/to/file`
  Put the new file content on the following lines.
- `/automation your prompt here`
- `/adb-snapshot`
- `/adb-log 200`

These commands bypass normal advisory chat and directly perform the requested action.

## Repo Write Path

Repo reads and writes use GitHub Contents API through the admin panel backend.

Required Vercel env:

- `GITHUB_TOKEN`
- `GITHUB_OWNER`
- `GITHUB_REPO`

The token must have repo contents write access.

## Device Bridge

Vercel cannot access local `adb` directly. For device logs and runtime inspection, run the local bridge on the machine that has the phone connected.

Start it locally:

```bash
STORYLINE_DEVICE_BRIDGE_TOKEN=change-me node /home/am/storyline/scripts/storyline-device-bridge.js
```

Or with a custom port:

```bash
STORYLINE_DEVICE_BRIDGE_PORT=47831 STORYLINE_DEVICE_BRIDGE_TOKEN=change-me node /home/am/storyline/scripts/storyline-device-bridge.js
```

Then expose it through a trusted tunnel or reachable HTTPS endpoint and configure Vercel env:

- `STORYLINE_DEVICE_BRIDGE_URL`
- `STORYLINE_DEVICE_BRIDGE_TOKEN`

Example bridge endpoints:

- `GET /health`
- `GET /devices`
- `POST /runtime`
- `POST /logcat`

## Notes

- Direct file write is intentionally powerful and writes straight to the repo default branch.
- Device bridge should be protected with a token and never exposed publicly without access control.
