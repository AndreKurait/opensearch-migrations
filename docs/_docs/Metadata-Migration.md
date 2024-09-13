## Metadata Migration

Metadata migration is a relatively fast process to execute so we recommend attempting this workflow as quickly as possible to discover any issues which could impact longer running migration steps.

### Prerequisites
A snapshot of the cluster state will need to be taken, [[guide to create a snapshot|Snapshot Creation]].

### Metadata verification with evaluate command

By scanning the contents of the source cluster, applying filtering, and applying modifications a list of all items that will be migrated will be created.  Any items not seen in this output will not be migrated onto the target cluster if the migrate command was to be run.  This is a safety check before making modifications on the target cluster.

```shell
console metadata evaluate
```

<details>
<summary>
<b>Example evaluate command output<b>
</summary>

```
Starting Metadata Evaluation
Clusters:
   Source:
      Remote Cluster: OpenSearch 1.3.16 ConnectionContext(uri=http://localhost:33039, protocol=HTTP, insecure=false, compressionSupported=false)

   Target:
      Remote Cluster: OpenSearch 2.14.0 ConnectionContext(uri=http://localhost:33037, protocol=HTTP, insecure=false, compressionSupported=false)


Migration Candidates:
   Index Templates:
      simple_index_template

   Component Templates:
      simple_component_template

   Indexes:
      blog_2023, movies_2023

   Aliases:
      alias1, movies-alias


Results:
   0 issue(s) detected
```
</details>

### Metadata migration with migrate command

Running through the same data as the evaluate command all of the migrated items will be applied onto the target cluster.  If re-run multiple times items that were previously migrated will not be recreated.  If any items do need to be re-migrated, please delete them from the target cluster and then rerun the evaluate then migrate commands to ensure the desired changes are made.

```shell
console metadata migrate
```

<details>
<summary>
<b>Example migrate command output</b>
</summary>

```
Starting Metadata Migration

Clusters:
   Source:
      Snapshot: OpenSearch 1.3.16 FileSystemRepo(repoRootDir=/tmp/junit10626813752669559861)

   Target:
      Remote Cluster: OpenSearch 2.14.0 ConnectionContext(uri=http://localhost:33042, protocol=HTTP, insecure=false, compressionSupported=false)


Migrated Items:
   Index Templates:
      simple_index_template

   Component Templates:
      simple_component_template

   Indexes:
      blog_2023, movies_2023

   Aliases:
      alias1, movies-alias


Results:
   0 issue(s) detected
```
</details>

### Metadata verification process

Before moving on to additional migration steps, it is recommended to confirm details of your cluster.  Depending on your configuration, this could be checking the sharding strategy or making sure index mappings are correctly defined by ingesting a test document.

### Troubleshoot issues

#### Access Detailed Logs
Metadata migration creates a detailed log file that includes low level tracing information for troubleshooting. For each execution of the program a log file is created inside a shared volume on the migration console named `shared-logs-output` the following command will list all log files, one for each run of the command.

```shell
ls -al /shared-logs-output/migration-console-default/*/metadata/
```

To inspect the file within the console `cat`, `tail` and `grep` commands line tools.  By looking for warnings, errors and exceptions in this log file can help understand the source of failures, or at the very least be useful for creating issues in this project.

```shell
tail /shared-logs-output/migration-console-default/*/metadata/*.log
```

## How the tool works

This tool gathers information from a source cluster, through a snapshot or through HTTP requests against the source cluster.  These snapshots are fully compatible with the backfill process for Reindex-From-Snapshot (RFS) scenarios, [[learn more|Migrate-Existing-Data-from-Source-Cluster]].

After collecting information on the source cluster comparisons are made on the target cluster.  If running a migration, any metadata items do not already exist will be created on the target cluster.

### Breaking change compatibility

Metadata migration needs to modify data from the source to the target versions to recreate items.  Sometimes these features are no longer supported and have been removed from the target version.  Sometimes these features are not available on the target version, which is especially true when downgrading.  While this tool is meant to make this process easier, it is not exhaustive in its support.  When encountering a compatibility issue or an important feature gap for your migration, please [search the issues](https://github.com/opensearch-project/opensearch-migrations/issues) and comment + upvote or a [create a new](https://github.com/opensearch-project/opensearch-migrations/issues/new/choose) issue if one cannot be found.

#### Deprecation of Mapping Types
In Elasticsearch 6.8 the mapping types feature was discontinued in Elasticsearch 7.0+ which has created complexity in migrating to newer versions of Elasticsearch and OpenSearch, [learn more](https://www.elastic.co/guide/en/elasticsearch/reference/7.17/removal-of-types.html).

As Metadata migration supports migrating from ES 6.8 on to the latest versions of OpenSearch this scenario is handled by removing the type mapping types and restructuring the template or index properties.  Note that, at the time of this writing multiple type mappings are not supported, [tracking task](https://opensearch.atlassian.net/browse/MIGRATIONS-1778).


**Example starting state with mapping type foo (ES 6):**
```json
{
  "mappings": [
    {
      "foo": {
        "properties": {
          "field1": { "type": "text" },
          "field2": { "type": "keyword" }
        }
      }
    }
  ]
}
```

**Example ending state with foo removed (ES 7):**
```json
{
  "mappings": {
    "properties": {
      "field1": { "type": "text" },
      "field2": { "type": "keyword" },
    }
  }
}
```

*Technical details are available, [view source code](https://github.com/opensearch-project/opensearch-migrations/blob/main/transformation/src/main/java/org/opensearch/migrations/transformation/rules/IndexMappingTypeRemoval.java).*