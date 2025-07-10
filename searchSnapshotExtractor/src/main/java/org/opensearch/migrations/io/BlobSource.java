package org.opensearch.migrations.io;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Abstraction for accessing blob/file data from various sources (filesystem, S3, etc.)
 * Provides a simple, stream-based interface for reading blob data.
 */
public interface BlobSource {
    
    /**
     * Get a blob as an InputStream
     * @param path the path/key of the blob to retrieve
     * @return InputStream for reading the blob data
     * @throws IOException if the blob cannot be accessed or doesn't exist
     */
    InputStream getBlob(String path) throws IOException;
    
    /**
     * Check if a blob exists at the given path
     * @param path the path/key to check
     * @return true if the blob exists, false otherwise
     */
    boolean exists(String path);
    
    /**
     * Get the size of a blob in bytes
     * @param path the path/key of the blob
     * @return the size in bytes
     * @throws IOException if the blob cannot be accessed or doesn't exist
     */
    long getBlobSize(String path) throws IOException;
    
    /**
     * List all blobs with the given prefix
     * @param prefix the prefix to search for (can be empty for all blobs)
     * @return list of blob paths/keys that match the prefix
     * @throws IOException if the listing operation fails
     */
    List<String> listBlobs(String prefix) throws IOException;
}
