import argparse
import io
import json
import os
import time
import zipfile

import boto3
from botocore.exceptions import ClientError


def ensure_bucket(s3_client, bucket_name, region):
    try:
        s3_client.head_bucket(Bucket=bucket_name)
        return
    except ClientError:
        pass
    params = {"Bucket": bucket_name}
    if region != "us-east-1":
        params["CreateBucketConfiguration"] = {"LocationConstraint": region}
    s3_client.create_bucket(**params)


def ensure_table(dynamodb_client, table_name, hash_key, range_key=None):
    try:
        dynamodb_client.describe_table(TableName=table_name)
        return
    except ClientError as error:
        if error.response["Error"]["Code"] != "ResourceNotFoundException":
            raise
    key_schema = [{"AttributeName": hash_key, "KeyType": "HASH"}]
    attributes = [{"AttributeName": hash_key, "AttributeType": "S"}]
    if range_key:
        key_schema.append({"AttributeName": range_key, "KeyType": "RANGE"})
        attributes.append({"AttributeName": range_key, "AttributeType": "S"})
    dynamodb_client.create_table(
        TableName=table_name,
        AttributeDefinitions=attributes,
        KeySchema=key_schema,
        BillingMode="PAY_PER_REQUEST",
    )
    waiter = dynamodb_client.get_waiter("table_exists")
    waiter.wait(TableName=table_name)


def ensure_role(iam_client, role_name, region, account_id, table_arns, bucket_arn):
    trust_policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Principal": {"Service": "lambda.amazonaws.com"},
                "Action": "sts:AssumeRole",
            }
        ],
    }
    role_arn = None
    try:
        role_arn = iam_client.get_role(RoleName=role_name)["Role"]["Arn"]
    except ClientError as error:
        if error.response["Error"]["Code"] != "NoSuchEntity":
            raise
        role_arn = iam_client.create_role(
            RoleName=role_name,
            AssumeRolePolicyDocument=json.dumps(trust_policy),
            Description="Storyline AWS control plane Lambda role",
        )["Role"]["Arn"]
        time.sleep(8)

    policy = {
        "Version": "2012-10-17",
        "Statement": [
            {
                "Effect": "Allow",
                "Action": [
                    "logs:CreateLogGroup",
                    "logs:CreateLogStream",
                    "logs:PutLogEvents",
                ],
                "Resource": f"arn:aws:logs:{region}:{account_id}:*",
            },
            {
                "Effect": "Allow",
                "Action": [
                    "dynamodb:PutItem",
                    "dynamodb:GetItem",
                    "dynamodb:UpdateItem",
                    "dynamodb:Scan",
                    "dynamodb:Query",
                ],
                "Resource": table_arns,
            },
            {
                "Effect": "Allow",
                "Action": [
                    "s3:PutObject",
                    "s3:GetObject",
                ],
                "Resource": [f"{bucket_arn}/*"],
            },
        ],
    }
    iam_client.put_role_policy(
        RoleName=role_name,
        PolicyName="storyline-control-plane-inline",
        PolicyDocument=json.dumps(policy),
    )
    time.sleep(12)
    return role_arn


def package_lambda(source_path):
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", zipfile.ZIP_DEFLATED) as archive:
        archive.write(source_path, arcname="lambda_function.py")
    buffer.seek(0)
    return buffer.read()


def ensure_lambda(lambda_client, function_name, role_arn, zip_bytes, env_vars):
    try:
        lambda_client.get_function(FunctionName=function_name)
        lambda_client.update_function_code(FunctionName=function_name, ZipFile=zip_bytes, Publish=True)
        lambda_client.update_function_configuration(
            FunctionName=function_name,
            Runtime="python3.12",
            Role=role_arn,
            Handler="lambda_function.lambda_handler",
            Timeout=30,
            MemorySize=256,
            Environment={"Variables": env_vars},
        )
    except ClientError as error:
        if error.response["Error"]["Code"] != "ResourceNotFoundException":
            raise
        last_error = None
        for _ in range(8):
            try:
                lambda_client.create_function(
                    FunctionName=function_name,
                    Runtime="python3.12",
                    Role=role_arn,
                    Handler="lambda_function.lambda_handler",
                    Code={"ZipFile": zip_bytes},
                    Timeout=30,
                    MemorySize=256,
                    Publish=True,
                    Environment={"Variables": env_vars},
                )
                last_error = None
                break
            except ClientError as create_error:
                last_error = create_error
                if create_error.response["Error"]["Code"] != "InvalidParameterValueException":
                    raise
                time.sleep(10)
        if last_error is not None:
            raise last_error
    waiter = lambda_client.get_waiter("function_active_v2")
    waiter.wait(FunctionName=function_name)


