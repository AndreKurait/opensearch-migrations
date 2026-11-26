"""Route the pinned test-mode corpus descriptors to the runner's bridge-local broker."""

from pathlib import Path
import sys


def main(workloads, broker):
    upstream = 'https://dbyiw3u3rf9yr.cloudfront.net/corpora/'
    for workload in ('geonames', 'http_logs', 'nested', 'nyc_taxis'):
        descriptor = Path(workloads, workload, 'workload.json')
        source = descriptor.read_text()
        if upstream not in source:
            raise ValueError(f'No pinned corpus origin in {descriptor}')
        descriptor.write_text(source.replace(upstream, f'{broker}/benchmark/corpora/'))


if __name__ == '__main__':
    main(*sys.argv[1:])
