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


def render(path, output_dir=None):
    with ZipFile(path) as archive:
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
        # Only these two files go to GitHub. The source archive is PR-controlled.
        summary = dict(suite=public_text(root.get('name', '')), tests=len(cases), failures=outcomes['failed'],
                       errors=outcomes['error'], skipped=outcomes['skipped'])
        output_dir.mkdir()
        (output_dir / 'junit.xml').write_text(public_text(ET.tostring(root, encoding='unicode')), encoding='utf-8')
        (output_dir / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n', encoding='utf-8')
    return '\n'.join([counts, '', '| Test case | Result |', '| --- | --- |', *lines]), outcomes
