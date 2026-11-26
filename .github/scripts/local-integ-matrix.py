"""Select the local integration test jobs for one GitHub Actions run."""

import argparse
import json
import re

# Source maps to its normal target and test IDs. Solr <8 needs filesystem backup and is
# exercised only by the explicit compatibility matrix, as before.
SOURCES = {
    'ES_1.5': ('OS_3.1', '0001,0002,0003'),
    'ES_2.4': ('OS_3.1', '0001,0002,0003'),
    'ES_5.6': ('OS_3.1', '0001,0002,0003,0004,0005'),
    'ES_6.8': ('OS_3.1', '0001,0002,0003,0006,0036'),
    'ES_7.10': ('OS_3.1', '0001,0002,0003,0006,0008,0020,0029'),
    'ES_8.19': ('OS_3.1', '0001,0002,0003,0020,0029,0035,0040'),
    'OS_1.3': ('OS_3.1', '0001,0002,0003'),
    'SOLR_6.6': (None, '0071,0070'),
    'SOLR_7.7': (None, '0071,0070'),
    'SOLR_8.11': ('OS_2.19', '0071,0070'),
    'SOLR_9.8': ('OS_2.19', '0071,0070'),
}
TARGETS = ('OS_1.3', 'OS_2.19', 'OS_3.1')


def select(mode, source, target, test_ids):
    if mode == 'canary':
        # Release jobs include both the compatibility matrix and independent
        # local suites; deduplicate identical source/target/ID selections.
        jobs = select('matrix', 'all', 'all', '')
        return jobs + [job for job in select('standard', 'all', 'all', '') if job not in jobs]

    jobs = []
    for version, (default_target, default_ids) in SOURCES.items():
        if source != 'all' and version != source:
            continue
        if mode == 'matrix':
            # The full historical matrix includes OS_1.3 as a target only;
            # it also has its own independent default source test.
            if source == 'all' and version == 'OS_1.3':
                continue
            targets = TARGETS if target == 'all' else [target]
        else:
            if default_target is None:
                continue
            if mode == 'hourly' and version.startswith('SOLR'):
                continue
            if mode == 'solr' and not version.startswith('SOLR'):
                continue
            targets = [default_target]
        for destination in targets:
            if version != destination:
                jobs.append(dict(source=version, target=destination, ids=test_ids or default_ids))
    if mode == 'standard':
        jobs.append(dict(source='ES_7.10', target='OS_2.19', ids='0011'))
    return jobs


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--mode', choices=['standard', 'hourly', 'solr', 'matrix', 'canary'], default='standard')
    parser.add_argument('--source', choices=['all', *SOURCES], default='all')
    parser.add_argument('--target', choices=['all', *TARGETS], default='all')
    parser.add_argument('--test-ids', default='')
    args = parser.parse_args()
    if args.mode != 'matrix' and (args.source != 'all' or args.target != 'all' or args.test_ids):
        parser.error('select matrix mode to override source, target or IDs')
    if args.test_ids and not re.fullmatch(r'all|\d{4}(,\d{4})*', args.test_ids):
        parser.error('test IDs must be comma-separated four-digit IDs, or all')
    jobs = select(args.mode, args.source, args.target, args.test_ids)
    if not jobs:
        parser.error('the selection contains no distinct source and target pair')
    if args.mode == 'standard':
        # clusterWorkflows.yaml uses one custom source image for each ES stripe.
        # The OpenSearch and modern Solr templates pull published source images.
        custom_images = {
            'ES_1.5': 'customElasticsearch152', 'ES_2.4': 'customElasticsearch246',
            'ES_5.6': 'customElasticsearch5616', 'ES_6.8': 'customElasticsearch6823',
            'ES_7.10': 'customElasticsearch710', 'ES_8.19': 'customElasticsearch81914',
            'OS_1.3': '', 'SOLR_8.11': '', 'SOLR_9.8': '',
        }
        for job in jobs:
            if job['source'] in custom_images:
                image = custom_images[job['source']]
                job['testImageTask'] = f':buildKit_{image}_amd64' if image else 'none'
    print(json.dumps({'include': jobs}, separators=(',', ':')))
