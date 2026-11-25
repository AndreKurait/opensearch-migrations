#!/usr/bin/env python3
"""Run an integration suite in its own CodeBuild worker and AWS namespace."""

import argparse
from contextlib import contextmanager
from contextvars import ContextVar
from dataclasses import asdict
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import traceback
import urllib.request

from suites import ALIASES, AOSS_SNAPSHOT_PREFIX, DISABLED_SUITES, SUITES, byos_snapshot, fixture_uri, nodepool
from verification import dashboards, junit, nodepools

HERE = Path(__file__).resolve().parent
DEADLINE = ContextVar('worker_deadline', default=None)


@contextmanager
def phase(name, minutes):
    cutoff = time.monotonic() + minutes * 60
    outer = DEADLINE.get()
    token = DEADLINE.set((cutoff, name) if outer is None or cutoff < outer[0] else outer)
    try:
        yield
    finally:
        DEADLINE.reset(token)


def command(*args, cwd=None, capture=False, input=None, **kwargs):
    print('+', ' '.join(map(str, args)), flush=True)
    deadline = DEADLINE.get()
    timeout = deadline[0] - time.monotonic() if deadline else None
    if timeout is not None and timeout <= 0:
        raise TimeoutError(f'{deadline[1]} exceeded its time limit')
    cmd = list(map(str, args))
    process = subprocess.Popen(
        cmd,
        cwd=cwd,
        text=True,
        start_new_session=True,
        stdin=subprocess.PIPE if input is not None else None,
        stdout=subprocess.PIPE if capture else None,
        **kwargs,
    )
    try:
        output, errors = process.communicate(input=input, timeout=timeout)
    except BaseException as exc:
        # Terminate background image mirrors as well as their bootstrap shell.
        try:
            os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        process.wait()
        if isinstance(exc, subprocess.TimeoutExpired):
            raise TimeoutError(f'{deadline[1]} exceeded its time limit') from exc
        raise
    if process.returncode:
        raise subprocess.CalledProcessError(process.returncode, cmd, output, errors)
    return output.strip() if capture else None


def worker_cidr():
    cidr = os.environ.get('CI_WORKER_CIDR')
    if not cidr:
        with urllib.request.urlopen('https://checkip.amazonaws.com', timeout=10) as response:
            cidr = response.read(64).decode().strip() + '/32'
    network = ipaddress.IPv4Network(cidr)
    if network.prefixlen != 32:
        raise ValueError('CI_WORKER_CIDR must identify one worker egress IPv4 address')
    return str(network)


def mirror_prefix(stage):
    return f'migration-ecr-{stage}-mirrors/'


def release_bootstrap(source, destination, stage):
    """Scope OCI repositories in immutable releases that predate --ecr-mirror-prefix."""
    script = source.read_text()
    if '--ecr-mirror-prefix' in script:
        return source
    prefix = mirror_prefix(stage)
    # Keep source registries, chart filenames and upstream third-party image names intact.
    for original, scoped in [
        ('"mirrored/', f'"{prefix}mirrored/'),
        ('$ECR/mirrored"', f'$ECR/{prefix}mirrored"'),
        ('${ECR_HOST}/mirrored/', f'${{ECR_HOST}}/{prefix}mirrored/'),
        ('"charts/${name}"', f'"{prefix}charts/${{name}}"'),
        ('oci://${ecr_host}/charts', f'oci://${{ecr_host}}/{prefix}charts'),
        ('oci://${ECR}/charts/', f'oci://${{ECR}}/{prefix}charts/'),
    ]:
        if original not in script:
            raise ValueError(f'Unrecognized bootstrap mirror format, expected {original!r}')
        script = script.replace(original, scoped)
    # Older published bootstraps saved empty registry credentials when the
    # sandbox could not request a public ECR token. Use anonymous public pulls.
    script = re.sub(
        r'aws ecr-public get-login-password --region us-east-1 2>/dev/null \| \\\n[ \t]+'
        r'(?:crane auth login|helm registry login) public\.ecr\.aws -u AWS --password-stdin 2>/dev/null \|\| true',
        ': # Pull from ECR Public anonymously.',
        script,
    )
    destination.write_text(script)
    return destination


def enable_release_tag_projection(repository, harness):
    """Backport only the CloudTrail transport when the published test runner predates the sandbox."""
    package = Path('libraries/testAutomation/testAutomation')
    verifier = repository / package / 'resource_tag_verifier.py'
    text = verifier.read_text()
    marker = '    if __package__:\n        from .tag_event_pages import iter_tag_event_pages'
    if marker in text:
        return
    start = '    ct = boto3.client("cloudtrail", region_name=region, config=_cloudtrail_retry_config())'
    end = '        for event in page["Events"]:'
    approved = (harness / package / 'resource_tag_verifier.py').read_text()
    if text.count(start) != 1 or text.count(end) != 1 or approved.count(marker) != 1:
        raise ValueError('Cannot adapt the published tag-verification paginator to the sandbox')
    adapter = approved[approved.index(marker):approved.index(end, approved.index(marker))]
    text = text[:text.index(start)] + adapter + text[text.index(end, text.index(start)):]
    shutil.copy2(harness / package / 'tag_event_pages.py', repository / package)
    verifier.write_text(text)


