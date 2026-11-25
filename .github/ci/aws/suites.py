"""Public-cloud integration suite definitions used by the disposable test worker."""

from dataclasses import dataclass
import os
import re
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Suite:
    source: str = 'ES_7.10'
    target: str = 'OS_1.3'
    tests: str = ''
    timeout_minutes: int = 180


SUITES = {
    'eks': Suite(tests='0001,0002'),
    'cdc': Suite(tests='0031,0032,0033,0040,0042', timeout_minutes=300),
    'cdc-k6': Suite(tests='0080', timeout_minutes=300),
    'cdc-aoss': Suite(target='AOSS', tests='0034,0041', timeout_minutes=240),
    'aoss': Suite(source='OS_1.3', target='AOSS', tests='0021,0024'),
    'byos': Suite(target='OS_2.19', tests='0010', timeout_minutes=1080),
    'isolated': Suite(target='OS_2.19', tests='0040', timeout_minutes=240),
    'cfn-create': Suite(),
    'cfn-import': Suite(),
}
ALIASES = {'aoss-timeseries': 'aoss', 'aoss-vector': 'aoss'}
DISABLED_SUITES = frozenset({'byos', 'aoss'})
SNAPSHOT_BUCKET = 's3://migrations-snapshots-library-us-east-1'
AOSS_SNAPSHOT_PREFIX = 'aoss-osb-data/os1x-aoss-osb-data'
PRESETS = {
    'small-es7x-osb': ('ma_osb_data/es7x-osb-data', 'es7x-osb-data', 'ES_7.10', '1', 'default'),
    'small-es5x-300k': ('another_set/large-snapshot-es5x', 'large-snapshot', 'ES_5.6', '1', 'default'),
    'large-es5x': ('large-snapshot-es5x', 'large-snapshot', 'ES_5.6', '90', 'large'),
    'large-es6x-20B': ('large-snapshot-es6x', 'large-snapshot', 'ES_6.8', '90', 'large'),
    'large-es7x-24B': ('large-snapshot-es7x', 'large-snapshot', 'ES_7.10', '90', 'large'),
}


def fixture_uri(bucket, prefix, override=None):
    """Map original snapshot URIs onto the trusted project's byte-for-byte replica."""
    if override:
        parts = urlsplit(override)
        if parts.scheme != 's3' or parts.netloc not in (urlsplit(SNAPSHOT_BUCKET).netloc, bucket):
            raise ValueError('Choose a snapshot in the configured fixture library')
        prefix = parts.path.strip('/')
        if parts.query or parts.fragment or not all(
            re.fullmatch(r'[A-Za-z0-9_-][A-Za-z0-9._-]*', part) for part in prefix.split('/')
        ):
            raise ValueError('Invalid snapshot repository key')
    return f's3://{bucket}/{prefix}/'


def byos_snapshot(bucket):
    preset = os.environ.get('TEST_PRESET', 'custom')
    path, snapshot, version, workers, size = PRESETS['small-es7x-osb' if preset == 'custom' else preset]
    config = dict(
        SNAPSHOT_NAME=snapshot,
        SOURCE_VERSION=version,
        RFS_WORKERS=workers,
        TARGET_CLUSTER_SIZE=size,
    )
    if preset == 'custom':
        config.update({key: os.environ[key] for key in config if key in os.environ})
    config['S3_REPO_URI'] = fixture_uri(bucket, path, os.environ.get('S3_REPO_URI') if preset == 'custom' else None)
    return config


def nodepool(arm):
    return dict(
        architectures=['arm64' if arm else 'amd64'],
        capacityTypes=['on-demand'],
        instanceCategories=['c', 'm', 'r'] if arm else ['m', 'r'],
        minInstanceGeneration=6,
        instanceSizes=['large', 'xlarge', '2xlarge', '4xlarge'] + (['8xlarge'] if arm else []),
        limits={'cpu': '80000m' if arm else '96000m', 'memory': '160Gi' if arm else '192Gi'},
        disruption={'consolidationPolicy': 'WhenEmpty', 'consolidateAfter': '45m' if arm else '20m'},
    )
