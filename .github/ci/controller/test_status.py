import io
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
from zipfile import ZipFile

import status


class StatusTests(unittest.TestCase):
    def setUp(self):
        self.plan = dict(run=123, attempt=2, sha='a' * 40, suites=['eks', 'cdc', 'isolated', 'cfn-create'])
        self.url = f'https://github.com/{status.REPO}/actions/runs/123/attempts/2'
        self.asset = {'id': 12, 'name': 'aws-approved-plan-123-2', 'workflow_run': {'id': 123},
                      'size_in_bytes': 200, 'expired': False}
        self.temp = Path(self.enterContext(tempfile.TemporaryDirectory()))
        self.enterContext(patch.dict(os.environ, GITHUB_REPOSITORY=status.REPO, GITHUB_REF='refs/heads/main',
                                     GITHUB_RUN_ID='123', GITHUB_RUN_ATTEMPT='2', RUNNER_TEMP=str(self.temp)))

    def test_writes_only_reviewed_status_plan_and_posts_pending_for_each_suite(self):
        matrix = json.dumps([{'suite': s, 'lane': 1} for s in self.plan['suites']])
        with patch.dict(os.environ, SHA=self.plan['sha'], MATRIX=matrix):
            with patch('sys.argv', ['status.py', 'plan']):
                status.main()
        self.assertEqual(json.loads((self.temp / status.PLAN_FILE).read_text()), self.plan)
        responses = [b'[]'] + [b''] * len(self.plan['suites'])
        with patch.object(status, 'api', side_effect=responses) as api:
            with patch('sys.argv', ['status.py', 'pending']):
                status.main()
        self.assertEqual(len(api.call_args_list), 1 + len(self.plan['suites']))
        for call in api.call_args_list[1:]:
            self.assertIn('state=pending', call.args)
            self.assertIn(f'target_url={self.url}', call.args)

    def test_delayed_pending_cannot_replace_a_newer_suite_status(self):
        newer = {'context': 'aws-integration/eks', 'state': 'success', 'target_url': self.url.replace('/123/', '/124/')}
        with patch.object(status, 'api', return_value=json.dumps([{'statuses': [newer]}])) as api:
            status.publish({**self.plan, 'suites': ['eks']}, 'pending')
        self.assertEqual(api.call_count, 1)

    def test_stale_cancel_or_outcome_cannot_overwrite_another_run_attempt_or_finished_suite(self):
        rows = [{'context': 'aws-integration/' + suite, 'state': state, 'target_url': url}
                for suite, state, url in [
                    ('eks', 'pending', self.url),
                    ('cdc', 'pending', self.url.replace('/123/', '/124/')),
                    ('isolated', 'pending', self.url.replace('/attempts/2', '/attempts/3')),
                    ('cfn-create', 'success', self.url)]]
        for state in ('error', 'success', 'failure'):
            with self.subTest(state=state):
                pages = json.dumps([{'statuses': rows[:2]}, {'statuses': rows[2:]}])
                with patch.object(status, 'api', side_effect=[pages, b'']) as api:
                    status.publish(self.plan, state)
                self.assertEqual(api.call_count, 2)
                self.assertIn('context=aws-integration/eks', api.call_args_list[-1].args)
                self.assertIn('state=' + state, api.call_args_list[-1].args)

    def test_rerunning_only_failed_jobs_can_finish_its_own_older_attempt(self):
        for prior in ('failure', 'pending', 'error'):
            rows = [{'context': 'aws-integration/eks', 'state': prior, 'target_url': self.url[:-1] + '1'}]
            pages = json.dumps([{'statuses': rows}])
            for result in ('success', 'failure', 'error'):
                with self.subTest(prior=prior, result=result):
                    responses = [pages] if result == 'error' else [pages, b'']
                    with patch.object(status, 'api', side_effect=responses) as api:
                        status.publish(self.plan, result)
                    self.assertEqual(api.call_count, len(responses))
                    if result != 'error':
                        self.assertIn(f'target_url={self.url}', api.call_args_list[-1].args)

    def test_archive_identity_size_schema_and_owner_are_checked(self):
        def archive(entries):
            buf = io.BytesIO()
            with ZipFile(buf, 'w') as file:
                for name, value in entries.items():
                    file.writestr(name, value)
            return buf.getvalue()

        valid = archive({status.PLAN_FILE: json.dumps(self.plan)})
        listing = {'total_count': 1, 'artifacts': [self.asset]}
        with patch.object(status, 'api', side_effect=[json.dumps(listing), valid]) as api:
            self.assertEqual(status.read_artifact(123, 2), self.plan)
            self.assertIn('--allow-escape-sequences', api.call_args_list[-1].args)
        for changes in [{'expired': True}, {'size_in_bytes': status.MAX_ARCHIVE + 1},
                        {'workflow_run': {'id': 124}}, {'name': 'aws-approved-plan-123-1'}]:
            with self.subTest(changes=changes):
                listing = {'total_count': 1, 'artifacts': [dict(self.asset, **changes)]}
                with patch.object(status, 'api', return_value=json.dumps(listing)) as api:
                    with self.assertRaises(ValueError):
                        status.read_artifact(123, 2)
                    api.assert_called_once()
        listing = {'total_count': 1, 'artifacts': [self.asset]}
        for entries in [{'../x': '{}'}, {status.PLAN_FILE: '{}', 'extra': 'x'},
                        {status.PLAN_FILE: 'x' * (status.MAX_PLAN + 1)},
                        {status.PLAN_FILE: json.dumps(dict(self.plan, suites=['other']))},
                        {status.PLAN_FILE: json.dumps(dict(self.plan, run=124))},
                        {status.PLAN_FILE: json.dumps(dict(self.plan, attempt=1))}]:
            with self.subTest(entries=tuple(entries)):
                with patch.object(status, 'api', side_effect=[json.dumps(listing), archive(entries)]):
                    with self.assertRaises(ValueError):
                        status.read_artifact(123, 2)
        with patch.object(status, 'api', return_value='{"total_count":0,"artifacts":[]}'):
            self.assertIsNone(status.read_artifact(123, 2))

    def test_recovery_requires_the_completed_trusted_main_workflow(self):
        event_path = self.temp / 'event.json'
        run = {'id': 123, 'run_attempt': 2, 'status': 'completed', 'conclusion': 'cancelled', 'path': status.WORKFLOW,
               'head_branch': 'main', 'event': 'issue_comment', 'head_repository': {'full_name': status.REPO}}
        with patch.dict(os.environ, GITHUB_EVENT_PATH=str(event_path)), patch('sys.argv', ['status.py', 'cancelled']):
            for change in [{'head_branch': 'pr'}, {'event': 'pull_request'}, {'path': '.github/workflows/other.yml'},
                           {'head_repository': {'full_name': 'contributor/fork'}}, {'conclusion': 'success'}]:
                event_path.write_text(json.dumps({'action': 'completed', 'workflow_run': dict(run, **change)}))
                with patch.object(status, 'api') as api, self.assertRaises(ValueError):
                    status.main()
                api.assert_not_called()
            event_path.write_text(json.dumps({'action': 'completed', 'workflow_run': run}))
            with patch.object(status, 'read_artifact', return_value=self.plan), patch.object(status, 'publish') as post:
                status.main()
            post.assert_called_once_with(self.plan, 'error')
