"""Scope the AOSS endpoint API (which lacks resource-level IAM) to CI VPCs."""
import json
import os
import re
import time
import urllib.request
from urllib.parse import urlsplit

import boto3


def endpoint(client, endpoint_id):
    result = client.batch_get_vpc_endpoint(ids=[endpoint_id])
    for error in result.get('vpcEndpointErrorDetails', []):
        if error.get('errorCode') not in ('ResourceNotFoundException', 'NotFound'):
            raise ValueError(f'Endpoint lookup failed: {error}')
    return next(iter(result['vpcEndpointDetails']), None)


def wait(client, endpoint_id, deleting, remaining):
    while remaining() > 25000:
        current = endpoint(client, endpoint_id)
        if deleting and current is None:
            return
        status = current and current['status']
        if not deleting and status == 'ACTIVE':
            return
        if status == 'FAILED':
            raise RuntimeError(f'Endpoint {endpoint_id} failed')
        time.sleep(5)
    raise TimeoutError('Timed out waiting for endpoint')


def response_url(value, region):
    # A caller allowed to invoke this broker cannot turn it into an HTTP proxy.
    bucket = 'cloudformation-custom-resource-response-' + region.replace('-', '')
    url = urlsplit(value)
    # CloudFormation can sign callbacks against S3's legacy dash-delimited regional endpoint.
    endpoints = {f's3.{region}.amazonaws.com', f's3-{region}.amazonaws.com', 's3.amazonaws.com'}
    permitted = url.hostname in {f'{bucket}.{host}' for host in endpoints} or (
        url.hostname in endpoints and url.path.startswith('/' + bucket + '/'))
    if url.scheme != 'https' or url.username is not None or url.password is not None or url.port or not permitted:
        raise ValueError('Expected the regional CloudFormation response bucket')


def handler(event, context):
    props = event['ResourceProperties']
    account = context.invoked_function_arn.split(':')[4]
    region = os.environ['AWS_REGION']
    arn = event['StackId']
    prefix = f'arn:aws:cloudformation:{region}:{account}:stack/'
    stack = re.fullmatch(re.escape(prefix) + r'(ci-[a-z0-9]{1,12})-endpoints/[\da-f-]+', arn)
    response_url(event['ResponseURL'], region)
    endpoint_id = event.get('PhysicalResourceId', 'never-created')
    response = {'Status': 'FAILED', 'Reason': 'See private endpoint provider logs', 'StackId': arn,
                'RequestId': event['RequestId'], 'LogicalResourceId': event['LogicalResourceId']}
    try:
        if not stack or props['Name'] != stack[1] + '-classic':
            raise ValueError('Unexpected stack or endpoint name')
        recorded = boto3.client('cloudformation').describe_stacks(StackName=arn)['Stacks'][0]
        vpc_id = next(p['ParameterValue'] for p in recorded['Parameters'] if p['ParameterKey'] == 'VpcId')
        if props['VpcId'] != vpc_id:
            raise ValueError('VPC does not match stack')
        client = boto3.client('opensearchserverless')
        if event['RequestType'] == 'Delete':
            # When Create never returned a physical ID, CloudFormation supplies its generated ID.
            placeholder = f'{stack[1]}-endpoints-{event["LogicalResourceId"]}-'
            if endpoint_id != 'never-created' and not re.fullmatch(re.escape(placeholder) + r'[A-Z0-9]+', endpoint_id):
                current = endpoint(client, endpoint_id)
                if current and current['name'] == props['Name'] and current['vpcId'] == vpc_id:
                    if current['status'] != 'DELETING':
                        client.delete_vpc_endpoint(id=endpoint_id)
                    wait(client, endpoint_id, True, context.get_remaining_time_in_millis)
        else:
            ec2 = boto3.client('ec2')
            vpc = ec2.describe_vpcs(VpcIds=[vpc_id])['Vpcs'][0]
            if {'Key': 'MigrationCI', 'Value': 'github'} not in vpc.get('Tags', []):
                raise ValueError('Expected a sandbox VPC')
            for collection, query, key in [('Subnets', ec2.describe_subnets, 'SubnetIds'),
                                           ('SecurityGroups', ec2.describe_security_groups, 'GroupIds')]:
                ids = props['SubnetIds' if key == 'SubnetIds' else 'SecurityGroupIds']
                if not ids or any(item['VpcId'] != vpc_id for item in query(**{key: ids})[collection]):
                    raise ValueError('Endpoint subnets and security groups must belong to the sandbox VPC')
            if event['RequestType'] == 'Create':
                created = client.create_vpc_endpoint(name=props['Name'], vpcId=vpc_id, subnetIds=props['SubnetIds'],
                                                     securityGroupIds=props['SecurityGroupIds'],
                                                     clientToken=event['RequestId'])
                endpoint_id = created['createVpcEndpointDetail']['id']
                wait(client, endpoint_id, False, context.get_remaining_time_in_millis)
            elif props != event.get('OldResourceProperties'):
                raise ValueError('Replace the endpoint for configuration updates')
        response.update(Status='SUCCESS', Reason='Complete')
    except Exception as exc:
        print(type(exc).__name__, str(exc))
    response['PhysicalResourceId'] = endpoint_id
    req = urllib.request.Request(event['ResponseURL'], data=json.dumps(response).encode(), method='PUT',
                                 headers={'Content-Type': ''})
    with urllib.request.urlopen(req, timeout=15) as result:
        print('CloudFormation response:', result.status)
