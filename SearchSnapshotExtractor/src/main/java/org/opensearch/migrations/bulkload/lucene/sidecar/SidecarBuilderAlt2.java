package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

import lombok.extern.slf4j.Slf4j;

/**
 * Alternative {@link PostingsSink} / sidecar builder that uses a per-doc in-heap accumulator
 * instead of an external disk-backed sort. Public behavior matches {@link SidecarBuilder}:
 * consumes {@code (termId, docId, positions[])} triples in term-major / docId-ascending order
 * and produces {@code terms.dat}, {@code term-offsets.dat}, {@code sidecar.dat}, and
 * {@code doc-index.dat} in {@code spillDir} — readable by {@link SidecarReader}.
 *
 * <p><b>Core idea.</b> The baseline's external sort exists because the walk order (term-major)
 * is incompatible with the output format (doc-major, position-ASC). But each term is visited
 * in docId-ASC order, so we simply accumulate per-doc (position, termId) pairs in heap, then
 * sort each doc's pairs independently at build time. Per-doc sorts are tiny (typically ≤ a
 * few hundred positions per doc); their total cost is O(totalPositions) amortized — a log-factor
 * cheaper than the baseline's O(totalPositions · log totalPositions) external sort. Disk I/O
 * collapses from 3 writes + 3 reads of every tuple down to a single sequential write pass.
 *
 * <p><b>Storage representation.</b> Per-doc pairs are packed into a growable {@code long[]}
 * with the high 32 bits holding {@code position} and the low 32 bits holding {@code termId}.
 * A single {@code Arrays.sort(long[])} call per doc then sorts in (position ASC, termId ASC)
 * order — because both components are non-negative ints, the packed long's sign bit is always
 * zero so signed and unsigned long orderings agree. Using a 64-bit slot per entry (rather than
 * the int-packed {@code (position << 24) | termId} suggested in the design note) avoids a silent
 * overflow when {@code position ≥ 256}, at the cost of one extra word per entry.
 *
 * <p><b>Trade-off.</b> Memory scales with {@code totalPositions × 8 B} plus a few dozen bytes
 * of per-non-empty-doc overhead. For segments whose accumulator does not fit in heap the
 * disk-backed baseline is the right choice. This alt's sweet spot is medium segments whose
 * total position count fits comfortably in process heap — trading RAM for a single write pass
 * and no external sort.
 *
 * <p>API parity with {@link SidecarBuilder}: {@code sortBufferBytes} is accepted for signature
 * compatibility and ignored. {@link #registerTerm} throws {@link IllegalStateException} if the
 * termId space would exceed {@code 2^24} — the spec cap shared across alternatives so
 * cross-alt benchmarks stay apples-to-apples.
 *
 * <p>Thread-safety: not thread-safe. A single (segment, field) is fed by exactly one producer.
 */
@Slf4j
public final class SidecarBuilderAlt2 implements PostingsSink, AutoCloseable {

    /** Hard cap on registered terms shared across alternatives so numeric widths stay consistent. */
    static final int MAX_TERMS = 1 << 24;

    private final Path spillDir;
    private final int maxDoc;

    private final DataOutputStream termsOut;
    private final DataOutputStream termOffsetsOut;

    private final Path termsFile;
    private final Path termOffsetsFile;

    /** Dense array indexed by docId; slots are {@code null} until the doc receives its first emission. */
    private final DocAccum[] perDoc;

    private int nextTermId = 0;
    private int currentTermOffset = 0;
    private boolean built = false;
    private boolean closed = false;

    /**
     * @param spillDir                per-(segment,field) directory owned by this builder. Created if missing.
     * @param unusedSortBufferBytes   accepted for API parity with {@link SidecarBuilder}; ignored.
     * @param maxDoc                  the segment's {@code maxDoc}, used to size the doc-index output.
     */
    public SidecarBuilderAlt2(Path spillDir, int unusedSortBufferBytes, int maxDoc) throws IOException {
        this.spillDir = spillDir;
        this.maxDoc = Math.max(1, maxDoc);
        Files.createDirectories(spillDir);
        this.termsFile = spillDir.resolve(SidecarBuilder.TERMS_FILE);
        this.termOffsetsFile = spillDir.resolve(SidecarBuilder.TERM_OFFSETS_FILE);
        this.termsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.termOffsetsOut = new DataOutputStream(new BufferedOutputStream(
            Files.newOutputStream(termOffsetsFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)));
        this.perDoc = new DocAccum[this.maxDoc];
    }

    @Override
    public int registerTerm(BytesRefLike term) throws IOException {
        if (nextTermId >= MAX_TERMS) {
            throw new IllegalStateException("Term count exceeds SidecarBuilderAlt2 limit of 2^24");
        }
        int id = nextTermId++;
        termOffsetsOut.writeInt(Integer.reverseBytes(currentTermOffset)); // little-endian
        termsOut.writeInt(term.length());                                  // big-endian length prefix
        termsOut.write(term.bytes(), term.offset(), term.length());
        currentTermOffset += 4 + term.length();
        return id;
    }

