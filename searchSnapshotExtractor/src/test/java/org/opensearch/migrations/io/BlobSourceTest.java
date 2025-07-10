package org.opensearch.migrations.io;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.*;

class BlobSourceTest {

    @TempDir
    Path tempDir;
    
    private FileBlobSource fileBlobSource;
    
    @BeforeEach
    void setUp() throws IOException {
        // Create test files
        Path testFile1 = tempDir.resolve("test1.txt");
        Path testFile2 = tempDir.resolve("subdir/test2.txt");
        
        Files.createDirectories(testFile2.getParent());
        Files.write(testFile1, "Hello World".getBytes());
        Files.write(testFile2, "Hello from subdirectory".getBytes());
        
        fileBlobSource = new FileBlobSource(tempDir);
    }
    
    @Test
    void testGetBlob() throws IOException {
        try (InputStream stream = fileBlobSource.getBlob("test1.txt")) {
            String content = new String(stream.readAllBytes());
            assertEquals("Hello World", content);
        }
    }
    
    @Test
    void testGetBlobFromSubdirectory() throws IOException {
        try (InputStream stream = fileBlobSource.getBlob("subdir/test2.txt")) {
            String content = new String(stream.readAllBytes());
            assertEquals("Hello from subdirectory", content);
        }
    }
    
    @Test
    void testExists() {
        assertTrue(fileBlobSource.exists("test1.txt"));
        assertTrue(fileBlobSource.exists("subdir/test2.txt"));
        assertFalse(fileBlobSource.exists("nonexistent.txt"));
    }
    
    @Test
    void testGetBlobSize() throws IOException {
        assertEquals("Hello World".length(), fileBlobSource.getBlobSize("test1.txt"));
        assertEquals("Hello from subdirectory".length(), fileBlobSource.getBlobSize("subdir/test2.txt"));
    }
    
    @Test
    void testListBlobs() throws IOException {
        List<String> allBlobs = fileBlobSource.listBlobs("");
        assertEquals(2, allBlobs.size());
        assertTrue(allBlobs.contains("test1.txt"));
        assertTrue(allBlobs.contains("subdir/test2.txt"));
        
        List<String> subdirBlobs = fileBlobSource.listBlobs("subdir");
        assertEquals(1, subdirBlobs.size());
        assertTrue(subdirBlobs.contains("subdir/test2.txt"));
    }
    
    @Test
    void testGetBlobNonExistent() {
        assertThrows(IOException.class, () -> {
            fileBlobSource.getBlob("nonexistent.txt");
        });
    }
    
    @Test
    void testGetBlobSizeNonExistent() {
        assertThrows(IOException.class, () -> {
            fileBlobSource.getBlobSize("nonexistent.txt");
        });
    }
}
