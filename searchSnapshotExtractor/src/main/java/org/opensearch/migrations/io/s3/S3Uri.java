package org.opensearch.migrations.io.s3;

import java.net.URI;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Utilities;

/**
 * S3 URI utility for parsing and working with S3 URIs
 */
@Builder
public record S3Uri(
    @JsonProperty("bucketName") String bucketName,
    @JsonProperty("key") String key,
    @JsonProperty("uri") String uri
) {
    
    /**
     * Parse an S3 URI string into its components
     */
    public S3Uri(String rawUri) {
        this(parseS3Uri(rawUri));
    }
    
    /**
     * Create an S3Uri from bucket and key components
     */
    public S3Uri(String bucketName, String key) {
        this(
            validateBucketName(bucketName),
            key != null ? key : "",
            "s3://" + validateBucketName(bucketName) + ((key != null && !key.isEmpty()) ? "/" + key : "")
        );
    }
    
    private S3Uri(ParsedS3Uri parsed) {
        this(parsed.bucketName, parsed.key, parsed.uri);
    }
    
    public String getUri() {
        return uri;
    }
    
    public String getBucketName() {
        return bucketName;
    }
    
    public String getKey() {
        return key;
    }
    
    private static String validateBucketName(String bucketName) {
        if (bucketName == null || bucketName.trim().isEmpty()) {
            throw new IllegalArgumentException("Bucket name cannot be null or empty");
        }
        return bucketName;
    }
    
    private static ParsedS3Uri parseS3Uri(String rawUri) {
        if (!rawUri.startsWith("s3://")) {
            throw new IllegalArgumentException("URI must start with s3://: " + rawUri);
        }
        
        try {
            URI parsedUri = URI.create(rawUri);
            S3Utilities s3Utilities = S3Utilities.builder()
                .region(Region.US_EAST_1)
                .build();
            
            var parsedS3Uri = s3Utilities.parseUri(parsedUri);
            
            String bucketName = parsedS3Uri.bucket().orElseThrow(
                () -> new IllegalArgumentException("No bucket found in S3 URI: " + rawUri)
            );
            
            String rawKey = parsedS3Uri.key().orElse("");
            if (!rawKey.isEmpty() && rawKey.endsWith("/")) {
                rawKey = rawKey.substring(0, rawKey.length() - 1);
            }
            
            String normalizedUri = rawUri.endsWith("/") ? rawUri.substring(0, rawUri.length() - 1) : rawUri;
            
            return new ParsedS3Uri(bucketName, rawKey, normalizedUri);
            
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid S3 URI: " + rawUri, e);
        }
    }
    
    private record ParsedS3Uri(String bucketName, String key, String uri) {}
}
