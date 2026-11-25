import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET

from run import Worker, command, phase, stage_bootstrap
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

    def test_mirror_writes_helm_pulls_and_buildkit_use_the_same_run_namespace(self):
        stage = 'ci-a123'
        repo = Path(__file__).resolve().parents[3]
        source = self.temp / 'bootstrap.sh'
        subprocess.run([repo / 'deployment/k8s/aws/assemble-bootstrap.sh', '--output', source],
                       stdout=subprocess.DEVNULL, check=True)
        scoped = stage_bootstrap(source, self.temp / 'scoped.sh', stage)
        subprocess.run(['bash', '-n', scoped], check=True)
        script = scoped.read_text()
        prefix = f'migration-ecr-{stage}-mirrors/'
        self.assertIn(f'local ecr_repo="{prefix}mirrored/', script)
        self.assertIn(f'"{prefix}charts/${{name}}"', script)
        self.assertIn(f'"oci://${{ecr_host}}/{prefix}charts"', script)
        self.assertIn(f'${{ECR_HOST}}/{prefix}mirrored/docker.io/moby/buildkit', script)
        start = script.index('generate_private_ecr_values()')
        end = script.index('# --- END inlined: generatePrivateEcrValues.sh ---', start)
        values = subprocess.check_output(['bash', '-c', script[start:end] + '\ngenerate_private_ecr_values ecr.test'],
                                         text=True)
        self.assertIn(f'oci://ecr.test/{prefix}charts/cert-manager', values)
        self.assertIn(f'ecr.test/{prefix}mirrored/mirror.gcr.io/library/busybox', values)
        self.assertIn(f'"{prefix}mirrored/mirror.gcr.io/grafana/grafana"', values)

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

    def test_disabled_suites_are_rejected_before_worker_setup_and_cleanup_still_runs(self):
        script = Path(__file__).parent / 'run.py'
        for suite in ['aoss', 'byos', 'aoss-vector', 'aoss-timeseries']:
            with self.subTest(suite=suite):
                run = subprocess.run(['python3', script, suite], text=True, capture_output=True)
                self.assertEqual(run.returncode, 2)
                self.assertIn('temporarily disabled', run.stderr)
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
