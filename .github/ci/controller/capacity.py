"""Assign each AWS suite a shared GitHub queue using the Oregon sandbox's available capacity."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
import hashlib
import json
import os
import re
import subprocess
import time

REGION = 'us-west-2'
PROJECT = 'migrations-github-eks'
REPO = 'AndreKurait/opensearch-migrations'
ENVIRONMENT = 'aws-ci'
QUOTAS = {
    'builds': ('codebuild', 'L-4DDC4A99'), 'eips': ('ec2', 'L-0263D0A3'), 'vpcs': ('vpc', 'L-F678F1CE'),
    'igws': ('vpc', 'L-A4707A72'), 'egws': ('vpc', 'L-45FE3B85'), 'nat_per_az': ('vpc', 'L-FE5A380F'),
    'enis': ('vpc', 'L-DF5E4CA3'), 'vcpus': ('ec2', 'L-1216C47A'),
}
# Every source suite runs native amd64 and arm64 BuildKit pods requesting 4 CPUs each. A four-vCPU
# host cannot fit one after kubelet reservations. Allow for two eight-vCPU hosts, system nodes and
# the console; CDC's independent workload pools and the overlapping isolated clusters need more.
# Use the same conservative reservation for release canaries and previously approved candidate code.
RESERVATIONS = {
    suite: dict(zip(QUOTAS, values)) for suite, values in {
        'isolated': (1, 4, 2, 2, 1, 2, 128, 32), 'cfn-import': (1, 1, 1, 1, 0, 1, 64, 24),
        'cdc-k6': (1, 3, 1, 1, 1, 1, 128, 32), 'cdc-aoss': (1, 3, 1, 1, 1, 1, 96, 32),
        'cdc': (1, 3, 1, 1, 1, 1, 96, 32), 'eks': (1, 3, 1, 1, 1, 1, 64, 24),
        'cfn-create': (1, 3, 1, 1, 1, 1, 64, 24),
    }.items()
}


def cli(*args, payload=None):
    result = subprocess.run(args, text=True, input=payload, capture_output=True)
    if result.returncode:
        detail = re.sub(r'[0-9]{12}', '[account]', result.stderr.strip())
        raise RuntimeError(f'{args[0]} failed: {detail}')
    return result.stdout


def client(profile=None):
    prefix = ['aws', '--region', REGION, '--no-cli-pager', '--output', 'json']
    if profile:
        prefix += ['--profile', profile]

    def call(*args):
        return json.loads(cli(*prefix, *args))
    return call


def parallel(tasks):
    with ThreadPoolExecutor(max_workers=8) as pool:
        pending = {name: pool.submit(f) for name, f in tasks.items()}
        return {name: result.result() for name, result in pending.items()}


def quotas(call):
    def read(service, code):
        return int(call('service-quotas', 'get-service-quota', '--service-code', service,
                        '--quota-code', code)['Quota']['Value'])
    tasks = {k: (lambda s=s, c=c: read(s, c)) for k, (s, c) in QUOTAS.items()}
    limits = parallel(dict(tasks, project=lambda: call('codebuild', 'batch-get-projects', '--names', PROJECT)))
    project = limits.pop('project')['projects'][0]
    # Both the regional size quota and the project's own running limit apply to cleanup workers.
    ceiling = project.get('concurrentBuildLimit') or limits['builds']
    limits['builds'] = max(0, min(limits['builds'], ceiling) - 1)
    return limits


def active_builds(call):
    # Only this project's runs are eligible for stage-based reservations. AWS enforces the regional build ceiling.
    # Start from the most recent builds, including pagination when earlier active projects are still running.
    active = []
    token = []
    while True:
        page = call('codebuild', 'list-builds-for-project', '--project-name', PROJECT, '--no-paginate', *token)
        if not page['ids']:
            break
        rows = call('codebuild', 'batch-get-builds', '--ids', *page['ids'])['builds']
        active.extend(b for b in rows if b['buildStatus'] == 'IN_PROGRESS')
        if not page.get('nextToken') or (rows and all(time.time() - time_from_build(b) > 48 * 3600 for b in rows)):
            break
        token = ['--next-token', page['nextToken']]
    return active


def time_from_build(build):
    return datetime.fromisoformat(build['startTime']).timestamp()


def usage(call, ignore_active=False):
    queries = {'vpcs': ('describe-vpcs', 'Vpcs'), 'igws': ('describe-internet-gateways', 'InternetGateways'),
               'egws': ('describe-egress-only-internet-gateways', 'EgressOnlyInternetGateways'),
               'eips': ('describe-addresses', 'Addresses'),
               'enis': ('describe-network-interfaces', 'NetworkInterfaces'),
               'nats': ('describe-nat-gateways', 'NatGateways'), 'subnets': ('describe-subnets', 'Subnets'),
               'instances': ('describe-instances', 'Reservations')}
    tasks = {key: (lambda op=op, prop=prop: call('ec2', op)[prop]) for key, (op, prop) in queries.items()}
    rows = parallel(dict(tasks, builds=lambda: active_builds(call)))
    # Resources owned by running stages are covered by their full reservation until teardown finishes.
    stages = {}
    for b in rows['builds']:
        env = {e['name']: e['value'] for e in b['environment']['environmentVariables']}
        suite, stage = env.get('CI_SUITE'), env.get('CI_STAGE')
        if suite in RESERVATIONS and stage:
            stages[stage] = suite
    owner = dict((s, s) for s in stages)
    for stage, suite in stages.items():
        if suite == 'isolated':
            owner['ci-' + hashlib.sha256((stage + '-build').encode()).hexdigest()[:12]] = stage

    def tags(row):
        return {t['Key']: t['Value'] for t in row.get('Tags', row.get('TagSet', []))}

    def direct(row):
        t = tags(row)
        if t.get('MigrationCI') == 'github':
            stack = re.match(r'^(ci-[a-f0-9]{12})-', t.get('aws:cloudformation:stack-name', ''))
            return owner.get(t.get('MATestStage')) or (owner.get(stack[1]) if stack else None)
        return None

    vpcs = {v['VpcId']: direct(v) for v in rows['vpcs']}
    by_stage = {s: Counter() for s in stages}
    other = Counter()

    def charge(row, key, count=1, parent=None):
        stage = direct(row) or parent or vpcs.get(row.get('VpcId'))
        target = by_stage[stage] if stage in by_stage else other
        target[key] += count

    for key in ('vpcs', 'igws', 'egws'):
        for row in rows[key]:
            attachments = row.get('Attachments', [])
            parent = vpcs.get(attachments[0].get('VpcId')) if attachments else None
            charge(row, key, parent=parent)
    for eni in rows['enis']:
        charge(eni, 'eni:' + eni['AvailabilityZone'])
    zones = {r['SubnetId']: r['AvailabilityZone'] for r in rows['subnets']}
    nat_eips = {}
    for nat in rows['nats']:
        if nat['State'] in ('pending', 'available', 'deleting'):
            charge(nat, 'nat:' + zones[nat['SubnetId']])
            nat_eips.update({a.get('AllocationId'): vpcs.get(nat['VpcId']) for a in nat['NatGatewayAddresses']})
    for row in rows['eips']:
        charge(row, 'eips', parent=nat_eips.get(row.get('AllocationId')))
    for res in rows['instances']:
        for i in res['Instances']:
            family = i['InstanceType'].split('.')[0]
            standard = family.startswith(('a', 'c', 'd', 'h', 'i', 'm', 'r', 't', 'z'))
            if standard and i['State']['Name'] in ('running', 'pending') and i.get('InstanceLifecycle') != 'spot':
                # Standard On-Demand quota uses default instance vCPUs, including when CPU options reduce visible cores.
                charge(i, 'type:' + i['InstanceType'])
    instance_types = sorted({k[5:] for c in [other, *by_stage.values()] for k in c if k.startswith('type:')})
    sizes = {}
    for start in range(0, len(instance_types), 100):
        page = call('ec2', 'describe-instance-types', '--instance-types', *instance_types[start:start + 100])
        sizes.update({i['InstanceType']: i['VCpuInfo']['DefaultVCpus'] for i in page['InstanceTypes']})
    for counter in [other, *by_stage.values()]:
        counter['vcpus'] = sum(counter['type:' + name] * size for name, size in sizes.items())
        counter['nat_per_az'] = max((n for k, n in counter.items() if k.startswith('nat:')), default=0)
        counter['enis'] = max((n for k, n in counter.items() if k.startswith('eni:')), default=0)
    other['builds'] = 0 if ignore_active else len(rows['builds'])
    if not ignore_active:
        for stage, suite in stages.items():
            for key in QUOTAS:
                if key != 'builds':
                    other[key] += max(RESERVATIONS[suite][key], by_stage[stage][key])
    return {k: other[k] for k in QUOTAS}


def lanes(room):
    # All suites in a lane serialize. Different lanes can run together across all PRs and schedules.
    # Sum of the per-resource maxima in every lane must fit for *any* selection, including overlapping runs.
    buckets = []
    assignment = {}
    for suite, need in RESERVATIONS.items():
        if any(need[k] > room[k] for k in QUOTAS):
            continue
        for idx in [len(buckets), *range(len(buckets))]:
            candidate = dict(buckets[idx]) if idx < len(buckets) else dict.fromkeys(QUOTAS, 0)
            peak = {k: max(candidate[k], need[k]) for k in QUOTAS}
            if all(peak[k] + sum(b[k] for j, b in enumerate(buckets) if j != idx) <= room[k] for k in QUOTAS):
                if idx == len(buckets):
                    buckets.append(peak)
                else:
                    buckets[idx] = peak
                assignment[suite] = idx + 1
                break
    return assignment


def config():
    cfg = json.loads(os.environ['AWS_CI_CONFIG'])
    if not re.fullmatch(r'[0-9]{12}', cfg['account']) or cfg['region'] != REGION:
        raise ValueError('Invalid sandbox account configuration')
    if set(cfg['room']) != set(QUOTAS) or any(type(v) is not int or v < 0 for v in cfg['room'].values()):
        raise ValueError('Invalid sandbox capacity configuration')
    return cfg


def admit(suite, call, cfg, wait_seconds=4 * 3600):
    deadline = time.monotonic() + wait_seconds
    limits = quotas(call)
    while True:
        used = usage(call)
        short = [k for k, n in RESERVATIONS[suite].items() if n + used[k] > limits[k] or n > cfg['room'][k]]
        if not short:
            return
        print('Waiting for capacity in ' + ', '.join(short), flush=True)
        if time.monotonic() >= deadline:
            raise RuntimeError('Oregon capacity is still occupied; rerun this suite when resources are available')
        time.sleep(60)


def publish(cfg):
    base = f'repos/{REPO}/environments/{ENVIRONMENT}'
    # The environment is private to main even for same-repository PRs. Configure restrictions before writing a secret.
    existing = subprocess.run(['gh', 'api', base], capture_output=True, text=True)
    if existing.returncode == 0:
        policy = json.loads(existing.stdout)['deployment_branch_policy']
        if not policy or not policy['custom_branch_policies']:
            raise ValueError('Existing aws-ci environment needs custom deployment branches before publishing')
    elif '404' in existing.stderr:
        cli('gh', 'api', '-X', 'PUT', base, '--input', '-', payload=json.dumps({
            'deployment_branch_policy': {'protected_branches': False, 'custom_branch_policies': True}}))
    else:
        raise RuntimeError('Could not inspect GitHub environment permissions')
    policies = json.loads(cli('gh', 'api', base + '/deployment-branch-policies?per_page=100'))
    allowed = [(r['name'], r['type']) for r in policies['branch_policies']]
    if allowed not in ([], [('main', 'branch')]) or policies['total_count'] > 1:
        raise ValueError('aws-ci must allow only the main branch')
    if not policies['branch_policies']:
        cli('gh', 'api', '-X', 'POST', base + '/deployment-branch-policies', '-f', 'name=main', '-f', 'type=branch')
    cli('gh', 'secret', 'set', 'AWS_CI_CONFIG', '--repo', REPO, '--env', ENVIRONMENT,
        payload=json.dumps(cfg, separators=(',', ':')))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--profile', help='Operator AWS profile')
    parser.add_argument('--publish', action='store_true', help='Set AWS_CI_CONFIG on the main-only environment')
    opts = parser.parse_args()
    aws = client(opts.profile)
    measured = parallel({'limits': lambda: quotas(aws), 'used': lambda: usage(aws, ignore_active=True)})
    room = {k: max(0, measured['limits'][k] - measured['used'][k]) for k in QUOTAS}
    account = aws('sts', 'get-caller-identity')['Account']
    cfg = dict(account=account, region=REGION, room=room)
    print('Oregon capacity after background usage:', json.dumps(room, sort_keys=True))
    print('Shared GitHub queues:', json.dumps(lanes(room), sort_keys=True))
    if opts.publish:
        publish(cfg)
        print('Set AWS_CI_CONFIG in the main-only aws-ci environment.')
