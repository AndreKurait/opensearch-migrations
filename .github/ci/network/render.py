#!/usr/bin/env python3
"""Render or verify the first steps of every local job without reformatting its workflow."""

import argparse
from hashlib import sha256
import json
from pathlib import Path
import re
from textwrap import indent

HERE = Path(__file__).resolve().parent
WORKFLOWS = HERE.parent.parent / 'workflows'
POLICY = json.loads((HERE / 'policy.json').read_text())
JOB = re.compile(r'^  ([\w-]+):[ \t]*(?:#.*)?$', re.M)
START = '# BEGIN generated network guard'
END = '# END generated network guard'
BASE = '${{ github.event.pull_request.base.sha || github.event.merge_group.base_sha || github.workflow_sha }}'


def bootstrap(file, native, platform):
    digest = sha256((HERE / file).read_bytes()).hexdigest()
    lines = [
        f'- name: Enforce {platform} network policy before candidate checkout',
        '  shell: bash',
        '  working-directory: ${{ runner.temp }}',
        '  env:',
        f'    POLICY_SHA: {BASE}',
        '  run: |',
        '    set -euo pipefail',
        f'    guard="$RUNNER_TEMP/{file}"',
        '    curl -fsSL --retry 3 "https://raw.githubusercontent.com/$GITHUB_REPOSITORY/'
        f'$POLICY_SHA/.github/ci/network/{file}" -o "$guard"',
        f'    expected={digest}',
        '    test "$(shasum -a 256 "$guard" | cut -d\' \' -f1)" = "$expected"',
    ]
    if platform == 'Linux':
        lines += [
            '    sudo /usr/bin/env RUNNER_ENVIRONMENT="$RUNNER_ENVIRONMENT" /usr/bin/python3 "$guard" install',
            '    /usr/bin/python3 "$guard" probe',
        ]
        if not native:
            lines += [
                '    sudo /usr/bin/python3 "$guard" lock-down',
                '    if sudo -n true >/dev/null 2>&1 || docker info >/dev/null 2>&1; then exit 1; fi',
            ]
    else:
        lines += ['    /usr/bin/python3 "$guard" install']
    return '\n'.join(lines)


def guard(job, definitions):
    if job.get('platform') == 'macos':
        guard_step = bootstrap('darwin_guard.py', False, 'macOS')
        endpoints = ' '.join(
            dict.fromkeys(host for name in job['purposes'] for host in POLICY['purposes'][name])
        )
        return guard_step.replace('  run: |', '    PUBLIC_ENDPOINTS: ' + endpoints + '\n  run: |')
    purposes = job['purposes']
    hosts = set()
    groups = []
    for purpose in purposes:
        group = [host for host in POLICY['purposes'][purpose] if host not in hosts]
        for host in group:
            if not re.fullmatch(r'(?:[a-z0-9-]+\.)+[a-z0-9-]+:(?:443|80)', host):
                raise ValueError(f'Invalid public network destination: {host}')
        hosts.update(group)
        if group:
            groups.append(' '.join(group))
    key = tuple(groups)
    prefix = f'network_{len(definitions[0]) + 1}'
    if key in definitions[0]:
        first = '- *' + definitions[0][key]
    else:
        definitions[0][key] = prefix
        first = '\n'.join(
            [
                f'- &{prefix}',
                '  name: Restrict runner and container egress',
                f"  uses: step-security/harden-runner@{POLICY['action']} # {POLICY['version']}",
                '  with:',
                '    egress-policy: block',
                '    disable-telemetry: true',
                '    allowed-endpoints: >-',
                *['      ' + group for group in groups],
            ]
        )
    native = job['nativePrivileges']
    kind = 'native' if native else 'unprivileged'
    if kind in definitions[1]:
        second = '- *enforce_' + kind
    else:
        definitions[1].add(kind)
        bits = bootstrap('linux_guard.py', native, 'Linux').splitlines()
        second = '\n'.join(['- &enforce_' + kind, '  ' + bits[0][2:], *bits[1:]])
    parts = [first, second]
    if 'unreviewedForkSandbox' in job:
        works = job['unreviewedForkSandbox']
        parts += [
            '- name: Require isolated execution for external fork code',
            "  if: github.event_name == 'pull_request' && github.event.pull_request.head.repo.full_name "
            f"!= github.repository && !({works})",
            '  shell: bash',
            '  working-directory: ${{ runner.temp }}',
            '  run: |',
            '    echo "::error::This external fork test requires an isolated worker. A maintainer can review '
            'the exact commit and run it from a trusted repository ref."',
            '    exit 1',
        ]
    return '\n'.join(parts)


