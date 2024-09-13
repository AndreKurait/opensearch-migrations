## Snapshot Creation

### Limitations
Using incremental or a series of 'delta' snapshots is not yet supported, [tracking issue](https://opensearch.atlassian.net/browse/MIGRATIONS-1624).  There must be a single snapshot for a backfill.  


### Snapshot creation from the console

Create the initial Snapshot on the Source Cluster with the following commands:

```shell
console snapshot create
```

Depending on the size of the data on the source cluster and the bandwidth you've configured the cluster to provide for snapshots, this can take a while.  You can tweak the maximum rate at which the source cluster's nodes will create the snapshot using the `--max-snapshot-rate-mb-per-node` option.  The tradeoff here is that increasing the speed at which the snapshot is created also increases the amount of resources the nodes are devoting to that creation instead of serving normal traffic.  If you don't specify a number for this, it will use the default for whatever version of source cluster you're using.  See [the docs for ES 7.10 here](https://www.elastic.co/guide/en/elasticsearch/reference/7.10/put-snapshot-repo-api.html#put-snapshot-repo-api-request-body) for more context.

You can get a point-in-time update on the snapshot status like so:

```shell
console snapshot status --deep-check
```

<details>
<summary>
<b>Example output a snapshot is completed</b>
</summary>

```shell
console snapshot status --deep-check

SUCCESS
Snapshot is SUCCESS.
Percent completed: 100.00%
Data GiB done: 29.211/29.211
Total shards: 40
Successful shards: 40
Failed shards: 0
Start time: 2024-07-22 18:21:42
Duration: 0h 13m 4s
Anticipated duration remaining: 0h 0m 0s
Throughput: 38.13 MiB/sec
```
</details>


### How to contribute
* 