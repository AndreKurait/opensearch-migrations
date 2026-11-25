"""Read write-event pages for one cluster's tag verifier."""

import json
import os


def iter_tag_event_pages(region, cluster, start_time, end_time, retry_config):
    import boto3

    function = os.environ.get('CI_TAG_ORACLE_FUNCTION')
    if not function:
        trail = boto3.client('cloudtrail', region_name=region, config=retry_config)
        yield from trail.get_paginator('lookup_events').paginate(
            LookupAttributes=[{'AttributeKey': 'ReadOnly', 'AttributeValue': 'false'}],
            StartTime=start_time, EndTime=end_time)
        return

    client = boto3.client('lambda', region_name=region, config=retry_config)
    request = {'cluster': cluster, 'start_time': start_time.isoformat(), 'end_time': end_time.isoformat()}
    while True:
        response = client.invoke(FunctionName=function, Payload=json.dumps(request).encode())
        with response['Payload'] as payload:
            if response.get('FunctionError'):
                raise RuntimeError(f'Tag event projection failed: {response["FunctionError"]}')
            page = json.load(payload)
        events, scanned = page['Events'], page['ScannedCount']
        if not isinstance(events, list) or type(scanned) is not int or not len(events) <= scanned <= 50:
            raise ValueError('Invalid tag event projection page')
        yield page
        token = page.get('NextToken')
        if not token:
            return
        if token == request.get('next_token'):
            raise ValueError('Tag event projection repeated its pagination token')
        request['next_token'] = token
