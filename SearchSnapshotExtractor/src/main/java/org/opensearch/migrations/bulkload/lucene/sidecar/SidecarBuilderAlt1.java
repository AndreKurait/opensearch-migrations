package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.PriorityQueue;

import lombok.extern.slf4j.Slf4j;

/**
 * Alternative SidecarBuilder implementation: stream-into-sort-buffer, no {@code postings.raw}
 * scratch file.
 *
 * <p>The baseline {@link SidecarBuilder} takes a three-pass approach — write every tuple to
 * {@code postings.raw}, external-sort it into {@code postings.sorted}, then re-read to
 * encode into the compact sidecar. This alt fuses the first pass with the sort chunk and
 * fuses the k-way merge with the encode, so:
 *
 * <ul>
 *   <li>{@link #accept} packs each position into an in-memory sort chunk backed by three
 *       parallel primitive arrays ({@code int[] docIds}, {@code int[] positions},
 *       {@code int[] termIds}) sized to {@code sortBufferBytes / 12} records. When the
 *       chunk fills, it is sorted in-place by {@code (docId, position, termId)} via
 *       {@link PrimitiveIndirectSort} and flushed as an already-sorted run file.</li>
 *   <li>{@link #buildAndOpenReader} flushes the tail of the in-memory chunk, then runs a
 *       k-way merge that feeds tuples directly into the sidecar encoder — no intermediate
 *       {@code postings.sorted} is ever written.</li>
 * </ul>
 *
 * <p>Disk I/O per tuple:
 * <pre>
 *   baseline : 3 writes + 3 reads (postings.raw, run-files, postings.sorted)
 *   alt1     : 2 writes + 2 reads (run-files, sidecar.dat)
 * </pre>
 *
 * <p>On-disk file layout is identical to {@link SidecarBuilder}, so the returned
 * {@link SidecarReader} is bit-for-bit compatible with the baseline reader.
 *
 * <p>Thread-safety: not thread-safe. A single (segment,field) is fed by exactly one
 * producer.
 */
@Slf4j
public final class SidecarBuilderAlt1 implements PostingsSink, AutoCloseable {

    /** Fixed-width record size in run files: docId (int32) | position (int32) | termId (int32). */
    public static final int RECORD_BYTES = 12;

    private final Path spillDir;
    private final int maxDoc;
    private final int capacity;

    private final DataOutputStream termsOut;
    private final DataOutputStream termOffsetsOut;

    private final Path termsFile;
    private final Path termOffsetsFile;

    // In-memory sort chunk, parallel primitive arrays — no boxing, no ByteBuffer copy.
    private final int[] bufDocIds;
    private final int[] bufPositions;
    private final int[] bufTermIds;
    private int bufPos = 0;

    // Pre-allocated scratch for the indirect sort — reused across every chunk flush.
    private final long[] sortKeys;
    private final int[] sortIdx;

    private final List<Path> runFiles = new ArrayList<>();

    private int nextTermId = 0;
    private int currentTermOffset = 0;
    private boolean built = false;
    private boolean closed = false;

    /**
     * @param spillDir        per-(segment,field) directory owned by this builder. Created if missing.
     * @param sortBufferBytes in-memory sort chunk budget. Values &lt; {@link #RECORD_BYTES} are
     *                        clamped up to {@link #RECORD_BYTES} (one record per chunk — degenerate
     *                        but valid; causes many tiny run files).
     * @param maxDoc          the segment's {@code maxDoc}, used to size the doc-index output.
     */
    public SidecarBuilderAlt1(Path spillDir, int sortBufferBytes, int maxDoc) throws IOException {
        this.spillDir = spillDir;
        int cap = Math.max(RECORD_BYTES, sortBufferBytes) / RECORD_BYTES;
        this.capacity = Math.max(1, cap);
        this.maxDoc = Math.max(1, maxDoc);
        Files.createDirectories(spillDir);
        this.termsFile = spillDir.resolve(SidecarBuilder.TERMS_FILE);
        this.termOffsetsFile = spillDir.resolve(SidecarBuilder.TERM_OFFSETS_FILE);
        this.termsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.termOffsetsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termOffsetsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.bufDocIds = new int[capacity];
        this.bufPositions = new int[capacity];
        this.bufTermIds = new int[capacity];
        this.sortKeys = new long[capacity];
        this.sortIdx = new int[capacity];
    }

    @Override
    public int registerTerm(BytesRefLike term) throws IOException {
        int id = nextTermId++;
        // Little-endian int[] in term-offsets.dat; DataOutputStream writes big-endian by default.
        termOffsetsOut.writeInt(Integer.reverseBytes(currentTermOffset));
        termsOut.writeInt(term.length());
        termsOut.write(term.bytes(), term.offset(), term.length());
        currentTermOffset += 4 + term.length();
        return id;
    }

