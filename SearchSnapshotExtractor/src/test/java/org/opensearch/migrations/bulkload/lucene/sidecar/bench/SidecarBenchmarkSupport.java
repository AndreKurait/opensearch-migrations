package org.opensearch.migrations.bulkload.lucene.sidecar.bench;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.opensearch.migrations.bulkload.lucene.sidecar.BytesRefLike;

/**
 * Shared fixture for the sidecar JMH benchmarks. Generates a synthetic
 * term-major emission stream that mirrors the shape of a real Lucene
 * TermsEnum walk:
 *   - terms sorted bytes-ASC
 *   - within term: docId ASC
 *   - within (term, doc): positions ASC, non-negative
 *
 * Keeps the generator deterministic (fixed seed per scale) so runs are
 * comparable across baseline / alt1 / alt2 / alt3.
 */
public final class SidecarBenchmarkSupport {

    private SidecarBenchmarkSupport() {}

    /** One emission = one (term, doc, position) tuple. */
    public static final class Emission {
        public final String term;
        public final int docId;
        public final int position;
        public Emission(String term, int docId, int position) {
            this.term = term;
            this.docId = docId;
            this.position = position;
        }
    }

    /** The generated workload, ready to feed to any PostingsSink-shaped builder. */
    public static final class Workload {
        public final List<Emission> emissions;        // term-major, doc-ASC, pos-ASC
        public final List<String> sortedDistinctTerms; // term bytes ASC
        public final int maxDoc;
        public final long totalPositions;

        public Workload(List<Emission> emissions, List<String> sortedDistinctTerms, int maxDoc, long totalPositions) {
            this.emissions = emissions;
            this.sortedDistinctTerms = sortedDistinctTerms;
            this.maxDoc = maxDoc;
            this.totalPositions = totalPositions;
        }
    }

    /**
     * Generate a deterministic workload.
     *
     * @param numTerms            distinct terms (realistic segment: 50k-500k)
     * @param numDocs             maxDoc of the synthetic segment
     * @param avgPositionsPerDocTerm average number of positions a term contributes per doc it appears in
     * @param seed                RNG seed for reproducibility
     */
    public static Workload generate(int numTerms, int numDocs, int avgPositionsPerDocTerm, long seed) {
        Random rng = new Random(seed);
        List<String> terms = new ArrayList<>(numTerms);
        for (int i = 0; i < numTerms; i++) {
            terms.add("t" + String.format("%08d", i));
        }
        List<Emission> out = new ArrayList<>();
        long totalPos = 0;
        for (String term : terms) {
            // Each term appears in some fraction of docs. Shape like a Zipf-ish slice.
            int docsWithTerm = 1 + rng.nextInt(Math.max(1, numDocs / 8));
            boolean[] chosen = new boolean[numDocs];
            for (int k = 0; k < docsWithTerm; k++) {
                chosen[rng.nextInt(numDocs)] = true;
            }
            for (int doc = 0; doc < numDocs; doc++) {
                if (!chosen[doc]) continue;
                int numPos = 1 + rng.nextInt(Math.max(1, avgPositionsPerDocTerm * 2));
                TreeSet<Integer> pos = new TreeSet<>();
                while (pos.size() < numPos) pos.add(rng.nextInt(4096));
                for (int p : pos) {
                    out.add(new Emission(term, doc, p));
                    totalPos++;
                }
            }
        }
        // Term-major, then doc ASC, then pos ASC — matches Lucene TermsEnum walk.
        out.sort(Comparator.<Emission>comparingInt(e -> termIdOf(e.term))
            .thenComparingInt(e -> e.docId)
            .thenComparingInt(e -> e.position));
        return new Workload(out, new ArrayList<>(new TreeSet<>(terms)), numDocs, totalPos);
    }

    // Deterministic int ordering that matches lex order for the "t%08d" names.
    private static int termIdOf(String t) {
        return Integer.parseInt(t.substring(1));
    }

    /** Encode a term as a BytesRefLike for builder.registerTerm(...). */
    public static BytesRefLike bytesRef(String term) {
        byte[] b = term.getBytes(StandardCharsets.UTF_8);
        return new BytesRefLike(b, 0, b.length);
    }

    /** Delete a temp dir tree — swallows IOExceptions to avoid masking benchmark failures. */
    public static void deleteRecursive(Path root) {
        if (root == null) return;
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try { Files.deleteIfExists(p); } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}
    }
}
