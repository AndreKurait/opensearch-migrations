package org.opensearch.migrations.io.s3;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.opensearch.migrations.io.BlobSource;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.async.AsyncResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3AsyncClient;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Object;

/**
 * BlobSource implementation for Amazon S3 access
 */
@Slf4j
public class S3BlobSource implements BlobSource {
    
    private static final double S3_TARGET_THROUGHPUT_GIBPS = 8.0;
    private static final long S3_MAX_MEMORY_BYTES = 1024L * 1024 * 1024; // 1GB
    private static final long S3_MINIMUM_PART_SIZE_BYTES = 8L * 1024 * 1024; // 8MB
    
    private final S3AsyncClient s3Client;
    private final String bucketName;
    private final String keyPrefix;
    private final Path localCacheDir;
    
    /**
     * Create an S3BlobSource with default settings
     * @param s3Uri the S3 URI (e.g., "s3://bucket/prefix/")
     * @param region the AWS region
     * @param localCacheDir optional local cache directory for downloaded files
     */
    public S3BlobSource(String s3Uri, String region, Path localCacheDir) {
        this(s3Uri, region, localCacheDir, null);
    }
    
    /**
     * Create an S3BlobSource with custom endpoint
     * @param s3Uri the S3 URI (e.g., "s3://bucket/prefix/")
     * @param region the AWS region
     * @param localCacheDir optional local cache directory for downloaded files
     * @param endpoint custom S3 endpoint (for testing with LocalStack, etc.)
     */
    public S3BlobSource(String s3Uri, String region, Path localCacheDir, URI endpoint) {
        S3Uri parsedUri = new S3Uri(s3Uri);
        this.bucketName = parsedUri.getBucketName();
        this.keyPrefix = parsedUri.getKey();
        this.localCacheDir = localCacheDir;
        
        var clientBuilder = S3AsyncClient.crtBuilder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.builder().build())
            .retryConfiguration(r -> r.numRetries(3))
            .targetThroughputInGbps(S3_TARGET_THROUGHPUT_GIBPS)
            .maxNativeMemoryLimitInBytes(S3_MAX_MEMORY_BYTES)
            .minimumPartSizeInBytes(S3_MINIMUM_PART_SIZE_BYTES);
            
        if (endpoint != null) {
            clientBuilder.endpointOverride(endpoint);
        }
        
        this.s3Client = clientBuilder.build();
        
        // Create cache directory if specified
        if (localCacheDir != null) {
            try {
                Files.createDirectories(localCacheDir);
            } catch (IOException e) {
                log.warn("Failed to create cache directory: {}", localCacheDir, e);
            }
        }
    }
    
    @Override
    public InputStream getBlob(String path) throws IOException {
        String fullKey = buildFullKey(path);
        
        // Check local cache first if enabled
        if (localCacheDir != null) {
            Path cachedFile = getCachedFilePath(path);
            if (Files.exists(cachedFile)) {
                log.debug("Using cached file: {}", cachedFile);
                return Files.newInputStream(cachedFile);
            }
        }
        
        log.debug("Downloading S3 object: s3://{}/{}", bucketName, fullKey);
        
        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucketName)
            .key(fullKey)
            .build();
            
        try {
            CompletableFuture<ResponseBytes<GetObjectResponse>> future = 
                s3Client.getObject(request, AsyncResponseTransformer.toBytes());
            ResponseBytes<GetObjectResponse> response = future.join();
            
            // If caching is enabled, cache the file
            if (localCacheDir != null) {
                return cacheAndReturnStream(response, path);
            }
            
            // Return stream from response bytes
            return new ByteArrayInputStream(response.asByteArray());
        } catch (Exception e) {
            throw new IOException("Failed to get S3 object: s3://" + bucketName + "/" + fullKey, e);
        }
    }
    
    @Override
    public boolean exists(String path) {
        String fullKey = buildFullKey(path);
        
        // Check local cache first if enabled
        if (localCacheDir != null) {
            Path cachedFile = getCachedFilePath(path);
            if (Files.exists(cachedFile)) {
                return true;
            }
        }
        
        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(bucketName)
            .key(fullKey)
            .build();
            
        try {
            s3Client.headObject(request).join();
            return true;
        } catch (Exception e) {
            if (e.getCause() instanceof NoSuchKeyException) {
                return false;
            }
            log.warn("Error checking if S3 object exists: s3://{}/{}", bucketName, fullKey, e);
            return false;
        }
    }
    
    @Override
    public long getBlobSize(String path) throws IOException {
        String fullKey = buildFullKey(path);
        
        // Check local cache first if enabled
        if (localCacheDir != null) {
            Path cachedFile = getCachedFilePath(path);
            if (Files.exists(cachedFile)) {
                return Files.size(cachedFile);
            }
        }
        
        HeadObjectRequest request = HeadObjectRequest.builder()
            .bucket(bucketName)
            .key(fullKey)
            .build();
            
        try {
            return s3Client.headObject(request).join().contentLength();
        } catch (Exception e) {
            throw new IOException("Failed to get S3 object size: s3://" + bucketName + "/" + fullKey, e);
        }
    }
    
    @Override
    public List<String> listBlobs(String prefix) throws IOException {
        String fullPrefix = buildFullKey(prefix);
        List<String> results = new ArrayList<>();
        
        ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
            .bucket(bucketName)
            .prefix(fullPrefix);
            
        try {
            ListObjectsV2Response response;
            do {
                response = s3Client.listObjectsV2(requestBuilder.build()).join();
                
                for (S3Object s3Object : response.contents()) {
                    // Remove the key prefix to get the relative path
                    String relativePath = removeKeyPrefix(s3Object.key());
                    if (!relativePath.isEmpty()) {
                        results.add(relativePath);
                    }
                }
                
                requestBuilder.continuationToken(response.nextContinuationToken());
            } while (response.isTruncated());
            
        } catch (Exception e) {
            throw new IOException("Failed to list S3 objects with prefix: s3://" + bucketName + "/" + fullPrefix, e);
        }
        
        return results;
    }
    
    private String buildFullKey(String path) {
        if (keyPrefix.isEmpty()) {
            return path;
        }
        return keyPrefix + "/" + path;
    }
    
    private String removeKeyPrefix(String fullKey) {
        if (keyPrefix.isEmpty()) {
            return fullKey;
        }
        String prefixWithSlash = keyPrefix + "/";
        if (fullKey.startsWith(prefixWithSlash)) {
            return fullKey.substring(prefixWithSlash.length());
        }
        return fullKey;
    }
    
    private Path getCachedFilePath(String path) {
        return localCacheDir.resolve(path);
    }
    
    private InputStream cacheAndReturnStream(ResponseBytes<GetObjectResponse> responseBytes, String path) throws IOException {
        Path cachedFile = getCachedFilePath(path);
        
        // Create parent directories if needed
        Files.createDirectories(cachedFile.getParent());
        
        // Write bytes to local file
        Files.write(cachedFile, responseBytes.asByteArray());
        
        log.debug("Cached S3 object to: {}", cachedFile);
        
        // Return stream from cached file
        return Files.newInputStream(cachedFile);
    }
    
    /**
     * Close the S3 client and release resources
     */
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }
}
