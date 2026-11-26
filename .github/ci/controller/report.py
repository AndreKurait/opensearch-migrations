"""Read only bounded JUnit data from the worker archive for the GitHub job summary."""
import json
import os
import re
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def public_text(value):
    config = os.environ.get('AWS_CI_CONFIG')
    if config:
        value = value.replace(json.loads(config)['account'], '[account]')
    return re.sub(r'(?<!\d)\d{12}(?!\d)', '[account]', value)


def dependency_report(archive):
    names = archive.namelist()
    if 'dependency-origin.jsonl' not in names:
        return None
    limits = {'dependency-origin.jsonl': 32 * 1024**2, 'dependency-cache-stats.json': 2048}
    for name, limit in limits.items():
        if names.count(name) != 1 or archive.getinfo(name).file_size > limit:
            raise ValueError('Missing, duplicate or oversized dependency report')
    stats = json.loads(archive.read('dependency-cache-stats.json'))
    if set(stats) != {'restored', 'bytes', 'originRequests', 'misses'} or not all(
        type(value) is int and 0 <= value < 2**53 for value in stats.values()
    ):
        raise ValueError('Invalid native cache counters')
    rows = []
    for line in archive.read('dependency-origin.jsonl').splitlines():
        row = json.loads(line)
        if (not isinstance(row, dict) or set(row) != {'key', 'host', 'status'} or
                not isinstance(row['key'], str) or len(row['key']) > 2048 or
                not re.fullmatch(r'/[A-Za-z0-9._+@!:/%-]+', row['key']) or
                not isinstance(row['host'], str) or len(row['host']) > 253 or
                not re.fullmatch(r'[a-z0-9.-]+', row['host']) or
                not (row['status'] == 'error' or type(row['status']) is int and 100 <= row['status'] <= 599)):
            raise ValueError('Invalid native origin request record')
        rows.append(row)
    return stats, rows


def render(path, output_dir=None):
    with ZipFile(path) as archive:
        dependencies = dependency_report(archive)
        if output_dir and dependencies is not None:
            stats, rows = dependencies
            output_dir.mkdir(exist_ok=True)
            (output_dir / 'dependency-origin.jsonl').write_text(''.join(json.dumps(row) + '\n' for row in rows))
            (output_dir / 'dependency-cache-stats.json').write_text(json.dumps(stats) + '\n')
        entries = [entry for entry in archive.infolist() if entry.filename == 'junit.xml']
        if len(entries) != 1 or entries[0].file_size > 8 * 1024**2:
            raise ValueError('Expected one root junit.xml under 8 MiB')
        with archive.open(entries[0]) as report:
            raw = report.read(8 * 1024**2 + 1).decode('utf-8')
    if len(raw) > 8 * 1024**2 or '<!' in raw or '\x00' in raw:
        raise ValueError('Unsupported JUnit document')
    root = ET.fromstring(public_text(raw))
    cases = root.findall('testcase')
    if root.tag != 'testsuite' or not cases:
        raise ValueError('Worker JUnit contains no test cases')
    outcomes = {'passed': 0, 'failed': 0, 'error': 0, 'skipped': 0}
    lines = []
    for case in cases:
        state = next((label for tag, label in [('error', 'error'), ('failure', 'failed'), ('skipped', 'skipped')]
                      if case.find(tag) is not None), 'passed')
        outcomes[state] += 1
        # Only display identifier characters. Failure text and long case names stay in the artifact.
        name = public_text(case.get('name', ''))
        if len(lines) < 100 and re.fullmatch(r'[A-Za-z0-9_. -]{1,128}', name):
            lines.append(f'| `{name}` | {state} |')
    counts = ' · '.join(f'**{count} {label}**' for label, count in outcomes.items())
    if output_dir:
        # Publish only validated fields from the candidate archive.
        summary = dict(suite=public_text(root.get('name', '')), tests=len(cases), failures=outcomes['failed'],
                       errors=outcomes['error'], skipped=outcomes['skipped'])
        output_dir.mkdir(exist_ok=True)
        (output_dir / 'junit.xml').write_text(public_text(ET.tostring(root, encoding='unicode')), encoding='utf-8')
        (output_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    if dependencies is not None:
        stats, rows = dependencies
        counts += (f"\n\nDependency cache restored {stats['restored']} objects ({stats['bytes']} bytes). "
                   f"The proxy recorded {len(rows)} public HTTP request attempts across "
                   f"{stats['originRequests']} upstream artifact resolutions; "
                   f"{stats['misses']} cache keys were missing.")
    return '\n'.join([counts, '', '| Test case | Result |', '| --- | --- |', *lines]), outcomes
