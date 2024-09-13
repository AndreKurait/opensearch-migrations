## Moving existing data with Reindex-from-Snapshot

The Migration Assistant provides a tool called Reindex-from-Snapshot (RFS) to move the existing documents in your Source Cluster to the Target Cluster.  The premise of RFS is that we can take a Snapshot of a Source Cluster then have a separate process (or processes) parse the Snapshot to extract the documents and reindex them on the Target Cluster without having to involve the Source Cluster again after the snapshot is finished.  The format of Elasticsearch/OpenSearch indices is such that each shard of each index can be parsed, extracted, and  independently, meaning the work can be fanned out at the shard level.  

This approach improves the migration experience by:
* Removing load from the source cluster during backfill migration after the Source Cluster Snapshot is taken
* Enabling “hopping” across multiple major versions without having to pass through the intermediate versions
* Creating a migration path from post-fork versions of Elasticsearch to OpenSearch
* Increasing the speed of backfill migration by parallelizing work at the shard level
* Making pausing/resuming a migration trivial

You can gain more context on the problem it solves by looking at [this RFC](https://github.com/opensearch-project/OpenSearch/issues/12667) and [this design doc](https://github.com/opensearch-project/opensearch-migrations/blob/main/RFS/docs/DESIGN.md).

## Caveats and Limitations

RFS is under active development as its capabilities are expanded.  With that in mind, here are some current known limitations:
* **Source Version:** RFS supports a subset of the available Elasticsearch and OpenSearch clusters.
* **Target Version:** RFS supports a subset of the available OpenSearch versions.
* **Authentication:** RFS supports several authentication options for the cluster cluster including no-auth, basic auth, and sigv4.

_See the [[Where to begin]] page which covers more specific details._

## How to use RFS

All the following operations should be performed on the Migration Console using the Console CLI.  You can learn more about the individual commands and additional options they have with the `--help` flag.  You can also look at [the documentation for the Console CLI here](https://github.com/opensearch-project/opensearch-migrations/blob/main/TrafficCapture/dockerSolution/src/main/docker/migrationConsole/lib/console_link/README.md) for additional details about the operations.

### Check the starting state of the clusters

You can see the indices that are currently on the source and target cluster by running the command:

```shell
console clusters cat-indices
```

### (Conditional) Metadata Migration

Depending on your use-case, you may want to migrate the Source Cluster's templates, indices, and other settings before moving the documents themselves.  Some of advantages of doing so include ensuring documents are ingested correctly and ensuring the correct number of shards on your indices.  Please refer to [[the guide on Metadata Migration|Metadata-Migration]] for more details.

If you don't want to perform a Metadata Migration using the Migration Assistant, you will want to ensure the indices on the Target Cluster have an appropriate number of shards for the data that RFS will transfer to them; see [the official Amazon OpenSearch Service documentation here](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/sizing-domains.html#bp-sharding).  You can ensure your indices will have the correct number of shards on the Target Cluster by manually creating indices of the same names as those you will be be migrating the documents from on the Source Cluster.  For example, if you have have an index `logs-2024-08-06-01` on the Source Cluster with 20 shards, you will want to use the Target Cluster's REST API to create an identically-named index (`logs-2024-08-06-01`) and 20 shards.  RFS will find these indices on the Target Cluster and migrate the correct documents to them.

If you do not pre-create the indices on the Target Cluster, either manually or via a Metadata Migration, RFS will create new indices on the Target Cluster by using the implicit, default settings per the Target Cluster's version.  This will almost certainly mean the wrong number of shards for your indices.

### Document Migration

This step kicks off a fleet of Amazon ECS workers which are responsible for transferring the documents from the Source Snapshot to the Target Cluster.  This fleet of workers can be scaled up and down using the Console CLI to increase the rate at which documents are transferred to the Target Cluster, at the expense of increased load on the target cluster.  The workers coordinate using metadata stored in a special index on the Target Cluster, and each worker is responsible to moving one shard from the Source Cluster's Snapshot at a time.  The work they perform is independent, so they can hypothetically be scaled up to match the number of shards present in the snapshot - if the target cluster can handle that volume of ingest traffic.

We can start the process by running the following command, which will scale up the worker fleet from 0 -> 1:

```shell
console backfill start
```

Because this command spins up an ECS Container, it may take a couple minutes before work starts happening.  After beginning the document migration, you can check progress in a couple ways.  First, you can see the states indices on the target cluster (how many docs they have, how much data) using the command:

```shell
console clusters cat-indices
```

To speed up the transfer, you can scale the number of workers with its own command.  As before, it may take a few minutes for these additional workers to come online.  This will update the worker fleet to a size of ten:

```shell
console backfill scale 10
```

You can use the status check command to see more detail about things like the number of shards completed, in progress, remaining, and the overall status of the operation:

```shell
console backfill status --deep-check

BackfillStatus.RUNNING
Running=10
Pending=0
Desired=10
Shards total: 48
Shards completed: 16
Shards incomplete: 32
Shards in progress: 0
Shards unclaimed: 32
```

When the operation is finished, the `console backfill status --deep-check` command will report that all shards are completed:

```shell
console backfill status --deep-check

BackfillStatus.RUNNING
Running=2
Pending=8
Desired=10
Shards total: 48
Shards completed: 48
Shards incomplete: 0
Shards in progress: 0
Shards unclaimed: 0
```

Note that the status will still be "RUNNING".  This is an indication that the worker fleet is still active, even though all the shards in the snapshot have been migrated.  You can spin down all the workers with the command:

```shell
console backfill stop
```

You can also scale the cluster down just like you scaled it up:

```shell
console backfill scale 0
```

### Properly scaling the RFS worker fleet

There are several considerations when picking a number of RFS workers to run.  RFS is theoretically capable of migrating every shard, simultaneously, in parallel.  This is due to the work for each shard being independent.  In practice, however, the ability to parallelize the work of migrating documents will be limited by the ability of the Target Cluster to handle the ingest traffic from the RFS workers.

It is recommended to scale up the RFS worker fleet slowly while monitoring the health metrics of the Target Cluster to avoid over-saturating it.  Amazon OpenSearch Domains provide a number of metrics and logs that can provide this insight; refer to [the official documentation on the subject](https://docs.aws.amazon.com/opensearch-service/latest/developerguide/monitoring.html).  The AWS Console for Amazon Opensearch Service surfaces details that can be useful for this as well.

### Verifying the document migration

Before examining the contents of the Target Cluster, it is recommended to run a `_refresh` ([docs here](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/indices-refresh.html)) and `_flush` ([docs here](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/indices-flush.html)) on the Target.  This will help ensure that the contents of the Cluster will be accurate portrayed.

You can check the contents of the Target Cluster after the migration using the Console CLI:

```
console clusters cat-indices
```

This will display the number of documents on each of the indices on the Target Cluster.  It is further recommended to run some queries against the Target Cluster that mimic your production workflow and closely examine the results returned.
