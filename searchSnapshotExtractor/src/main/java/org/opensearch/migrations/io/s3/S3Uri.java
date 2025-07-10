package org.opensearch.migrations.io.s3;

import java.net.URI;

import lombok.ToString;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Utilities;

/**
 * Utility class for parsing and working with S3 URIs using AWS SDK S3Utilities
 */
@ToString
public class S3Uri {
    public final String bucketName;
    public final String key;
    public final String uri;

    /**
     * Parse an S3 URI string into its components using AWS SDK S3Utilities
     * @param rawUri the S3 URI string (e.g., "s3://bucket/path/to/object")
     */
    public S3Uri(String rawUri) {
        if (!rawUri.startsWith("s3://")) {
            throw new IllegalArgumentException("URI must start with s3://: " + rawUri);
        }
        
        try {
            URI parsedUri = URI.create(rawUri);
            // Use a default region for URI parsing - the region doesn't affect URI parsing logic
            S3Utilities s3Utilities = S3Utilities.builder()
                .region(Region.US_EAST_1)
                .build();
            
            // Parse the S3 URI using AWS SDK utilities
            var parsedS3Uri = s3Utilities.parseUri(parsedUri);
            
            this.bucketName = parsedS3Uri.bucket().orElseThrow(
                () -> new IllegalArgumentException("No bucket found in S3 URI: " + rawUri)
            );
            
            // Handle key - remove trailing slash if present
            String rawKey = parsedS3Uri.key().orElse("");
            if (!rawKey.isEmpty() && rawKey.endsWith("/")) {
                rawKey = rawKey.substring(0, rawKey.length() - 1);
            }
            this.key = rawKey;
            
            // Normalize the URI (remove trailing slash)
            this.uri = rawUri.endsWith("/") ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid S3 URI: " + rawUri, e);
        }
    }
    
    /**
     * Create an S3Uri from bucket and key components
     * @param bucketName the S3 bucket name
     * @param key the S3 object key
     */
    public S3Uri(String bucketName, String key) {
        if (bucketName == null || bucketName.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket name cannot be null or empty");
        }
        
        this.bucketName = bucketName;
        this.key = key != null ? key : "";
        this.uri = "s3://" + bucketName + (this.key.isEmpty() ? "" : "/" + this.key);
    }
    
    /**
     * Get the full S3 URI string
     * @return the S3 URI string
     */
    public String getUri() {
        return uri;
    }
    
    /**
     * Get the bucket name
     * @return the bucket name
     */
    public String getBucketName() {
        return bucketName;
    }
    
    /**
     * Get the object key
     * @return the object key
     */
    public String getKey() {
        return key;
    }
}
