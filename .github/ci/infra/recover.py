"""Recover sandbox stages interrupted before the worker's teardown completed."""
import hashlib
import os
import re
from datetime import datetime, timedelta, timezone

import boto3

FAILED = {'FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT'}


def variables(build):
    return {item['name']: item['value'] for item in build['environment']['environmentVariables']}


def recent_builds(client, project):
    # Scan by time, so a busy day does not hide an older interrupted build.
    cutoff = datetime.now(timezone.utc) - timedelta(days=2)
    token = {}
    while True:
        page = client.list_builds_for_project(projectName=project, sortOrder='DESCENDING', **token)
        if page['ids']:
            builds = client.batch_get_builds(ids=page['ids'])['builds']
            yield from (build for build in builds if build['startTime'] >= cutoff)
            if any(build['startTime'] < cutoff for build in builds):
                return
        if 'nextToken' not in page:
            return
        token = {'nextToken': page['nextToken']}


def handler(event, context):
    project = os.environ['PROJECT']
    client = boto3.client('codebuild')
    past = list(recent_builds(client, project))
    if 'build-id' in event.get('detail', {}):
        build_id = event['detail']['build-id'].split('build/')[-1]
        candidates = [b for b in past if b['id'] == build_id]
    else:
        candidates = past
    recovery = []
    for build in candidates:
        if build['projectName'] != project or build['buildStatus'] not in FAILED:
            continue
        env = variables(build)
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
        started = client.start_build(projectName=project, timeoutInMinutesOverride=150, idempotencyToken=key,
                                     environmentVariablesOverride=variables_override)
        recovery.append(started['build']['id'])
        if len(recovery) == 4:
            break
    return recovery
