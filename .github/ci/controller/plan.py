"""Select suites and authorize the immutable revision before acquiring AWS credentials."""
import json
import os
import re
import subprocess
from pathlib import Path

from capacity import config, lanes
from parameters import ALIASES, SUITES, parameters
from suites import DISABLED_SUITES

ALL = tuple(s for s in SUITES if s not in DISABLED_SUITES)
DEFAULT_PR_SUITE = 'cfn-create'
GROUPS = {
    'all': ALL,
    'run-eks-tests': ('eks', 'aoss', 'cdc', 'cdc-aoss', 'cdc-k6'),
    'run-eks-byos-tests': ('byos',), 'run-cfn-tests': ('cfn-create', 'cfn-import'),
    'run-eks-isolated-tests': ('isolated',), 'cdc-tests': ('cdc', 'cdc-aoss', 'cdc-k6'),
}
SCHEDULES = {
    '13 */2 * * *': ('eks',),
    '27 */6 * * *': ('byos', 'aoss', 'cdc', 'cdc-aoss', 'cdc-k6'),
    '41 */12 * * *': ('cfn-create', 'cfn-import'),
    '53 */6 * * *': ('eks', 'aoss', 'byos', 'cdc', 'cdc-aoss', 'cdc-k6', 'cfn-create', 'cfn-import'),
}
CANARY = '53 */6 * * *'
RELEASE = r'[0-9]+\.[0-9]+\.[0-9]+'
SHA = r'[0-9a-f]{40}'


def api(path):
    return json.loads(subprocess.check_output(['gh', 'api', path], text=True))


def select(group):
    if ',' in group:
        return tuple(dict.fromkeys(suite for name in group.split(',') for suite in select(name)))
    if group in GROUPS:
        enabled = tuple(suite for suite in GROUPS[group] if suite not in DISABLED_SUITES)
        if enabled:
            return enabled
        raise ValueError('Snapshot suites are paused pending fixture access')
    if ALIASES.get(group, group) in DISABLED_SUITES:
        raise ValueError('Snapshot suites are paused pending fixture access')
    if group in SUITES or group in ALIASES:
        return (ALIASES.get(group, group),)
    raise ValueError(f'Unknown suite. Enabled suites: {", ".join(ALL)}')


def latest_release():
    # Use the published Migration Assistant distribution. The fork also contains unrelated historical CLI releases.
    release = api('repos/opensearch-project/opensearch-migrations/releases/latest')
    if 'aws-bootstrap.sh' not in {asset['name'] for asset in release['assets']}:
        raise ValueError('The latest published release has no aws-bootstrap.sh asset')
    return release['tag_name']


def plan(event_name, event, repository, sha, ref, authenticate=api):
    if ref != 'refs/heads/main' or event['repository']['full_name'] != repository:
        raise ValueError('AWS test dispatch must use main in the base repository')
    git_ref = ref
    release = ''
    pull = None
    options = '{}'
    group = None
    if event_name == 'issue_comment':
        if 'pull_request' not in event['issue']:
            raise ValueError('AWS tests must be requested on a pull request')
        command = re.fullmatch(rf'/aws-test\s+({SHA})(?:\s+([a-z0-9,-]+))?', event['comment']['body'].strip())
        if not command:
            raise ValueError('Expected /aws-test <full lowercase commit SHA> [suite or group]')
        sha, group = command.groups()
        pull = event['issue']['number']
        actor = event['comment']['user']['login']
    elif event_name == 'schedule':
        suites = tuple(s for s in SCHEDULES[event['schedule']] if s not in DISABLED_SUITES)
        if event['schedule'] == CANARY:
            release = latest_release()
    elif event_name == 'workflow_dispatch':
        inputs = event['inputs']
        group = inputs.get('suite') or None
        suites = select(group) if group else (DEFAULT_PR_SUITE,)
        release = inputs.get('release', '').strip()
        options = inputs.get('parameters', '{}')
        if inputs.get('pr') or inputs.get('sha'):
            pull = inputs.get('pr', '')
            sha = inputs.get('sha', '')
            if not re.fullmatch(r'[1-9][0-9]*', pull) or not re.fullmatch(SHA, sha):
                raise ValueError('For PR tests, pass both the PR number and its full current SHA')
            actor = event['sender']['login']
    elif event_name == 'push':
        suites = ALL
    else:
        raise ValueError('Unsupported event')
    if pull:
        permission = authenticate(f'repos/{repository}/collaborators/{actor}/permission')['permission']
        if permission not in ('write', 'admin', 'maintain'):
            raise PermissionError('Running AWS tests requires current repository write permission')
        pr = authenticate(f'repos/{repository}/pulls/{pull}')
        if (pr['state'], pr['base']['repo']['full_name'], pr['base']['ref'], pr['head']['sha']) != (
            'open', repository, 'main', sha
        ):
            raise ValueError('The approved SHA must be the current head of an open PR targeting main in this repo')
        git_ref = f'refs/pull/{pull}/head'
        if group:
            suites = select(group)
        else:
            labels = {label['name'] for label in pr['labels']}
            matched = [key for key in GROUPS if key != 'all' and key in labels]
            suites = tuple(dict.fromkeys(s for key in matched for s in GROUPS[key] if s not in DISABLED_SUITES))
            if matched and not suites:
                raise ValueError('Only paused snapshot suites were requested')
            suites = suites or (DEFAULT_PR_SUITE,)
    if not re.fullmatch(SHA, sha) or (release and not re.fullmatch(RELEASE, release)):
        raise ValueError('Invalid commit SHA or release version')
    return {'sha': sha, 'ref': git_ref, 'matrix': json.dumps(list(suites), separators=(',', ':')),
            'release': release, 'pr': str(pull or ''), 'parameters': json.dumps(parameters(suites, options))}


if __name__ == '__main__':
    result = plan(os.environ['GITHUB_EVENT_NAME'], json.loads(Path(os.environ['GITHUB_EVENT_PATH']).read_text()),
                  os.environ['GITHUB_REPOSITORY'], os.environ['GITHUB_SHA'], os.environ['GITHUB_REF'])
    selected = json.loads(result['matrix'])
    slots = lanes(config()['room'])
    if any(s not in slots for s in selected):
        raise ValueError('Sandbox capacity cannot fit this selection; refresh AWS_CI_CONFIG after capacity changes')
    result['matrix'] = json.dumps([dict(suite=s, lane=slots[s]) for s in selected])
    with open(os.environ['GITHUB_OUTPUT'], 'a') as output:
        for key, value in result.items():
            print(f'{key}={value}', file=output)
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as summary:
        print(f"**Revision** `{result['sha']}` · **Source** `{result['ref']}`", file=summary)
        print('\n' + '\n'.join(f'- {s}' for s in selected), file=summary)
