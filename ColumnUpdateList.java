final class ColumnUpdateList {
    private int[] values;
    private int size;

    ColumnUpdateList(int initialCapacity) {
        values = new int[Math.max(2, initialCapacity * 2)];
    }

    void clear() {
        size = 0;
    }

    void add(int x, int z) {
        ensureCapacity(size + 2);
        values[size++] = x;
        values[size++] = z;
    }

    int size() {
        return size / 2;
    }

    int xAt(int index) {
        return values[index * 2];
    }

    int zAt(int index) {
        return values[index * 2 + 1];
    }

    private void ensureCapacity(int required) {
        if (required <= values.length) {
            return;
        }
        int[] next = new int[Math.max(required, values.length * 2)];
        System.arraycopy(values, 0, next, 0, size);
        values = next;
    }
}
