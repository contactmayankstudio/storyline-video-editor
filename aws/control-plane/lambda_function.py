import json
import os
import time
import uuid
from decimal import Decimal

import boto3
from boto3.dynamodb.conditions import Key

dynamodb = boto3.resource("dynamodb")
s3 = boto3.client("s3")

DEVICES_TABLE = os.environ["DEVICES_TABLE"]
COMMANDS_TABLE = os.environ["COMMANDS_TABLE"]
EVENTS_TABLE = os.environ["EVENTS_TABLE"]
ARTIFACTS_BUCKET = os.environ["ARTIFACTS_BUCKET"]
ADMIN_TOKEN = os.environ["ADMIN_TOKEN"]
DEVICE_SHARED_KEY = os.environ.get("DEVICE_SHARED_KEY", "")

devices_table = dynamodb.Table(DEVICES_TABLE)
commands_table = dynamodb.Table(COMMANDS_TABLE)
events_table = dynamodb.Table(EVENTS_TABLE)


def json_response(status, payload):
    return {
        "statusCode": status,
        "headers": {
            "content-type": "application/json",
            "access-control-allow-origin": "*",
            "access-control-allow-headers": "content-type,authorization,x-admin-token,x-device-key",
            "access-control-allow-methods": "GET,POST,OPTIONS",
        },
        "body": json.dumps(payload, default=_json_default),
    }


def _json_default(value):
    if isinstance(value, Decimal):
        return int(value) if value % 1 == 0 else float(value)
    raise TypeError(f"Unsupported type: {type(value)!r}")


def body_json(event):
    body = event.get("body")
    if not body:
        return {}
    if event.get("isBase64Encoded"):
        import base64

        body = base64.b64decode(body).decode("utf-8")
    return json.loads(body)


def header(event, name):
    headers = event.get("headers") or {}
    for key, value in headers.items():
        if key.lower() == name.lower():
            return value
    return ""


def ensure_admin(event):
    provided = header(event, "authorization").replace("Bearer ", "").strip() or header(event, "x-admin-token").strip()
    if not provided or provided != ADMIN_TOKEN:
        raise PermissionError("Invalid admin token")


def ensure_device(event):
    if not DEVICE_SHARED_KEY:
        return
    provided = header(event, "x-device-key").strip()
    if not provided or provided != DEVICE_SHARED_KEY:
        raise PermissionError("Invalid device key")


def normalize_device(payload):
    now_ms = int(time.time() * 1000)
    installation_id = str(payload.get("installationId") or payload.get("deviceId") or "").strip()
    if not installation_id:
        raise ValueError("installationId is required")
    return {
        "installationId": installation_id,
        "sessionId": str(payload.get("sessionId") or "unknown")[:96],
        "updatedAtMs": int(payload.get("updatedAtMs") or now_ms),
        "appState": str(payload.get("appState") or "foreground")[:32],
        "currentScreen": str(payload.get("currentScreen") or "home")[:64],
        "lastAction": str(payload.get("lastAction") or "heartbeat")[:160],
        "hasProjectContent": bool(payload.get("hasProjectContent", False)),
        "isPlaying": bool(payload.get("isPlaying", False)),
        "versionName": str(payload.get("versionName") or "unknown")[:48],
        "versionCode": int(payload.get("versionCode") or 0),
        "packageName": str(payload.get("packageName") or "com.storyline.app")[:96],
        "deviceModel": str(payload.get("deviceModel") or "unknown")[:96],
        "deviceManufacturer": str(payload.get("deviceManufacturer") or "unknown")[:96],
        "androidVersion": str(payload.get("androidVersion") or "unknown")[:32],
        "source": str(payload.get("source") or "android-client")[:32],
    }


def health():
    return json_response(
        200,
        {
            "ok": True,
            "service": "storyline-control-plane",
            "artifactsBucket": ARTIFACTS_BUCKET,
            "deviceAuthEnabled": bool(DEVICE_SHARED_KEY),
        },
    )


def list_devices(event):
    ensure_admin(event)
    result = devices_table.scan(Limit=50)
    items = sorted(result.get("Items", []), key=lambda item: int(item.get("updatedAtMs", 0)), reverse=True)
    return json_response(200, {"devices": items})


def upsert_device_heartbeat(event):
    ensure_device(event)
    payload = normalize_device(body_json(event))
    devices_table.put_item(Item=payload)
    return json_response(200, {"ok": True, "installationId": payload["installationId"]})


