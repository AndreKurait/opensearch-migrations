package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
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

import lombok.extern.slf4j.Slf4j;

/**
 * Builds a per-field "sidecar" on disk that answers {@code docId -> List<String> (position order)}
 * via {@link SidecarReader} using mmap'd binary search instead of eager heap materialization.
 *
 * <p>Flow (one builder per (segment, field)):
 *
 * <ol>
 *   <li>{@link #registerTerm(BytesRefLike)} — call once per distinct term in ascending-bytes
 *       order (the natural walk order of Lucene's {@code TermsEnum}). Returns the assigned
 *       {@code termId}. Term bytes are streamed straight to {@code terms.dat}; no heap retention.
 *   <li>{@link #accept(int, int, int[], int)} — the {@link PostingsSink} callback. For each
 *       {@code (termId, docId, positions[0..len])} tuple, appends {@code len} fixed-width 12-byte
 *       records {@code (docId, position, termId)} to {@code postings.raw}.
 *   <li>{@link #buildAndOpenReader()} — performs an external merge sort of {@code postings.raw}
 *       by {@code (docId ASC, position ASC, termId ASC)}, converts the sorted stream into the
 *       compact varint-encoded {@code sidecar.dat} plus a mmap'd {@code doc-index.dat} lookup
 *       table, deletes the scratch files, and returns a {@link SidecarReader}.
 * </ol>
 *
 * <p>Heap bound per builder:
 *   <ul>
 *     <li>No {@code ArrayList<Integer>}. Term offsets are written straight to {@code term-offsets.dat};
 *         the builder keeps only {@code currentTermOffset} (one long) and {@code currentTermId}
 *         (one int).
 *     <li>The in-memory sort chunk uses three parallel primitive arrays totaling
 *         {@code 16 bytes per record}: {@code long[] keys}, {@code int[] termIds},
 *         {@code int[] indices} (indirect sort to avoid boxing). No {@code Integer[]}.
 *     <li>Term-bytes append uses a fixed 4 KiB staging buffer — no growing byte buffer.
 *   </ul>
 *
 * <p>Disk layout written on build:
 * <pre>
 *   terms.dat          — length-prefixed UTF-8 bytes, one per term, in termId order
 *   term-offsets.dat   — little-endian int[numTerms] of byte offsets into terms.dat
 *   sidecar.dat        — header + varint-compact per-doc posting lists (see {@link SidecarReader})
 *   doc-index.dat      — long[maxDoc] of absolute offsets into sidecar.dat, or -1 sentinel
 * </pre>
 *
 * <p>Thread-safety: not thread-safe. A single (segment,field) is fed by exactly one producer
 * (the Lucene leaf reader's {@code streamFieldPostings}).
 */
@Slf4j
public final class SidecarBuilder implements PostingsSink, AutoCloseable {

    /** Fixed-width layout of each scratch tuple: docId (int32) | position (int32) | termId (int32). */
    public static final int RECORD_BYTES = 12;

    /** Sentinel written into doc-index.dat for docs that have no tokens in this field. */
    static final long DOC_INDEX_NO_TOKENS = -1L;

    /** File names — package-private so {@link SidecarReader} can find them. */
    static final String TERMS_FILE            = "terms.dat";
    static final String TERM_OFFSETS_FILE     = "term-offsets.dat";
    static final String SIDECAR_FILE          = "sidecar.dat";
    static final String DOC_INDEX_FILE        = "doc-index.dat";
    static final String RAW_FILE              = "postings.raw";

    private final Path spillDir;
    private final int sortBufferBytes;
    private final int maxDoc;

    private final DataOutputStream termsOut;
    private final DataOutputStream termOffsetsOut;
    private final DataOutputStream rawOut;

    private final Path termsFile;
    private final Path termOffsetsFile;
    private final Path rawFile;

    private int nextTermId = 0;
    private int currentTermOffset = 0;
    private long tuplesWritten = 0;
    private boolean built = false;
    private boolean closed = false;

