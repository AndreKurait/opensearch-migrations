"""Return tag-verification fields for the real control-plane role of a CI cluster."""
import json
import os
import re
from datetime import datetime, timedelta, timezone

import boto3
from botocore.config import Config


def project_tags(params):
    # CloudTrail varies these shapes across services and API versions. Preserve
    # the forms consumed by the tag verifier while dropping all other parameters.
    groups = [params.get('tags'), params.get('tagSet')]
    for item in (params.get('tagSpecificationSet') or {}).get('items', []) + (params.get('tagSpecifications') or []):
        if isinstance(item, dict):
            groups.append(item.get('tags', item.get('Tags')))
    tags = {}
    for group in groups:
        for tag in group or []:
            if isinstance(tag, dict):
                key = tag.get('key', tag.get('Key'))
                if isinstance(key, str):
                    tags[key] = tag.get('value', tag.get('Value', '')) or ''
    return [{'key': k, 'value': v} for k, v in tags.items()]


def handler(event, context):
    cluster = event.get('cluster', '')
    region = os.environ['AWS_REGION']
    account = context.invoked_function_arn.split(':')[4]
    matched = re.fullmatch(r'migration-eks-cluster-(ci-[a-z0-9]{1,12})-' + re.escape(region), cluster)
    if not matched:
        raise ValueError('Expected a CI cluster in this region')
    start = datetime.fromisoformat(event['start_time'])
    end = datetime.fromisoformat(event['end_time'])
    now = datetime.now(timezone.utc)
    if (not start.tzinfo or not end.tzinfo or
            not (now - timedelta(days=2) <= start <= end <= now + timedelta(minutes=2))):
        raise ValueError('Expected a valid test time window in the previous 48 hours')
    real = boto3.client('eks').describe_cluster(name=cluster)['cluster']
    role = real['roleArn']
    prefix = f'arn:aws:iam::{account}:role/{matched[1]}-'
    if not role.startswith(prefix) or 'EKSInfraMigrationsEKSClusterRole' not in role:
        raise ValueError('Expected the bounded CI control-plane role')
    query = {'LookupAttributes': [{'AttributeKey': 'ReadOnly', 'AttributeValue': 'false'}],
             'StartTime': start, 'EndTime': end, 'MaxResults': 50}
    if event.get('next_token'):
        query['NextToken'] = event['next_token']
    client = boto3.client('cloudtrail', config=Config(retries={'mode': 'adaptive', 'total_max_attempts': 8}))
    page = client.lookup_events(**query)
    rows = []
    for entry in page['Events']:
        detail = json.loads(entry['CloudTrailEvent'])
        issuer = detail.get('userIdentity', {}).get('sessionContext', {}).get('sessionIssuer', {}).get('arn')
        if issuer != role:
            continue
        fields = ('eventSource', 'eventName', 'eventTime', 'eventID', 'errorCode')
        projected = {key: detail[key] for key in fields if key in detail}
        if detail.get('errorMessage'):
            projected['errorMessage'] = 'AWS error detail is retained in the private CloudTrail event'
        projected['userIdentity'] = {'sessionContext': {'sessionIssuer': {'arn': role}}}
        projected['requestParameters'] = {'tags': project_tags(detail.get('requestParameters') or {})}
        rows.append({'CloudTrailEvent': json.dumps(projected)})
    response = {'Events': rows, 'ScannedCount': len(page['Events'])}
    if 'NextToken' in page:
        response['NextToken'] = page['NextToken']
    return response
