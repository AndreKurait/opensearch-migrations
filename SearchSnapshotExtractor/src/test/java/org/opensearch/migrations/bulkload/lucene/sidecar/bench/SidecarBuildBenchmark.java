package org.opensearch.migrations.bulkload.lucene.sidecar.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilder;
import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarReader;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.infra.Blackhole;

/**
 * Baseline JMH benchmark for the PR #2882 three-pass SidecarBuilder
 * (raw scratch → external sort → encode).
 *
 * <p>Two measurements:
 * <ol>
 *   <li>buildSidecar — end-to-end time from empty spillDir to a readable
 *       mmap sidecar, measured in ms/op (Mode.AverageTime).</li>
 *   <li>readDocLatency — per-doc get() throughput on an already-built sidecar,
 *       measured in ns/op (Mode.AverageTime).</li>
 * </ol>
 *
 * <p>Three scales (numTerms x numDocs x avgPos): small/medium/large to observe
 * scaling behavior. Small runs in seconds; large is bounded so a full sweep fits
 * in a single CI benchmark run (~10 min).
 */
public class SidecarBuildBenchmark {

    // ------------- build-side benchmark -------------

    @State(Scope.Thread)
    public static class BuildState {
        /** Scale knob: sm=fast smoke, md=representative, lg=stress. */
        @Param({"sm", "md"})
        public String scale;

        SidecarBenchmarkSupport.Workload workload;
        Path spillDir;
        int sortBufferBytes;

        @Setup(Level.Trial)
        public void setupTrial() {
            switch (scale) {
                case "sm":
                    workload = SidecarBenchmarkSupport.generate(
                        /*terms=*/  2_000,
                        /*docs=*/     500,
                        /*avgPos=*/     2,
                        /*seed=*/   42L);
                    sortBufferBytes = 64 * 1024;        // 64 KiB
                    break;
                case "md":
                    workload = SidecarBenchmarkSupport.generate(
                        /*terms=*/ 20_000,
                        /*docs=*/   5_000,
                        /*avgPos=*/     3,
                        /*seed=*/   42L);
                    sortBufferBytes = 4 * 1024 * 1024;  // 4 MiB
                    break;
                case "lg":
                    workload = SidecarBenchmarkSupport.generate(
                        /*terms=*/ 80_000,
                        /*docs=*/  20_000,
                        /*avgPos=*/     4,
                        /*seed=*/   42L);
                    sortBufferBytes = 16 * 1024 * 1024; // 16 MiB
                    break;
                default:
                    throw new IllegalArgumentException("unknown scale " + scale);
            }
        }

        @Setup(Level.Invocation)
        public void setupInvocation() throws IOException {
            spillDir = Files.createTempDirectory("sidecar-jmh-");
        }

        @TearDown(Level.Invocation)
        public void tearDownInvocation() {
            SidecarBenchmarkSupport.deleteRecursive(spillDir);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.MILLISECONDS)
    @Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    public SidecarReader buildSidecar(BuildState s) throws IOException {
        SidecarBuilder builder = new SidecarBuilder(s.spillDir, s.sortBufferBytes, s.workload.maxDoc);
        Map<String, Integer> termIds = new HashMap<>();
        for (String t : s.workload.sortedDistinctTerms) {
            termIds.put(t, builder.registerTerm(SidecarBenchmarkSupport.bytesRef(t)));
        }
        // Group ordered emissions into (termId,docId) runs and emit positions[].
        List<SidecarBenchmarkSupport.Emission> em = s.workload.emissions;
        int i = 0;
        int[] positions = new int[64];
        while (i < em.size()) {
            SidecarBenchmarkSupport.Emission head = em.get(i);
            int tid = termIds.get(head.term);
            int doc = head.docId;
            int n = 0;
            while (i < em.size()
                && termIds.get(em.get(i).term) == tid
                && em.get(i).docId == doc) {
                if (n == positions.length) positions = Arrays.copyOf(positions, positions.length * 2);
                positions[n++] = em.get(i).position;
                i++;
            }
            builder.accept(tid, doc, positions, n);
        }
        return builder.buildAndOpenReader();
    }

    // ------------- read-side benchmark -------------

    @State(Scope.Thread)
    public static class ReadState {
        @Param({"md"})
        public String scale;

        SidecarBenchmarkSupport.Workload workload;
        Path spillDir;
        SidecarReader reader;
        int[] probeDocs;
        int probeCursor;

        @Setup(Level.Trial)
        public void setup() throws IOException {
            switch (scale) {
                case "sm":
                    workload = SidecarBenchmarkSupport.generate(2_000, 500, 2, 42L);
                    break;
                case "md":
                default:
                    workload = SidecarBenchmarkSupport.generate(20_000, 5_000, 3, 42L);
                    break;
            }
            spillDir = Files.createTempDirectory("sidecar-jmh-read-");
            SidecarBuilder builder = new SidecarBuilder(spillDir, 4 * 1024 * 1024, workload.maxDoc);
            Map<String, Integer> termIds = new HashMap<>();
            for (String t : workload.sortedDistinctTerms) {
                termIds.put(t, builder.registerTerm(SidecarBenchmarkSupport.bytesRef(t)));
            }
            List<SidecarBenchmarkSupport.Emission> em = workload.emissions;
            int i = 0;
            int[] positions = new int[64];
            while (i < em.size()) {
                SidecarBenchmarkSupport.Emission head = em.get(i);
                int tid = termIds.get(head.term);
                int doc = head.docId;
                int n = 0;
                while (i < em.size()
                    && termIds.get(em.get(i).term) == tid
                    && em.get(i).docId == doc) {
                    if (n == positions.length) positions = Arrays.copyOf(positions, positions.length * 2);
                    positions[n++] = em.get(i).position;
                    i++;
                }
                builder.accept(tid, doc, positions, n);
            }
            reader = builder.buildAndOpenReader();

            // Precompute a shuffled probe pattern so the benchmark exercises scattered reads.
            probeDocs = new int[Math.min(4096, workload.maxDoc)];
            java.util.Random r = new java.util.Random(7L);
            for (int j = 0; j < probeDocs.length; j++) {
                probeDocs[j] = r.nextInt(workload.maxDoc);
            }
        }

        @TearDown(Level.Trial)
        public void tearDown() throws IOException {
            if (reader != null) reader.close();
            SidecarBenchmarkSupport.deleteRecursive(spillDir);
        }
    }

    @Benchmark
    @BenchmarkMode(Mode.AverageTime)
    @OutputTimeUnit(TimeUnit.NANOSECONDS)
    @Measurement(iterations = 3, time = 5, timeUnit = TimeUnit.SECONDS)
    public void readDocLatency(ReadState s, Blackhole bh) throws IOException {
        int doc = s.probeDocs[s.probeCursor];
        s.probeCursor = (s.probeCursor + 1) % s.probeDocs.length;
        bh.consume(s.reader.get(doc));
    }
}
