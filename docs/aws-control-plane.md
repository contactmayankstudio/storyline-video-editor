# AWS Control Plane

Phone-first AWS control plane for Storyline. This is meant to complement the existing Vercel admin UI and GitHub automation.

## What it provisions

- Lambda function URL backend
- DynamoDB device table
- DynamoDB command table
- DynamoDB event table
- S3 bucket for APK/log/screenshot artifacts
- IAM role for the Lambda

## Files

- `aws/control-plane/lambda_function.py`
- `aws/control-plane/bootstrap.py`

## Deploy

```bash
cd /home/am/storyline
AWS_ACCESS_KEY_ID=... \
AWS_SECRET_ACCESS_KEY=... \
AWS_DEFAULT_REGION=ap-south-1 \
python3 aws/control-plane/bootstrap.py \
  --admin-token 'set-a-long-random-token' \
  --device-shared-key 'optional-device-secret'
```

This writes deployment output to:

- `aws/control-plane/deploy-output.json`

## Routes

- `GET /health`
- `GET /admin/devices`
- `POST /admin/commands`
- `POST /admin/presign-upload`
- `POST /device/heartbeat`
- `GET /device/commands?installationId=...`
- `POST /device/commands/ack`
- `POST /device/events`

## Auth

- Admin routes expect `Authorization: Bearer <ADMIN_TOKEN>` or `x-admin-token`
- Device routes can optionally be protected by `x-device-key` if `DEVICE_SHARED_KEY` is set

## Intended next step

Wire Vercel admin API routes to this Lambda URL using env vars:

- `AWS_CONTROL_PLANE_URL`
- `AWS_CONTROL_PLANE_TOKEN`
- `AWS_CONTROL_PLANE_DEVICE_KEY`
