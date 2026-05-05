package org.opensearch.migrations.bulkload.lucene.sidecar;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Round-trip correctness tests for {@link SidecarBuilderAlt1} + {@link SidecarReader}.
 *
 * <p>The alt1 builder produces the same on-disk layout as the baseline builder, so the
 * same {@link SidecarReader} must read it back. We assert that for every docId,
 * {@code reader.get(docId)} returns exactly the position-ordered term list the in-heap
 * reference implementation would produce.
 *
 * <p>Key cases covered:
 *   <ul>
 *     <li>Small fixed input with hand-verified expected output.</li>
 *     <li>Randomized fuzz with a tiny sort buffer that forces many-way merges — exercises
 *         the in-memory sort chunk spill-to-run-file path and the fused merge+encode.</li>
 *     <li>Duplicate positions with different termIds — pins the {@code termId ASC}
 *         tie-break rule.</li>
 *     <li>Sparse docs — verifies the no-tokens sentinel in doc-index.dat.</li>
 *     <li>Empty stream — degenerate but valid.</li>
 *   </ul>
 */
class SidecarBuilderAlt1RoundTripTest {

    private Path spillDir;

    @BeforeEach
    void setUp() throws IOException {
        spillDir = Files.createTempDirectory("sidecar-alt1-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (spillDir != null) deleteRecursive(spillDir);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) { /* best-effort */ }
            });
        }
    }

    @Test
    void smallFixedInput_roundTrip() throws IOException {
        List<Emission> stream = List.of(
            e("apple",  0, 0),
            e("apple",  0, 5),
            e("apple",  2, 1),
            e("banana", 0, 3),
            e("banana", 1, 0),
            e("cherry", 2, 4)
        );
        SidecarReader reader = buildAndOpen(stream, /*maxDoc=*/3, /*sortBufferBytes=*/1024);
        try {
            assertEquals(List.of("apple", "banana", "apple"), reader.get(0));
            assertEquals(List.of("banana"),                   reader.get(1));
            assertEquals(List.of("apple", "cherry"),          reader.get(2));
            assertEquals(Collections.emptyList(),             reader.get(3)); // out of range
        } finally {
            reader.close();
        }
    }

    @Test
    void fuzz_matchesInHeapReference_multiRunMerge() throws IOException {
        Random rng = new Random(0xBADF00DL);
        // 200 docs x 100 terms x ~3 pos → on the order of a few thousand tuples.
        List<Emission> stream = randomStream(rng, /*numTerms=*/100, /*numDocs=*/200, /*avgPositionsPerDocTerm=*/2);
        Map<Integer, List<String>> reference = inHeapReference(stream);

        // sortBufferBytes=240 → capacity=20 records, forcing many run files + k-way merge
        // without exhausting file-descriptor limits.
        SidecarReader reader = buildAndOpen(stream, 200, 240);
        try {
            for (int doc = 0; doc < 200; doc++) {
                List<String> got = reader.get(doc);
                List<String> want = reference.getOrDefault(doc, Collections.emptyList());
                assertEquals(want, got, "Mismatch for docId=" + doc);
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void tiesAtSamePosition_breakByTermIdAscending() throws IOException {
        // "aa" is termId 0 (registered first), "bb" is termId 1. Both at doc 0 position 5.
        // Tie-break must be termId ASC.
        List<Emission> stream = List.of(
            e("aa", 0, 5),
            e("bb", 0, 5)
        );
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            assertEquals(List.of("aa", "bb"), reader.get(0));
        } finally {
            reader.close();
        }
    }

    @Test
    void sparseDocs_returnEmptyForUnemittedDocs() throws IOException {
        List<Emission> stream = List.of(
            e("x", 3, 0),
            e("x", 7, 0)
        );
        SidecarReader reader = buildAndOpen(stream, 10, 1024);
        try {
            for (int d = 0; d < 10; d++) {
                if (d == 3 || d == 7) assertEquals(List.of("x"), reader.get(d));
                else assertEquals(Collections.emptyList(), reader.get(d));
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void emptyStream_producesValidEmptyReader() throws IOException {
        SidecarReader reader = buildAndOpen(Collections.emptyList(), 5, 1024);
        try {
            for (int d = 0; d < 5; d++) {
                assertEquals(Collections.emptyList(), reader.get(d));
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void tinySortBuffer_oneRecordPerRun() throws IOException {
        // Exercise the degenerate one-record-per-run path (capacity clamped to 1) with a
        // small stream so we don't exhaust fds.
        List<Emission> stream = List.of(
            e("a", 0, 0),
            e("a", 1, 2),
            e("b", 0, 1),
            e("b", 1, 0)
        );
        SidecarReader reader = buildAndOpen(stream, 2, 1); // sortBufferBytes=1 → capacity=1
        try {
            assertEquals(List.of("a", "b"), reader.get(0));
            assertEquals(List.of("b", "a"), reader.get(1));
        } finally {
            reader.close();
        }
    }

    @Test
    void buildDoesNotTouchSpillDirAfterReaderOpen() throws IOException {
        List<Emission> stream = List.of(e("alpha", 0, 0));
        SidecarReader reader = buildAndOpen(stream, 1, 1024);
        try {
            assertEquals(List.of("alpha"), reader.get(0));
        } finally {
            reader.close();
        }
        assertTrue(Files.exists(spillDir), "Spill dir should survive reader close");
    }

    // ---- helpers --------------------------------------------------------

    private SidecarReader buildAndOpen(List<Emission> stream, int maxDoc, int sortBufferBytes) throws IOException {
        SidecarBuilderAlt1 builder = new SidecarBuilderAlt1(spillDir, sortBufferBytes, maxDoc);
        Map<String, Integer> termIds = new HashMap<>();
        List<String> sortedDistinctTerms = new ArrayList<>(new TreeSet<>(
            stream.stream().map(em -> em.term).toList()
        ));
        for (String t : sortedDistinctTerms) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            int id = builder.registerTerm(new BytesRefLike(bytes, 0, bytes.length));
            termIds.put(t, id);
        }
        List<Emission> mutable = new ArrayList<>(stream);
        mutable.sort(Comparator.<Emission>comparingInt(em -> termIds.get(em.term))
                              .thenComparingInt(em -> em.docId)
                              .thenComparingInt(em -> em.position));
        int i = 0;
        while (i < mutable.size()) {
            Emission head = mutable.get(i);
            int termId = termIds.get(head.term);
            int docId = head.docId;
            int[] positions = new int[16];
            int n = 0;
            while (i < mutable.size()
                   && termIds.get(mutable.get(i).term) == termId
                   && mutable.get(i).docId == docId) {
                if (n == positions.length) positions = Arrays.copyOf(positions, positions.length * 2);
                positions[n++] = mutable.get(i).position;
                i++;
            }
            builder.accept(termId, docId, positions, n);
        }
        return builder.buildAndOpenReader();
    }

    private static Map<Integer, List<String>> inHeapReference(List<Emission> stream) {
        Map<Integer, TreeMap<Long, String>> byDoc = new HashMap<>();
        List<String> sortedDistinctTerms = new ArrayList<>(new TreeSet<>(
            stream.stream().map(em -> em.term).toList()
        ));
        Map<String, Integer> termIds = new HashMap<>();
        for (int i = 0; i < sortedDistinctTerms.size(); i++) termIds.put(sortedDistinctTerms.get(i), i);
        for (Emission em : stream) {
            long key = ((long) em.position << 32) | (termIds.get(em.term) & 0xFFFFFFFFL);
            byDoc.computeIfAbsent(em.docId, k -> new TreeMap<>()).put(key, em.term);
        }
        Map<Integer, List<String>> out = new HashMap<>();
        byDoc.forEach((d, map) -> out.put(d, new ArrayList<>(map.values())));
        return out;
    }

    private static List<Emission> randomStream(Random rng, int numTerms, int numDocs, int avgPositionsPerDocTerm) {
        List<String> terms = new ArrayList<>(numTerms);
        for (int i = 0; i < numTerms; i++) terms.add("t" + String.format("%05d", i));
        List<Emission> out = new ArrayList<>();
        for (String term : terms) {
            int docsWithTerm = 1 + rng.nextInt(Math.max(1, numDocs / 4));
            boolean[] chosen = new boolean[numDocs];
            for (int k = 0; k < docsWithTerm; k++) chosen[rng.nextInt(numDocs)] = true;
            for (int doc = 0; doc < numDocs; doc++) {
                if (!chosen[doc]) continue;
                int numPos = 1 + rng.nextInt(Math.max(1, avgPositionsPerDocTerm * 2));
                TreeSet<Integer> pos = new TreeSet<>();
                while (pos.size() < numPos) pos.add(rng.nextInt(1000));
                for (int p : pos) out.add(e(term, doc, p));
            }
        }
        return out;
    }

    private static Emission e(String term, int docId, int pos) {
        return new Emission(term, docId, pos);
    }

    private static final class Emission {
        final String term;
        final int docId;
        final int position;
        Emission(String term, int docId, int position) {
            this.term = term;
            this.docId = docId;
            this.position = position;
        }
    }
}
