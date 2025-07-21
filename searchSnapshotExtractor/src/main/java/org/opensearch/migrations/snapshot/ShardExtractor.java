package org.opensearch.migrations.snapshot;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import org.opensearch.migrations.io.BlobSource;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Extracts shard data from snapshot blobs and reconstructs Lucene index files.
 * Simplified version of RFS's SnapshotShardUnpacker.
 */
@RequiredArgsConstructor
@Slf4j
public class ShardExtractor {
    
    private final BlobSource blobSource;
    private final Path tempDirectory;
    private static final int BUFFER_SIZE = 8192;
    
    /**
     * Extract shard files from snapshot to a temporary directory.
     * 
     * @param shardMetadata The shard metadata containing file information
     * @return Path to the directory containing the extracted Lucene files
     * @throws IOException if extraction fails
     */
    public Path extractShard(ShardMetadata shardMetadata) throws IOException {
        // Create directory for this shard's files
        Path shardDir = tempDirectory.resolve(shardMetadata.getIndexName())
                                    .resolve(String.valueOf(shardMetadata.getShardId()));
        Files.createDirectories(shardDir);
        
        log.info("Extracting shard {}/{} to {}", 
                 shardMetadata.getIndexName(), 
                 shardMetadata.getShardId(), 
                 shardDir);
        
        // Extract each file
        for (ShardMetadata.ShardFileInfo fileInfo : shardMetadata.getFiles()) {
            extractFile(shardMetadata, fileInfo, shardDir);
        }
        
        return shardDir;
    }
    
    private void extractFile(ShardMetadata shardMetadata, 
                           ShardMetadata.ShardFileInfo fileInfo, 
                           Path targetDir) throws IOException {
        
        Path targetFile = targetDir.resolve(fileInfo.getPhysicalName());
        
        log.debug("Extracting file {} -> {}", fileInfo.getName(), targetFile);
        
        // Handle virtual files (like segments.gen) that only contain metadata hash
        if (fileInfo.getName().startsWith("v__")) {
            // For virtual files, we just create an empty file
            // In a full implementation, we'd write the metadata hash
            Files.createFile(targetFile);
            return;
        }
        
        // For ES1, files are stored directly without multi-part splitting
        String blobPath = getBlobPath(shardMetadata, fileInfo);
        
        try (InputStream input = blobSource.getBlob(blobPath);
             OutputStream output = Files.newOutputStream(targetFile, 
                                                        StandardOpenOption.CREATE,
                                                        StandardOpenOption.TRUNCATE_EXISTING)) {
            
            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalBytes = 0;
            
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
                totalBytes += bytesRead;
            }
            
            log.debug("Extracted {} bytes for file {}", totalBytes, fileInfo.getPhysicalName());
            
            if (totalBytes != fileInfo.getLength()) {
                log.warn("File size mismatch for {}: expected {} but got {}", 
                        fileInfo.getPhysicalName(), fileInfo.getLength(), totalBytes);
            }
        }
    }
    
    private String getBlobPath(ShardMetadata shardMetadata, ShardMetadata.ShardFileInfo fileInfo) {
        // ES1 blob path format: indices/{index}/{shard}/{blobName}
        return String.format("indices/%s/%d/%s", 
                           shardMetadata.getIndexName(),
                           shardMetadata.getShardId(),
                           fileInfo.getName());
    }
    
    /**
     * Clean up extracted files for a shard.
     * 
     * @param shardDir The directory containing extracted files
     */
    public void cleanup(Path shardDir) {
        try {
            if (Files.exists(shardDir)) {
                Files.walk(shardDir)
                     .sorted((a, b) -> b.compareTo(a)) // Delete files before directories
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                         } catch (IOException e) {
                             log.warn("Failed to delete {}: {}", path, e.getMessage());
                         }
                     });
            }
        } catch (IOException e) {
            log.warn("Failed to cleanup shard directory {}: {}", shardDir, e.getMessage());
        }
    }
}
