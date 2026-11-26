import io
import json
import os
import tempfile
import unittest
from contextlib import redirect_stdout
from pathlib import Path
from unittest.mock import patch
from zipfile import ZipFile

import build
import capacity
import plan
from parameters import parameters
from report import render

REPO = 'AndreKurait/opensearch-migrations'
SHA = 'c' * 40
REF = 'refs/heads/main'
BUILD = build.PROJECT + ':12345678-1234-1234-1234-1234567890ab'


class ApprovalTests(unittest.TestCase):
    def setUp(self):
        self.pr = dict(state='open', head={'sha': SHA, 'repo': {'full_name': 'contributor/fork'}},
                       base={'repo': {'full_name': REPO}, 'ref': 'main'}, labels=[])
        self.event = {'repository': {'full_name': REPO}, 'issue': {'pull_request': {}, 'number': 12},
                      'comment': {'body': '/aws-test ' + SHA, 'user': {'login': 'reviewer'}}}
        self.permission = 'maintain'

    def api(self, path):
        values = {f'repos/{REPO}/pulls/12': self.pr,
                  f'repos/{REPO}/collaborators/reviewer/permission': {'permission': self.permission}}
        return values[path]

    def select(self, event='issue_comment', ref=REF):
        return plan.plan(event, self.event, REPO, 'a' * 40, ref, self.api)

    def test_reviewed_fork_sha_and_labels(self):
        self.pr['labels'] = [{'name': 'run-cfn-tests'}, {'name': 'unrelated'}]
        result = self.select()
        self.assertEqual((SHA, 'refs/pull/12/head', '12'), (result['sha'], result['ref'], result['pr']))
        self.assertEqual(['cfn-create', 'cfn-import'], json.loads(result['matrix']))
        self.pr['head']['sha'] = 'd' * 40
        with self.assertRaises(ValueError):
            self.select()

    def test_change_to_issue_as_user_or_non_main_base_cannot_authorize(self):
        for field, value in [('state', 'closed'), ('base', {'repo': {'full_name': REPO}, 'ref': 'other'}),
                             ('base', {'repo': {'full_name': 'someone/else'}, 'ref': 'main'})]:
            with self.subTest(field=field, value=value), patch.dict(self.pr, {field: value}):
                with self.assertRaises(ValueError):
                    self.select()
        self.permission = 'read'
        self.event['comment']['author_association'] = 'MEMBER'
        with self.assertRaises(PermissionError):
            self.select()

    def test_source_inputs_and_incomplete_approval(self):
        for cmd in ['/aws-test deadbeef', '/aws-test HEAD', '/aws-test ' + SHA + ' eks; date',
                    '/aws-test ' + SHA + ' eks\nadditional text']:
            self.event['comment']['body'] = cmd
            with self.assertRaises(ValueError):
                self.select()
        with self.assertRaises(ValueError):
            self.select(ref='refs/pull/12/merge')
        self.event['issue'].pop('pull_request')
        with self.assertRaises(ValueError):
            self.select()

    def test_dispatch_review_and_parameters(self):
        self.event.update(sender={'login': 'reviewer'}, inputs={
            'suite': 'cdc', 'pr': '12', 'sha': SHA, 'parameters': '{"TEST_IDS":"0040"}'})
        result = self.select('workflow_dispatch')
        self.assertEqual(SHA, result['sha'])
        self.assertEqual({'TEST_IDS': '0040'}, json.loads(result['parameters']))
        for key in ('pr', 'sha'):
            with patch.dict(self.event['inputs'], {key: ''}), self.assertRaises(ValueError):
                self.select('workflow_dispatch')
        self.permission = 'triage'
        with self.assertRaises(PermissionError):
            self.select('workflow_dispatch')

    def test_push_schedules_default_suite_and_canary(self):
        self.assertEqual(set(plan.ALL), set(json.loads(self.select('push')['matrix'])))
        with patch.object(plan, 'latest_release', return_value='3.4.2'):
            for cron, suites in plan.SCHEDULES.items():
                self.event['schedule'] = cron
                result = self.select('schedule')
                self.assertEqual([s for s in suites if s not in plan.DISABLED_SUITES], json.loads(result['matrix']))
                self.assertEqual('3.4.2' if cron == plan.CANARY else '', result['release'])

    def test_snapshot_aliases_and_pr_labels_select_runnable_suites(self):
        for alias in ('aoss-timeseries', 'aoss-vector'):
            self.assertEqual(('aoss',), plan.select(alias))
        for label in ('run-eks-byos-tests', 'cdc-tests'):
            self.pr['labels'] = [{'name': label}]
            self.assertEqual(list(plan.GROUPS[label]), json.loads(self.select()['matrix']))

    def test_select_several_suites_on_one_approval(self):
        self.event['comment']['body'] = f'/aws-test {SHA} cfn-import,cdc-tests,cdc'
        result = self.select()
        self.assertEqual(['cfn-import', 'cdc', 'cdc-aoss', 'cdc-k6'], json.loads(result['matrix']))
        for selection in ('cdc,unknown', 'cdc,'):
            with self.subTest(selection=selection), self.assertRaises(ValueError):
                plan.select(selection)

    def test_unnamed_request_runs_deployment_smoke_and_all_remains_explicit(self):
        self.assertEqual(['cfn-create'], json.loads(self.select()['matrix']))
        self.event.update(sender={'login': 'reviewer'}, inputs={'pr': '12', 'sha': SHA})
        self.assertEqual(['cfn-create'], json.loads(self.select('workflow_dispatch')['matrix']))
        self.event['inputs'].update(suite='all')
        self.event['inputs']['cache-only'] = 'true'
        result = self.select('workflow_dispatch')
        self.assertEqual(set(plan.ALL), set(json.loads(result['matrix'])))
        self.assertEqual({'CI_DEPENDENCY_MODE': 'replay'}, json.loads(result['parameters']))

    def test_only_supported_inputs_for_each_suite(self):
        self.assertEqual({'TEST_IDS': '0040,0042'}, parameters(['cdc'], '{"TEST_IDS":"0040,0042"}'))
        for key in ('AOSS_TEST_IDS', 'TEST_IDS'):
            self.assertEqual({key: '0021,0024'}, parameters(['aoss-vector'], json.dumps({key: '0021; 0024'})))
        for suites, opts in [(['cdc'], {'CI_ROLE_BOUNDARY_ARN': 'other'}), (['eks'], {'TEST_IDS': '42\nX='}),
                             (['aoss'], {'TEST_IDS': 'all'}), (['byos'], {'S3_REPO_URI': 's3://other/secret'}),
                             (['cdc', 'eks'], {'TEST_IDS': '0001'}), (['cfn-import'], {'TEST_IDS': '0001'}),
                             (['byos'], {'TEST_PRESET': 'large-es5x', 'RFS_WORKERS': 4})]:
            with self.subTest(opts=opts), self.assertRaises(ValueError):
                parameters(suites, json.dumps(opts))