    /**
     * @param spillDir        per-(segment,field) directory owned by this builder. Created if missing.
     * @param sortBufferBytes in-memory sort chunk budget. Values &lt; {@link #RECORD_BYTES} are clamped
     *                        up to {@link #RECORD_BYTES} (one record per chunk — degenerate but valid).
     * @param maxDoc          the segment's {@code maxDoc}, used to size the doc-index output.
     */
    public SidecarBuilder(Path spillDir, int sortBufferBytes, int maxDoc) throws IOException {
        this.spillDir = spillDir;
        this.sortBufferBytes = Math.max(RECORD_BYTES, sortBufferBytes);
        this.maxDoc = Math.max(1, maxDoc);
        Files.createDirectories(spillDir);
        this.termsFile = spillDir.resolve(TERMS_FILE);
        this.termOffsetsFile = spillDir.resolve(TERM_OFFSETS_FILE);
        this.rawFile = spillDir.resolve(RAW_FILE);
        this.termsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.termOffsetsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termOffsetsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.rawOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(rawFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
    }

    /**
     * Registers a term and returns its assigned {@code termId}. Must be called in
     * ascending-bytes order — the order produces a sorted term dictionary and lets the
     * reader binary-search by term bytes if ever needed.
     */
    public int registerTerm(BytesRefLike term) throws IOException {
        int id = nextTermId++;
        termOffsetsOut.writeInt(Integer.reverseBytes(currentTermOffset)); // little-endian
        termsOut.writeInt(term.length());
        termsOut.write(term.bytes(), term.offset(), term.length());
        currentTermOffset += 4 + term.length();
        return id;
    }

    /**
     * Appends the {@code (termId, docId, positions[])} tuple to the scratch file as {@code len}
     * fixed-width 12-byte records. Caller is responsible for:
     * <ul>
     *   <li>{@code termId} having been returned by a prior {@link #registerTerm} call;
     *   <li>positions in ASCII within this call — the external sort enforces global order,
     *       but keeping positions monotonic per call slightly improves locality on write.
     * </ul>
     */
    @Override
    public void accept(int termId, int docId, int[] positions, int positionCount) throws IOException {
        for (int i = 0; i < positionCount; i++) {
            rawOut.writeInt(docId);
            rawOut.writeInt(positions[i]);
            rawOut.writeInt(termId);
            tuplesWritten++;
        }
    }

    /**
     * Finalizes the builder: flushes scratch files, runs external merge sort, converts the sorted
     * tuple stream into the compact {@code sidecar.dat} + {@code doc-index.dat}, deletes scratch
     * files, and returns an open {@link SidecarReader}.
     */
    public SidecarReader buildAndOpenReader() throws IOException {
        if (built) throw new IllegalStateException("SidecarBuilder.buildAndOpenReader already called");
        built = true;

        // Flush scratch files — must be closed before sort reads them.
        termsOut.flush();
        termsOut.close();
        termOffsetsOut.flush();
        termOffsetsOut.close();
        rawOut.flush();
        rawOut.close();

        Path sortedFile = spillDir.resolve("postings.sorted");
        if (tuplesWritten == 0) {
            Files.createFile(sortedFile);
        } else {
            externalSort(rawFile, sortedFile, sortBufferBytes);
        }
        Files.deleteIfExists(rawFile);

        Path sidecarFile  = spillDir.resolve(SIDECAR_FILE);
        Path docIndexFile = spillDir.resolve(DOC_INDEX_FILE);
        encodeSidecar(sortedFile, sidecarFile, docIndexFile, maxDoc);
        Files.deleteIfExists(sortedFile);

        closed = true;
        return SidecarReader.open(spillDir, maxDoc, nextTermId);
    }

    @Override
    public void close() {
        if (closed) return;
        // Abort path — only reached if buildAndOpenReader() wasn't called (e.g. producer threw).
        closeQuietly(termsOut);
        closeQuietly(termOffsetsOut);
        closeQuietly(rawOut);
        tryDelete(termsFile);
        tryDelete(termOffsetsFile);
        tryDelete(rawFile);
        closed = true;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            // Abort path — best-effort close; downstream tryDelete calls still run.
            log.debug("Ignored close error during SidecarBuilder abort: {}", e.toString());
        }
    }

    // --------------------------------------------------------------------------------
    // External sort (primitive arrays, no boxing)
    // --------------------------------------------------------------------------------

    /**
     * Sorts {@code src} (sequence of 12-byte {@code (docId, position, termId)} records in
     * arbitrary order) into {@code dst} keyed by {@code (docId ASC, position ASC, termId ASC)}.
     *
     * <p>Algorithm: read chunks of up to {@code bufferBytes} records, sort each chunk
     * in-place using indirect sort over parallel primitive arrays (no {@code Integer[]},
     * no autoboxing), write as a run file; k-way merge all runs with a min-heap into
     * {@code dst}. Peak heap during a sort chunk is {@code 16 bytes per record} vs
     * the old implementation's {@code ~60 bytes per record} (which included a boxed
     * {@code Integer[]} indirection array — the dominant contributor to the 64 GB OOM).
     */
    static void externalSort(Path src, Path dst, int bufferBytes) throws IOException {
        int recordsPerChunk = Math.max(1, bufferBytes / RECORD_BYTES);
        List<Path> runs = new ArrayList<>();
        try (InputStream in = new BufferedInputStream(Files.newInputStream(src))) {
            byte[] chunk = new byte[recordsPerChunk * RECORD_BYTES];
            boolean more = true;
            while (more) {
                int read = readFully(in, chunk, 0, chunk.length);
                if (read == 0) {
                    more = false;
                } else {
                    int records = read / RECORD_BYTES;
                    sortChunkInPlace(chunk, records);
                    Path runFile = Files.createTempFile(src.getParent(), "run-", ".tmp");
                    try (OutputStream out = new BufferedOutputStream(Files.newOutputStream(runFile))) {
                        out.write(chunk, 0, records * RECORD_BYTES);
                    }
                    runs.add(runFile);
                    if (read < chunk.length) more = false;
                }
            }
        }
        try {
            kWayMerge(runs, dst);
        } finally {
            for (Path r : runs) tryDelete(r);
        }
    }

