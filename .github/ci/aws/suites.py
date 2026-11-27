"""Public-cloud integration suite definitions used by the disposable test worker."""

from __future__ import annotations

from dataclasses import dataclass
import os
import re
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Reservation:
    # One create-VPC solution stack uses three NATs/EIPs in separate AZs. Two
    # four-CPU native BuildKit pods need eight-vCPU hosts after kubelet reserves.
    builds: int = 1
    vpcs: int = 1
    igws: int = 1
    egws: int = 1
    eips: int = 3
    nat_per_az: int = 1
    enis: int = 64  # peak per availability zone
    vcpus: int = 24


@dataclass(frozen=True)
class Suite:
    source: str = 'ES_7.10'
    target: str = 'OS_1.3'
    tests: str = ''
    timeout_minutes: int = 180
    reservation: Reservation | None = Reservation()
    snapshot_prefixes: tuple[str, ...] = ()


SNAPSHOT_BUCKET = 's3://migrations-snapshots-library-us-east-1'
AOSS_SNAPSHOT_PREFIX = 'aoss-osb-data/os1x-aoss-osb-data'
PRESETS = {
    'small-es7x-osb': ('ma_osb_data/es7x-osb-data', 'es7x-osb-data', 'ES_7.10', '1', 'default'),
    'small-es5x-300k': ('another_set/large-snapshot-es5x', 'large-snapshot', 'ES_5.6', '1', 'default'),
    'large-es5x': ('large-snapshot-es5x', 'large-snapshot', 'ES_5.6', '90', 'large'),
    'large-es6x-20B': ('large-snapshot-es6x', 'large-snapshot', 'ES_6.8', '90', 'large'),
    'large-es7x-24B': ('large-snapshot-es7x', 'large-snapshot', 'ES_7.10', '90', 'large'),
}

SUITES = {
    'eks': Suite(tests='0001,0002'),
    'cdc': Suite(tests='0031,0032,0033,0040,0042', timeout_minutes=300, reservation=Reservation(enis=96, vcpus=32)),
    'cdc-k6': Suite(tests='0080', timeout_minutes=300, reservation=Reservation(enis=128, vcpus=32)),
    'cdc-aoss': Suite(target='AOSS', tests='0034,0041', timeout_minutes=240,
                      reservation=Reservation(enis=96, vcpus=32)),
    'aoss': Suite(source='OS_1.3', target='AOSS', tests='0021,0024', reservation=Reservation(enis=96, vcpus=32),
                  snapshot_prefixes=(AOSS_SNAPSHOT_PREFIX,)),
    'byos': Suite(target='OS_2.19', tests='0010', timeout_minutes=1080, reservation=Reservation(enis=96, vcpus=32),
                  snapshot_prefixes=tuple(preset[0] for preset in PRESETS.values())),
    'isolated': Suite(target='OS_2.19', tests='0040', timeout_minutes=240,
                      reservation=Reservation(vpcs=2, igws=2, eips=4, nat_per_az=2, enis=128, vcpus=32)),
    'cfn-create': Suite(),
    'cfn-import': Suite(reservation=Reservation(eips=1, egws=0)),
}


def cleanup_minutes(suite):
    return 60 if suite in ('cfn-create', 'cfn-import', 'aoss') else 75


def worker_timeout_minutes(suite):
    # The integration deadline excludes teardown. Reserve time for CodeBuild image
    # startup, post-build receipt collection and native artifact upload as well.
    return SUITES[suite].timeout_minutes + cleanup_minutes(suite) + 30


ALIASES = {'aoss-timeseries': 'aoss', 'aoss-vector': 'aoss'}
# Remove these skips when the original Jenkins fixtures are restored in the CI replica.
DISABLED_SUITES = frozenset({'aoss', 'byos'})


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