class ObservationTests(unittest.TestCase):
    def test_cache_writer_role_only_for_recording_main(self):
        env = dict(GITHUB_RUN_ID='123', GITHUB_REPOSITORY=REPO, GITHUB_RUN_ATTEMPT='2')
        with patch.dict(os.environ, env), patch.object(build, 'summary'), patch.object(build, 'output'), \
                patch.object(build, 'config', return_value={'account': '123456789012'}), \
                patch.object(build, 'aws', return_value={'build': {'id': BUILD}}) as api:
            for ref, options, writer in [('refs/heads/main', '{}', True),
                                         ('refs/heads/main', '{"CI_DEPENDENCY_MODE":"replay"}', False),
                                         ('refs/pull/12/head', '{}', False)]:
                build.start('cfn-create', SHA, ref, '', options)
                self.assertEqual('--service-role-override' in api.call_args.args, writer)
                args = api.call_args.args
                self.assertEqual('270', args[args.index('--timeout-in-minutes-override') + 1])

    def test_start_uses_bounded_timeout_and_identity(self):
        env = dict(GITHUB_RUN_ID='123', GITHUB_REPOSITORY=REPO, GITHUB_RUN_ATTEMPT='2')
        with patch.dict(os.environ, env), patch.object(build, 'summary'), patch.object(build, 'output'), \
                patch.object(build, 'aws', return_value={'build': {'id': BUILD}}) as api:
            build.start('cdc', SHA, 'refs/pull/12/head', '', '{"SPEEDUP_FACTOR":8}')
            args = api.call_args.args
            self.assertEqual('405', args[args.index('--timeout-in-minutes-override') + 1])
            overrides = {i['name']: i['value'] for i in json.loads(args[-1])}
            self.assertEqual(('8', SHA), (overrides['SPEEDUP_FACTOR'], overrides['CI_GIT_SHA']))
            self.assertEqual(('123', '2'), (overrides['CI_GH_RUN_ID'], overrides['CI_GH_RUN_ATTEMPT']))
            self.assertRegex(overrides['CI_STAGE'], r'^ci-[a-f0-9]{12}$')

    def test_lease_waits_for_successful_recovery_after_a_failed_attempt(self):
        earlier, retry = 'older-cleanup', 'newer-cleanup'
        env = {'environmentVariables': [{'name': 'CI_MODE', 'value': 'cleanup'},
                                        {'name': 'CI_RECOVER_BUILD', 'value': BUILD}]}
        rows = [dict(id=retry, buildStatus='SUCCEEDED', environment=env),
                dict(id=earlier, buildStatus='TIMED_OUT', environment=env)]
        with patch.object(build, 'aws', side_effect=[{'ids': [retry, earlier, BUILD]}, {'builds': rows}]), \
                patch.object(build, 'summary'):
            self.assertEqual('SUCCEEDED', build.wait_for_cleanup(BUILD, 0))

    def test_empty_next_page_and_delayed_stream(self):
        logs = {'groupName': build.LOG_GROUP, 'streamName': 'worker'}
        pages = [{'events': [{'message': '::error::from worker\r\n'}], 'nextForwardToken': 'next'},
                 {'events': [], 'nextForwardToken': 'next'}]
        buf = io.StringIO()
        with patch.object(build, 'aws', side_effect=pages) as api, redirect_stdout(buf):
            self.assertEqual('next', build.stream(logs, 'prior'))
            self.assertEqual('next', api.call_args.args[-1])
        self.assertRegex(buf.getvalue(), r'::stop-commands::(\w+)\n::error::from worker\n::\1::')
        with patch.object(build, 'aws', side_effect=RuntimeError('ResourceNotFoundException')):
            self.assertEqual('', build.stream(logs, ''))

    def test_success_failed_or_absent_artifact(self):
        result = {'id': BUILD, 'projectName': build.PROJECT, 'currentPhase': 'COMPLETED', 'buildStatus': 'SUCCEEDED'}
        with patch.object(build, 'summary'), patch.object(build, 'output'), \
                patch.object(build, 'aws', return_value={'builds': [result]}):
            with self.assertRaises(RuntimeError):
                build.watch(BUILD, '', 0)
            result['buildStatus'] = 'FAILED'
            result['phases'] = [dict(phaseType='BUILD', phaseStatus='TIMED_OUT',
                                     contexts=[{'statusCode': 'BUILD_TIMED_OUT', 'message': 'private detail'}])]
            with tempfile.TemporaryDirectory() as tmp, patch.dict(os.environ, RUNNER_TEMP=tmp):
                with self.assertRaises(SystemExit):
                    build.watch(BUILD, '', 0)
                evidence = json.loads((Path(tmp) / 'aws-test-results/codebuild-status.json').read_text())
                self.assertFalse(evidence['workerReportAvailable'])
                self.assertEqual('TIMED_OUT', evidence['phases'][0]['phaseStatus'])
                self.assertEqual(['BUILD_TIMED_OUT'], evidence['phases'][0]['statusCodes'])
                self.assertNotIn('private detail', json.dumps(evidence))
        outputs = {'Stacks': [{'Outputs': [{'OutputKey': 'ArtifactBucketName', 'OutputValue': 'expected'}]}]}
        with patch.object(build, 'aws', return_value=outputs) as api, self.assertRaises(ValueError):
            build.collect(dict(id=BUILD, artifacts={'location': 'arn:aws:s3:::other/other'}))
        self.assertEqual(1, api.call_count)

    def test_native_origin_report_survives_sanitized_artifact_export(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            with ZipFile(base / 'result.zip', 'w') as archive:
                archive.writestr('junit.xml', '<testsuite name="ci"><testcase name="test"/></testsuite>')
                stats = dict(restored=2, bytes=4, originRequests=1, misses=1)
                archive.writestr('dependency-cache-stats.json', json.dumps(stats))
                rows = [{'key': '/raw/helm/package.tgz', 'host': 'get.helm.sh', 'status': 302},
                        {'key': '/raw/helm/package.tgz', 'host': 'get.helm.sh', 'status': 200}]
                archive.writestr('dependency-origin.jsonl', '\n'.join(json.dumps(row) for row in rows))
            text, _ = build.render(base / 'result.zip', base / 'output')
            self.assertIn('2 public HTTP request attempts', text)
            origin_log = base / 'output/dependency-origin.jsonl'
            self.assertEqual(rows, [json.loads(row) for row in origin_log.read_text().splitlines()])

    def test_dependency_report_survives_failure_before_junit(self):
        with tempfile.TemporaryDirectory() as tmp:
            base = Path(tmp)
            stats = dict(restored=2, bytes=4, originRequests=0, misses=1)
            with ZipFile(base / 'result.zip', 'w') as archive:
                archive.writestr('dependency-cache-stats.json', json.dumps(stats))
                archive.writestr('dependency-origin.jsonl', '')
            with self.assertRaisesRegex(ValueError, 'junit.xml'):
                render(base / 'result.zip', base / 'output')
            self.assertEqual(stats, json.loads((base / 'output/dependency-cache-stats.json').read_text()))
            self.assertEqual(b'', (base / 'output/dependency-origin.jsonl').read_bytes())
            self.assertFalse((base / 'output/junit.xml').exists())

    def test_bounded_report_and_safe_summary(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'report.zip'

            def write(xml):
                with ZipFile(path, 'w') as archive:
                    archive.writestr('junit.xml', xml)
                    archive.writestr('../../untrusted', 'unused')
            write('<testsuite><testcase name="Test0040_ok"/><testcase name="![x](h)"><failure/></testcase></testsuite>')
            text, counts = render(path)
            self.assertIn('Test0040_ok', text)
            self.assertNotIn('![x]', text)
            self.assertEqual((1, 1), (counts['passed'], counts['failed']))
            write('<testsuite><testcase name="case"><failure>acct 123456789012</failure></testcase></testsuite>')
            dest = path.with_name('published')
            render(path, output_dir=dest)
            self.assertEqual({'junit.xml', 'summary.json'}, {file.name for file in dest.iterdir()})
            self.assertNotIn('123456789012', (dest / 'junit.xml').read_text())
            self.assertEqual(1, json.loads((dest / 'summary.json').read_text())['failures'])
            for xml in ['<testsuite/>', '<!DOCTYPE testsuite><testsuite/>']:
                write(xml)
                with self.assertRaises(ValueError):
                    render(path)


class CapacityTests(unittest.TestCase):
    def test_every_enabled_suite_reserves_every_quota_by_name(self):
        self.assertEqual(set(plan.ALL), set(capacity.RESERVATIONS))
        self.assertFalse(set(capacity.RESERVATIONS) & plan.DISABLED_SUITES)
        for name, needs in capacity.RESERVATIONS.items():
            with self.subTest(name=name):
                self.assertEqual(set(capacity.QUOTAS), set(needs))
                self.assertTrue(all(type(n) is int and n >= 0 for n in needs.values()))
        self.assertEqual(4, capacity.RESERVATIONS['isolated']['eips'])
        self.assertEqual(0, capacity.RESERVATIONS['cfn-import']['egws'])

    def test_all_possible_queue_combinations_fit(self):
        room = dict(builds=12, vpcs=4, igws=4, egws=5, eips=5, nat_per_az=5, vcpus=32, enis=5000)
        lanes = capacity.lanes(room)
        self.assertEqual(set(plan.ALL), set(lanes))
        self.assertEqual(1, len(set(lanes.values())))
        self.assertEqual(2, len(set(capacity.lanes(dict(room, vcpus=56)).values())))
        for key in room:
            self.assertLessEqual(sum(max(capacity.RESERVATIONS[s][key] for s, n in lanes.items() if n == lane)
                                     for lane in set(lanes.values())), room[key])
        for key in ('vpcs', 'eips', 'builds', 'enis', 'vcpus'):
            self.assertEqual({}, capacity.lanes(dict(room, **{key: 0})))

    def test_two_full_size_lanes_share_the_suites(self):
        room = dict(builds=11, vpcs=4, igws=4, egws=5, eips=7, nat_per_az=5, vcpus=64, enis=5000)
        lanes = capacity.lanes(room)
        self.assertEqual(set(plan.ALL), set(lanes))
        self.assertEqual([4, 5], sorted(list(lanes.values()).count(lane) for lane in set(lanes.values())))
        for key, limit in room.items():
            peaks = [max(capacity.RESERVATIONS[s][key] for s in lanes if lanes[s] == lane)
                     for lane in set(lanes.values())]
            self.assertLessEqual(sum(peaks), limit)

    def usage_rows(self):
        return {
            'describe-vpcs': {'Vpcs': []}, 'describe-instances': {'Reservations': []},
            'describe-capacity-reservations': {'CapacityReservations': []},
            'describe-instance-types': {'InstanceTypes': [{'InstanceType': 'm6g.2xlarge',
                                                           'VCpuInfo': {'DefaultVCpus': 8}}]},
            'describe-internet-gateways': {'InternetGateways': []},
            'describe-egress-only-internet-gateways': {'EgressOnlyInternetGateways': []},
            'describe-addresses': {'Addresses': []}, 'describe-network-interfaces': {'NetworkInterfaces': []},
            'describe-nat-gateways': {'NatGateways': []}, 'describe-subnets': {'Subnets': []},
        }

    def test_compute_in_a_stopped_build_vpc_is_still_counted_until_it_terminates(self):
        stage = 'ci-' + 'a' * 12
        rows = self.usage_rows()
        rows['describe-vpcs']['Vpcs'].append({'VpcId': 'vpc-1', 'Tags': [
            {'Key': 'MigrationCI', 'Value': 'github'}, {'Key': 'MATestStage', 'Value': stage}]})
        items = [{'VpcId': 'vpc-1', 'InstanceType': 'm6g.2xlarge', 'State': {'Name': state}}
                 for state in ('pending', 'running', 'terminated')]
        rows['describe-instances']['Reservations'] = [{'Instances': items}]
        with patch.object(capacity, 'active_builds', return_value=[]):
            used = capacity.usage(lambda service, operation, *args: rows[operation], '1' * 12)
        self.assertEqual(16, used['vcpus'])

    def test_owned_reserved_capacity_includes_shared_unused_and_future_slots(self):
        rows = self.usage_rows()
        account = '1' * 12
        reservation = dict(InstanceType='m6g.2xlarge', OwnerId=account, AvailableInstanceCount=0)
        states = ['assessing', 'scheduled', 'pending', 'active', 'delayed', 'expired', 'cancelled', 'failed']
        reservations = [dict(reservation, State=state, CapacityReservationId=state, TotalInstanceCount=2)
                        for state in states]
        reservations.append(dict(reservation, State='active', OwnerId='2' * 12,
                                 CapacityReservationId='shared-in', TotalInstanceCount=5))
        rows['describe-capacity-reservations']['CapacityReservations'] = reservations
        instance = dict(InstanceType='m6g.2xlarge', State={'Name': 'running'})
        rows['describe-instances']['Reservations'] = [{'Instances': [
            dict(instance, CapacityReservationId='active'), dict(instance, CapacityReservationId='shared-in'),
            dict(instance, CapacityReservationId='absent'), dict(instance, InstanceLifecycle='spot')]}]

        def call(service, operation, *args):
            return rows[operation]

        with patch.object(capacity, 'active_builds', return_value=[]):
            self.assertEqual(88, capacity.usage(call, account)['vcpus'])
            active = next(r for r in reservations if r['State'] == 'active')
            active['AvailableInstanceCount'] = 2  # A concurrent termination made the instance scan stale.
            self.assertEqual(96, capacity.usage(call, account)['vcpus'])
            rows['describe-instance-types']['InstanceTypes'] = []
            with self.assertRaisesRegex(ValueError, 'incomplete vCPU'):
                capacity.usage(call, account)

    def test_separate_quota_families_and_nat_deletion_during_discovery(self):
        for name in ('m8g.2xlarge', 'c7gn.xlarge', 'im4gn.large', 'h1.2xlarge', 'i7ie.large', 'd3en.xlarge'):
            self.assertTrue(capacity.standard_type(name), name)
        for name in ('inf2.xlarge', 'trn2.3xlarge', 'dl1.24xlarge', 'mac2.metal', 'hpc7g.4xlarge',
                     'g6.xlarge', 'p6e-gb200.36xlarge', 'x2iezn.2xlarge', 'u-6tb1.56xlarge'):
            self.assertFalse(capacity.standard_type(name), name)
        rows = self.usage_rows()
        rows['describe-instance-types']['InstanceTypes'] = []
        rows['describe-subnets']['Subnets'] = [{'SubnetId': 'present', 'AvailabilityZone': 'us-west-2a'}]
        rows['describe-nat-gateways']['NatGateways'] = [
            {'SubnetId': subnet, 'VpcId': 'vpc-1', 'State': state}
            for subnet, state in [('present', 'available'), ('gone', 'deleting'), ('another', 'deleted')]]
        with patch.object(capacity, 'active_builds', return_value=[]):
            used = capacity.usage(lambda service, operation, *args: rows[operation], '1' * 12)
        self.assertEqual(2, used['nat_per_az'])

    def test_occupied_capacity_prevents_launch(self):
        room = dict.fromkeys(capacity.QUOTAS, 100)
        occupied = dict.fromkeys(room, 0)
        occupied['enis'] = 99
        with patch.object(capacity, 'quotas', return_value=room.copy()), \
                patch.object(capacity, 'usage', return_value=occupied), \
                self.assertRaisesRegex(RuntimeError, 'occupied'):
            capacity.admit('cfn-import', None, dict(account='1' * 12, room=room), wait_seconds=0)
