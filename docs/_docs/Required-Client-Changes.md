
The goal of the Migration Assistant is to make it straightforward to migrate from one location or version of Elasticsearch/OpenSearch to another, but to complete a migration, there are sometimes incompatibilities between the clients that need to be resolved before the clients begin communicating directly with the target cluster.

It's helpful to be aware of the necessary changes before embarking upon the migration. The previous page on [[breaking changes between versions|Understanding-breaking-changes]] is a helpful set of considerations.

Any time you apply a transformation to your data (changing index names, field names, field mappings, splitting indices with type mappings, etc.), this change may need to be reflected in your clients. For instance, if they generally 

We recommend running queries against the target cluster with production-like before switching over production traffic to ensure that the client is able to communicate with it, find the necessary indices and fields, and obtain the expected results.

If you have a complicated situation with multiple transformations or breaking changes, we highly recommend performing a migration with representative, non-production data (e.g. a staging environment) and fully ensuring client compatibility with the target cluster.

### Limitations

Client changes are generally beyond the scope of the Migration Assistant because they deal with direct, unmediated communication between clients and the customer's clusters.

There are specific concerns around migrating from post-fork Elasticsearch (7.10.2+) to OpenSearch because some Elasticsearch clients contain checks of the license or version that artificially break compatibility. No post-fork Elasticsearch clients are fully compatible with OpenSearch 2.x, so there are particularly acute challenges here and it may be necessary to switch to an Opensearch 1 client before doing the migration.

### Troubleshooting

The Replayer outputs tuples that give a detailed view into the exact requests being sent to the cluster. Examining these tuples can help identify any transformations between the requests sent to the source and target to ensure these are accounted for in client code.

### Related Links

https://opensearch.org/docs/latest/clients/

### How to Contribute



owner: @MikaylaThompson