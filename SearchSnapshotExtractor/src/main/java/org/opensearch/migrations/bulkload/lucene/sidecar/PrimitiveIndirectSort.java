package org.opensearch.migrations.bulkload.lucene.sidecar;

/**
 * Iterative quicksort over a primitive {@code int[]} index array — boxing-free replacement
 * for {@code Arrays.sort(Integer[], Comparator)}.
 *
 * <p>A naive sort using {@code Arrays.sort(Integer[], Comparator)} pays ~20 bytes per
 * record for the {@code Integer[]} index array. This class sorts {@code int[] idx}
 * by looking up
 * {@code keys[idx[i]]} (primary key) and {@code termIds[idx[i]]} (tie-breaker) — all
 * primitive array accesses, no autoboxing.
 *
 * <p>Algorithm: standard iterative three-way quicksort with a median-of-three pivot. The
 * manual recursion-to-stack conversion is to keep worst-case input (already-sorted or
 * reverse-sorted tuples) from blowing the JVM stack on segments with millions of tuples.
 */
final class PrimitiveIndirectSort {

    private PrimitiveIndirectSort() {}

    /**
     * Sorts {@code idx[from..to)} so that reading {@code keys[idx[i]]} (primary) then
     * {@code termIds[idx[i]]} (tie-breaker) is ascending.
     */
    static void sort(int[] idx, int from, int to, long[] keys, int[] termIds) {
        if (to - from < 2) return;
        // Explicit stack of (lo, hi) pairs. Max depth is ~log2(n) for balanced pivots;
        // with median-of-three we're robust enough in practice. Pre-size for 2 * 64 = safe
        // up to 2^64 entries.
        int[] stack = new int[128];
        int sp = 0;
        stack[sp++] = from;
        stack[sp++] = to;
        while (sp > 0) {
            int hi = stack[--sp];
            int lo = stack[--sp];
            if (hi - lo < 16) {
                insertionSort(idx, lo, hi, keys, termIds);
                continue;
            }
            int pivotIdx = partition(idx, lo, hi, keys, termIds);
            // Push larger partition first so the stack depth stays O(log n) even adversarially.
            if (pivotIdx - lo > hi - (pivotIdx + 1)) {
                stack[sp++] = lo; stack[sp++] = pivotIdx;
                stack[sp++] = pivotIdx + 1; stack[sp++] = hi;
            } else {
                stack[sp++] = pivotIdx + 1; stack[sp++] = hi;
                stack[sp++] = lo; stack[sp++] = pivotIdx;
            }
            if (sp + 4 >= stack.length) {
                int[] bigger = new int[stack.length * 2];
                System.arraycopy(stack, 0, bigger, 0, sp);
                stack = bigger;
            }
        }
    }

    private static int partition(int[] idx, int lo, int hi, long[] keys, int[] termIds) {
        int mid = lo + ((hi - lo) >>> 1);
        // Median-of-three: sort lo, mid, hi-1 and use mid as pivot.
        if (cmp(idx[mid], idx[lo], keys, termIds) < 0) swap(idx, lo, mid);
        if (cmp(idx[hi - 1], idx[lo], keys, termIds) < 0) swap(idx, lo, hi - 1);
        if (cmp(idx[hi - 1], idx[mid], keys, termIds) < 0) swap(idx, mid, hi - 1);
        int pivot = idx[mid];
        // Move pivot to hi-2 (second-to-last); scan [lo+1, hi-2).
        swap(idx, mid, hi - 2);
        int i = lo;
        int j = hi - 2;
        while (true) {
            // Hoare-style partition scan: advance i past keys strictly less than the pivot
            // and j past keys strictly greater. The loop body is intentionally empty — all
            // work happens in the increment and the comparison.
            while (cmp(idx[++i], pivot, keys, termIds) < 0) { /* scan forward */ }
            while (cmp(idx[--j], pivot, keys, termIds) > 0) { /* scan backward */ }
            if (i >= j) break;
            swap(idx, i, j);
        }
        swap(idx, i, hi - 2);
        return i;
    }

    private static void insertionSort(int[] idx, int lo, int hi, long[] keys, int[] termIds) {
        for (int i = lo + 1; i < hi; i++) {
            int cur = idx[i];
            int j = i - 1;
            while (j >= lo && cmp(idx[j], cur, keys, termIds) > 0) {
                idx[j + 1] = idx[j];
                j--;
            }
            idx[j + 1] = cur;
        }
    }

    private static int cmp(int aIdx, int bIdx, long[] keys, int[] termIds) {
        int c = Long.compare(keys[aIdx], keys[bIdx]);
        if (c != 0) return c;
        return Integer.compare(termIds[aIdx], termIds[bIdx]);
    }

    private static void swap(int[] a, int i, int j) {
        int t = a[i];
        a[i] = a[j];
        a[j] = t;
    }
}
