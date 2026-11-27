import json
import os
from pathlib import Path
import subprocess
import tempfile
from threading import Event
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from run import Worker, command, phase, release_bootstrap
from suites import PRESETS, SNAPSHOT_BUCKET, byos_snapshot, fixture_uri
from verification import junit


class TestCloudWorker(unittest.TestCase):
    def setUp(self):
        self.temp = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.account = '1' * 12
        self.enterContext(patch.dict(os.environ, {
            'CODEBUILD_SRC_DIR': str(self.temp),
            'CI_STAGE': 'ci-a123',
            'CI_WORKER_ROLE_ARN': f'arn:aws:iam::{self.account}:role/Worker',
            'CI_ROLE_BOUNDARY_ARN': f'arn:aws:iam::{self.account}:policy/Sandbox',
            'CI_WORKER_CIDR': '192.0.2.1/32',
        }, clear=True))

    def test_aoss_filters_select_only_the_requested_endpoints(self):
        for ids, expected in [('0021', (True, False)), ('0024', (False, True)), ('0024; 0021', (True, True))]:
            with self.subTest(test_ids=ids), patch.dict(os.environ, AOSS_TEST_IDS=ids):
                worker = Worker('aoss', self.temp)
                self.assertEqual((worker.classic, worker.nextgen), expected)
                self.assertEqual(worker.results, self.temp / 'ci-results')

    def test_cdc_aoss_provisions_test_endpoints_before_bootstrap_finishes(self):
        worker = Worker('cdc-aoss', self.temp)
        provisioned = Event()
        endpoints = {'ClassicEndpoint': 'vpce-example'}

        def bootstrap():
            self.assertTrue(provisioned.wait(5), 'Test endpoints were not provisioned during bootstrap')

        def clusters(vpc):
            self.assertEqual(vpc, 'vpc-test')
            provisioned.set()
            return endpoints

        with patch.object(worker, 'build'), patch.object(worker, 'pipenv'), \
                patch.object(worker, 'bootstrap', side_effect=bootstrap), \
                patch.object(worker, 'clusters', side_effect=clusters) as provision, \
                patch.object(worker, 'configure_clusters') as configure, \
                patch.object(worker, 'aws', side_effect=[
                    {'Stacks': [{'StackStatus': 'CREATE_COMPLETE'}]},
                    {'cluster': {'resourcesVpcConfig': {'vpcId': 'vpc-test'}}},
                ]), patch('run.wait', return_value=SimpleNamespace(done=set())), \
                patch('run.nodepools'):
            worker.execute()
        provision.assert_called_once_with('vpc-test')
        configure.assert_called_once_with(endpoints)

    def test_source_suites_prepare_only_the_production_bootstrap(self):
        for suite, release in [('eks', ''), ('cfn-create', ''), ('cfn-import', ''), ('cdc-k6', '3.4.2')]:
            with self.subTest(suite=suite, release=release), patch.dict(os.environ, CI_RELEASE_VERSION=release):
                worker = Worker(suite, self.temp)
                with patch('run.command') as execute:
                    worker.build()
                execute.assert_called_once_with('./deployment/k8s/aws/assemble-bootstrap.sh')
                self.assertEqual(worker.bootstrap_script, worker.root / 'deployment/k8s/aws/dist/aws-bootstrap.sh')

    def test_published_release_suite_fetches_only_its_tagged_bootstrap(self):
        with patch.dict(os.environ, CI_RELEASE_VERSION='3.4.2'), patch('run.command') as execute:
            worker = Worker('eks', self.temp)
            worker.build()
        self.assertEqual(worker.bootstrap_script, self.temp / 'aws-bootstrap.sh')
        execute.assert_called_once_with(
            'curl', '--fail', '--silent', '--show-error', '--location', '--output', worker.bootstrap_script,
            'https://github.com/opensearch-project/opensearch-migrations/releases/download/3.4.2/aws-bootstrap.sh',
        )

    def test_published_release_uses_configured_dependency_proxy(self):
        environment = dict(CI_RELEASE_VERSION='3.4.2', CI_DEPENDENCY_PROXY_URL='http://localhost:1234/')
        with patch.dict(os.environ, environment), patch('run.command') as execute:
            Worker('eks', self.temp).build()
        self.assertEqual(execute.call_args.args[-1], 'http://localhost:1234/migration-release/3.4.2/aws-bootstrap.sh')

    def test_isolated_recovery_includes_both_clusters_and_the_external_vpc(self):
        worker = Worker('isolated', self.temp)
        with patch.object(worker, 'remove_ma') as ma, patch.object(worker, 'remove_stack') as stack:
            with patch.object(worker, 'remove_bucket') as bucket, patch.object(worker, 'remove_mirrors') as mirrors:
                self.assertEqual(worker.cleanup(), [])
        self.assertNotEqual(worker.build_cluster, worker.cluster)
        ma.assert_any_call('ci-a123-ma', worker.cluster)
        ma.assert_any_call('ci-a123-build', worker.build_cluster)
        stack.assert_any_call('ci-a123-network')
        bucket.assert_any_call(worker.stage)
        bucket.assert_any_call(worker.build_stage)
        mirrors.assert_any_call(worker.stage)
        mirrors.assert_any_call(worker.build_stage)

    def test_source_bootstrap_uses_the_native_scope_for_both_isolated_clusters(self):
        worker = Worker('isolated', self.temp)
        worker.bootstrap_script = self.temp / 'assembled.sh'
        for stage, build_phase in [(worker.build_stage, True), (worker.stage, False)]:
            exports = (f'export MIGRATIONS_EKS_CLUSTER_NAME={worker.cluster_name(stage)};'
                       'export MIGRATIONS_ECR_REGISTRY=ecr')
            with patch('run.release_bootstrap') as legacy, patch('run.command') as execute:
                with patch.dict(os.environ, PATH='/bin', ECR_MIRROR_PREFIX='ignored'), patch.object(
                    worker, 'outputs', return_value={'MigrationsExportString': exports}
                ):
                    worker.bootstrap(build_phase=build_phase)
            legacy.assert_not_called()
            self.assertEqual(execute.call_args.args[:2], ('bash', worker.bootstrap_script))
            self.assertEqual(execute.call_args.kwargs['env']['ECR_MIRROR_PREFIX'], f'migration-ecr-{stage}-mirrors')

    def test_new_release_bootstrap_supports_the_native_prefix_and_rejects_invalid_names(self):
        repo = Path(__file__).resolve().parents[3]
        source = self.temp / 'bootstrap.sh'
        subprocess.run([repo / 'deployment/k8s/aws/assemble-bootstrap.sh', '--output', source],
                       stdout=subprocess.DEVNULL, check=True)
        self.assertEqual(release_bootstrap(source, self.temp / 'legacy.sh', 'ci-a123'), source)
        result = subprocess.run(['bash', source, '--ecr-mirror-prefix', '../invalid'], text=True, capture_output=True)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Invalid ECR mirror namespace', result.stderr)

    def test_mirror_cleanup_deletes_only_repository_names_inside_its_own_stage(self):
        worker = Worker('cfn-import', self.temp)
        names = ['migration-ecr-ci-a123-mirrors/charts/grafana',
                 'migration-ecr-ci-a123-mirrors/mirrored/docker.io/busybox',
                 'migration-ecr-ci-a1234-mirrors/charts/grafana',
                 'migration-ecr-ci-a123-mirrors-other/data',
                 'migration-ecr-ci-a123']
        listing = {'repositories': [{'repositoryName': name} for name in names]}
        with patch.object(worker, 'aws', side_effect=[listing, {}, {}]) as aws:
            worker.remove_mirrors(worker.stage)
        self.assertEqual([call.args[3] for call in aws.call_args_list[1:]], names[:2])

    def test_released_mirror_skips_all_legacy_empty_token_logins(self):
        source = self.temp / 'release-bootstrap.sh'
        # Frozen path formats from 3.4.2 are independent of the current source implementation.
        source.write_text('echo "mirrored/image" "$ECR/mirrored" "${ECR_HOST}/mirrored/buildkit"\n'
                          'echo "charts/${name}" "oci://${ecr_host}/charts" "oci://${ECR}/charts/chart"\n')
        with source.open('a') as script:
            for login, indent in [('crane auth login', 4), ('helm registry login', 4), ('crane auth login', 12)]:
                script.write('aws ecr-public get-login-password --region us-east-1 2>/dev/null | \\\n'
                             f'{" " * indent}{login} public.ecr.aws -u AWS --password-stdin 2>/dev/null || true\n')
        scoped = release_bootstrap(source, self.temp / 'stage-bootstrap.sh', 'ci-a123')
        self.assertNotIn('get-login-password --region us-east-1 2>/dev/null |', scoped.read_text())
        self.assertEqual(scoped.read_text().count('migration-ecr-ci-a123-mirrors/'), 6)
        subprocess.run(['bash', '-n', scoped], check=True)

    def test_phase_timeout_kills_background_writer_before_cleanup_can_run(self):
        marker = self.temp / 'late-write'
        with self.assertRaisesRegex(TimeoutError, 'bootstrap'), phase('bootstrap', 0.001):
            command('/bin/bash', '-c', '(sleep 0.3; touch "$1") & wait', '--', marker)
        self.assertEqual(command('/bin/bash', '-c', 'sleep 0.4; echo done', capture=True), 'done')
        self.assertFalse(marker.exists())

    def test_bootstrap_applies_the_eks_boundary_only_to_the_control_plane_role(self):
        roles = ['EKSInfraMigrationsEKSClusterRole243C21EF',
                 'EKSInfraMigrationsEKSClusterMigrationsEKSClusternodePoolRoleB06A3C37', 'PodRole', 'SnapshotRole']
        template = self.temp / 'eks.json'
        resources = {name: {'Type': 'AWS::IAM::Role', 'Properties': {}} for name in roles}
        template.write_text(json.dumps({'Resources': resources}))
        aws = self.temp / 'dump-template'
        aws.write_text('#!/usr/bin/env python3\nimport sys\n'
                       'print(open(sys.argv[sys.argv.index("--template-file")+1]).read())\n')
        aws.chmod(0o755)
        env = dict(os.environ, CI_AWS_CLI=str(aws), CI_CONTROL_PLANE_BOUNDARY_ARN='control-boundary')
        shim = Path(__file__).parent / 'bootstrap-bin/aws'
        output = subprocess.check_output([shim, 'cloudformation', 'deploy', '--template-file', template], env=env)
        applied = json.loads(output)['Resources']
        self.assertEqual(applied[roles[0]]['Properties']['PermissionsBoundary'], 'control-boundary')
        for role in roles[1:]:
            self.assertEqual(applied[role]['Properties']['PermissionsBoundary'], os.environ['CI_ROLE_BOUNDARY_ARN'])

    def test_missing_selected_test_or_malformed_report_fails(self):
        report = self.temp / 'test-report-source-to-target.json'
        report.write_text(json.dumps({'tests': [{'name': 'Test0001Basic', 'result': 'passed', 'duration': 1}]}))
        self.assertTrue(junit(self.temp, 'eks', [], '0001,0002'))
        self.assertEqual(ET.parse(self.temp / 'junit.xml').getroot().get('errors'), '1')
        self.assertFalse(junit(self.temp, 'eks', [], '0001'))
        report.write_text('invalid JSON')
        self.assertTrue(junit(self.temp, 'eks', [], 'all'))

    def test_empty_test_report_requires_a_failure_in_a_migration_suite(self):
        self.assertTrue(junit(self.temp, 'cdc', [], '0031'))
        self.assertFalse(junit(self.temp, 'cfn-create', []))
        self.assertFalse(junit(self.temp, 'cdc', [], cleanup=True))

    def test_snapshot_presets_use_same_keys_in_the_oregon_replica(self):
        bucket = f'migrations-ci-fixtures-{self.account}-us-west-2'
        for preset, (prefix, name, *_) in PRESETS.items():
            with self.subTest(preset=preset), patch.dict(os.environ, TEST_PRESET=preset):
                snapshot = byos_snapshot(bucket)
                self.assertEqual(snapshot['S3_REPO_URI'], f's3://{bucket}/{prefix}/')
                self.assertEqual(snapshot['SNAPSHOT_NAME'], name)
        for source in (SNAPSHOT_BUCKET, f's3://{bucket}'):
            self.assertEqual(fixture_uri(bucket, 'default', f'{source}/custom/repo/'),
                             f's3://{bucket}/custom/repo/')
        with self.assertRaises(ValueError):
            fixture_uri(bucket, 'default', 's3://unrelated/repo/')

    def test_missing_snapshot_fails_before_cluster_or_domain_deployment(self):
        worker = Worker('aoss', self.temp)
        with patch.object(worker, 'aws', return_value={}) as aws, patch.object(worker, 'build') as build:
            with self.assertRaisesRegex(RuntimeError, 'Missing snapshot metadata'):
                worker.execute()
        self.assertEqual(worker.region, 'us-west-2')
        self.assertEqual(aws.call_count, 1)
        build.assert_not_called()

    def test_snapshot_suites_are_disabled_and_support_cleanup(self):
        script = Path(__file__).parent / 'run.py'
        for suite in ['aoss', 'byos', 'aoss-vector', 'aoss-timeseries']:
            with self.subTest(suite=suite):
                run = subprocess.run(['python3', script, suite, '--plan'], text=True, capture_output=True)
                self.assertEqual(run.returncode, 0, run.stderr)
                self.assertFalse(json.loads(run.stdout)['enabled'])
                self.assertFalse((self.temp / 'ci-results').exists())
        with patch('run.Worker') as worker, patch('sys.argv', [str(script), 'cleanup']):
            with patch.dict(os.environ, CI_SUITE='aoss'):
                from run import main
                worker.return_value.results = self.temp
                worker.return_value.root = self.temp
                worker.return_value.role = 'arn:aws:iam::123:role/Worker'
                worker.return_value.account = '123'
                worker.return_value.aws.return_value = {
                    'Account': '123', 'Arn': 'arn:aws:sts::123:assumed-role/Worker/id',
                }
                self.assertEqual(main(), 0)
                worker.return_value.cleanup.assert_called_once()

    def test_public_xml_and_summary_redact_aws_account_numbers(self):
        account = '9' * 12
        report = {'tests': [{'name': f'Test0001-{account}', 'result': 'failed', 'duration': 0,
                             'error': f'arn:aws:iam::{account}:role/Worker'}]}
        (self.temp / 'test-report-local.json').write_text(json.dumps(report))
        self.assertTrue(junit(self.temp, 'eks', [('worker', f'{account}.dkr.ecr.us-west-2.amazonaws.com')]))
        for name in ('junit.xml', 'summary.json'):
            self.assertNotIn(account, (self.temp / name).read_text())

    def test_worker_setup_failure_writes_a_report(self):
        script = Path(__file__).parent / 'run.py'
        fake = self.temp / 'aws'
        fake.write_text('#!/bin/sh\nexit 1\n')
        fake.chmod(0o755)
        env = dict(os.environ, PATH=f'{self.temp}:/usr/bin:/bin')
        result = subprocess.run(['python3', script, 'eks'], capture_output=True, env=env)
        self.assertNotEqual(result.returncode, 0)
        case = ET.parse(self.temp / 'ci-results/junit.xml').getroot().find('testcase')
        self.assertEqual(case.get('name'), 'worker setup')
        self.assertIsNotNone(case.find('error'))