def queue_command(event):
    ensure_admin(event)
    payload = body_json(event)
    installation_id = str(payload.get("installationId") or "").strip()
    command = str(payload.get("command") or "").strip()
    if not installation_id or not command:
        raise ValueError("installationId and command are required")
    now_ms = int(time.time() * 1000)
    command_id = str(payload.get("commandId") or f"{command}_{now_ms}_{uuid.uuid4().hex[:8]}")
    item = {
        "installationId": installation_id,
        "commandId": command_id,
        "command": command,
        "status": "queued",
        "issuedBy": str(payload.get("issuedBy") or "admin-panel")[:120],
        "source": str(payload.get("source") or "aws-admin")[:64],
        "createdAtMs": now_ms,
        "updatedAtMs": now_ms,
        "payload": payload.get("payload") or {},
    }
    commands_table.put_item(Item=item)
    return json_response(200, {"ok": True, "command": item})


def poll_commands(event):
    ensure_device(event)
    installation_id = str((event.get("queryStringParameters") or {}).get("installationId") or "").strip()
    if not installation_id:
        raise ValueError("installationId is required")
    result = commands_table.query(
        KeyConditionExpression=Key("installationId").eq(installation_id),
        ScanIndexForward=False,
        Limit=20,
    )
    queued = [item for item in result.get("Items", []) if item.get("status") == "queued"]
    return json_response(200, {"commands": queued})


def ack_command(event):
    ensure_device(event)
    payload = body_json(event)
    installation_id = str(payload.get("installationId") or "").strip()
    command_id = str(payload.get("commandId") or "").strip()
    status = str(payload.get("status") or "").strip()
    if not installation_id or not command_id or status not in {"running", "completed", "failed"}:
        raise ValueError("installationId, commandId and valid status are required")
    now_ms = int(time.time() * 1000)
    update_parts = [
        "SET #status = :status",
        "updatedAtMs = :updatedAtMs",
        "handledBySource = :handledBySource",
        "resultMessage = :resultMessage",
    ]
    values = {
        ":status": status,
        ":updatedAtMs": now_ms,
        ":handledBySource": str(payload.get("handledBySource") or "android-client")[:64],
        ":resultMessage": str(payload.get("resultMessage") or "")[:240],
    }
    names = {"#status": "status"}
    if status in {"completed", "failed"}:
        update_parts.append("completedAtMs = :completedAtMs")
        values[":completedAtMs"] = now_ms
    commands_table.update_item(
        Key={"installationId": installation_id, "commandId": command_id},
        UpdateExpression=", ".join(update_parts),
        ExpressionAttributeNames=names,
        ExpressionAttributeValues=values,
    )
    return json_response(200, {"ok": True})


def record_event(event):
    ensure_device(event)
    payload = body_json(event)
    installation_id = str(payload.get("installationId") or "").strip()
    if not installation_id:
        raise ValueError("installationId is required")
    now_ms = int(time.time() * 1000)
    item = {
        "installationId": installation_id,
        "eventId": str(payload.get("eventId") or f"evt_{now_ms}_{uuid.uuid4().hex[:8]}"),
        "type": str(payload.get("type") or "event")[:64],
        "message": str(payload.get("message") or "")[:512],
        "createdAtMs": now_ms,
        "payload": payload.get("payload") or {},
    }
    events_table.put_item(Item=item)
    return json_response(200, {"ok": True, "eventId": item["eventId"]})


def presign_upload(event):
    ensure_admin(event)
    payload = body_json(event)
    key = str(payload.get("key") or f"uploads/{int(time.time())}.bin").lstrip("/")
    content_type = str(payload.get("contentType") or "application/octet-stream")
    url = s3.generate_presigned_url(
        "put_object",
        Params={"Bucket": ARTIFACTS_BUCKET, "Key": key, "ContentType": content_type},
        ExpiresIn=3600,
    )
    return json_response(200, {"ok": True, "bucket": ARTIFACTS_BUCKET, "key": key, "uploadUrl": url})


def lambda_handler(event, context):
    method = (event.get("requestContext", {}).get("http", {}).get("method") or event.get("httpMethod") or "GET").upper()
    path = event.get("rawPath") or event.get("path") or "/"
    try:
        if method == "OPTIONS":
            return json_response(200, {"ok": True})
        if method == "GET" and path == "/health":
            return health()
        if method == "GET" and path == "/admin/devices":
            return list_devices(event)
        if method == "POST" and path == "/admin/commands":
            return queue_command(event)
        if method == "POST" and path == "/admin/presign-upload":
            return presign_upload(event)
        if method == "POST" and path == "/device/heartbeat":
            return upsert_device_heartbeat(event)
        if method == "GET" and path == "/device/commands":
            return poll_commands(event)
        if method == "POST" and path == "/device/commands/ack":
            return ack_command(event)
        if method == "POST" and path == "/device/events":
            return record_event(event)
        return json_response(404, {"error": "Not found", "path": path, "method": method})
    except PermissionError as error:
        return json_response(401, {"error": str(error)})
    except ValueError as error:
        return json_response(400, {"error": str(error)})
    except Exception as error:
        return json_response(500, {"error": str(error)})