def ensure_function_url(lambda_client, function_name):
    try:
        config = lambda_client.get_function_url_config(FunctionName=function_name)
    except ClientError as error:
        if error.response["Error"]["Code"] != "ResourceNotFoundException":
            raise
        config = lambda_client.create_function_url_config(FunctionName=function_name, AuthType="NONE")
    return config["FunctionUrl"]


def ensure_function_url_permission(lambda_client, function_name):
    try:
        lambda_client.add_permission(
            FunctionName=function_name,
            StatementId="storyline-function-url-public",
            Action="lambda:InvokeFunctionUrl",
            Principal="*",
            FunctionUrlAuthType="NONE",
        )
    except ClientError as error:
        if error.response["Error"]["Code"] != "ResourceConflictException":
            raise


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--region", default=os.getenv("AWS_DEFAULT_REGION", "ap-south-1"))
    parser.add_argument("--admin-token", required=True)
    parser.add_argument("--device-shared-key", default="")
    parser.add_argument("--output", default="aws/control-plane/deploy-output.json")
    args = parser.parse_args()

    session = boto3.session.Session(region_name=args.region)
    sts = session.client("sts")
    account_id = sts.get_caller_identity()["Account"]
    suffix = f"{account_id}-{args.region}"
    bucket_name = f"storyline-ops-{suffix}"
    devices_table = f"storyline-devices-{suffix}"
    commands_table = f"storyline-commands-{suffix}"
    events_table = f"storyline-events-{suffix}"
    role_name = f"storyline-control-plane-role-{args.region}"
    function_name = f"storyline-control-plane-{args.region}"

    s3_client = session.client("s3")
    dynamodb_client = session.client("dynamodb")
    iam_client = session.client("iam")
    lambda_client = session.client("lambda")

    ensure_bucket(s3_client, bucket_name, args.region)
    ensure_table(dynamodb_client, devices_table, "installationId")
    ensure_table(dynamodb_client, commands_table, "installationId", "commandId")
    ensure_table(dynamodb_client, events_table, "installationId", "eventId")

    table_arns = [
        f"arn:aws:dynamodb:{args.region}:{account_id}:table/{devices_table}",
        f"arn:aws:dynamodb:{args.region}:{account_id}:table/{commands_table}",
        f"arn:aws:dynamodb:{args.region}:{account_id}:table/{events_table}",
    ]
    bucket_arn = f"arn:aws:s3:::{bucket_name}"
    role_arn = ensure_role(iam_client, role_name, args.region, account_id, table_arns, bucket_arn)

    zip_bytes = package_lambda(os.path.join(os.path.dirname(__file__), "lambda_function.py"))
    env_vars = {
        "DEVICES_TABLE": devices_table,
        "COMMANDS_TABLE": commands_table,
        "EVENTS_TABLE": events_table,
        "ARTIFACTS_BUCKET": bucket_name,
        "ADMIN_TOKEN": args.admin_token,
        "DEVICE_SHARED_KEY": args.device_shared_key,
    }
    ensure_lambda(lambda_client, function_name, role_arn, zip_bytes, env_vars)
    function_url = ensure_function_url(lambda_client, function_name)
    ensure_function_url_permission(lambda_client, function_name)

    output = {
        "accountId": account_id,
        "region": args.region,
        "bucketName": bucket_name,
        "devicesTable": devices_table,
        "commandsTable": commands_table,
        "eventsTable": events_table,
        "roleName": role_name,
        "functionName": function_name,
        "functionUrl": function_url,
    }
    os.makedirs(os.path.dirname(args.output), exist_ok=True)
    with open(args.output, "w", encoding="utf-8") as handle:
        json.dump(output, handle, indent=2)
    print(json.dumps(output, indent=2))


if __name__ == "__main__":
    main()
