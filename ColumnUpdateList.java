final class ColumnUpdateList {
    private int[] values;
    private int size;

    ColumnUpdateList(int initialCapacity) {
        values = new int[Math.max(3, initialCapacity * 3)];
    }

    void clear() {
        size = 0;
    }

    void add(int x, int y, int z) {
        ensureCapacity(size + 3);
        values[size++] = x;
        values[size++] = y;
        values[size++] = z;
    }

    int size() {
        return size / 3;
    }

    int xAt(int index) {
        return values[index * 3];
    }

    int yAt(int index) {
        return values[index * 3 + 1];
    }

    int zAt(int index) {
        return values[index * 3 + 2];
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
