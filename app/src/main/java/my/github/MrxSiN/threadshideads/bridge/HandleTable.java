package my.github.MrxSiN.threadshideads.bridge;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Iterator;

/**
 * Opaque u32 handles for the Java objects one request touches.
 *
 * A handle is {@code generation << 16 | index}; 0 means null. The table is
 * cleared after every top-level request and the generation advances, so a
 * handle kept past its request is rejected instead of naming a new object.
 * Each entry also remembers the slot it was read from, which is how
 * CAP_WRITE_BACK writes a replacement into the right field, entry or index.
 */
final class HandleTable {

    static final int CAPACITY = 1 << 16;

    static final int SLOT_NONE = 0;
    static final int SLOT_LIST = 1;
    static final int SLOT_ARRAY = 2;
    static final int SLOT_MAP_ENTRY = 3;
    static final int SLOT_FIELD = 4;

    private final Object[] objects = new Object[CAPACITY];
    private final Object[] slotOwner = new Object[CAPACITY];
    private final Object[] slotKey = new Object[CAPACITY];
    private final int[] slotIndex = new int[CAPACITY];
    private final byte[] slotKind = new byte[CAPACITY];
    private final Object[] extra = new Object[CAPACITY];
    private final Iterator<?>[] iterators = new Iterator<?>[CAPACITY];
    private final int[] iteratorNext = new int[CAPACITY];

    private int size = 1;
    private int generation = 1;

    /** Allocates a handle for a non-null object; 0 for null or when full. */
    int put(Object value) {
        if (value == null || size >= CAPACITY) {
            return 0;
        }
        int index = size++;
        objects[index] = value;
        return (generation << 16) | index;
    }

    int putSlot(Object value, int kind, Object owner, Object key, int slot) {
        int handle = put(value);
        if (handle != 0) {
            int index = handle & 0xFFFF;
            slotKind[index] = (byte) kind;
            slotOwner[index] = owner;
            slotKey[index] = key;
            slotIndex[index] = slot;
        }
        return handle;
    }

    /** True when the handle is null or names a live entry of this request. */
    boolean valid(int handle) {
        if (handle == 0) {
            return true;
        }
        int index = handle & 0xFFFF;
        return (handle >>> 16) == generation && index > 0 && index < size;
    }

    Object get(int handle) {
        return handle == 0 ? null : objects[handle & 0xFFFF];
    }

    int slotKind(int handle) {
        return slotKind[handle & 0xFFFF];
    }

    Object slotOwner(int handle) {
        return slotOwner[handle & 0xFFFF];
    }

    Object slotKey(int handle) {
        return slotKey[handle & 0xFFFF];
    }

    int slotIndex(int handle) {
        return slotIndex[handle & 0xFFFF];
    }

    Field slotField(int handle) {
        Object key = slotKey[handle & 0xFFFF];
        return key instanceof Field ? (Field) key : null;
    }

    /** Per-handle cache, for example serialized Pando JSON by flag pair. */
    Object extra(int handle) {
        return extra[handle & 0xFFFF];
    }

    void setExtra(int handle, Object value) {
        extra[handle & 0xFFFF] = value;
    }

    /**
     * The iterator of a collection or map handle positioned at element
     * {@code position}; iteration restarts when asked to go backwards.
     */
    Iterator<?> iteratorAt(int handle, Iterable<?> source, int position) {
        int index = handle & 0xFFFF;
        if (iterators[index] == null || iteratorNext[index] > position) {
            iterators[index] = source.iterator();
            iteratorNext[index] = 0;
        }
        Iterator<?> iterator = iterators[index];
        while (iteratorNext[index] < position && iterator.hasNext()) {
            iterator.next();
            iteratorNext[index]++;
        }
        return iterator;
    }

    void advanced(int handle) {
        iteratorNext[handle & 0xFFFF]++;
    }

    /** Frees every handle allocated at or after this one (stack discipline). */
    void releaseFrom(int handle) {
        if (handle == 0 || !valid(handle)) {
            return;
        }
        int from = handle & 0xFFFF;
        clear(from, size);
        size = from;
    }

    /** Ends the request: every handle becomes stale. */
    void reset() {
        clear(1, size);
        size = 1;
        generation = generation == 0xFFFF ? 1 : generation + 1;
    }

    int size() {
        return size - 1;
    }

    private void clear(int from, int to) {
        if (from >= to) {
            return;
        }
        Arrays.fill(objects, from, to, null);
        Arrays.fill(slotOwner, from, to, null);
        Arrays.fill(slotKey, from, to, null);
        Arrays.fill(slotKind, from, to, (byte) 0);
        Arrays.fill(extra, from, to, null);
        Arrays.fill(iterators, from, to, null);
        Arrays.fill(iteratorNext, from, to, 0);
    }
}
