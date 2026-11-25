#!/usr/bin/env python3
"""Render the CI stack and deploy it with local AWS CLI credentials (requires PyYAML)."""
import argparse
import gzip
import hashlib
import io
import json
import os
import subprocess
import tarfile
import tempfile
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent


class TemplateLoader(yaml.SafeLoader):
    pass


def include(loader, node):
    return yaml.load((ROOT / loader.construct_scalar(node)).read_text(), Loader=TemplateLoader)


TemplateLoader.add_constructor('!Include', include)
TemplateLoader.add_constructor('!File', lambda loader, node: (ROOT / loader.construct_scalar(node)).read_text())
for tag, intrinsic in [('!Ref', 'Ref'), ('!GetAtt', 'Fn::GetAtt'), ('!Sub', 'Fn::Sub')]:
    TemplateLoader.add_constructor(tag, lambda loader, node, fn=intrinsic: {fn: loader.construct_scalar(node)})


def render():
    template = yaml.load((ROOT / 'stack.yml').read_text(), Loader=TemplateLoader)
    # Sid is optional. Omitting it keeps the reviewed ceiling within IAM's 6144
    # character limit after CloudFormation expands the concrete ARNs.
    resources = template['Resources']
    workloads = resources['SandboxBoundary']['Properties']['PolicyDocument']['Statement']
    control_plane = resources['ControlPlaneBoundary']['Properties']['PolicyDocument']['Statement']
    for statement in workloads:
        if statement.get('Sid') in {'DiscoverRegionalInfrastructure', 'TagAtCreation', 'UseSandboxNetwork',
                                    'UseImages', 'AutoModeCreate', 'AutoModeManage', 'EksManagedSecurityGroup',
                                    'NodeInstanceProfile', 'NodeAndSnapshotRole'}:
            control_plane.append(statement.copy())
        statement.pop('Sid', None)
    for statement in control_plane:
        statement.pop('Sid', None)
    return template


def recovery_archive():
    repo = ROOT.parents[2]
    # Preserve a reviewed independent teardown even when a PR changes its cleanup
    # code, gets deleted or introduces the CI system before it reaches main.
    paths = [repo / '.github/ci/aws' / name for name in ('run.py', 'suites.py', 'verification.py')]
    paths.extend(repo / 'libraries/testAutomation' / name for name in ('Pipfile', 'Pipfile.lock'))
    paths.extend(sorted((repo / 'libraries/testAutomation/testAutomation').glob('*.py')))
    paths.append(repo / 'deployment/k8s/installK6Chart.sh')
    output = io.BytesIO()
    with gzip.GzipFile(fileobj=output, mode='wb', mtime=0) as zipped, tarfile.open(fileobj=zipped, mode='w') as tar:
        for path in paths:
            data = path.read_bytes()
            info = tarfile.TarInfo(str(path.relative_to(repo)))
            info.size = len(data)
            info.mode = 0o755 if path.stat().st_mode & 0o111 else 0o644
            tar.addfile(info, io.BytesIO(data))
    return output.getvalue()


