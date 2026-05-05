package org.opensearch.migrations.bulkload.lucene.sidecar.bench;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.opensearch.migrations.bulkload.lucene.sidecar.SidecarBuilderAlt3;
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
 * JMH benchmark for {@link SidecarBuilderAlt3} — doc-banked streaming with a long-packed
 * per-doc sort. Mirrors {@link SidecarBuildBenchmark} verbatim (same params, scales,
 * iteration/warmup counts, and probe pattern) so results are directly comparable to the
 * three-pass baseline.
 */
public class SidecarBuildBenchmarkAlt3 {

    // ------------- build-side benchmark -------------

    @State(Scope.Thread)
    public static class BuildState {
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
                    sortBufferBytes = 64 * 1024;
                    break;
                case "md":
                    workload = SidecarBenchmarkSupport.generate(
                        /*terms=*/ 20_000,
                        /*docs=*/   5_000,
                        /*avgPos=*/     3,
                        /*seed=*/   42L);
                    sortBufferBytes = 4 * 1024 * 1024;
                    break;
                case "lg":
                    workload = SidecarBenchmarkSupport.generate(
                        /*terms=*/ 80_000,
                        /*docs=*/  20_000,
                        /*avgPos=*/     4,
                        /*seed=*/   42L);
                    sortBufferBytes = 16 * 1024 * 1024;
                    break;
                default:
                    throw new IllegalArgumentException("unknown scale " + scale);
            }
        }

        @Setup(Level.Invocation)
        public void setupInvocation() throws IOException {
            spillDir = Files.createTempDirectory("sidecar-alt3-jmh-");
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
        SidecarBuilderAlt3 builder = new SidecarBuilderAlt3(s.spillDir, s.sortBufferBytes, s.workload.maxDoc);
        Map<String, Integer> termIds = new HashMap<>();
        for (String t : s.workload.sortedDistinctTerms) {
            termIds.put(t, builder.registerTerm(SidecarBenchmarkSupport.bytesRef(t)));
        }
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
            spillDir = Files.createTempDirectory("sidecar-alt3-jmh-read-");
            SidecarBuilderAlt3 builder = new SidecarBuilderAlt3(spillDir, 4 * 1024 * 1024, workload.maxDoc);
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
