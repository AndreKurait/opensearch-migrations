"""Launch one sandbox worker and relay its logs to GitHub, across hosted runner time limits."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import signal
import subprocess
import sys
import time

from capacity import admit, client, config
from parameters import ALIASES, SUITES, parameters
from plan import RELEASE, SHA, select
from report import public_text, render

PROJECT = 'migrations-github-eks'
LOG_GROUP = '/aws/codebuild/migrations-github-eks'
UUID = r'[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}'
BUILD_ID = rf'{PROJECT}:{UUID}'
TERMINAL = {'SUCCEEDED', 'FAILED', 'FAULT', 'STOPPED', 'TIMED_OUT'}


def aws(*args):
    result = subprocess.run(['aws', *args, '--output', 'json', '--no-cli-pager'], capture_output=True, text=True)
    if result.returncode:
        raise RuntimeError(public_text(f'AWS {args[0]} {args[1]}: {result.stderr.strip()}'))
    return json.loads(result.stdout) if result.stdout.strip() else {}


def output(**items):
    with open(os.environ['GITHUB_OUTPUT'], 'a') as file:
        for key, value in items.items():
            if '\n' in str(value) or '\r' in str(value):
                raise ValueError(f'Unexpected multiline output: {key}')
            print(f'{key}={value}', file=file)


def summary(line):
    with open(os.environ['GITHUB_STEP_SUMMARY'], 'a') as file:
        print(line, file=file)


def start(suite, sha, ref, release, options):
    if select(suite) != (suite,) or not re.fullmatch(SHA, sha):
        raise ValueError('A single suite and a full SHA are required')
    if not re.fullmatch(r'refs/(heads/main|pull/[1-9][0-9]*/head)', ref):
        raise ValueError('Unsupported git ref')
    if release and not re.fullmatch(RELEASE, release):
        raise ValueError('Invalid release version')
    run_key = (f"{os.environ['GITHUB_REPOSITORY']}/{os.environ['GITHUB_RUN_ID']}/"
               f"{os.environ['GITHUB_RUN_ATTEMPT']}/{suite}")
    digest = hashlib.sha256(run_key.encode()).hexdigest()
    stage = 'ci-' + digest[:12]
    values = {'CI_SUITE': suite, 'CI_GIT_SHA': sha, 'CI_GIT_REF': ref, 'CI_STAGE': stage}
    values.update(parameters((suite,), options))
    if release:
        values['CI_RELEASE_VERSION'] = release
    variables = [dict(name=name, value=value, type='PLAINTEXT') for name, value in values.items()]
    build = aws('codebuild', 'start-build', '--project-name', PROJECT, '--idempotency-token', digest,
                '--timeout-in-minutes-override', str(SUITES[ALIASES.get(suite, suite)].timeout_minutes),
                '--environment-variables-override', json.dumps(variables))['build']
    if not re.fullmatch(BUILD_ID, build['id']):
        raise ValueError('CodeBuild returned an unexpected build ID')
    output(build_id=build['id'])
    summary(f"**{suite}** · `{sha}` · `{stage}` · CodeBuild `{build['id']}`")
    print(f"Started {build['id']} for {sha} ({stage})", flush=True)


def stream(logs, cursor, max_pages=50):
    if not logs.get('streamName'):
        return cursor
    if logs['groupName'] != LOG_GROUP:
        raise ValueError('Unexpected log group')
    for _ in range(max_pages):
        args = ['logs', 'get-log-events', '--log-group-name', LOG_GROUP, '--log-stream-name', logs['streamName'],
                '--start-from-head', '--no-paginate']
        if cursor:
            args.extend(['--next-token', cursor])
        try:
            page = aws(*args)
        except RuntimeError as error:
            if 'ResourceNotFoundException' in str(error):
                return cursor  # The build can register the stream before CloudWatch creates it.
            raise
        # Remote output is data. Disable runner commands even for multiline messages with command-like text.
        marker = secrets.token_hex(32)
        if page['events']:
            print('::stop-commands::' + marker, flush=True)
            try:
                for entry in page['events']:
                    text = re.sub(r'\x1b\[[0-?]*[ -/]*[@-~]|[\x00-\x08\x0b-\x1f\x7f]', '', entry['message'])
                    print(public_text(text), end='' if text.endswith('\n') else '\n', flush=True)
            finally:
                print(f'::{marker}::', flush=True)
        end = page['nextForwardToken']
        if end == cursor:
            break
        cursor = end
    return cursor


def collect(build):
    location = build.get('artifacts', {}).get('location')
    if not location:
        return False
    outputs = aws('cloudformation', 'describe-stacks', '--stack-name', 'migrations-github-ci')['Stacks'][0]['Outputs']
    bucket = next(x['OutputValue'] for x in outputs if x['OutputKey'] == 'ArtifactBucketName')
    uuid = build['id'].split(':')[1]
    key = f'builds/{uuid}/results.zip'
    if location != f'arn:aws:s3:::{bucket}/{key}':
        raise ValueError('Unexpected CodeBuild artifact location')
    target = Path(os.environ['RUNNER_TEMP']) / 'aws-test-results.zip'
    head = aws('s3api', 'head-object', '--bucket', bucket, '--key', key)
    if not 0 < head['ContentLength'] <= 64 * 1024**2:
        raise ValueError('Worker artifact is empty or exceeds the 64 MiB report limit')
    private = target.with_suffix('.private.zip')
    try:
        aws('s3api', 'get-object', '--bucket', bucket, '--key', key, str(private))
        report, counts = render(private, public_archive=target)
    finally:
        private.unlink(missing_ok=True)
    summary(report)
    if build['buildStatus'] == 'SUCCEEDED' and (counts['failed'] or counts['error'] or not counts['passed']):
        raise ValueError('Successful worker returned failing or empty test evidence')
    return True


def watch(build_id, cursor, minutes):
    if not re.fullmatch(BUILD_ID, build_id):
        raise ValueError('Unexpected build ID')
    stop_at = time.monotonic() + minutes * 60
    previous = ''
    while True:
        rows = aws('codebuild', 'batch-get-builds', '--ids', build_id)['builds']
        if len(rows) != 1 or rows[0]['id'] != build_id or rows[0]['projectName'] != PROJECT:
            raise ValueError('Could not locate the requested build')
        build = rows[0]
        phase = build['currentPhase']
        if phase != previous:
            print(f'CodeBuild phase: {phase}', flush=True)
            previous = phase
        state = build['buildStatus']
        cursor = stream(build.get('logs', {}), cursor, max_pages=10000 if state in TERMINAL else 50)
        if state in TERMINAL:
            if state != 'SUCCEEDED':
                for section in build.get('phases', []):
                    print(section.get('phaseType'), section.get('phaseStatus'),
                          [error.get('statusCode') for error in section.get('contexts', [])], flush=True)
            summary(f"CodeBuild `{build_id}` finished **{state}**.")
            # In a provisioning failure CodeBuild can advertise an S3 destination without creating an object.
            # A successful suite must have produced its evidence. A failed suite remains failed either way.
            try:
                artifact = collect(build)
            except Exception as error:
                if state == 'SUCCEEDED':
                    raise
                print(f'Failed build report unavailable: {error}', file=sys.stderr)
                artifact = False
            output(state=state, cursor=cursor, artifact=str(artifact).lower())
            if state != 'SUCCEEDED':
                sys.exit(1)
            if not build.get('artifacts', {}).get('location'):
                raise RuntimeError('Successful build has no test report')
            return
        if state != 'IN_PROGRESS':
            raise ValueError('Unexpected CodeBuild state')
        if time.monotonic() >= stop_at:
            output(state='IN_PROGRESS', cursor=cursor, artifact='false')
            summary(f"CodeBuild `{build_id}` is still {phase}. The next hosted job resumes the log stream.")
            return
        time.sleep(10)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest='operation', required=True)
    start_parser = sub.add_parser('start')
    for arg in ('suite', 'sha', 'ref', 'release', 'options'):
        start_parser.add_argument(arg)
    watch_parser = sub.add_parser('watch')
    watch_parser.add_argument('build_id')
    watch_parser.add_argument('cursor')
    watch_parser.add_argument('--minutes', type=int, default=305)
    opts = parser.parse_args()
    if opts.operation == 'start':
        admit(opts.suite, client(), config())
        start(opts.suite, opts.sha, opts.ref, opts.release, opts.options)
    else:
        def cancel(signum, _frame):
            if re.fullmatch(BUILD_ID, opts.build_id):
                aws('codebuild', 'stop-build', '--id', opts.build_id)
            sys.exit(128 + signum)

        signal.signal(signal.SIGINT, cancel)
        signal.signal(signal.SIGTERM, cancel)
        watch(opts.build_id, opts.cursor, opts.minutes)
