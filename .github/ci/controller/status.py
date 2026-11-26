"""Record PR commit statuses and reconcile an interrupted AWS workflow attempt."""
import argparse
import io
import json
import os
from pathlib import Path
import re
import subprocess
from zipfile import ZipFile

from capacity import REPO
from parameters import SUITES
from plan import SHA

WORKFLOW = '.github/workflows/aws-integration.yml'
PLAN_FILE = 'approved-plan.json'
MAX_ARCHIVE = 16384
MAX_PLAN = 2048


def api(path, *args):
    return subprocess.check_output(['gh', 'api', f'repos/{REPO}/{path}', *args])


def positive(value):
    return type(value) is int and value > 0


def validate(plan, run, attempt):
    if (not isinstance(plan, dict) or set(plan) - {'pr'} != {'sha', 'suites', 'run', 'attempt'} or
            ('pr' in plan and not positive(plan['pr'])) or
            not positive(plan['run']) or not positive(plan['attempt']) or
            (plan['run'], plan['attempt']) != (run, attempt) or
            not isinstance(plan['sha'], str) or not re.fullmatch(SHA, plan['sha'])):
        raise ValueError('Invalid approved run or revision')
    suites = plan['suites']
    if (not isinstance(suites, list) or not 1 <= len(suites) <= len(SUITES) or
            any(not isinstance(s, str) or s not in SUITES for s in suites) or len(set(suites)) != len(suites)):
        raise ValueError('Invalid approved suite list')
    return plan


def read_artifact(run, attempt):
    name = f'aws-approved-plan-{run}-{attempt}'
    listing = json.loads(api(f'actions/runs/{run}/artifacts?name={name}&per_page=2'))
    if listing['total_count'] == 0:
        return None  # A run cancelled before approval has no pending statuses.
    if listing['total_count'] != 1 or len(listing['artifacts']) != 1:
        raise ValueError('Expected one approved plan artifact')
    asset = listing['artifacts'][0]
    if (asset['name'] != name or asset['expired'] or not positive(asset['id']) or
            asset['workflow_run']['id'] != run or not 0 < asset['size_in_bytes'] <= MAX_ARCHIVE):
        raise ValueError('Invalid approved plan artifact metadata')
    body = api(f'actions/artifacts/{asset["id"]}/zip', '--allow-escape-sequences')
    if len(body) > MAX_ARCHIVE:
        raise ValueError('Approved plan archive exceeds limit')
    with ZipFile(io.BytesIO(body)) as archive:
        if archive.namelist() != [PLAN_FILE] or not 0 < archive.getinfo(PLAN_FILE).file_size <= MAX_PLAN:
            raise ValueError('Expected a single bounded approved-plan.json')
        return validate(json.loads(archive.read(PLAN_FILE)), run, attempt)


def publish(plan, state):
    sha, run, attempt = plan['sha'], plan['run'], plan['attempt']
    attempt_prefix = f'https://github.com/{REPO}/actions/runs/{run}/attempts/'
    target = f'{attempt_prefix}{attempt}'
    descriptions = {'pending': 'Approved and queued for Oregon capacity', 'error': 'AWS test run cancelled',
                    'failure': 'AWS test failure', 'success': 'AWS test success'}
    pages = json.loads(api(f'commits/{sha}/status?per_page=100', '--paginate', '--slurp'))
    latest = {row['context']: row for page in pages for row in page['statuses']}
    for suite in plan['suites']:
        context = f'aws-integration/{suite}'
        row = latest.get(context, {})
        if state == 'pending':
            run_pattern = re.escape(f'https://github.com/{REPO}/actions/runs/') + r'(\d+)/attempts/(\d+)'
            previous = re.fullmatch(run_pattern, row.get('target_url') or '')
            if previous and tuple(map(int, previous.groups())) >= (run, attempt):
                continue
        else:
            previous_target = row.get('target_url') or ''
            pending = row.get('state') == 'pending' and previous_target == target
            # Rerunning only failed jobs reuses the successful selector and its prior attempt's status.
            previous = re.fullmatch(re.escape(attempt_prefix) + r'([1-9][0-9]*)', previous_target)
            rerun = state != 'error' and previous and int(previous[1]) < attempt
            if not (pending or rerun):
                continue
        api(f'statuses/{sha}', '-X', 'POST', '--silent', '-f', f'state={state}', '-f', f'context={context}',
            '-f', f'description={descriptions[state]}', '-f', f'target_url={target}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=['plan', 'pending', 'success', 'failure', 'cancelled', 'identify'])
    action = parser.parse_args().action
    if os.environ['GITHUB_REPOSITORY'] != REPO or os.environ['GITHUB_REF'] != 'refs/heads/main':
        raise ValueError('PR statuses must originate from the fork default branch')
    if action in ('cancelled', 'identify'):
        event = json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text())
        run = event['workflow_run']
        if (event['action'] != 'completed' or run['status'] != 'completed' or run['conclusion'] != 'cancelled' or
                run['path'] != WORKFLOW or run['head_repository']['full_name'] != REPO or
                run['head_branch'] != 'main' or run['event'] not in ('issue_comment', 'workflow_dispatch') or
                not positive(run['id']) or not positive(run['run_attempt'])):
            raise ValueError('Unexpected cancelled workflow')
        plan = read_artifact(run['id'], run['run_attempt'])
        if plan and action == 'identify':
            pr = plan.get('pr')
            if not pr:
                # Previous plans recorded only the reviewed commit. Legacy issue-comment
                # runs already identify their PR in the workflow's controlled run title.
                title = re.fullmatch(r'AWS tests · PR ([1-9]\d*)', run.get('display_title', ''))
                if title and run['event'] == 'issue_comment':
                    pr = int(title[1])
                else:
                    candidates = json.loads(api(f'commits/{plan["sha"]}/pulls?per_page=100'))
                    matches = {p['number'] for p in candidates if positive(p.get('number')) and
                               p.get('head', {}).get('sha') == plan['sha'] and p['base']['repo']['full_name'] == REPO}
                    if len(matches) != 1:
                        raise ValueError('Cannot identify unique PR for historical approved plan')
                    pr = matches.pop()
            with open(os.environ['GITHUB_OUTPUT'], 'a') as outputs:
                print(f'pr={pr}', file=outputs)
        elif plan:
            publish(plan, 'error')
        return
    run, attempt = int(os.environ['GITHUB_RUN_ID']), int(os.environ['GITHUB_RUN_ATTEMPT'])
    file = Path(os.environ['RUNNER_TEMP']) / PLAN_FILE
    if action == 'pending':
        plan = validate(json.loads(file.read_bytes()), run, attempt)
    else:
        suites = ([row['suite'] for row in json.loads(os.environ['MATRIX'])]
                  if action == 'plan' else [os.environ['SUITE']])
        plan = dict(sha=os.environ['SHA'], suites=suites, run=run, attempt=attempt)
        if action == 'plan' and os.environ.get('PR'):
            plan['pr'] = int(os.environ['PR'])
        validate(plan, run, attempt)
    if action == 'plan':
        file.write_text(json.dumps(plan))
    else:
        publish(plan, action)


if __name__ == '__main__':
    main()
