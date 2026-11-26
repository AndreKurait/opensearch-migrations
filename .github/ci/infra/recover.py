"""Recover sandbox stages interrupted before the worker's teardown completed."""
import hashlib
import json
import os
import re
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timedelta, timezone
from urllib.error import URLError
from urllib.request import HTTPRedirectHandler, Request, build_opener


FAILED = {'FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT'}


def variables(build):
    return {item['name']: item['value'] for item in build['environment']['environmentVariables']}


def recent_builds(client, project):
    # Scan by time, so a busy day does not hide an older interrupted build.
    cutoff = datetime.now(timezone.utc) - timedelta(days=2)
    token = {}
    while True:
        page = client.list_builds_for_project(projectName=project, **token)
        if page['ids']:
            builds = client.batch_get_builds(ids=page['ids'])['builds']
            yield from (build for build in builds if build['startTime'] >= cutoff)
            if any(build['startTime'] < cutoff for build in builds):
                return
        if 'nextToken' not in page:
            return
        token = {'nextToken': page['nextToken']}


class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def cancelled_attempt(run_id, attempt):
    repo = 'AndreKurait/opensearch-migrations'
    request = Request(f'https://api.github.com/repos/{repo}/actions/runs/{run_id}/attempts/{attempt}',
                      headers={'Accept': 'application/vnd.github+json', 'User-Agent': 'migration-ci-recovery'})
    try:
        with build_opener(NoRedirect()).open(request, timeout=4) as response:
            data = json.load(response)
        return (data.get('id') == int(run_id) and data.get('run_attempt') == int(attempt) and
                data.get('repository', {}).get('id') == 744243892 and
                data.get('repository', {}).get('full_name') == repo and
                data.get('path') == '.github/workflows/aws-integration.yml' and
                data.get('status') == 'completed' and data.get('conclusion') == 'cancelled')
    except (URLError, OSError, ValueError, TypeError, AttributeError) as error:
        # An unavailable, rate-limited or malformed response is never cancellation evidence.
        print('GitHub status unavailable', run_id, attempt, type(error).__name__)
        return False


def stop_cancelled_attempts(client, builds, project):
    grouped = {}
    for build in builds:
        if build['buildStatus'] != 'IN_PROGRESS' or build['projectName'] != project:
            continue
        env = variables(build)
        if env.get('CI_MODE') == 'cleanup' or env.get('CI_RECOVER_BUILD'):
            continue
        run_id, attempt = env.get('CI_GH_RUN_ID', ''), env.get('CI_GH_RUN_ATTEMPT', '')
        if not re.fullmatch(r'[1-9][0-9]{0,19}', run_id) or not re.fullmatch(r'[1-9][0-9]{0,7}', attempt):
            continue
        grouped.setdefault((run_id, attempt), []).append(build['id'])
    # One request per distinct attempt. Four sweeps of at most 12 calls fit the
    # public API's hourly limit; rotate the starting point for larger projects.
    keys = sorted(grouped)
    if not keys:
        return
    first = int(datetime.now(timezone.utc).timestamp() // 900) * 12 % len(keys)
    selected = (keys[first:] + keys[:first])[:12]
    with ThreadPoolExecutor(max_workers=4) as pool:
        pending = {key: pool.submit(cancelled_attempt, *key) for key in selected}
        for key, future in pending.items():
            if future.result():
                for build_id in grouped[key]:
                    client.stop_build(id=build_id)
                    print('Stopped worker for cancelled GitHub attempt', *key, build_id)


def handler(event, context):
    import boto3

    project = os.environ['PROJECT']
    client = boto3.client('codebuild')
    past = list(recent_builds(client, project))
    if 'build-id' in event.get('detail', {}):
        build_id = event['detail']['build-id'].split('build/')[-1]
        candidates = [b for b in past if b['id'] == build_id]
    else:
        candidates = past
        stop_cancelled_attempts(client, past, project)
    recovery = []
    for build in candidates:
        env = variables(build)
        separate = env.get('CI_SEPARATE_CLEANUP') == 'true' and build['buildStatus'] == 'SUCCEEDED'
        if build['projectName'] != project or (build['buildStatus'] not in FAILED and not separate):
            continue
        stage = env.get('CI_STAGE', '')
        suite = env.get('CI_SUITE', '')
        if env.get('CI_MODE') == 'cleanup' or not re.fullmatch(r'ci-[a-z0-9]{1,12}', stage):
            continue
        if not re.fullmatch(r'[a-z][a-z0-9-]{1,48}', suite):
            continue
        attempts = [b for b in past if variables(b).get('CI_RECOVER_BUILD') == build['id']]
        # Never start a second worker while cleanup is active or after it passed.
        if len(attempts) >= 3 or any(b['buildStatus'] not in FAILED for b in attempts):
            continue
        overrides = {'CI_STAGE': stage, 'CI_SUITE': suite, 'CI_MODE': 'cleanup', 'CI_RECOVER_BUILD': build['id']}
        key = hashlib.sha256(f"{build['id']}/{len(attempts)}".encode()).hexdigest()
        variables_override = [{'name': k, 'value': v} for k, v in overrides.items()]
        role = {}
        if build.get('serviceRole') and build['serviceRole'] == os.environ.get('MAIN_WORKER_ROLE'):
            role = {'serviceRoleOverride': build['serviceRole']}
        started = client.start_build(projectName=project, **role, timeoutInMinutesOverride=150, idempotencyToken=key,
                                     environmentVariablesOverride=variables_override)
        recovery.append(started['build']['id'])
        if len(recovery) == 4:
            break
    return recovery