def publish_recovery(aws, bucket, digest, archive):
    key = f'recovery/{digest}.tar.gz'
    with tempfile.NamedTemporaryFile() as source:
        source.write(archive)
        source.flush()
        # A content-addressed key is written once. A repeat deploy verifies that
        # the existing object hashes to the same value without overwriting it.
        put = subprocess.run([*aws, 's3api', 'put-object', '--bucket', bucket, '--key', key,
                              '--body', source.name, '--if-none-match', '*'], capture_output=True)
        if put.returncode:
            if b'PreconditionFailed' not in put.stderr:
                raise RuntimeError(put.stderr.decode())
            with tempfile.NamedTemporaryFile() as existing:
                subprocess.run([*aws, 's3api', 'get-object', '--bucket', bucket, '--key', key, existing.name],
                               stdout=subprocess.DEVNULL, check=True)
                if hashlib.sha256(Path(existing.name).read_bytes()).hexdigest() != digest:
                    raise ValueError('The recovery object does not match the deployed digest')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile')
    parser.add_argument('--region', default='us-west-2')
    parser.add_argument('--expected-account', default=os.environ.get('AWS_CI_ACCOUNT_ID'),
                        help='Expected sandbox account number. Defaults to AWS_CI_ACCOUNT_ID.')
    parser.add_argument('--worker-limit', type=int, default=12, help='CodeBuild project concurrency limit')
    parser.add_argument('--print-template', action='store_true')
    args = parser.parse_args()
    template = json.dumps(render(), separators=(',', ':'))
    if args.print_template:
        print(template)
        return
    aws = ['aws', '--region', args.region] + (['--profile', args.profile] if args.profile else [])

    def call(*arguments):
        return json.loads(subprocess.check_output([*aws, *arguments, '--output', 'json']))

    account = call('sts', 'get-caller-identity')['Account']
    if not args.expected_account or account != args.expected_account or args.region != 'us-west-2':
        parser.error('Select the expected sandbox account with --expected-account and the us-west-2 region')
    issuer = 'token.actions.githubusercontent.com'
    arn = f'arn:aws:iam::{account}:oidc-provider/{issuer}'
    providers = call('iam', 'list-open-id-connect-providers')['OpenIDConnectProviderList']
    if {'Arn': arn} not in providers:
        call('iam', 'create-open-id-connect-provider', '--url', 'https://' + issuer,
             '--client-id-list', 'sts.amazonaws.com')
    provider = call('iam', 'get-open-id-connect-provider', '--open-id-connect-provider-arn', arn)
    if 'sts.amazonaws.com' not in provider['ClientIDList']:
        subprocess.run([*aws, 'iam', 'add-client-id-to-open-id-connect-provider',
                        '--open-id-connect-provider-arn', arn, '--client-id', 'sts.amazonaws.com'], check=True)
    # EKS, Auto Mode and OpenSearch use AWS-managed service-linked roles. AWS
    # creates these during first use. Keep that global permission off the worker.
    services = [('eks.amazonaws.com', 'AmazonEKS'),
                ('elasticloadbalancing.amazonaws.com', 'ElasticLoadBalancing'),
                ('opensearchservice.amazonaws.com', 'AmazonOpenSearchService'),
                ('observability.aoss.amazonaws.com', 'AmazonOpenSearchServerless'),
                ('spot.amazonaws.com', 'EC2Spot')]
    for service, suffix in services:
        result = subprocess.run([*aws, 'iam', 'get-role', '--role-name', 'AWSServiceRoleFor' + suffix],
                                capture_output=True)
        if result.returncode and b'NoSuchEntity' in result.stderr:
            create = subprocess.run([*aws, 'iam', 'create-service-linked-role', '--aws-service-name', service],
                                    capture_output=True)
            if create.returncode and b'has been taken' not in create.stderr:
                raise RuntimeError(create.stderr.decode())
        elif result.returncode:
            raise RuntimeError(result.stderr.decode())
    archive = recovery_archive()
    digest = hashlib.sha256(archive).hexdigest()
    # Publish before updating the digest that CodeBuild will consume. For a new
    # installation the bucket is created by the initial stack, before any PR jobs.
    existing = subprocess.run([*aws, 'cloudformation', 'describe-stacks', '--stack-name', 'migrations-github-ci',
                               '--output', 'json'], capture_output=True)
    bucket = None
    if not existing.returncode:
        outputs = json.loads(existing.stdout)['Stacks'][0]['Outputs']
        bucket = next(o['OutputValue'] for o in outputs if o['OutputKey'] == 'ArtifactBucketName')
        publish_recovery(aws, bucket, digest, archive)
    elif b'does not exist' not in existing.stderr:
        raise RuntimeError(existing.stderr.decode())
    with tempfile.NamedTemporaryFile(mode='w', suffix='.json') as file:
        file.write(template)
        file.flush()
        subprocess.run([*aws, 'cloudformation', 'deploy', '--stack-name', 'migrations-github-ci',
                        '--template-file', file.name, '--capabilities', 'CAPABILITY_NAMED_IAM',
                        '--parameter-overrides', f'ProviderArn={arn}', f'RecoveryDigest={digest}',
                        f'WorkerLimit={args.worker_limit}',
                        '--no-fail-on-empty-changeset'], check=True)
    if bucket is None:
        stack = call('cloudformation', 'describe-stacks', '--stack-name', 'migrations-github-ci')['Stacks'][0]
        bucket = next(o['OutputValue'] for o in stack['Outputs'] if o['OutputKey'] == 'ArtifactBucketName')
        publish_recovery(aws, bucket, digest, archive)


if __name__ == '__main__':
    main()
