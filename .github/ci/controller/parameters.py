"""Translate the typed per-suite controls into the worker's existing environment variables."""
import json
import re
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'aws'))
from suites import ALIASES, PRESETS, SNAPSHOT_BUCKET, SUITES

SOURCE_VERSIONS = ('ES_7.10', 'ES_1.5', 'ES_2.4', 'ES_5.6', 'ES_6.8', 'ES_8.19', 'OS_1.3', 'OS_2.19')


def parameters(suites, raw):
    if len(raw) > 2048:
        raise ValueError('Suite parameters exceed 2048 characters')
    supplied = json.loads(raw or '{}')
    if not isinstance(supplied, dict):
        raise ValueError('Suite parameters must be a JSON object')
    if not supplied:
        return {}
    if len(suites) != 1:
        raise ValueError('Select a single suite when supplying parameters')
    suite = ALIASES.get(suites[0], suites[0])
    config = SUITES[suite]
    patterns = {}
    choices = {'CI_DEPENDENCY_MODE': ('replay',)}
    if config.tests:
        patterns['TEST_IDS'] = r'(?:[0-9]{4}(?:,[0-9]{4})*|all)'
        choices['SOURCE_VERSION'] = SOURCE_VERSIONS if suite == 'byos' else (config.source,)
        if config.target != 'AOSS':
            choices['TARGET_VERSION'] = ('OS_2.19', 'OS_3.1') if suite == 'byos' else ('OS_1.3', 'OS_2.19', 'OS_3.1')
    if config.snapshot_prefixes:
        choices['S3_REPO_URI'] = {f'{SNAPSHOT_BUCKET}/{prefix}{end}'
                                  for prefix in config.snapshot_prefixes for end in ('', '/')}
        patterns.update(SNAPSHOT_NAME=r'[A-Za-z0-9_-][A-Za-z0-9._-]{0,254}', RFS_WORKERS=r'[1-9][0-9]*',
                        MONITOR_RETRY_LIMIT=r'[1-9][0-9]*')
    if suite == 'aoss':
        patterns['AOSS_TEST_IDS'] = patterns['TEST_IDS'] = r'(?:0021|0024)(?:,(?:0021|0024))*'
        if 'AOSS_TEST_IDS' in supplied and 'TEST_IDS' in supplied:
            raise ValueError('Pass either TEST_IDS or AOSS_TEST_IDS')
    if suite == 'byos':
        choices.update(TEST_PRESET=('custom', *PRESETS), TARGET_CLUSTER_SIZE=('default', 'large'))
        if supplied.get('TEST_PRESET', 'custom') != 'custom' and set(supplied) & {
            'RFS_WORKERS', 'S3_REPO_URI', 'SNAPSHOT_NAME', 'SOURCE_VERSION', 'TARGET_CLUSTER_SIZE',
        }:
            raise ValueError('The chosen snapshot preset already defines those snapshot parameters')
    if suite in ('cdc', 'cdc-k6'):
        patterns['SPEEDUP_FACTOR'] = r'[1-9][0-9]*(?:\.[0-9]+)?'
    result = {}
    for key, value in supplied.items():
        if type(value) not in (str, int):
            raise ValueError(f'{key} must be a string or integer')
        value = str(value)
        if key in choices and value in choices[key] or key in patterns and re.fullmatch(patterns[key], value):
            result[key] = value
        else:
            raise ValueError(f'Unknown or invalid parameter for {suite}: {key}')
    return result
