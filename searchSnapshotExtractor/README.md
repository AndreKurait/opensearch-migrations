# Search Snapshot Extractor

This module provides a clean abstraction for accessing blob/file data from various sources, designed to support the extraction of documents from Elasticsearch/OpenSearch snapshots.

## Overview

The Search Snapshot Extractor library is the first step in refactoring the RFS (Reindex From Snapshot) functionality to separate document extraction capabilities from the broader migration orchestration logic.

## BlobSource Abstraction

The core abstraction is the `BlobSource` interface, which provides a simple, stream-based API for accessing blob data:

```java
public interface BlobSource {
    InputStream getBlob(String path) throws IOException;
    boolean exists(String path);
    long getBlobSize(String path) throws IOException;
    List<String> listBlobs(String prefix) throws IOException;
}
```

## Implementations

### FileBlobSource

Provides access to files on the local filesystem:

```java
FileBlobSource blobSource = new FileBlobSource("/path/to/snapshot/directory");
try (InputStream stream = blobSource.getBlob("indices/my-index/0/segments_1")) {
    // Process the blob data
}
```

### S3BlobSource

Provides access to files stored in Amazon S3, with optional local caching:

```java
S3BlobSource blobSource = new S3BlobSource(
    "s3://my-bucket/snapshots/snapshot-1", 
    "us-west-2", 
    Paths.get("/tmp/cache")  // Optional local cache directory
);

try (InputStream stream = blobSource.getBlob("indices/my-index/0/segments_1")) {
    // Process the blob data
}
```

The S3BlobSource uses AWS SDK's S3Utilities for proper S3 URI parsing and validation.

## Key Features

- **Simple Interface**: Stream-based API that's easy to use and test
- **Multiple Sources**: Support for both filesystem and S3 storage
- **Local Caching**: S3BlobSource supports optional local caching for performance
- **Extensible**: Easy to add new blob source implementations (HTTP, Azure, etc.)
- **Reduced Dependencies**: Minimal dependencies compared to the full RFS library

## Goals Achieved

This initial implementation addresses several goals from the RFS refactoring:

1. **Library Isolation**: Clean separation between blob access and migration logic
2. **Reduced Dependencies**: Consumers only need blob access dependencies, not full RFS stack
3. **Extensible Design**: Easy to add new source types (delta snapshots, remote mounts, etc.)
4. **Testability**: Simple interface that's easy to mock and test

## Snapshot Repository Reading

The library includes functionality to read and parse Elasticsearch/OpenSearch snapshot repository metadata using version-specific parsers:

### SnapshotRepository

The main class for interacting with snapshot repositories:

```java
// Create from local filesystem path
SnapshotRepository repository = SnapshotRepository.fromPath("/path/to/snapshot/repo");

// Read repository metadata (automatically detects version)
RepositoryMetadata metadata = repository.readRepositoryMetadata();

// Access snapshot information
for (SnapshotInfo snapshot : metadata.getSnapshots()) {
    System.out.println("Snapshot: " + snapshot.getName() + " (" + snapshot.getUuid() + ")");
    System.out.println("Version: " + snapshot.getVersion());
    System.out.println("State: " + snapshot.getStateDescription());
}

// Access index information
for (IndexInfo index : metadata.getIndices()) {
    System.out.println("Index: " + index.getName() + " (" + index.getId() + ")");
    System.out.println("Shards: " + index.getShardCount());
    System.out.println("In snapshots: " + index.getSnapshotUuids());
}
```

### Version-Specific Parsers

The library uses a clean architecture with version-specific parsers:

- **ES7SnapshotRepositoryParser**: Handles Elasticsearch 7.x+ and OpenSearch formats
- **ES6SnapshotRepositoryParser**: Handles Elasticsearch 6.x formats
- **Automatic Detection**: Parsers automatically detect which format they can handle
- **Extensible**: Easy to add new version parsers (ES 5.x, future versions)

### Parser Architecture

Each parser uses Jackson DTOs with Java 17 records for clean, maintainable code:

```java
@JsonIgnoreProperties(ignoreUnknown = true)
public record ES7RepositoryMetadataDto(
    @JsonProperty("snapshots") List<ES7SnapshotDto> snapshots,
    @JsonProperty("indices") Map<String, ES7IndexDto> indices,
    @JsonProperty("min_version") String minVersion,
    @JsonProperty("index_metadata_identifiers") Map<String, String> indexMetadataIdentifiers
) {}
```

### Supported Versions

- **Elasticsearch 6.x**: Basic snapshot and index metadata (no version info, no shard generations)
- **Elasticsearch 7.x+**: Full metadata including version info and shard generations
- **OpenSearch 1.x+**: Compatible with Elasticsearch 7.x format

### Key Features

- **Automatic Version Detection**: Detects format and uses appropriate parser
- **Cross-Version Support**: Handles differences between ES/OS versions gracefully
- **Rich Metadata**: Extracts snapshots, indices, and repository information
- **Search Capabilities**: Find snapshots and indices by name or UUID
- **Clean Architecture**: Version-specific parsers with shared intermediate representation
- **Jackson Integration**: Uses Jackson DTOs for robust JSON parsing

## Future Enhancements

This is the foundation for the larger refactoring effort. Future additions will include:

- Lucene document extraction
- Version-specific snapshot readers
- Shard-level document streaming
- Index manifest generation

## Usage in RFS

The RFS library already depends on this module and can gradually migrate to use these abstractions instead of the current tightly-coupled implementations.

## Testing

Run the tests with:

```bash
./gradlew :searchSnapshotExtractor:test
```

The test suite includes:
- FileBlobSource functionality tests
- S3Uri parsing tests
- Error handling verification
