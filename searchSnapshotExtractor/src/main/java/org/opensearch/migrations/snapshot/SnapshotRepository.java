package org.opensearch.migrations.snapshot;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.opensearch.migrations.io.BlobSource;
import org.opensearch.migrations.io.FileBlobSource;
import org.opensearch.migrations.snapshot.es_v6.ES6SnapshotRepositoryParser;
import org.opensearch.migrations.snapshot.es_v7.ES7SnapshotRepositoryParser;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Represents an Elasticsearch/OpenSearch snapshot repository and provides methods
 * to read and parse snapshot metadata using version-specific parsers.
 */
@RequiredArgsConstructor
@Slf4j
public class SnapshotRepository {
    
    private final BlobSource blobSource;
    
    // Available parsers in order of preference (newest first)
    private final List<SnapshotRepositoryParser> parsers = Arrays.asList(
        new ES7SnapshotRepositoryParser(),
        new ES6SnapshotRepositoryParser()
    );
    
    /**
     * Create a SnapshotRepository from a local filesystem path
     * @param repositoryPath the path to the snapshot repository
     * @return a new SnapshotRepository instance
     */
    public static SnapshotRepository fromPath(String repositoryPath) {
        return fromPath(Paths.get(repositoryPath));
    }
    
    /**
     * Create a SnapshotRepository from a local filesystem path
     * @param repositoryPath the path to the snapshot repository
     * @return a new SnapshotRepository instance
     */
    public static SnapshotRepository fromPath(Path repositoryPath) {
        return new SnapshotRepository(new FileBlobSource(repositoryPath));
    }
    
    /**
     * Find and return the latest index file in the repository
     * @return the name of the latest index file (e.g., "index-1")
     * @throws IOException if no index files are found or if there's an error reading
     */
    public String findLatestIndexFile() throws IOException {
        // First try to read index.latest if it exists
        if (blobSource.exists("index.latest")) {
            try (InputStream stream = blobSource.getBlob("index.latest")) {
                String content = new String(stream.readAllBytes()).trim();
                String indexFile = "index-" + content;
                if (blobSource.exists(indexFile)) {
                    log.debug("Found latest index file from index.latest: {}", indexFile);
                    return indexFile;
                }
            }
        }
        
        // Fallback: scan for index-N files and find the highest version
        List<String> allFiles = blobSource.listBlobs("");
        Pattern pattern = Pattern.compile("^index-(\\d+)$");
        String highestVersionedFile = null;
        int highestVersion = -1;
        
        for (String fileName : allFiles) {
            Matcher matcher = pattern.matcher(fileName);
            if (matcher.matches()) {
                int version = Integer.parseInt(matcher.group(1));
                if (version > highestVersion) {
                    highestVersion = version;
                    highestVersionedFile = fileName;
                }
            }
        }
        
        if (highestVersionedFile == null) {
            throw new IOException("No index files found in repository");
        }
        
        log.debug("Found latest index file by scanning: {}", highestVersionedFile);
        return highestVersionedFile;
    }
    
    /**
     * Read and parse the repository metadata from the latest index file
     * @return parsed RepositoryMetadata
     * @throws IOException if there's an error reading or parsing the metadata
     */
    public RepositoryMetadata readRepositoryMetadata() throws IOException {
        String indexFile = findLatestIndexFile();
        
        // Try each parser until we find one that can handle the format
        for (SnapshotRepositoryParser parser : parsers) {
            try (InputStream stream = blobSource.getBlob(indexFile)) {
                if (parser.canParse(stream)) {
                    log.debug("Using parser: {}", parser.getClass().getSimpleName());
                    // Create a fresh stream for parsing since canParse consumed the stream
                    try (InputStream parseStream = blobSource.getBlob(indexFile)) {
                        return parser.parse(parseStream);
                    }
                }
            } catch (Exception e) {
                log.debug("Parser {} failed to handle format: {}", parser.getClass().getSimpleName(), e.getMessage());
                // Continue to next parser
            }
        }
        
        throw new IOException("No suitable parser found for snapshot repository format");
    }
    
    /**
     * Get the underlying BlobSource for direct access if needed
     * @return the BlobSource instance
     */
    public BlobSource getBlobSource() {
        return blobSource;
    }
}
