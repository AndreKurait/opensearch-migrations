package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;

/**
 * Base interface for version-specific snapshot repository parsers
 */
public interface SnapshotRepositoryParser {
    
    /**
     * Parse snapshot repository metadata from an InputStream
     * @param inputStream the input stream containing the index-N file content
     * @return parsed RepositoryMetadata
     * @throws IOException if parsing fails
     */
    RepositoryMetadata parse(InputStream inputStream) throws IOException;
    
    /**
     * Check if this parser can handle the given metadata format
     * @param inputStream the input stream to check (will be reset after checking)
     * @return true if this parser can handle the format
     * @throws IOException if there's an error reading the stream
     */
    boolean canParse(InputStream inputStream) throws IOException;
}
