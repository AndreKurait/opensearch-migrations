package org.opensearch.migrations.bulkload.lucene.sidecar;

/**
 * Growing primitive {@code int[]} list — a boxing-free replacement for
 * {@code ArrayList<Integer>}. A naive {@code ArrayList<Integer> termOffsetList}
 * allocates 16 bytes per {@code Integer} + 4 bytes array slot = 20 bytes per entry.
 * For a segment with 20M
 * unique terms (a large analyzed-text field) that {@code ArrayList} alone cost ~400 MB of
 * heap. An {@code int[]} costs 4 bytes per entry — 80 MB for the same segment.
 *
 * <p>Kept deliberately minimal: only {@link #add}, {@link #get}, {@link #size} — no
 * iterator, no boxing-boundary API. If you need to stream out the contents, iterate
 * from 0 to {@link #size()}.
 */
public final class IntArrayList {

    private int[] data;
    private int size;

    public IntArrayList() {
        this(16);
    }

    public IntArrayList(int initialCapacity) {
        this.data = new int[Math.max(1, initialCapacity)];
    }

    public void add(int value) {
        if (size == data.length) {
            // Geometric growth matching ArrayList's 1.5x policy so peak allocations mirror
            // what a correctness-equivalent ArrayList<Integer> would do.
            int newCap = data.length + (data.length >> 1);
            if (newCap < data.length + 1) newCap = data.length + 1; // handle small overflow
            int[] bigger = new int[newCap];
            System.arraycopy(data, 0, bigger, 0, size);
            data = bigger;
        }
        data[size++] = value;
    }

    public int get(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + " size=" + size);
        }
        return data[index];
    }

    public int size() {
        return size;
    }

    /** Returns a compact {@code int[]} containing exactly the stored values. */
    public int[] toArray() {
        int[] out = new int[size];
        System.arraycopy(data, 0, out, 0, size);
        return out;
    }
}