    /**
     * Appends {@code positionCount} packed {@code (position, termId)} longs to the per-doc
     * accumulator for {@code docId}. Positions are copied into the accumulator; the caller's
     * array is not retained.
     */
    @Override
    public void accept(int termId, int docId, int[] positions, int positionCount) {
        if (docId < 0 || docId >= maxDoc) {
            throw new IllegalArgumentException("docId out of range: " + docId + " (maxDoc=" + maxDoc + ")");
        }
        DocAccum acc = perDoc[docId];
        if (acc == null) {
            acc = new DocAccum();
            perDoc[docId] = acc;
        }
        long termIdBits = termId & 0xFFFF_FFFFL;
        for (int i = 0; i < positionCount; i++) {
            long packed = (((long) positions[i]) << 32) | termIdBits;
            acc.add(packed);
        }
    }

    /**
     * Finalizes the builder: flushes the terms outputs, sorts each doc's accumulator, encodes
     * {@code sidecar.dat} and {@code doc-index.dat} in a single sequential write, and returns an
     * open {@link SidecarReader}. Frees each doc's accumulator as soon as it is emitted to keep
     * peak heap bounded to the unprocessed tail.
     */
    public SidecarReader buildAndOpenReader() throws IOException {
        if (built) throw new IllegalStateException("SidecarBuilderAlt2.buildAndOpenReader already called");
        built = true;

        termsOut.flush();
        termsOut.close();
        termOffsetsOut.flush();
        termOffsetsOut.close();

        Path sidecarFile = spillDir.resolve(SidecarBuilder.SIDECAR_FILE);
        Path docIndexFile = spillDir.resolve(SidecarBuilder.DOC_INDEX_FILE);
        encodeSidecar(sidecarFile, docIndexFile);

        closed = true;
        return SidecarReader.open(spillDir, maxDoc, nextTermId);
    }

    /**
     * For each doc with a non-empty accumulator: sort packed pairs in (position ASC, termId ASC)
     * order, encode {@code uvint(numEntries)} followed by {@code numEntries} pairs of
     * {@code uvint(positionDelta)} + {@code uvint(termId)}, and record the absolute sidecar.dat
     * offset in {@code docOffsets[docId]}. Docs without tokens get {@link SidecarBuilder#DOC_INDEX_NO_TOKENS}.
     */
    private void encodeSidecar(Path sidecarFile, Path docIndexFile) throws IOException {
        long[] docOffsets = new long[maxDoc];
        Arrays.fill(docOffsets, SidecarBuilder.DOC_INDEX_NO_TOKENS);

        try (FileChannel sideCh = FileChannel.open(sidecarFile,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buf = ByteBuffer.allocate(4096).order(ByteOrder.LITTLE_ENDIAN);
            long offset = 0;
            for (int docId = 0; docId < maxDoc; docId++) {
                DocAccum acc = perDoc[docId];
                if (acc == null || acc.size == 0) continue;

                // Sort this doc's pairs by (position ASC, termId ASC). Packed high bit is always
                // zero so signed Arrays.sort gives the correct unsigned order.
                Arrays.sort(acc.data, 0, acc.size);

                docOffsets[docId] = offset;
                buf.clear();
                // uvint header (≤5 bytes) + up to 10 bytes per entry (2× 5-byte uvints).
                int worstCase = 5 + 10 * acc.size;
                if (buf.capacity() < worstCase) {
                    buf = ByteBuffer.allocate(Math.max(worstCase, buf.capacity() * 2))
                            .order(ByteOrder.LITTLE_ENDIAN);
                }
                VarintCoder.writeUVInt(buf, acc.size);

                int prevPos = 0;
                for (int i = 0; i < acc.size; i++) {
                    long packed = acc.data[i];
                    int position = (int) (packed >>> 32);
                    int termId = (int) (packed & 0xFFFF_FFFFL);
                    VarintCoder.writeUVInt(buf, position - prevPos);
                    VarintCoder.writeUVInt(buf, termId);
                    prevPos = position;
                }
                buf.flip();
                int payload = buf.remaining();
                while (buf.hasRemaining()) sideCh.write(buf);
                offset += payload;

                // Release this doc's accumulator as soon as its bytes are emitted.
                perDoc[docId] = null;
            }
        }

        writeDocIndex(docIndexFile, docOffsets, maxDoc);
    }

    private static void writeDocIndex(Path docIndexDst, long[] docOffsets, int maxDoc) throws IOException {
        // Flat little-endian long[maxDoc] — mmap-friendly, matches SidecarReader.readDocOffset.
        try (FileChannel ch = FileChannel.open(docIndexDst,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
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

    @Override
    public void close() {
        if (closed) return;
        // Abort path — only reached if buildAndOpenReader() wasn't called.
        closeQuietly(termsOut);
        closeQuietly(termOffsetsOut);
        tryDelete(termsFile);
        tryDelete(termOffsetsFile);
        closed = true;
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException e) {
            log.debug("Ignored close error during SidecarBuilderAlt2 abort: {}", e.toString());
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

    /** Growing {@code long[]} accumulator for one doc's packed (position, termId) pairs. */
    private static final class DocAccum {
        long[] data = new long[16];
        int size = 0;

        void add(long v) {
            if (size == data.length) {
                // 1.5x growth mirrors ArrayList so peak allocations match the reference shape.
                int newCap = data.length + (data.length >> 1);
                if (newCap <= data.length) newCap = data.length + 1;
                long[] bigger = new long[newCap];
                System.arraycopy(data, 0, bigger, 0, size);
                data = bigger;
            }
            data[size++] = v;
        }
    }
}
