"""Dispatch each newly visible protected-main worker receipt, including failed test jobs."""
import json
import os
import re
import subprocess
import time


def gh(*args):
    return subprocess.check_output(['gh', *args], text=True)


def observe(repo, run, attempt):
    if not re.fullmatch(r'[0-9]+', run) or not re.fullmatch(r'[0-9]+', attempt):
        raise ValueError('Expected a numeric main run and attempt')
    prefix = f'migrations-main-requests-v1-{run}-{attempt}-'
    dispatched = set()
    terminal = 0
    while terminal < 2:
        caches = json.loads(gh('api', '--paginate', '--slurp', '-X', 'GET', f'repos/{repo}/actions/caches',
                               '-f', 'ref=refs/heads/main', '-f', f'key={prefix}', '-F', 'per_page=100'))
        for page in caches:
            for row in page['actions_caches']:
                key = row['key']
                if key in dispatched or not re.fullmatch(re.escape(prefix) + r'[a-f0-9-]{36}', key):
                    continue
                # A completed-event recovery observer can run after the first observer exited.
                # A queued/in-progress sync for this immutable receipt already owns the retry.
                runs = json.loads(gh('api', '-X', 'GET', f'repos/{repo}/actions/workflows/dependency-cache-sync.yml/runs',
                                     '-f', 'branch=main', '-F', 'per_page=100'))['workflow_runs']
                title = f'Publish dependency cache · {key}'
                if not any(r['display_title'] == title and (r['status'] != 'completed' or r['conclusion'] == 'success') for r in runs):
                    gh('workflow', 'run', 'dependency-cache-sync.yml', '--repo', repo, '--ref', 'main', '-f', f'receipt={key}')
                    print(f'Queued protected main receipt {key}', flush=True)
                dispatched.add(key)
        status = gh('api', f'repos/{repo}/actions/runs/{run}', '--jq', '.status').strip()
        terminal = terminal + 1 if status == 'completed' else 0
        if terminal < 2:
            time.sleep(15 if terminal else 45)


if __name__ == '__main__':
    observe(os.environ['GITHUB_REPOSITORY'], os.environ['SOURCE_ID'], os.environ['SOURCE_ATTEMPT'])
