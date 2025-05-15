# Delta Snapshots in RFS Document Migration

## Overview

Delta snapshots provide the ability to migrate only the differences between two snapshots, rather than migrating the entire contents of a snapshot. This feature would allow users to supply a snapshot name as they do today, but also supply a `--from-snapshot-state` parameter with an initial snapshot name. RFS would then calculate and apply only the differences (deltas) between the two snapshots.

## Current POC Implementation

The current proof of concept (POC) implementation explores the feasibility of delta snapshots through several key components:

### 1. Snapshot Structure Analysis

As documented in `IncrementalSnapshots.md`, snapshots have a specific structure with files that represent the state of indices and shards. When comparing two snapshots (OLD and NEW), we can identify:

- Files shared between both snapshots
- Files unique to the OLD snapshot
- Files unique to the NEW snapshot

This analysis forms the foundation for determining what has changed between snapshots.

### 2. Lucene Index Comparison in IndexReader9

The `IndexReader9` class contains POC code that:

- Opens two commits from a Lucene directory (representing old and new states)
- Wraps them with `SoftDeletesDirectoryReaderWrapper`
- Compares the live documents between the two commits using `FixedBitSet`
- Identifies documents to add and documents to remove by comparing the bit sets

```java
public LuceneDirectoryReader getReader() throws IOException {
    try (var directory = FSDirectory.open(indexDirectoryPath)) {
        var commits = DirectoryReader.listCommits(directory);

        var oldReaders = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(commits.get(0)), softDeletesField);
        var newReaders = new SoftDeletesDirectoryReaderWrapper(DirectoryReader.open(commits.get(1)), softDeletesField);

        // Compare readers and identify documents to add/remove
        var firstOldReader = oldReaders.getContext().leaves().get(0).reader();
        var firstNewReader = newReaders.getContext().leaves().get(0).reader();

        var firstBitSet = ((FixedBitSet) firstOldReader.getLiveDocs());
        var secondBitSet = ((FixedBitSet) firstNewReader.getLiveDocs());

        // Calculate differences
        var docsToRemoveBits = firstBitSet.clone();
        docsToRemoveBits.andNot(secondBitSet.clone());

        var docsToAddBits = secondBitSet.clone();
        docsToAddBits.andNot(firstBitSet.clone());

        var docsToAdd = getSetBitIndexes(docsToAddBits);
        var docsToRemove = getSetBitIndexes(docsToRemoveBits);

        // Process the differences
        // ...
    }
}
```

### 3. Combining Files from Multiple Snapshots

In `RfsMigrateDocuments.java`, there's a POC implementation that combines files from two snapshots:

```java
BiFunction<String, Integer, ShardMetadata> shardMetadataSupplier = (name, shard) -> {
    var shardMetadataNew = shardMetadataFactory.fromRepo(snapshotName, name, shard);
    var shardMetadataOld = shardMetadataFactory.fromRepo("initial_snapshot", name, shard);

    var set = new LinkedHashSet<>(shardMetadataOld.getFiles());
    set.addAll(shardMetadataNew.getFiles());
    var combinedFiles = new ArrayList<>(set).stream()
            .map(ShardMetadataData_ES_7_10.FileInfo::toFileMetadataRaw)
            .toList();
    // ...
};
```

This approach creates a union of files from both snapshots, which is necessary for processing the delta.

## Findings from the POC

Based on the POC implementation, we've learned:

1. **Technical Feasibility**: It is technically feasible to identify and process only the differences between two snapshots.

2. **Comparison Mechanisms**: We can compare snapshots at multiple levels:
   - File level: Identifying new, modified, and deleted files
   - Document level: Using Lucene's bit sets to identify added and removed documents

3. **Performance Potential**: Processing only the delta between snapshots could significantly reduce migration time and resource usage for incremental updates.

4. **Implementation Complexity**: The implementation requires careful handling of:
   - Snapshot metadata
   - Lucene index structures
   - Document identification and processing

5. **Current Limitations**: The POC implementation hardcodes the initial snapshot name as "initial_snapshot" and doesn't yet support a user-specified parameter.

## Implementation Plan

To transform the POC into a full feature, we propose the following implementation plan:

### 1. Command Line Interface Enhancement

Add a new optional parameter to the `RfsMigrateDocuments` class:

```java
@Parameter(required = false,
    names = { "--from-snapshot-state", "--fromSnapshotState" },
    description = "Optional. The name of the initial snapshot to calculate delta from. " +
        "If provided, only the differences between this snapshot and the target snapshot will be migrated.")
public String fromSnapshotState = null;
```

### 2. Delta Detection Logic

Enhance the current implementation to:

- Check if `fromSnapshotState` is provided
- If provided, use delta processing logic
- If not provided, use the existing full snapshot processing logic

### 3. Snapshot Comparison Module

Create a dedicated module for snapshot comparison that:

- Compares metadata between two snapshots
- Identifies new, modified, and deleted indices
- For each modified index, identifies changed shards
- For each changed shard, identifies document-level changes

### 4. Delta Migration Workflow

Implement a workflow that:

1. Loads both snapshots (initial and target)
2. Performs comparison to identify differences
3. Creates work items only for the changed components
4. Processes only the necessary changes (additions, modifications, deletions)

### 5. Optimization Strategies

Implement optimizations for different scenarios:

- **Small Changes**: When only a few documents have changed, process only those specific documents
- **Large Changes**: When many documents have changed, determine if full reprocessing is more efficient
- **Structural Changes**: Handle index mapping changes and other structural modifications

### 6. Testing Framework

Develop comprehensive tests that:

- Verify correct identification of changes between snapshots
- Ensure data consistency after delta migration
- Measure performance improvements compared to full migration
- Test edge cases (e.g., schema changes, deleted indices)

### 7. Documentation

Update documentation to:

- Explain the delta snapshots feature
- Provide usage examples
- Document best practices and limitations
- Include performance considerations

## Proposed CLI Interface

The enhanced CLI interface would support the following usage pattern:

```shell
./gradlew DocumentsFromSnapshotMigration:run --args="\
  --snapshot-name target_snapshot \
  --from-snapshot-state initial_snapshot \
  --s3-local-dir /tmp/s3_files \
  --s3-repo-uri s3://your-s3-uri \
  --s3-region us-fake-1 \
  --lucene-dir /tmp/lucene_files \
  --target-host http://hostname:9200" \
  || { exit_code=$?; [[ $exit_code -ne 3 ]] && echo "Command failed with exit code $exit_code. Consider rerunning the command."; }
```

## Benefits and Use Cases

The delta snapshots feature would provide significant benefits for several use cases:

1. **Incremental Updates**: For clusters that take regular snapshots, migrating only the changes since the last migration
2. **Continuous Migration**: Supporting ongoing migration of changes with minimal overhead
3. **Testing and Validation**: Quickly testing migration of specific changes without processing the entire dataset
4. **Resource Optimization**: Reducing compute, network, and storage requirements for migrations

## Next Steps

1. Refine the POC implementation into a production-ready feature
2. Implement the CLI parameter and supporting logic
3. Develop comprehensive testing for the feature
4. Update documentation to include the new capability
5. Measure and optimize performance for different delta scenarios
