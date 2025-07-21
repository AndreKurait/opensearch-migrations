package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.opensearch.migrations.io.BlobSource;

import lombok.extern.slf4j.Slf4j;

/**
 * Base implementation of VersionAdapter providing common functionality
 * that can be shared across different Elasticsearch/OpenSearch versions.
 */
@Slf4j
public abstract class BaseVersionAdapter implements VersionAdapter {
    
    protected final BlobSource blobSource;
    protected final ObjectMapper objectMapper;
    
    /**
     * Create a new base version adapter.
     * 
     * @param blobSource The blob source for reading snapshot data
     */
    protected BaseVersionAdapter(BlobSource blobSource) {
        this.blobSource = blobSource;
        this.objectMapper = createObjectMapper();
        log.debug("Created {} version adapter", getVersionIdentifier());
    }
    
    /**
     * Create and configure the ObjectMapper for JSON parsing.
     * Subclasses can override this to customize JSON parsing behavior.
     * 
     * @return Configured ObjectMapper
     */
    protected ObjectMapper createObjectMapper() {
        return new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }
    
    /**
     * Default implementation for index metadata path.
     * Most versions use this pattern, but can be overridden if needed.
     */
    @Override
    public String getIndexMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef) {
        return String.format("indices/%s/snapshot-%s", indexRef.getName(), snapshotRef.getName());
    }
    
    /**
     * Default implementation for shard metadata path.
     * Most versions use this pattern, but can be overridden if needed.
     */
    @Override
    public String getShardMetadataPath(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) {
        return String.format("indices/%s/%d/snapshot-%s", indexRef.getName(), shardId, snapshotRef.getName());
    }
    
    /**
     * Helper method to read and parse JSON metadata from a blob.
     * Provides consistent error handling and logging.
     * 
     * @param path The blob path
     * @param clazz The class to deserialize to
     * @param <T> The type to deserialize to
     * @return Parsed object
     * @throws IOException if reading or parsing fails
     */
    protected <T> T readJsonMetadata(String path, Class<T> clazz) throws IOException {
        log.debug("Reading {} metadata from path: {}", clazz.getSimpleName(), path);
        
        try (InputStream stream = blobSource.getBlob(path)) {
            T result = objectMapper.readValue(stream, clazz);
            log.debug("Successfully parsed {} from {}", clazz.getSimpleName(), path);
            return result;
        } catch (IOException e) {
            log.error("Failed to read {} from path {}: {}", clazz.getSimpleName(), path, e.getMessage());
            throw new IOException("Failed to parse " + clazz.getSimpleName() + " from " + path, e);
        }
    }
    
    /**
     * Helper method to read JSON metadata with a custom input stream.
     * Useful when the stream is already available.
     * 
     * @param stream The input stream
     * @param clazz The class to deserialize to
     * @param description Description for logging/error messages
     * @param <T> The type to deserialize to
     * @return Parsed object
     * @throws IOException if parsing fails
     */
    protected <T> T readJsonMetadata(InputStream stream, Class<T> clazz, String description) throws IOException {
        log.debug("Reading {} metadata from stream: {}", clazz.getSimpleName(), description);
        
        try {
            T result = objectMapper.readValue(stream, clazz);
            log.debug("Successfully parsed {} from {}", clazz.getSimpleName(), description);
            return result;
        } catch (IOException e) {
            log.error("Failed to read {} from {}: {}", clazz.getSimpleName(), description, e.getMessage());
            throw new IOException("Failed to parse " + clazz.getSimpleName() + " from " + description, e);
        }
    }
    
    /**
     * Default implementation that uses the standard path and reads shard metadata.
     * Subclasses should override this to handle version-specific shard metadata formats.
     */
    @Override
    public ShardMetadata readShardMetadata(SnapshotReference snapshotRef, IndexReference indexRef, int shardId) throws IOException {
        String path = getShardMetadataPath(snapshotRef, indexRef, shardId);
        
        // This is a template method - subclasses should implement the actual parsing
        throw new UnsupportedOperationException(
            "Subclasses must implement readShardMetadata for version-specific parsing. " +
            "Path would be: " + path
        );
    }
    
    /**
     * Default implementation that uses the standard path and reads index metadata.
     * Subclasses should override this to handle version-specific index metadata formats.
     */
    @Override
    public IndexReference parseIndexMetadata(SnapshotReference snapshotRef, String indexName) throws IOException {
        String path = getIndexMetadataPath(snapshotRef, IndexReference.of(indexName, 1));
        
        // This is a template method - subclasses should implement the actual parsing
        throw new UnsupportedOperationException(
            "Subclasses must implement parseIndexMetadata for version-specific parsing. " +
            "Path would be: " + path
        );
    }
    
    /**
     * Utility method to safely parse integer values that might be strings.
     * Common in Elasticsearch metadata where numbers are sometimes stored as strings.
     * 
     * @param value The value to parse (can be String or Integer)
     * @param defaultValue Default value if parsing fails
     * @return Parsed integer value
     */
    protected int parseIntegerValue(Object value, int defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        if (value instanceof Integer) {
            return (Integer) value;
        }
        
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse integer from string '{}', using default {}", value, defaultValue);
                return defaultValue;
            }
        }
        
        log.warn("Unexpected value type {} for integer parsing, using default {}", 
                value.getClass().getSimpleName(), defaultValue);
        return defaultValue;
    }
    
    /**
     * Utility method to safely parse long values that might be strings.
     * 
     * @param value The value to parse (can be String or Long)
     * @param defaultValue Default value if parsing fails
     * @return Parsed long value
     */
    protected long parseLongValue(Object value, long defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        
        if (value instanceof Long) {
            return (Long) value;
        }
        
        if (value instanceof Integer) {
            return ((Integer) value).longValue();
        }
        
        if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                log.warn("Failed to parse long from string '{}', using default {}", value, defaultValue);
                return defaultValue;
            }
        }
        
        log.warn("Unexpected value type {} for long parsing, using default {}", 
                value.getClass().getSimpleName(), defaultValue);
        return defaultValue;
    }
}