class Worker:
    def __init__(self, suite, workdir):
        self.suite = suite
        self.root = Path.cwd()
        self.temp = Path(workdir)
        self.results = Path(os.environ.get('CODEBUILD_SRC_DIR', self.root)) / 'ci-results'
        self.results.mkdir(exist_ok=True, parents=True)
        self.stage = os.environ.get('CI_STAGE', '')
        if not re.fullmatch(r'ci-[a-z0-9]{1,12}', self.stage):
            raise ValueError('CI_STAGE must be ci- followed by 1 to 12 lowercase letters or digits')
        self.region = os.environ.get('AWS_REGION', 'us-west-2')
        os.environ['AWS_DEFAULT_REGION'] = self.region
        os.environ['AWS_PAGER'] = ''
        self.role = os.environ['CI_WORKER_ROLE_ARN']
        if not re.fullmatch(r'arn:aws:iam::\d{12}:role/[A-Za-z0-9_+=,.@-]+', self.role):
            raise ValueError('CI_WORKER_ROLE_ARN must identify the dedicated sandbox worker role')
        self.account = self.role.split(':')[4]
        self.cidr = worker_cidr()
        self.awscli = shutil.which('aws')
        self.release = os.environ.get('CI_RELEASE_VERSION', '')
        self.source_build = not self.release or self.suite == 'cdc-k6'
        self.bootstrap_script = None
        self.ma = self.stage + '-ma'
        self.cluster = self.cluster_name(self.stage)
        self.kube_context = self.cluster
        self.build_stage = 'ci-' + hashlib.sha256((self.stage + '-build').encode()).hexdigest()[:12]
        self.build_cluster = self.cluster_name(self.build_stage)
        self.cdc = suite.startswith('cdc')
        fixture_bucket = f'migrations-ci-fixtures-{self.account}-{self.region}'
        self.fixture_bucket = os.environ.get('CI_SNAPSHOT_BUCKET', fixture_bucket)
        self.fixture_region = os.environ.get('CI_SNAPSHOT_REGION', self.region)
        if self.fixture_region != self.region:
            raise ValueError('The snapshot fixture replica must be in the worker region')
        self.snapshot = byos_snapshot(self.fixture_bucket) if suite == 'byos' else {}
        self.fixture_uri = ''
        if suite == 'byos':
            self.fixture_uri = self.snapshot['S3_REPO_URI']
        elif suite == 'aoss':
            self.fixture_uri = fixture_uri(self.fixture_bucket, AOSS_SNAPSHOT_PREFIX, os.environ.get('S3_REPO_URI'))
        self.source = self.snapshot.get(
            'SOURCE_VERSION', os.environ.get('SOURCE_VERSION', SUITES[suite].source)
        )
        self.target = os.environ.get('TARGET_VERSION', SUITES[suite].target)
        defaults = os.environ.get('AOSS_TEST_IDS') if suite == 'aoss' else None
        test_ids = os.environ.get('TEST_IDS') or defaults or SUITES[suite].tests
        self.tests = ','.join(re.split(r'[,;\s]+', test_ids.strip())) if test_ids else ''
        self.exports = {}
        self.details = {}
        self.test_args = []
        self.verified_dashboards = False
        self.network = {}
        self.classic = self.suite == 'cdc-aoss' or (self.suite == 'aoss' and '0021' in self.tests.split(','))
        self.nextgen = self.suite == 'aoss' and '0024' in self.tests.split(',')
        if self.suite == 'aoss' and (not self.tests or set(self.tests.split(',')) - {'0021', '0024'}):
            raise ValueError('AOSS_TEST_IDS/TEST_IDS must select 0021, 0024 or both')
        if not os.environ.get('CI_ROLE_BOUNDARY_ARN'):
            raise ValueError('CI_ROLE_BOUNDARY_ARN is required')

    def cluster_name(self, stage):
        return f'migration-eks-cluster-{stage}-{self.region}'

    def aws(self, *args):
        # Keep errors for the not-found tests in cleanup and emit them for access denials.
        try:
            output = command(
                self.awscli,
                *args,
                '--region',
                self.region,
                '--output',
                'json',
                capture=True,
                stderr=subprocess.PIPE,
            )
        except subprocess.CalledProcessError as error:
            print(error.stderr, file=sys.stderr, flush=True)
            raise
        return json.loads(output) if output else {}

    def kube(self, *args, **kwargs):
        return command('kubectl', '--context', self.kube_context, '--namespace', 'ma', *args, **kwargs)

    def pipenv(self, *args, **kwargs):
        return command('pipenv', *args, cwd=self.root / 'libraries/testAutomation', **kwargs)

    def outputs(self, name):
        return {
            v['OutputKey']: v['OutputValue']
            for v in self.aws('cloudformation', 'describe-stacks', '--stack-name', name)['Stacks'][0][
                'Outputs'
            ]
        }

    def cfn(self, name, template, **params):
        command(
            self.awscli,
            'cloudformation',
            'deploy',
            '--region',
            self.region,
            '--stack-name',
            name,
            '--template-file',
            template,
            '--no-fail-on-empty-changeset',
            '--parameter-overrides',
            *(f'{key}={value}' for key, value in params.items()),
            '--tags',
            f'MATestStage={self.stage}',
            'MigrationCI=github',
        )
        return self.outputs(name)

    def build(self):
        if self.source_build:
            command('./deployment/k8s/aws/assemble-bootstrap.sh')
            self.bootstrap_script = self.root / 'deployment/k8s/aws/dist/aws-bootstrap.sh'
        else:
            if not re.fullmatch(r'\d+\.\d+\.\d+', self.release):
                raise ValueError('CI_RELEASE_VERSION requires the exact published semver tag')
            self.bootstrap_script = self.temp / 'aws-bootstrap.sh'
            release_url = 'https://github.com/opensearch-project/opensearch-migrations/releases/download'
            url = f'{release_url}/{self.release}/aws-bootstrap.sh'
            command(
                'curl',
                '--fail',
                '--silent',
                '--show-error',
                '--location',
                '--output',
                self.bootstrap_script,
                url,
            )

    def bootstrap(self, build_phase=False):
        stage = self.build_stage if build_phase else self.stage
        stack = self.stage + '-build' if build_phase else self.ma
        script = self.bootstrap_script
        if self.release:
            # Includes the k6 canary that assembles a bootstrap from the pinned release source.
            script = release_bootstrap(script, self.temp / f'{stage}-bootstrap.sh', stage)
        args = [
            'bash',
            script,
            '--region',
            self.region,
            '--stage',
            stage,
            '--stack-name',
            stack,
            '--eks-access-principal-arn',
            self.role,
            '--skip-console-exec',
            '--skip-setting-k8s-context',
            '--kubectl-context',
            self.cluster_name(stage),
        ]
        if self.source_build:
            args.extend(['--build', '--base-dir', self.root])
        else:
            args.extend(['--version', self.release])
        if self.network and not build_phase:
            args.extend(
                [
                    '--deploy-import-vpc-cfn',
                    '--vpc-id',
                    self.network['VpcId'],
                    '--subnet-ids',
                    self.network['IsolatedSubnetIds' if self.suite == 'isolated' else 'SubnetIds'],
                ]
            )
        else:
            args.append('--deploy-create-vpc-cfn')
        if self.suite == 'isolated' and not build_phase:
            args.extend(
                ['--ma-images-source', self.exports['MIGRATIONS_ECR_REGISTRY'], '--create-vpc-endpoints']
            )
        elif self.cdc:
            args.extend(
                [
                    '--tags',
                    f'MATestOwner=migrations-ci,MATestStage={stage},MigrationCI=github',
                    '--enforce-tags-on-create-for-tests',
                ]
            )
            if self.source_build:
                values = self.temp / 'nodepools.json'
                values.write_text(json.dumps({'workloadsNodePool': nodepool(self.suite == 'cdc-aoss')}))
                args.extend(['--helm-values', values])
            if any(test.startswith('008') for test in self.tests.split(',')):
                if not self.source_build:
                    raise ValueError('The k6 runner is only published by source builds')
                args.append('--with-load-test-images')
        elif self.suite != 'isolated':
            args.append('--use-general-node-pool')
        env = dict(
            os.environ,
            PATH=f'{HERE}/bootstrap-bin:{os.environ["PATH"]}',
            CI_AWS_CLI=self.awscli,
            CI_WORKER_CIDR=self.cidr,
            CI_VPC_CIDR='10.200.0.0/16' if self.network else '10.212.0.0/16',
            CI_INHERIT_STACK_TAGS=str(self.cdc).lower(),
            ECR_MIRROR_PREFIX=mirror_prefix(stage).rstrip('/'),
        )
        command(*args, env=env)
        exports = self.outputs(stack)['MigrationsExportString']
        self.exports = dict(
            s.strip().removeprefix('export ').split('=', 1) for s in exports.split(';') if '=' in s
        )
        if self.exports['MIGRATIONS_EKS_CLUSTER_NAME'] != self.cluster_name(stage):
            raise RuntimeError('Bootstrap returned a different EKS cluster')

    def managed(self, key, version, subnet_ids, sg):
        name = f'{self.stage}-{key}'
        principals = [
            self.role,
            self.pod_role,
            f'arn:aws:iam::{self.account}:role/{self.cluster}-snapshot-role',
        ]
        policy = {
            'Version': '2012-10-17',
            'Statement': [
                {
                    'Effect': 'Allow',
                    'Principal': {'AWS': principals},
                    'Action': 'es:ESHttp*',
                    'Resource': f'arn:aws:es:{self.region}:{self.account}:domain/{name}/*',
                }
            ],
        }
        cluster = dict(
            clusterId=key,
            clusterName=name,
            clusterVersion=version,
            clusterType='OPENSEARCH_MANAGED_SERVICE',
            clusterSubnetIds=subnet_ids[:2],
            clusterSecurityGroupIds=[sg],
            publicAccess=False,
            domainRemovalPolicy='DESTROY',
            accessPolicies=policy,
        )
        if self.suite == 'byos':
            large = self.snapshot['TARGET_CLUSTER_SIZE'] == 'large'
            cluster.update(
                dataNodeType='r8g.8xlarge.search' if large else 'r8g.large.search',
                dataNodeCount=24 if large else 2,
                dedicatedManagerNodeCount=3 if large else 0,
                dedicatedManagerNodeType='m6g.2xlarge.search' if large else 'm6g.large.search',
                ebsEnabled=True,
                ebsVolumeSize=2048 if large else 100,
                nodeToNodeEncryptionEnabled=True,
            )
            if large:
                cluster.update(ebsThroughput=1000, ebsIops=10000)
        return cluster

    @property
    def pod_role(self):
        return f'arn:aws:iam::{self.account}:role/{self.cluster}-migrations-role'

    def clusters(self):
        vpc = self.exports['VPC_ID']
        eks_net = self.aws('eks', 'describe-cluster', '--name', self.cluster)['cluster']['resourcesVpcConfig']
        endpoints = self.cfn(
            self.stage + '-endpoints',
            HERE / 'endpoints.yaml',
            Stage=self.stage,
            VpcId=vpc,
            Subnets=','.join(eks_net['subnetIds']),
            ClusterGroup=eks_net['clusterSecurityGroupId'],
            Classic=str(self.classic).lower(),
            NextGen=str(self.nextgen).lower(),
        )
        # Isolated EKS pods stay in route-less subnets; managed domains use the private NAT subnets.
        subnets = self.network['SubnetIds'].split(',') if self.suite == 'isolated' else eks_net['subnetIds']
        clusters = []
        if self.suite not in ('aoss', 'byos'):
            clusters.append(self.managed('source', self.source, subnets, endpoints['SecurityGroup']))
        if self.target != 'AOSS':
            clusters.append(self.managed('target', self.target, subnets, endpoints['SecurityGroup']))
        if self.classic:
            collections = (
                {'target': 'SEARCH'}
                if self.suite == 'cdc-aoss'
                else {'search': 'SEARCH', 'timeseries': 'TIMESERIES', 'vector': 'VECTORSEARCH'}
            )
            clusters.extend(
                dict(
                    clusterId=key,
                    clusterName=f'{self.stage}-{key}',
                    clusterType='OPENSEARCH_SERVERLESS',
                    collectionType=kind,
                    standbyReplicas='DISABLED',
                    domainRemovalPolicy='DESTROY',
                    dataAccessPrincipals=[self.role, self.pod_role],
                    vpcEndpointId=endpoints['ClassicEndpoint'],
                )
                for key, kind in collections.items()
            )
        if clusters:
            context = self.temp / 'domains.json'
            context.write_text(
                json.dumps(
                    dict(
                        stage=self.stage,
                        vpcId=vpc,
                        clusters=clusters,
                        tags={'MigrationCI': 'github', 'MATestStage': self.stage},
                    )
                )
            )
            command(
                HERE.parents[2] / 'test/awsDeployCluster.sh',
                '--stage',
                self.stage,
                '--context-file',
                context,
                '--vpc-id',
                vpc,
                cwd=self.root / 'test',
                env=dict(os.environ, MIGRATIONS_TEST_REPO_PATH=str(self.root)),
            )
            self.details = json.loads((self.root / f'test/tmp/cluster-details-{self.stage}.json').read_text())
        return endpoints

    def configure_clusters(self, endpoints):
        with phase('cluster configuration', 15):
            for key in ['source', 'target']:
                if key in self.details and not (key == 'target' and self.target == 'AOSS'):
                    self.configmap(
                        key, self.source if key == 'source' else self.target, self.details[key]['endpoint']
                    )
        env = {}
        if self.suite == 'cdc-aoss':
            env['AOSS_CDC_ENDPOINT'] = self.details['target']['endpoint']
        if self.suite == 'byos':
            env.update(
                BYOS_SNAPSHOT_NAME=self.snapshot['SNAPSHOT_NAME'],
                BYOS_S3_REPO_URI=self.snapshot['S3_REPO_URI'].rstrip('/') + '/',
                BYOS_S3_REGION=self.fixture_region,
                BYOS_POD_REPLICAS=self.snapshot['RFS_WORKERS'],
                BYOS_MONITOR_RETRY_LIMIT=os.environ.get('MONITOR_RETRY_LIMIT', '1000000'),
            )
        if self.suite == 'aoss':
            env.update(
                AOSS_S3_REGION=self.fixture_region,
                AOSS_S3_REPO_URI=self.fixture_uri,
                AOSS_SNAPSHOT_NAME=os.environ.get('SNAPSHOT_NAME', 'os1x-aoss-osb-data'),
                AOSS_MONITOR_RETRY_LIMIT=os.environ.get('MONITOR_RETRY_LIMIT', '33'),
            )
            env.update(
                {f'AOSS_{key.upper()}_ENDPOINT': details['endpoint'] for key, details in self.details.items()}
            )
            if os.environ.get('RFS_WORKERS'):
                env['AOSS_POD_REPLICAS'] = str(int(os.environ['RFS_WORKERS']))
        if self.nextgen:
            prefix = self.stage + '-ng'
            with phase('NextGen collection deployment', 30):
                outputs = self.cfn(
                    self.stage + '-nextgen',
                    HERE.parents[2] / 'test/aossNextGen/collections.yaml',
                    CollectionPrefix=prefix,
                    DataAccessPrincipals=','.join([self.role, self.pod_role]),
                    VpcEndpointId=endpoints['NextGenEndpoint'],
                )
            env.update(
                AOSS_COLLECTION_PREFIX=prefix,
                AOSS_ACCOUNT_ENDPOINT=f'https://{self.account}.aoss.{self.region}.on.aws',
                AOSS_NEXTGEN_SEARCH_ENDPOINT=outputs['SearchCollectionEndpoint'],
                AOSS_NEXTGEN_VECTOR_ENDPOINT=outputs['VectorCollectionEndpoint'],
            )
        if env:
            with phase('console configuration', 15):
                self.kube('set', 'env', 'statefulset/migration-console', *(f'{k}={v}' for k, v in env.items()))
                self.kube('rollout', 'status', 'statefulset/migration-console', '--timeout=120s')

    def configmap(self, key, version, endpoint):
        engine, number = version.split('_')
        slug = {'ES': 'elasticsearch', 'OS': 'opensearch'}[engine] + '-' + number.replace('.', '-')
        config = {'endpoint': endpoint, 'sigv4': {'region': self.region, 'service': 'es'}}
        if key == 'source':
            config['version'] = version
        manifest = {
            'apiVersion': 'v1',
            'kind': 'ConfigMap',
            'metadata': {'name': f'{key}-{slug}-migration-config', 'namespace': 'ma'},
            'data': {'cluster-config': json.dumps(config)},
        }
        self.kube('apply', '-f', '-', input=json.dumps(manifest))

    def transform_images(self):
        registry = self.exports['MIGRATIONS_ECR_REGISTRY']
        password = command(self.awscli, 'ecr', 'get-login-password', '--region', self.region, capture=True)
        command(
            'docker', 'login', '--username', 'AWS', '--password-stdin', registry.split('/')[0], input=password
        )
        for bank in ['basic', 'sequence', 'context']:
            assets = (
                self.root / f'migrationConsole/lib/integ_test/integ_test/transform_assets/mountable/{bank}'
            )
            digest = hashlib.sha256()
            for path in sorted(p for p in assets.rglob('*') if p.is_file()):
                digest.update(str(path.relative_to(assets)).encode() + b'\0')
                digest.update(hashlib.sha256(path.read_bytes()).digest())
            tag = f'transforms-{bank}-{digest.hexdigest()[:20]}'
            image = json.loads(
                command(
                    'deployment/k8s/package-transforms.sh',
                    assets,
                    registry,
                    tag,
                    '--output',
                    'json',
                    capture=True,
                )
            )['image']
            if '@sha256:' not in image:
                raise RuntimeError('Transform packager did not return a digest-pinned image')
            self.test_args.extend([f'--transform-image-{bank}', image])
        self.test_args.extend(
            [
                '--speedup-factor',
                os.environ.get('SPEEDUP_FACTOR', '20'),
                '--k6-runner-image',
                f'{registry}:migrations_k6_runner_latest',
            ]
        )

    def execute(self):
        if self.cdc and self.release and os.environ.get('CI_TAG_ORACLE_FUNCTION'):
            enable_release_tag_projection(self.root, HERE.parents[2])
        if self.fixture_uri:
            # Fail before starting EKS or the target domains when an approved fixture hasn't been copied yet.
            prefix = self.fixture_uri.removeprefix(f's3://{self.fixture_bucket}/').rstrip('/') + '/index-'
            listing = self.aws('s3api', 'list-objects-v2', '--bucket', self.fixture_bucket,
                               '--prefix', prefix, '--max-keys', '1', '--no-paginate')
            if not listing.get('Contents'):
                raise RuntimeError(f'Missing snapshot metadata at {self.fixture_uri} in {self.fixture_region}')
            self.aws('s3api', 'head-object', '--bucket', self.fixture_bucket, '--key', listing['Contents'][0]['Key'])
        self.build()
        self.pipenv('install', '--deploy')
        if self.suite == 'isolated':
            with phase('isolated build stack', 90):
                self.bootstrap(build_phase=True)
        if self.suite in ('cfn-import', 'isolated'):
            with phase('test VPC deployment', 10 if self.suite == 'cfn-import' else 90):
                self.network = self.cfn(
                    self.stage + '-network',
                    HERE / 'vpc.json',
                    Stage=self.stage,
                    Isolated=str(self.suite == 'isolated').lower(),
                )
        bootstrap_minutes = 90 if self.suite in ('aoss', 'isolated', 'cfn-import', 'cfn-create') else 150
        # Managed clusters for these three suites share the private EKS VPC; their deploys use the same deadline.
        bootstrap_end = time.monotonic() + bootstrap_minutes * 60
        with phase('EKS deployment and install', bootstrap_minutes):
            self.bootstrap()
        if self.suite.startswith('cfn-'):
            with phase('console deployment validation', 15):
                self.kube('wait', '--for=condition=ready', 'pod/migration-console-0', '--timeout=600s')
                self.kube('exec', 'migration-console-0', '--', '/bin/bash', '-lc', 'console --version')
            return
        if self.suite == 'isolated':
            # Verify the pod and curl are usable before asserting that internet connectivity fails.
            self.kube('exec', 'migration-console-0', '--', 'curl', '--version')
            self.kube(
                'exec',
                'migration-console-0',
                '--',
                'sh',
                '-ec',
                'if curl --max-time 10 -sSI https://aws.amazon.com; then exit 1; else rc=$?; fi; '
                'case "$rc" in 6|7|28) exit 0;; *) exit "$rc";; esac',
            )
            self.remove_ma(self.stage + '-build', self.build_cluster)
        cluster_minutes = {'aoss': 60, 'byos': 45, 'cdc-aoss': 60, 'isolated': 30}.get(self.suite)
        if cluster_minutes is None:
            cluster_minutes = (bootstrap_end - time.monotonic()) / 60
        with phase('private cluster deployment', cluster_minutes):
            endpoints = self.clusters()
        self.configure_clusters(endpoints)
        if self.suite == 'eks':
            with phase('dashboard validation', 5):
                dashboards(self)
            self.verified_dashboards = True
        if self.cdc:
            self.test_args.extend(
                [
                    '--verify-resource-tags',
                    '--ma-stack-name',
                    self.ma,
                    '--aws-region',
                    self.region,
                    '--eks-cluster-name',
                    self.cluster,
                ]
            )
            if self.suite != 'cdc-aoss':
                with phase('build transform test images', 20):
                    self.transform_images()
        if self.tests and self.tests != 'all':
            self.test_args.extend(['--test-ids', self.tests])
        test_minutes = {'byos': 720, 'cdc': 180, 'cdc-k6': 180}.get(self.suite, 120)
        with phase('migration tests', test_minutes):
            self.pipenv(
                'run',
                'app',
                '--source-version',
                self.source,
                '--target-type' if self.target == 'AOSS' else '--target-version',
                self.target,
                '--reuse-clusters',
                '--skip-delete',
                '--skip-install',
                '--kube-context',
                self.kube_context,
                '--test-reports-dir',
                self.results,
                '--copy-logs',
                *self.test_args,
            )
        if self.cdc and self.source_build:
            with phase('workload NodePool validation', 5):
                nodepools(self, nodepool(self.suite == 'cdc-aoss'))
        if self.suite == 'isolated':
            with phase('CloudWatch connectivity', 5):
                events = self.aws(
                    'logs',
                    'filter-log-events',
                    '--log-group-name',
                    f'/migration-assistant-{self.stage}-{self.region}/logs',
                    '--limit',
                    '1',
                    '--no-paginate',
                ).get('events')
            if not events:
                print('No CloudWatch events have arrived via the VPC endpoint.', flush=True)

    def describe_stack(self, stack):
        try:
            return self.aws('cloudformation', 'describe-stacks', '--stack-name', stack)['Stacks'][0]
        except subprocess.CalledProcessError as error:
            if 'ValidationError' in error.stderr and 'does not exist' in error.stderr:
                return None
            raise

    def remove_stack(self, stack):
        info = self.describe_stack(stack)
        if not info:
            return
        print(f'Deleting {stack}', flush=True)
        if info['StackStatus'] != 'DELETE_IN_PROGRESS':
            self.aws('cloudformation', 'delete-stack', '--stack-name', stack)
        try:
            self.aws('cloudformation', 'wait', 'stack-delete-complete', '--stack-name', stack)
        except subprocess.CalledProcessError:
            summary = self.aws(
                'cloudformation', 'describe-stack-events', '--stack-name', stack, '--max-items', '40'
            )['StackEvents']
            for event in summary:
                if event['ResourceStatus'] == 'DELETE_FAILED':
                    print(event['LogicalResourceId'], event.get('ResourceStatusReason', ''), flush=True)
            raise
        if self.describe_stack(stack):
            raise RuntimeError(f'Stack {stack} still exists after deletion')

    def teardown_application(self, cluster):
        try:
            live = self.aws('eks', 'describe-cluster', '--name', cluster)['cluster']
        except subprocess.CalledProcessError as error:
            if 'ResourceNotFoundException' not in error.stderr:
                raise
            live = {}
        if live.get('status') == 'ACTIVE':
            cidrs = live['resourcesVpcConfig'].get('publicAccessCidrs', [])
            if self.cidr not in cidrs:
                update = self.aws(
                    'eks',
                    'update-cluster-config',
                    '--name',
                    cluster,
                    '--resources-vpc-config',
                    f'endpointPublicAccess=true,endpointPrivateAccess=true,publicAccessCidrs={self.cidr}',
                )['update']
                for _ in range(90):
                    status = self.aws(
                        'eks', 'describe-update', '--name', cluster, '--update-id', update['id']
                    )['update']['status']
                    if status == 'Successful':
                        break
                    if status != 'InProgress':
                        raise RuntimeError(f'EKS cleanup endpoint update {status}')
                    time.sleep(10)
                else:
                    raise TimeoutError('EKS cleanup endpoint update')
            command(
                self.awscli,
                'eks',
                'update-kubeconfig',
                '--name',
                cluster,
                '--region',
                self.region,
                '--alias',
                cluster,
            )
            try:
                self.pipenv('run', 'app', '--delete-only', '--kube-context', cluster)
            finally:
                command(
                    'kubectl',
                    '--context',
                    cluster,
                    'delete',
                    'namespace',
                    'ma',
                    '--ignore-not-found',
                    '--timeout=300s',
                )

    def remove_instance_profiles(self, stack):
        resources = self.aws('cloudformation', 'list-stack-resources', '--stack-name', stack)[
            'StackResourceSummaries'
        ]
        for resource in resources:
            if resource['ResourceType'] != 'AWS::IAM::Role' or resource['ResourceStatus'] == 'DELETE_COMPLETE':
                continue
            role = resource.get('PhysicalResourceId')
            if not role:
                continue
            try:
                profiles = self.aws('iam', 'list-instance-profiles-for-role', '--role-name', role)[
                    'InstanceProfiles'
                ]
            except subprocess.CalledProcessError as error:
                if 'NoSuchEntity' in error.stderr:
                    continue
                raise
            for profile in profiles:
                name = profile['InstanceProfileName']
                self.aws(
                    'iam',
                    'remove-role-from-instance-profile',
                    '--instance-profile-name',
                    name,
                    '--role-name',
                    role,
                )
                self.aws('iam', 'delete-instance-profile', '--instance-profile-name', name)

    def remove_orphan_groups(self, cluster):
        groups = self.aws(
            'ec2', 'describe-security-groups', '--filters', f'Name=tag:aws:eks:cluster-name,Values={cluster}'
        )['SecurityGroups']
        for group in groups:
            for direction, field in [('ingress', 'IpPermissions'), ('egress', 'IpPermissionsEgress')]:
                if group[field]:
                    self.aws(
                        'ec2',
                        f'revoke-security-group-{direction}',
                        '--group-id',
                        group['GroupId'],
                        '--ip-permissions',
                        json.dumps(group[field]),
                    )
        for group in groups:
            for retry in range(10):
                try:
                    self.aws('ec2', 'delete-security-group', '--group-id', group['GroupId'])
                    break
                except subprocess.CalledProcessError as error:
                    if 'InvalidGroup.NotFound' in error.stderr:
                        break
                    if 'DependencyViolation' not in error.stderr or retry == 9:
                        raise
                    time.sleep(60)

    def remove_ma(self, stack, cluster):
        info = self.describe_stack(stack)
        if not info:
            self.remove_orphan_groups(cluster)
            return
        if info['StackStatus'] != 'DELETE_IN_PROGRESS':
            try:
                self.teardown_application(cluster)
            except (OSError, RuntimeError, subprocess.CalledProcessError) as error:
                print(
                    f'Application teardown failed for {cluster}: {error}. Continuing infrastructure cleanup.'
                )
            self.remove_instance_profiles(stack)
        try:
            self.remove_stack(stack)
        except subprocess.CalledProcessError:
            info = self.describe_stack(stack)
            if info and info['StackStatus'] == 'DELETE_FAILED':
                self.remove_orphan_groups(cluster)
                self.remove_instance_profiles(stack)
                self.remove_stack(stack)
            else:
                raise
        self.remove_orphan_groups(cluster)

    def remove_bucket(self, stage):
        """Recover the Helm-created bucket if cancellation interrupted its pre-delete hook."""
        bucket = f'migrations-default-{self.account}-{stage}-{self.region}'
        args = ('--bucket', bucket, '--expected-bucket-owner', self.account)
        try:
            self.aws('s3api', 'head-bucket', *args)
        except subprocess.CalledProcessError as error:
            if '(404)' in error.stderr or 'NoSuchBucket' in error.stderr:
                return
            raise
        # Re-read the first page after deletion to avoid losing the page marker we just removed.
        while True:
            page = self.aws('s3api', 'list-object-versions', *args, '--max-keys', '1000', '--no-paginate')
            versions = page.get('Versions', []) + page.get('DeleteMarkers', [])
            if not versions:
                break
            objects = [{'Key': v['Key'], 'VersionId': v['VersionId']} for v in versions]
            request = self.temp / 's3-delete.json'
            request.write_text(json.dumps({'Objects': objects, 'Quiet': True}))
            result = self.aws('s3api', 'delete-objects', *args, '--delete', f'file://{request}')
            if result.get('Errors'):
                raise RuntimeError(f'{bucket}: batch delete failed: {result["Errors"]}')
        self.aws('s3api', 'delete-bucket', *args)

    def remove_mirrors(self, stage):
        prefix = mirror_prefix(stage)
        repos = self.aws('ecr', 'describe-repositories')['repositories']
        for repo in repos:
            name = repo['repositoryName']
            if name.startswith(prefix):
                try:
                    self.aws('ecr', 'delete-repository', '--repository-name', name, '--force')
                except subprocess.CalledProcessError as error:
                    if 'RepositoryNotFoundException' not in error.stderr:
                        raise

    def cleanup(self):
        failures = []

        def attempt(name, step, *args):
            try:
                step(*args)
            except Exception as error:
                traceback.print_exc()
                failures.append((name, str(error)))

        if self.verified_dashboards:
            attempt('dashboard deletion', dashboards, self, True)
        for stack in [
            self.stage + '-nextgen',
            f'OpenSearch-{self.stage}-{self.region}',
            self.stage + '-endpoints',
        ]:
            attempt(f'delete {stack}', self.remove_stack, stack)
        attempt(f'delete {self.ma}', self.remove_ma, self.ma, self.cluster)
        attempt('delete the migration snapshot bucket', self.remove_bucket, self.stage)
        attempt('delete image and chart mirrors', self.remove_mirrors, self.stage)
        if self.suite == 'isolated':
            attempt('delete build stack', self.remove_ma, self.stage + '-build', self.build_cluster)
            attempt('delete the build snapshot bucket', self.remove_bucket, self.build_stage)
            attempt('delete build image and chart mirrors', self.remove_mirrors, self.build_stage)
        if self.suite in ('cfn-import', 'isolated'):
            attempt('delete test VPC', self.remove_stack, self.stage + '-network')
        return failures


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('suite', choices=sorted([*SUITES, *ALIASES, 'cleanup']))
    parser.add_argument(
        '--plan', action='store_true', help='Print the suite configuration without using credentials'
    )
    parser.add_argument(
        '--cleanup', action='store_true', help='Recover and delete this CI_STAGE in a fresh worker'
    )
    args = parser.parse_args()
    requested = os.environ.get('CI_SUITE') if args.suite == 'cleanup' else args.suite
    suite = ALIASES.get(requested, requested)
    if suite not in SUITES:
        parser.error('CI_SUITE must identify the suite being recovered')
    cleanup = args.cleanup or args.suite == 'cleanup'
    if args.plan:
        print(json.dumps(dict(suite=suite, enabled=suite not in DISABLED_SUITES, **asdict(SUITES[suite])), indent=2))
        return 0
    if suite in DISABLED_SUITES and not cleanup:
        parser.error(f'{suite} is temporarily disabled because its external snapshot fixture is unavailable')

    def interrupt(signum, _frame):
        raise InterruptedError(f'Worker received signal {signum}')

    signal.signal(signal.SIGTERM, interrupt)
    errors = []
    with tempfile.TemporaryDirectory(prefix='migration-ci-') as temp:
        try:
            worker = Worker(suite, temp)
            # Verify all account-specific inputs before invoking anything that can change AWS state.
            identity = worker.aws('sts', 'get-caller-identity')
            role_name = worker.role.rsplit('/', 1)[1]
            expected = f'arn:aws:sts::{worker.account}:assumed-role/{role_name}/'
            if identity['Account'] != worker.account or not identity['Arn'].startswith(expected):
                raise ValueError('Current AWS identity differs from CI_WORKER_ROLE_ARN')
        except Exception as error:
            traceback.print_exc()
            results = Path(os.environ.get('CODEBUILD_SRC_DIR', Path.cwd())) / 'ci-results'
            results.mkdir(parents=True, exist_ok=True)
            junit(results, suite, [('worker setup', str(error))])
            return 1
        try:
            if cleanup:
                worker.pipenv('install', '--deploy')
            else:
                worker.execute()
        except (Exception, KeyboardInterrupt) as error:
            traceback.print_exc()
            errors.append(('integration', str(error)))
        finally:
            cleanup_minutes = 60 if suite in ('cfn-create', 'cfn-import', 'aoss') else 75
            with phase('stage cleanup', cleanup_minutes):
                errors.extend(worker.cleanup())
            # Copy the test harness logs before the temporary worker is reclaimed.
            logs = worker.root / 'libraries/testAutomation/logs'
            if logs.is_dir():
                shutil.copytree(logs, worker.results / 'logs', dirs_exist_ok=True)
            failed_reports = junit(
                worker.results,
                suite,
                errors,
                expected_tests=None if suite.startswith('cfn-') else worker.tests,
                cleanup=cleanup,
            )
    for name, message in errors:
        print(f'{name}: {message}', file=sys.stderr)
    return int(bool(errors) or failed_reports)


if __name__ == '__main__':
    sys.exit(main())