    @Override
    public void accept(int termId, int docId, int[] positions, int positionCount) throws IOException {
        for (int i = 0; i < positionCount; i++) {
            if (bufPos == capacity) {
                flushBufferAsRun();
            }
            bufDocIds[bufPos] = docId;
            bufPositions[bufPos] = positions[i];
            bufTermIds[bufPos] = termId;
            bufPos++;
        }
    }

    /**
     * Sorts the in-memory chunk in place by {@code (docId, position, termId)} and writes it
     * out as a new run file of 12-byte big-endian records. After this call, {@code bufPos} is
     * reset to 0 and the parallel arrays are available for the next chunk.
     */
    private void flushBufferAsRun() throws IOException {
        int n = bufPos;
        if (n == 0) return;
        for (int i = 0; i < n; i++) {
            // Same sort-key packing as baseline sortChunkInPlace: (docId<<32) | position.
            sortKeys[i] = (((long) bufDocIds[i]) << 32) | (bufPositions[i] & 0xffffffffL);
            sortIdx[i] = i;
        }
        PrimitiveIndirectSort.sort(sortIdx, 0, n, sortKeys, bufTermIds);

        Path runFile = Files.createTempFile(spillDir, "run-", ".tmp");
        byte[] writeBuf = new byte[Math.min(n, 8192) * RECORD_BYTES];
        ByteBuffer bb = ByteBuffer.wrap(writeBuf).order(ByteOrder.BIG_ENDIAN);
        try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(runFile))) {
            for (int i = 0; i < n; i++) {
                int src = sortIdx[i];
                if (bb.remaining() < RECORD_BYTES) {
                    out.write(writeBuf, 0, bb.position());
                    bb.clear();
                }
                bb.putInt(bufDocIds[src]);
                bb.putInt(bufPositions[src]);
                bb.putInt(bufTermIds[src]);
            }
            if (bb.position() > 0) {
                out.write(writeBuf, 0, bb.position());
            }
        }
        runFiles.add(runFile);
        bufPos = 0;
    }

    /**
     * Finalizes: flushes the tail of the in-memory chunk as a final run, k-way merges all
     * runs directly into the compact sidecar encoder, deletes run files, and returns an
     * open {@link SidecarReader}.
     */
    public SidecarReader buildAndOpenReader() throws IOException {
        if (built) throw new IllegalStateException("SidecarBuilderAlt1.buildAndOpenReader already called");
        built = true;

        termsOut.flush();
        termsOut.close();
        termOffsetsOut.flush();
        termOffsetsOut.close();

        if (bufPos > 0) {
            flushBufferAsRun();
        }

        Path sidecarFile = spillDir.resolve(SidecarBuilder.SIDECAR_FILE);
        Path docIndexFile = spillDir.resolve(SidecarBuilder.DOC_INDEX_FILE);
        try {
            mergeAndEncode(runFiles, sidecarFile, docIndexFile, maxDoc);
        } finally {
            for (Path r : runFiles) tryDelete(r);
            runFiles.clear();
        }

        closed = true;
        return SidecarReader.open(spillDir, maxDoc, nextTermId);
    }

    @Override
    public void close() {
        if (closed) return;
        // Abort path — only reached if buildAndOpenReader() wasn't called.
        closeQuietly(termsOut);
        closeQuietly(termOffsetsOut);
        tryDelete(termsFile);
        tryDelete(termOffsetsFile);
        for (Path r : runFiles) tryDelete(r);
        runFiles.clear();
        closed = true;
    }

    // --------------------------------------------------------------------------------
    // Fused k-way merge + encode
    // --------------------------------------------------------------------------------

    /**
     * K-way merges the (already sorted) run files into the compact sidecar encoder in a
     * single pass. Each tuple polled from the min-heap is fed straight into the per-doc
     * varint buffer — there is no intermediate {@code postings.sorted} file.
     */
    private static void mergeAndEncode(List<Path> runs, Path sidecarDst, Path docIndexDst, int maxDoc)
            throws IOException {
        long[] docOffsets = new long[maxDoc];
        Arrays.fill(docOffsets, SidecarBuilder.DOC_INDEX_NO_TOKENS);

        try (CloseableStreams streams = new CloseableStreams(runs);
             FileChannel sideCh = FileChannel.open(sidecarDst,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            PriorityQueue<RunHead> heap = new PriorityQueue<>();
            for (int i = 0; i < streams.size(); i++) {
                RunHead h = RunHead.read(i, streams.get(i));
                if (h != null) heap.add(h);
            }

            ByteBuffer perDoc = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            long sidecarOffset = 0;
            int pendingDocId = -1;
            int pendingEntries = 0;
            int pendingPrevPos = 0;

            while (!heap.isEmpty()) {
                RunHead top = heap.poll();
                int docId = top.docId;
                int position = top.position;
                int termId = top.termId;

                if (docId != pendingDocId) {
                    if (pendingDocId >= 0) {
                        sidecarOffset = flushPerDocBuffer(sideCh, perDoc, header,
                                pendingDocId, pendingEntries, docOffsets, sidecarOffset);
                    }
                    pendingDocId = docId;
                    pendingEntries = 0;
                    pendingPrevPos = 0;
                    perDoc.clear();
                }
                perDoc = ensureCapacity(perDoc, 10); // two 5-byte uvints max
                VarintCoder.writeUVInt(perDoc, position - pendingPrevPos);
                VarintCoder.writeUVInt(perDoc, termId);
                pendingPrevPos = position;
                pendingEntries++;

                RunHead next = RunHead.read(top.runIdx, streams.get(top.runIdx));
                if (next != null) heap.add(next);
            }
            if (pendingDocId >= 0) {
                flushPerDocBuffer(sideCh, perDoc, header,
                        pendingDocId, pendingEntries, docOffsets, sidecarOffset);
            }
        }

        writeDocIndex(docIndexDst, docOffsets, maxDoc);
    }

    private static long flushPerDocBuffer(FileChannel sideCh, ByteBuffer perDoc, ByteBuffer header,
                                          int docId, int numEntries, long[] docOffsets,
                                          long sidecarOffset) throws IOException {
        docOffsets[docId] = sidecarOffset;
        header.clear();
        VarintCoder.writeUVInt(header, numEntries);
        header.flip();
        int headerBytes = header.remaining();
        while (header.hasRemaining()) sideCh.write(header);
        perDoc.flip();
        int payloadBytes = perDoc.remaining();
        while (perDoc.hasRemaining()) sideCh.write(perDoc);
        return sidecarOffset + headerBytes + payloadBytes;
    }

    private static void writeDocIndex(Path docIndexDst, long[] docOffsets, int maxDoc) throws IOException {
        try (FileChannel ch = FileChannel.open(docIndexDst,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(8192).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < maxDoc; i++) {
                if (!buf.hasRemaining()) {
                    buf.flip();
                    while (buf.hasRemaining()) ch.write(buf);
                    buf.clear();
                }
                buf.putLong(docOffsets[i]);
            }
            buf.flip();
            while (buf.hasRemaining()) ch.write(buf);
        }
    }

    private static ByteBuffer ensureCapacity(ByteBuffer buf, int additional) {
        if (buf.remaining() >= additional) return buf;
        int needed = buf.position() + additional;
        int newCap = buf.capacity();
        while (newCap < needed) newCap *= 2;
        ByteBuffer bigger = ByteBuffer.allocate(newCap).order(buf.order());
        buf.flip();
        bigger.put(buf);
        return bigger;
    }

    // --------------------------------------------------------------------------------
    // Shared helpers (copies of the run-file plumbing used by baseline's external sort)
    // --------------------------------------------------------------------------------

    private static final class CloseableStreams implements AutoCloseable {
        private final List<DataInputStream> streams = new ArrayList<>();

        CloseableStreams(List<Path> runs) throws IOException {
            try {
                for (Path r : runs) {
                    streams.add(new DataInputStream(new BufferedInputStream(Files.newInputStream(r))));
                }
            } catch (IOException e) {
                close();
                throw e;
            }
        }

        int size() { return streams.size(); }

        DataInputStream get(int i) { return streams.get(i); }

        @Override
        public void close() {
            IOException first = null;
            for (DataInputStream s : streams) {
                try {
                    s.close();
                } catch (IOException e) {
                    if (first == null) first = e;
                    else first.addSuppressed(e);
                }
            }
            if (first != null) {
                log.debug("Ignored close errors during k-way merge cleanup: {}", first.toString());
            }
        }
    }

    private static final class RunHead implements Comparable<RunHead> {
        final int runIdx;
        final int docId;
        final int position;
        final int termId;

        RunHead(int runIdx, int docId, int position, int termId) {
            this.runIdx = runIdx;
            this.docId = docId;
            this.position = position;
            this.termId = termId;
        }

        static RunHead read(int runIdx, DataInputStream in) throws IOException {
            try {
                int d = in.readInt();
                int p = in.readInt();
                int t = in.readInt();
                return new RunHead(runIdx, d, p, t);
            } catch (java.io.EOFException eof) {
                return null;
            }
        }

        @Override
        public int compareTo(RunHead o) {
            int c = Integer.compare(docId, o.docId);
            if (c != 0) return c;
            c = Integer.compare(position, o.position);
            if (c != 0) return c;
            return Integer.compare(termId, o.termId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RunHead)) return false;
            RunHead o = (RunHead) obj;
            // runIdx participates in identity so distinct heap entries from different runs
            // are never collapsed — consistent with PriorityQueue's compareTo-based ordering.
            return docId == o.docId
                    && position == o.position
                    && termId == o.termId
                    && runIdx == o.runIdx;
        }

        @Override
        public int hashCode() {
            int h = docId;
            h = 31 * h + position;
            h = 31 * h + termId;
            h = 31 * h + runIdx;
            return h;
        }
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            log.debug("Ignored close error during SidecarBuilderAlt1 abort: {}", e.toString());
        }
    }

    private static void tryDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete spill file {}: {}", p, e.toString());
        }
    }
}
