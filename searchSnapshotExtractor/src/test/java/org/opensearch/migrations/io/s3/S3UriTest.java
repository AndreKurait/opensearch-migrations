package org.opensearch.migrations.io.s3;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class S3UriTest {

    @Test
    void testParseBasicS3Uri() {
        S3Uri uri = new S3Uri("s3://my-bucket/path/to/object");
        
        assertEquals("my-bucket", uri.getBucketName());
        assertEquals("path/to/object", uri.getKey());
        assertEquals("s3://my-bucket/path/to/object", uri.getUri());
    }
    
    @Test
    void testParseS3UriWithTrailingSlash() {
        S3Uri uri = new S3Uri("s3://my-bucket/path/to/object/");
        
        assertEquals("my-bucket", uri.getBucketName());
        assertEquals("path/to/object", uri.getKey());
        assertEquals("s3://my-bucket/path/to/object", uri.getUri());
    }
    
    @Test
    void testParseS3UriWithoutKey() {
        S3Uri uri = new S3Uri("s3://my-bucket");
        
        assertEquals("my-bucket", uri.getBucketName());
        assertEquals("", uri.getKey());
        assertEquals("s3://my-bucket", uri.getUri());
    }
    
    @Test
    void testParseS3UriWithEmptyKey() {
        S3Uri uri = new S3Uri("s3://my-bucket/");
        
        assertEquals("my-bucket", uri.getBucketName());
        assertEquals("", uri.getKey());
        assertEquals("s3://my-bucket", uri.getUri());
    }
    
    @Test
    void testCreateFromComponents() {
        S3Uri uri = new S3Uri("my-bucket", "path/to/object");
        
        assertEquals("my-bucket", uri.getBucketName());
        assertEquals("path/to/object", uri.getKey());
        assertEquals("s3://my-bucket/path/to/object", uri.getUri());
    }
    
    @Test
    void testInvalidUri() {
        assertThrows(IllegalArgumentException.class, () -> {
            new S3Uri("http://my-bucket/path");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new S3Uri("not-a-uri");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new S3Uri(null, "key");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new S3Uri("", "key");
        });
        
        assertThrows(IllegalArgumentException.class, () -> {
            new S3Uri("  ", "key");
        });
    }
    
    @Test
    void testToString() {
        S3Uri uri = new S3Uri("s3://my-bucket/path/to/object");
        String toString = uri.toString();
        
        assertTrue(toString.contains("my-bucket"));
        assertTrue(toString.contains("path/to/object"));
    }
}
