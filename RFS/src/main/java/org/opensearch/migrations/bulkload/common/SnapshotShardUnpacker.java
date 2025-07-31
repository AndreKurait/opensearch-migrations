package org.opensearch.migrations.bulkload.common;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;

import org.opensearch.migrations.bulkload.models.ShardFileInfo;
import org.opensearch.migrations.bulkload.models.ShardMetadata;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import shadow.lucene9.org.apache.lucene.store.Directory;
import shadow.lucene9.org.apache.lucene.store.FSDirectory;
import shadow.lucene9.org.apache.lucene.store.IOContext;
import shadow.lucene9.org.apache.lucene.store.IndexOutput;
import shadow.lucene9.org.apache.lucene.store.LockFactory;
import shadow.lucene9.org.apache.lucene.store.MMapDirectory;
import shadow.lucene9.org.apache.lucene.store.NativeFSLockFactory;
import shadow.lucene9.org.apache.lucene.util.BytesRef;

@RequiredArgsConstructor
@Slf4j
public class SnapshotShardUnpacker {
    private final SourceRepoAccessor repoAccessor;
    private final Path luceneFilesBasePath;
    private final ShardMetadata shardMetadata;
    private final int bufferSize;

    @RequiredArgsConstructor
    public static class Factory {
        private final SourceRepoAccessor repoAccessor;
        private final Path luceneFilesBasePath;
        private final int bufferSize;

        public SnapshotShardUnpacker create(ShardMetadata shardMetadata) {
            return new SnapshotShardUnpacker(repoAccessor, luceneFilesBasePath, shardMetadata, bufferSize);
        }
    }

    public Path unpack() {
        try {
            // Ensure the blob files are prepped, if they need to be
            repoAccessor.prepBlobFiles(shardMetadata);

            // Create the directory for the shard's lucene files
            Path luceneIndexDir = Paths.get(
                luceneFilesBasePath + "/" + shardMetadata.getIndexName() + "/" + shardMetadata.getShardId()
            );
            Files.createDirectories(luceneIndexDir);

            int unpackParallelization = Math.max(Runtime.getRuntime().availableProcessors(), 1);
            AtomicInteger id = new AtomicInteger(0);

            // Thread-local FSDirectory (one per thread)
            ThreadLocal<Directory> threadLocalDirectory = ThreadLocal.withInitial(() -> {
                try {
                    return FSDirectory.open(luceneIndexDir);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });

            // Custom scheduler with thread-local cleanup
            var unpackingScheduler = Schedulers.newBoundedElastic(
                unpackParallelization,
                Integer.MAX_VALUE,
                r -> new Thread(() -> {
                    try {
                        r.run();
                    } finally {
                        try {
                            Directory dir = threadLocalDirectory.get();
                            if (dir != null) dir.close();
                        } catch (IOException e) {
                            // optional: log error
                        } finally {
                            threadLocalDirectory.remove();
                        }
                    }
                }, "transformationThread-" + id.incrementAndGet()),
                60
            );

            // Perform unpacking in parallel
            Flux.fromIterable(shardMetadata.getFiles())
                .flatMap(fileMetadata -> Mono.fromRunnable(() -> {
                    Directory directory = threadLocalDirectory.get();

                    log.atInfo().setMessage("Unpacking - Blob Name: {}, Lucene Name: {}")
                        .addArgument(fileMetadata::getName)
                        .addArgument(fileMetadata::getPhysicalName)
                        .log();

                    try (IndexOutput indexOutput = directory.createOutput(
                        fileMetadata.getPhysicalName(),
                        IOContext.READONCE)) {

                        if (fileMetadata.getName().startsWith("v__")) {
                            BytesRef hash = fileMetadata.getMetaHash();
                            indexOutput.writeBytes(hash.bytes, hash.offset, hash.length);
                        } else {
                            try (InputStream stream = new PartSliceStream(
                                repoAccessor,
                                fileMetadata,
                                shardMetadata.getIndexId(),
                                shardMetadata.getShardId())) {

                                byte[] buffer = new byte[Math.toIntExact(
                                    Math.min(bufferSize, fileMetadata.getLength()))];
                                int length;
                                while ((length = stream.read(buffer)) > 0) {
                                    indexOutput.writeBytes(buffer, 0, length);
                                }
                            }
                        }
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }).subscribeOn(unpackingScheduler), unpackParallelization)
                .subscribeOn(unpackingScheduler)
                .then()
                .block();
            return luceneIndexDir;
        } catch (Exception e) {
            throw new CouldNotUnpackShard(
                "Could not unpack shard: Index " + shardMetadata.getIndexId() + ", Shard " + shardMetadata.getShardId(),
                e
            );
        }
    }

    public static class CouldNotUnpackShard extends RfsException {
        public CouldNotUnpackShard(String message, Exception e) {
            super(message, e);
        }
    }

}