    private static int readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        int total = 0;
        while (total < len) {
            int n = in.read(buf, off + total, len - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    /**
     * Sorts {@code numRecords} 12-byte records in {@code chunk} in-place by
     * {@code (docId, position, termId)} using primitive parallel arrays.
     *
     * <p>Key packing: {@code keys[i] = (docId<<32) | (position & 0xffffffffL)}. The sort is
     * indirect — we sort an {@code int[] idx} array whose entries reference positions in
     * the other primitive arrays. We avoid {@code Integer[] idx} (which would cost ~20 B/entry
     * via autoboxing) by dispatching to {@link PrimitiveIndirectSort#sort} which does an
     * iterative quicksort over a primitive {@code int[]} index.
     */
    private static void sortChunkInPlace(byte[] chunk, int numRecords) {
        long[] keys = new long[numRecords];
        int[] termIds = new int[numRecords];
        int[] idx = new int[numRecords];
        ByteBuffer bb = ByteBuffer.wrap(chunk, 0, numRecords * RECORD_BYTES).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numRecords; i++) {
            int docId = bb.getInt();
            int position = bb.getInt();
            int termId = bb.getInt();
            keys[i] = (((long) docId) << 32) | (position & 0xffffffffL);
            termIds[i] = termId;
            idx[i] = i;
        }
        PrimitiveIndirectSort.sort(idx, 0, numRecords, keys, termIds);

        byte[] scratch = new byte[numRecords * RECORD_BYTES];
        ByteBuffer out = ByteBuffer.wrap(scratch).order(ByteOrder.BIG_ENDIAN);
        for (int i = 0; i < numRecords; i++) {
            long k = keys[idx[i]];
            int docId = (int) (k >>> 32);
            int position = (int) (k & 0xffffffffL);
            int termId = termIds[idx[i]];
            out.putInt(docId);
            out.putInt(position);
            out.putInt(termId);
        }
        System.arraycopy(scratch, 0, chunk, 0, numRecords * RECORD_BYTES);
    }

    /** K-way merge — each run already sorted by (docId, position, termId). */
    private static void kWayMerge(List<Path> runs, Path dst) throws IOException {
        try (CloseableStreams streams = new CloseableStreams(runs)) {
            java.util.PriorityQueue<RunHead> heap = new java.util.PriorityQueue<>();
            for (int i = 0; i < streams.size(); i++) {
                RunHead h = RunHead.read(i, streams.get(i));
                if (h != null) heap.add(h);
            }
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(dst)))) {
                while (!heap.isEmpty()) {
                    RunHead top = heap.poll();
                    out.writeInt(top.docId);
                    out.writeInt(top.position);
                    out.writeInt(top.termId);
                    RunHead next = RunHead.read(top.runIdx, streams.get(top.runIdx));
                    if (next != null) heap.add(next);
                }
            }
        }
    }

    /**
     * Owns a list of {@link DataInputStream} handles opened over run files and closes
     * them all on exit — includes suppressed-exception aggregation so a close failure
     * on one stream doesn't prevent the others from closing.
     */
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

        int size() {
            return streams.size();
        }

        DataInputStream get(int i) {
            return streams.get(i);
        }

        @Override
        public void close() {
            IOException first = null;
            for (DataInputStream s : streams) {
                try {
                    s.close();
                } catch (IOException e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
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
            int c = Integer.compare(this.docId, o.docId);
            if (c != 0) return c;
            c = Integer.compare(this.position, o.position);
            if (c != 0) return c;
            return Integer.compare(this.termId, o.termId);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) return true;
            if (!(obj instanceof RunHead)) return false;
            RunHead o = (RunHead) obj;
            // Consistent with compareTo — runIdx is an identity tag, not part of ordering,
            // but we include it so two distinct heap entries pointing at different runs are
            // never considered equal by Set/Map operations (PriorityQueue doesn't use equals,
            // but Sonar S1210 requires the contract regardless).
            return this.docId == o.docId
                    && this.position == o.position
                    && this.termId == o.termId
                    && this.runIdx == o.runIdx;
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

    // --------------------------------------------------------------------------------
    // Encode sorted tuples into final sidecar + doc-index
    // --------------------------------------------------------------------------------

    /**
     * Converts a sorted stream of 12-byte tuples into the compact {@code sidecar.dat}
     * format plus a fixed-width {@code doc-index.dat} of long[maxDoc] offsets.
     *
     * <p>The sidecar for each doc that has tokens is: {@code uvint(numEntries)} followed
     * by {@code numEntries} pairs of {@code uvint(positionDelta) uvint(termId)}. Docs
     * without tokens get {@link #DOC_INDEX_NO_TOKENS} in doc-index.dat. Docs whose index
     * is ≥ maxDoc are implicitly empty (reader bounds-checks).
     */
    private static void encodeSidecar(Path sortedSrc, Path sidecarDst, Path docIndexDst, int maxDoc)
            throws IOException {
        long[] docOffsets = new long[maxDoc];
        Arrays.fill(docOffsets, DOC_INDEX_NO_TOKENS);

        writeSortedTuplesAsSidecar(sortedSrc, sidecarDst, docOffsets);
        writeDocIndex(docIndexDst, docOffsets, maxDoc);
    }

    private static void writeSortedTuplesAsSidecar(Path sortedSrc, Path sidecarDst, long[] docOffsets)
            throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(
                Files.newInputStream(sortedSrc)));
             FileChannel sideCh = FileChannel.open(sidecarDst,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {

            // Scratch buffer for a single doc's encoded bytes. A doc with >5 MB of encoded
            // postings is absurd even at enron scale — we start small and double on overflow.
            ByteBuffer perDoc = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            ByteBuffer header = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
            DocRunState state = new DocRunState();

            int[] tuple = new int[3];
            while (readTuple(in, tuple)) {
                perDoc = appendTuple(sideCh, perDoc, header, state, docOffsets, tuple);
            }
            if (state.pendingDocId >= 0) {
                flushPerDocBuffer(sideCh, perDoc, header,
                        state.pendingDocId, state.pendingEntries, docOffsets, state.sidecarOffset);
            }
        }
    }

    /** Reads one (docId, position, termId) triple into {@code tuple}. Returns false on EOF. */
    private static boolean readTuple(DataInputStream in, int[] tuple) throws IOException {
        try {
            tuple[0] = in.readInt();
        } catch (java.io.EOFException eof) {
            return false;
        }
        tuple[1] = in.readInt();
        tuple[2] = in.readInt();
        return true;
    }

    private static ByteBuffer appendTuple(FileChannel sideCh,
                                          ByteBuffer perDoc,
                                          ByteBuffer header,
                                          DocRunState state,
                                          long[] docOffsets,
                                          int[] tuple) throws IOException {
        int docId = tuple[0];
        int position = tuple[1];
        int termId = tuple[2];

        ByteBuffer buf = perDoc;
        if (docId != state.pendingDocId) {
            if (state.pendingDocId >= 0) {
                state.sidecarOffset = flushPerDocBuffer(sideCh, buf, header,
                        state.pendingDocId, state.pendingEntries, docOffsets, state.sidecarOffset);
            }
            state.pendingDocId = docId;
            state.pendingEntries = 0;
            state.pendingPrevPos = 0;
            buf.clear();
        }
        // Encode delta-position (positions are ASC per doc) and raw termId.
        buf = ensureCapacity(buf, 10); // uvint ≤5 bytes × 2
        VarintCoder.writeUVInt(buf, position - state.pendingPrevPos);
        VarintCoder.writeUVInt(buf, termId);
        state.pendingPrevPos = position;
        state.pendingEntries++;
        return buf;
    }

    /** Mutable state for {@link #writeSortedTuplesAsSidecar}. Package-private for unit tests. */
    private static final class DocRunState {
        long sidecarOffset = 0;
        int pendingDocId = -1;
        int pendingEntries = 0;
        int pendingPrevPos = 0;
    }

    private static void writeDocIndex(Path docIndexDst, long[] docOffsets, int maxDoc) throws IOException {
        // Flat long[maxDoc] in little-endian byte order, mmap-friendly.
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

    private static long flushPerDocBuffer(FileChannel sideCh,
                                          ByteBuffer perDoc,
                                          ByteBuffer header,
                                          int docId,
                                          int numEntries,
                                          long[] docOffsets,
                                          long sidecarOffset) throws IOException {
        // Record doc-offset BEFORE writing the header+payload for this doc.
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
    // Misc
    // --------------------------------------------------------------------------------

    private static void tryDelete(Path p) {
        if (p == null) return;
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            log.warn("Failed to delete spill file {}: {}", p, e.toString());
        }
    }
}