def render(file, inventory):
    raw = file.read_text()
    pre, separator, body = raw.partition('\njobs:\n')
    if not separator:
        raise ValueError(f'{file.name}: expected a jobs mapping')
    headers = list(JOB.finditer(body))
    pieces = [pre, separator, body[: headers[0].start()]]
    definitions = ({}, set())
    for i, hdr in enumerate(headers):
        key = file.name + '/' + hdr[1]
        job = POLICY['jobs'][key]
        inventory.add(key)
        end = headers[i + 1].start() if i + 1 < len(headers) else len(body)
        chunk = body[hdr.start():end]
        if 'delegates' in job:
            if not re.search(r'^    uses: ' + re.escape(job['delegates']) + r'\s*$', chunk, re.M):
                raise ValueError(f'{key}: delegated workflow reference changed')
            pieces.append(chunk)
            continue
        runner = re.search(r'^    runs-on: (.+)$', chunk, re.M)
        labels = (
            re.findall(r'ubuntu-(?:latest|[0-9.]+)|macos-(?:latest|[0-9.]+)', runner[1]) if runner else []
        )
        platform = job.get('platform', 'linux')
        if not labels or any(
            ('macos' if label.startswith('macos-') else 'linux') != platform for label in labels
        ):
            raise ValueError(f'{key}: unsupported hosted runner platform')
        if re.search(r'^    (?:container|services):', chunk, re.M):
            raise ValueError(f'{key}: job containers start before runner policy')
        has_steps = re.search(r'^    steps:\s*\n(?P<space> +)(?:#|[-])', chunk, re.M)
        if not has_steps:
            raise ValueError(f'{key}: missing runner steps')
        space = has_steps.group('space')
        start = has_steps.start('space')
        rest = chunk[start:]
        if rest.startswith(space + START):
            pos = re.search('^' + re.escape(space + END) + '\n', rest, re.M)
            if not pos:
                raise ValueError(f'{key}: unterminated policy')
            rest = rest[pos.end():]
        # Migrate the initial fully expanded policy on branches that first installed the guard.
        elif rest.startswith(space + '- name: Restrict runner and container egress'):
            pos = re.search(
                '^' + re.escape(space) + r'- name: Verify outbound blocking before checkout\n[\s\S]+?^\s+PY\n',
                rest,
                re.M,
            )
            if not pos:
                raise ValueError(f'{key}: unsupported legacy guard')
            rest = rest[pos.end():]
        generated = indent(START + '\n' + guard(job, definitions) + '\n' + END, space) + '\n'
        pieces.append(chunk[:start] + generated + rest)
    result = ''.join(pieces)
    for anchor in set(definitions[0].values()) | {'enforce_' + key for key in definitions[1]}:
        if not re.search(r'(?m)^\s*- \*' + anchor + r'$', result):
            result = re.sub(r'(?m)^([ ]*)- &' + anchor + r'\n\1  name:', r'\1- name:', result)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--write', action='store_true', help='update workflow guard steps')
    parser.add_argument('--only', help='one workflow for a staged migration')
    args = parser.parse_args()
    files = [WORKFLOWS / args.only] if args.only else sorted(WORKFLOWS.glob('*.y*ml'))
    inventory = set()
    drift = []
    for file in files:
        expected = render(file, inventory)
        if expected != file.read_text():
            if args.write:
                file.write_text(expected)
            else:
                drift.append(file.name)
    if not args.only and set(POLICY['jobs']) != inventory:
        raise ValueError(
            'Inventory contains obsolete workflow jobs: ' + ', '.join(set(POLICY['jobs']) - inventory)
        )
    if drift:
        raise SystemExit('Workflow policy drift: ' + ', '.join(drift) + '. Run render.py --write')
    print(f'{len(inventory)} jobs match the network policy')


if __name__ == '__main__':
    main()
