package org.opensearch.migrations.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * BlobSource implementation for local filesystem access
 */
@RequiredArgsConstructor
@Slf4j
public class FileBlobSource implements BlobSource {
    
    private final Path rootPath;
    
    /**
     * Create a FileBlobSource with the given root directory
     * @param rootPath the root directory path
     */
    public FileBlobSource(String rootPath) {
        this(Paths.get(rootPath));
    }
    
    @Override
    public InputStream getBlob(String path) throws IOException {
        Path fullPath = rootPath.resolve(path);
        log.debug("Reading blob from: {}", fullPath);
        
        if (!Files.exists(fullPath)) {
            throw new IOException("File does not exist: " + fullPath);
        }
        
        if (!Files.isRegularFile(fullPath)) {
            throw new IOException("Path is not a regular file: " + fullPath);
        }
        
        return Files.newInputStream(fullPath);
    }
    
    @Override
    public boolean exists(String path) {
        Path fullPath = rootPath.resolve(path);
        return Files.exists(fullPath) && Files.isRegularFile(fullPath);
    }
    
    @Override
    public long getBlobSize(String path) throws IOException {
        Path fullPath = rootPath.resolve(path);
        
        if (!Files.exists(fullPath)) {
            throw new IOException("File does not exist: " + fullPath);
        }
        
        return Files.size(fullPath);
    }
    
    @Override
    public List<String> listBlobs(String prefix) throws IOException {
        List<String> results = new ArrayList<>();
        Path prefixPath = prefix.isEmpty() ? rootPath : rootPath.resolve(prefix);
        
        if (!Files.exists(prefixPath)) {
            return results; // Return empty list if prefix path doesn't exist
        }
        
        if (Files.isRegularFile(prefixPath)) {
            // If the prefix points to a file, return just that file
            results.add(rootPath.relativize(prefixPath).toString());
            return results;
        }
        
        // Recursively walk the directory tree
        walkDirectory(prefixPath, prefix, results);
        
        return results;
    }
    
    private void walkDirectory(Path directory, String prefix, List<String> results) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String relativePath = rootPath.relativize(entry).toString();
                
                if (Files.isRegularFile(entry)) {
                    // Only include files that match the prefix
                    if (relativePath.startsWith(prefix)) {
                        results.add(relativePath);
                    }
                } else if (Files.isDirectory(entry)) {
                    // Recursively walk subdirectories
                    walkDirectory(entry, prefix, results);
                }
            }
        }
    }
    
    /**
     * Get the root path of this blob source
     * @return the root path
     */
    public Path getRootPath() {
        return rootPath;
    }
}
