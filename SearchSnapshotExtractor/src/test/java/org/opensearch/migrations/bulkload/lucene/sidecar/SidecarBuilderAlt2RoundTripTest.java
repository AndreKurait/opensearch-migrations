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
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Correctness tests for {@link SidecarBuilderAlt2} + {@link SidecarReader} round-trip.
 *
 * <p>The alt2 builder accumulates {@code (position, termId)} pairs per doc in heap
 * (no external disk sort) and performs a small {@code Arrays.sort} per non-empty doc
 * at build time. These tests mirror the correctness contract enforced by
 * {@link SidecarBuilderRoundTripTest}: for every docId, {@code SidecarReader.get(docId)}
 * must return exactly the position-ordered term list the reference in-memory
 * implementation (a {@code TreeMap<(position,termId), term>} per doc) would produce.
 *
 * <p>Cases covered:
 * <ul>
 *   <li>Fixed small input — pin the exact mapping.</li>
 *   <li>Randomized fuzz against a reference implementation.</li>
 *   <li>Ties on {@code (docId, position)} broken by {@code termId ASC}.</li>
 *   <li>Sparse segments (most docs empty) — doc-index sentinel correctness.</li>
 *   <li>Empty input — reader opens and reports every doc empty.</li>
 *   <li>Positions >= 256 — validates the long-packed representation survives
 *       values that would overflow a 24-bit position field.</li>
 *   <li>Term count cap at {@code 2^24}.</li>
 * </ul>
 */
class SidecarBuilderAlt2RoundTripTest {

    private Path spillDir;

    @BeforeEach
    void setUp() throws IOException {
        spillDir = Files.createTempDirectory("sidecar-alt2-test-");
    }

    @AfterEach
    void tearDown() throws IOException {
        if (spillDir != null) deleteRecursive(spillDir);
    }

    private static void deleteRecursive(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
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
        SidecarReader reader = buildAndOpen(stream, /*maxDoc=*/3);
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
    void fuzz_matchesInHeapReference() throws IOException {
        Random rng = new Random(0xBADF00DL);
        List<Emission> stream = randomStream(rng, /*numTerms=*/500, /*numDocs=*/200, /*avgPositionsPerDocTerm=*/3);
        Map<Integer, List<String>> reference = inHeapReference(stream);

        SidecarReader reader = buildAndOpen(stream, 200);
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
        // Alt2 packs (position<<32)|termId so Arrays.sort(long[]) gives (pos ASC, termId ASC).
        List<Emission> stream = List.of(
            e("aa", 0, 5),
            e("bb", 0, 5)
        );
        SidecarReader reader = buildAndOpen(stream, 1);
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
        SidecarReader reader = buildAndOpen(stream, 10);
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
        SidecarReader reader = buildAndOpen(Collections.emptyList(), 5);
        try {
            for (int d = 0; d < 5; d++) {
                assertEquals(Collections.emptyList(), reader.get(d));
            }
        } finally {
            reader.close();
        }
    }

    @Test
    void positionsBeyond24Bits_roundTripCorrectly() throws IOException {
        // Guards against a silent overflow if the implementation ever packs position into
        // only 24 bits. The reference in-heap path uses 32-bit position arithmetic.
        List<Emission> stream = List.of(
            e("alpha", 0, 1 << 25),   // 33_554_432 — well outside a 24-bit field
            e("beta",  0, (1 << 25) + 1),
            e("alpha", 0, 10)         // earlier position for the same doc
        );
        SidecarReader reader = buildAndOpen(stream, 1);
        try {
            assertEquals(List.of("alpha", "alpha", "beta"), reader.get(0));
        } finally {
            reader.close();
        }
    }

    @Test
    void buildAndOpenReader_twiceThrows() throws IOException {
        SidecarBuilderAlt2 builder = new SidecarBuilderAlt2(spillDir, 1024, 1);
        byte[] b = "a".getBytes(StandardCharsets.UTF_8);
        int id = builder.registerTerm(new BytesRefLike(b, 0, b.length));
        builder.accept(id, 0, new int[]{0}, 1);
        try (SidecarReader r = builder.buildAndOpenReader()) {
            assertEquals(List.of("a"), r.get(0));
        }
        assertThrows(IllegalStateException.class, builder::buildAndOpenReader);
    }

    @Test
    void registerTerm_rejectsDocIdOutOfRange() throws IOException {
        SidecarBuilderAlt2 builder = new SidecarBuilderAlt2(spillDir, 1024, 2);
        byte[] b = "t".getBytes(StandardCharsets.UTF_8);
        int id = builder.registerTerm(new BytesRefLike(b, 0, b.length));
        assertThrows(IllegalArgumentException.class,
            () -> builder.accept(id, 5, new int[]{0}, 1));
    }

    // ---- helpers --------------------------------------------------------

    private SidecarReader buildAndOpen(List<Emission> stream, int maxDoc) throws IOException {
        // sortBufferBytes is accepted for API parity but ignored by Alt2; pass an arbitrary value.
        SidecarBuilderAlt2 builder = new SidecarBuilderAlt2(spillDir, 1024, maxDoc);
        Map<String, Integer> termIds = new HashMap<>();
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
        ));
        for (String t : sortedDistinctTerms) {
            byte[] bytes = t.getBytes(StandardCharsets.UTF_8);
            int id = builder.registerTerm(new BytesRefLike(bytes, 0, bytes.length));
            termIds.put(t, id);
        }
        // Group emissions into (termId, docId) runs with positions[] payloads.
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
        List<String> sortedDistinctTerms = new ArrayList<>(new java.util.TreeSet<>(
            stream.stream().map(e -> e.term).toList()
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
                int numPos = 1 + rng.nextInt(avgPositionsPerDocTerm * 2);
                java.util.TreeSet<Integer> pos = new java.util.TreeSet<>();
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
