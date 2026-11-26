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
    # A separate workflow_run completion event sweeps every remaining receipt.
    # Release this hosted runner while the source jobs are still queued.
    for poll in range(3):
        caches = json.loads(gh('api', '--paginate', '--slurp', '-X', 'GET', f'repos/{repo}/actions/caches',
                               '-f', 'ref=refs/heads/main', '-f', f'key={prefix}', '-F', 'per_page=100'))
        visible = {row['key'] for page in caches for row in page['actions_caches']
                   if re.fullmatch(re.escape(prefix) + r'[a-f0-9-]{36}', row['key'])}
        if visible - dispatched:
            endpoint = f'repos/{repo}/actions/workflows/dependency-cache-sync.yml/runs'
            runs = json.loads(gh('api', '-X', 'GET', endpoint, '-f', 'branch=main', '-F', 'per_page=100'))
            for previous in runs['workflow_runs']:
                if previous['status'] == 'completed' and previous['conclusion'] != 'success':
                    continue
                title = f'Publish dependency cache · {prefix}'
                if previous['display_title'].startswith(title):
                    dispatched.update(prefix + suffix for suffix in previous['display_title'][len(title):].split(','))
            pending = sorted(visible - dispatched)
            # Four UUIDs plus the shared run prefix fit in a single Actions run title.
            for offset in range(0, len(pending), 4):
                batch = pending[offset:offset + 4]
                receipt = prefix + ','.join(key[len(prefix):] for key in batch)
                gh('workflow', 'run', 'dependency-cache-sync.yml', '--repo', repo, '--ref', 'main',
                   '-f', f'receipt={receipt}')
                print(f'Queued {len(batch)} protected main worker receipts', flush=True)
                dispatched.update(batch)
        status = gh('api', f'repos/{repo}/actions/runs/{run}', '--jq', '.status').strip()
        terminal = terminal + 1 if status == 'completed' else 0
        if terminal == 2 or poll == 2:
            return
        time.sleep(15 if terminal else 45)


if __name__ == '__main__':
    observe(os.environ['GITHUB_REPOSITORY'], os.environ['SOURCE_ID'], os.environ['SOURCE_ATTEMPT'])
