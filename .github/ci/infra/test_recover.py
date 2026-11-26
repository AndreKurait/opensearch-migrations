"""Cancellation handoff checks using recorded response shapes and a simulated CodeBuild client."""
import io
import json
import unittest
from unittest.mock import Mock, call, patch
from urllib.error import HTTPError

import recover


PROJECT = 'migrations-github-eks'


def build(name, attempt='1', mode='', status='IN_PROGRESS', project=PROJECT):
    env = {'CI_GH_RUN_ID': '101', 'CI_GH_RUN_ATTEMPT': attempt, 'CI_MODE': mode}
    return {'id': name, 'projectName': project, 'buildStatus': status,
            'environment': {'environmentVariables': [{'name': k, 'value': v} for k, v in env.items()]}}


class CancelledAttemptTests(unittest.TestCase):
    def test_history_continuation_uses_only_project_and_token(self):
        client = Mock()
        client.list_builds_for_project.side_effect = [{'ids': [], 'nextToken': 'next'}, {'ids': []}]
        self.assertEqual(list(recover.recent_builds(client, PROJECT)), [])
        self.assertEqual(client.list_builds_for_project.call_args_list,
                         [call(projectName=PROJECT), call(projectName=PROJECT, nextToken='next')])

    def response(self, changes):
        body = {'id': 101, 'run_attempt': 1, 'repository': {'id': 744243892,
                'full_name': 'AndreKurait/opensearch-migrations'}, 'path': '.github/workflows/aws-integration.yml',
                'status': 'completed', 'conclusion': 'cancelled', **changes}
        return io.BytesIO(json.dumps(body).encode())

    @patch('recover.build_opener')
    def test_only_confirmed_cancellation_of_exact_workflow_and_attempt(self, opener):
        for changes, expected in [({}, True), ({'status': 'in_progress'}, False), ({'conclusion': 'success'}, False),
                                  ({'run_attempt': 2}, False), ({'id': 102}, False),
                                  ({'path': '.github/workflows/other.yml'}, False),
                                  ({'repository': {'id': 1, 'full_name': 'AndreKurait/opensearch-migrations'}}, False)]:
            with self.subTest(changes=changes):
                opener.return_value.open.return_value = self.response(changes)
                self.assertEqual(recover.cancelled_attempt('101', '1'), expected)
                request = opener.return_value.open.call_args.args[0]
                self.assertEqual(request.full_url,
                                 'https://api.github.com/repos/AndreKurait/opensearch-migrations/actions/runs/'
                                 '101/attempts/1')

    @patch('recover.build_opener')
    def test_rate_limit_and_invalid_response_leave_build_running(self, opener):
        opener.return_value.open.side_effect = HTTPError('https://api.github.com', 429, 'limited', {}, None)
        self.assertFalse(recover.cancelled_attempt('101', '1'))
        opener.return_value.open.side_effect = None
        opener.return_value.open.return_value = io.BytesIO(b'not json')
        self.assertFalse(recover.cancelled_attempt('101', '1'))

    @patch('recover.cancelled_attempt', side_effect=lambda run, attempt: attempt == '1')
    def test_stop_only_live_originals_and_lookup_each_attempt_once(self, lookup):
        client = Mock()
        rows = [build('first'), build('second'), build('healthy', attempt='2'), build('cleanup', mode='cleanup'),
                build('ended', status='SUCCEEDED'), build('other-project', project='other'),
                build('absent', attempt='')]
        recover.stop_cancelled_attempts(client, rows, PROJECT)
        self.assertCountEqual(lookup.call_args_list, [call('101', '1'), call('101', '2')])
        self.assertEqual(client.stop_build.call_args_list, [call(id='first'), call(id='second')])

    @patch('recover.cancelled_attempt', return_value=False)
    def test_rate_budget_counts_unique_attempts_even_in_large_project(self, lookup):
        client = Mock()
        rows = [build(f'build-{n}', attempt=str(n + 1)) for n in range(18)]
        recover.stop_cancelled_attempts(client, rows, PROJECT)
        self.assertEqual(lookup.call_count, 12)
        client.stop_build.assert_not_called()


if __name__ == '__main__':
    unittest.main()
