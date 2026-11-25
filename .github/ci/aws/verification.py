"""Assertions exercised against the running AWS deployment."""

import json
import re
import subprocess
import time
import xml.etree.ElementTree as ET


def dashboards(worker, delete=False):
    names = [
        f'MA-{worker.stage}-{worker.region}-{suffix}' for suffix in ('CaptureReplay', 'ReindexFromSnapshot')
    ]
    resources = [f'dashboards.cloudwatch.services.k8s.aws/{name.lower()}' for name in names]
    if delete:
        worker.kube('delete', *resources, '--wait=false')
        worker.kube('wait', '--for=delete', *resources, '--timeout=180s')
    else:
        worker.kube('wait', '--for=condition=ACK.ResourceSynced=True', *resources, '--timeout=180s')
    for name in names:
        args = ['cloudwatch', 'get-dashboard', '--dashboard-name', name]
        if not delete:
            widgets = json.loads(worker.aws(*args)['DashboardBody'])['widgets']
            if not any(w['type'] == 'metric' and w['properties'].get('metrics') for w in widgets):
                raise RuntimeError(f'CloudWatch dashboard {name} has no metric data')
            continue
        for _ in range(24):
            try:
                worker.aws(*args)
            except subprocess.CalledProcessError as error:
                if 'ResourceNotFound' in (error.stderr or ''):
                    break
                raise
            time.sleep(5)
        else:
            raise RuntimeError(f'CloudWatch dashboard {name} still exists')


def nodepools(worker, expected):
    # The chart defaults distinguish fields inherited by dedicated pools from their explicit overrides.
    # Pipenv already installs PyYAML through the Kubernetes client. Read using that interpreter.
    chart = worker.root / 'deployment/k8s/charts/aggregates/migrationAssistantWithArgo/values.yaml'
    script = 'import json,sys,yaml; print(json.dumps(yaml.safe_load(open(sys.argv[1]))["workloadsNodePool"]))'
    defaults = json.loads(worker.pipenv('run', 'python', '-c', script, str(chart), capture=True))
    pools = [(defaults.get('name', 'general-work-pool'), {})]
    if defaults.get('mode', 'isolated') == 'isolated':
        pools.extend(
            (pool['name'], pool)
            for pool in (defaults['dedicatedPools'][k] for k in ['kafka', 'proxy', 'replayer'])
        )
    labels = {
        'architectures': 'kubernetes.io/arch',
        'capacityTypes': 'karpenter.sh/capacity-type',
        'instanceCategories': 'eks.amazonaws.com/instance-category',
        'instanceSizes': 'eks.amazonaws.com/instance-size',
    }
    total = 0
    for name, overrides in pools:
        spec = json.loads(worker.kube('get', 'nodepool', name, '-o', 'json', capture=True))['spec']
        requirements = {r['key']: r for r in spec['template']['spec']['requirements']}
        nodes = json.loads(
            worker.kube('get', 'nodes', '-l', f'karpenter.sh/nodepool={name}', '-o', 'json', capture=True)
        )['items']
        print(f'{name}: {[n["metadata"]["name"] for n in nodes]}', flush=True)
        total += len(nodes)
        for field, label in labels.items():
            if field in overrides:
                continue
            wanted = set(expected[field])
            req = requirements[label]
            if req['operator'] != 'In' or set(req['values']) != wanted:
                raise RuntimeError(f'{name}: unexpected {label} requirement {req}')
            for node in nodes:
                if node['metadata']['labels'].get(label) not in wanted:
                    raise RuntimeError(f'{node["metadata"]["name"]}: {label} outside {wanted}')
        if 'minInstanceGeneration' not in overrides:
            minimum = expected['minInstanceGeneration']
            label = 'eks.amazonaws.com/instance-generation'
            req = requirements[label]
            if req['operator'] != 'Gte' or req['values'] != [str(minimum)]:
                raise RuntimeError(f'{name}: unexpected generation requirement {req}')
            if any(int(n['metadata']['labels'].get(label, 0)) < minimum for n in nodes):
                raise RuntimeError(f'{name}: node below instance generation {minimum}')
        for field in ['disruption'] + ([] if overrides else ['limits']):
            if field not in overrides and any(
                str(spec[field].get(k)) != str(v) for k, v in expected[field].items()
            ):
                raise RuntimeError(f'{name}: unexpected {field} {spec[field]}')
    if not total:
        raise RuntimeError('No workload nodes launched to exercise the selected instance constraints')


def public_report_text(value):
    return re.sub(r'(?<!\d)\d{12}(?!\d)', '[account]', str(value))


def junit(directory, suite, errors, expected_tests=None, cleanup=False):
    root = ET.Element('testsuite', name=suite)
    completed_ids = set()
    for report in sorted(directory.glob('test-report-*.json')):
        try:
            data = json.loads(report.read_text())
            for entry in data['tests']:
                case = ET.SubElement(
                    root, 'testcase', name=public_report_text(entry['name']), classname=suite,
                    time=str(float(entry['duration']))
                )
                if entry['result'] == 'skipped':
                    ET.SubElement(case, 'skipped')
                elif entry['result'] != 'passed':
                    ET.SubElement(case, 'failure').text = public_report_text(entry.get('error') or entry['result'])
                else:
                    match = re.match(r'Test(\d{4})', entry['name'])
                    if match:
                        completed_ids.add(match[1])
        except (OSError, ValueError, KeyError, TypeError) as error:
            errors.append((report.name, f'Invalid integration test report: {error}'))
    if expected_tests is not None and not cleanup:
        expected = set(expected_tests.split(',')) if expected_tests not in ('', 'all') else set()
        if expected - completed_ids:
            errors.append(
                (
                    'selection',
                    f'Selected test IDs did not pass: {", ".join(sorted(expected - completed_ids))}',
                )
            )
        elif not completed_ids:
            errors.append(('selection', 'No migration tests passed in the selected suite'))
    for step, message in errors:
        case = ET.SubElement(root, 'testcase', name=public_report_text(step), classname=suite)
        ET.SubElement(case, 'error').text = public_report_text(message)
    if not len(root):
        ET.SubElement(
            root, 'testcase', name='cleanup' if cleanup else 'deployment-validation', classname=suite
        )
    root.set('tests', str(len(root)))
    for name, tag in [('failures', 'failure'), ('errors', 'error'), ('skipped', 'skipped')]:
        root.set(name, str(len(root.findall(f'testcase/{tag}'))))
    ET.ElementTree(root).write(directory / 'junit.xml', encoding='utf-8', xml_declaration=True)
    (directory / 'summary.json').write_text(json.dumps(dict(suite=suite, **root.attrib), indent=2) + '\n')
    return any(root.find(f'testcase/{tag}') is not None for tag in ('failure', 'error'))
