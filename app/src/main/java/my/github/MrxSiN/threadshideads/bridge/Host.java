package my.github.MrxSiN.threadshideads.bridge;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Serves the capability frames Brainfuck programs issue (docs/BRAINFUCK_ABI.md,
 * "Capabilities"): reading objects, identity sets, per-class memos, list
 * mutation and text streams. Every method here performs an operation it was
 * asked for; the decision to ask lives in brainfuck/src.
 *
 * One Host per engine; an engine serves one request at a time.
 */
final class Host {

    /** Result flag telling the runtime that an answer continues. */
    static final int MORE = 0x40000000;

    private static final int DESCRIPTOR_SIZE = 25;

    static boolean DEBUG_ERRORS = Boolean.getBoolean("threadsbf.debugErrors");

    final HandleTable handles = new HandleTable();
    final Reflector reflector;

    /** Hooked call of the current boundary request, if any. */
    CallFrame frame;

    /** Removal reports of the current request: site, index, count. */
    final List<int[]> reports = new ArrayList<>();

    ViewAccess views;

    private final Map<Class<?>, byte[]> classMemo;
    private final Map<Class<?>, byte[]> fieldMemo;
    private final Map<Object, Boolean> treeVerdicts;

    @SuppressWarnings("unchecked")
    private final Map<Object, Boolean>[] visited = new Map[]{
            new IdentityHashMap<Object, Boolean>(), new IdentityHashMap<Object, Boolean>()};

    /** The list whose entries are marked; compared by identity, not handle. */
    private Object markedList;
    private final ArrayList<Integer> marks = new ArrayList<>();

    private final ArrayDeque<Object> scanQueue = new ArrayDeque<>();
    private Object scanLast;
    private int scanMaxChildren;

    private final CharSequence[] streams = new CharSequence[NativeCore.LEVELS];
    private final int[] streamPos = new int[NativeCore.LEVELS];
    private final boolean[] streamEnded = new boolean[NativeCore.LEVELS];
    private final byte[] encodeScratch = new byte[NativeCore.LEVEL_IN_CAP];
    private final int[] written = new int[1];
    private final byte[] payload = new byte[NativeCore.LEVEL_IN_CAP - BfAbi.HEADER_SIZE];

    Host(SharedFacts shared) {
        reflector = shared.reflector;
        classMemo = shared.classMemo;
        fieldMemo = shared.fieldMemo;
        treeVerdicts = shared.treeVerdicts;
    }

    // ------------------------------------------------------------ lifecycle

    void endRequest() {
        handles.reset();
        frame = null;
        reports.clear();
        // IdentityHashMap.clear() costs its capacity, which one large
        // traversal can grow; a used set is replaced instead.
        for (int i = 0; i < visited.length; i++) {
            if (!visited[i].isEmpty()) {
                visited[i] = new IdentityHashMap<>();
            }
        }
        marks.clear();
        markedList = null;
        scanQueue.clear();
        scanLast = null;
        for (int i = 0; i < streams.length; i++) {
            streams[i] = null;
        }
    }

    // ------------------------------------------------------------ capabilities

    int capability(ByteBuffer request, int length, ByteBuffer dst, int offset) {
        int op = request.get(2) & 0xFF;
        int status = BfAbi.ST_OK;
        int size = 0;
        try {
            switch (op) {
                case BfAbi.CAP_NODE:
                    size = node(request.getInt(8));
                    break;
                case BfAbi.CAP_CHILD:
                    size = child(request.getInt(8), request.getInt(12), request.get(16));
                    break;
                case BfAbi.CAP_CHILDREN:
                    size = children(request.getInt(8), request.getInt(12),
                            request.getShort(16) & 0xFFFF, request.get(18));
                    break;
                case BfAbi.CAP_VISIT:
                    size = visit(request.get(8), request.get(9), request.getInt(10));
                    break;
                case BfAbi.CAP_CLASS_INFO:
                    payload[0] = bool(reflector.overridesToString(object(request.getInt(8)).getClass()));
                    size = 1;
                    break;
                case BfAbi.CAP_MEMO_GET:
                    payload[0] = memoGet(request.getInt(8), request.get(12), request.getShort(14) & 0xFFFF);
                    size = 1;
                    break;
                case BfAbi.CAP_MEMO_PUT:
                    memoPut(request.getInt(8), request.get(12), request.get(13), request.getShort(14) & 0xFFFF);
                    payload[0] = 0;
                    size = 1;
                    break;
                case BfAbi.CAP_JSON_LENGTH:
                    putInt(0, jsonLength(request.getInt(8), request.get(12) & 3));
                    size = 4;
                    break;
                case BfAbi.CAP_MARK:
                    mark(request.getInt(8), request.getInt(12));
                    break;
                case BfAbi.CAP_REMOVE_MARKED:
                    putInt(0, removeMarked(request.getInt(8)));
                    size = 4;
                    break;
                case BfAbi.CAP_FILTERED_COPY:
                    putInt(0, filteredCopy(request.getInt(8)));
                    size = 4;
                    break;
                case BfAbi.CAP_WRITE_BACK:
                    payload[0] = bool(writeBack(request.getInt(8), request.getInt(12)));
                    size = 1;
                    break;
                case BfAbi.CAP_COPY_OF:
                    putInt(0, copyOf(request.getInt(8), request.getInt(12), request.get(16)));
                    size = 4;
                    break;
                case BfAbi.CAP_RELEASE:
                    handles.releaseFrom(request.getInt(8));
                    break;
                case BfAbi.CAP_SUPERCLASS:
                    putInt(0, superclass(request.getInt(8), request.get(12) & 0xFF));
                    size = 4;
                    break;
                case BfAbi.CAP_FIND_ACCESSOR:
                    payload[0] = bool(findAccessor(request));
                    size = 1;
                    break;
                case BfAbi.CAP_SCAN_NEXT:
                    size = scanNext(request.getInt(8), request.get(12) & 0xFF, request.get(13));
                    break;
                case BfAbi.CAP_ARG:
                    size = arg(request.get(8) & 0xFF);
                    break;
                case BfAbi.CAP_SET_ARG:
                    setArg(request.get(8) & 0xFF, request.getInt(9));
                    break;
                case BfAbi.CAP_TREE_VERDICT_GET:
                    payload[0] = treeVerdictGet(request.getInt(8));
                    size = 1;
                    break;
                case BfAbi.CAP_TREE_VERDICT_PUT:
                    treeVerdictPut(request.getInt(8), request.get(12));
                    break;
                case BfAbi.CAP_REPORT:
                    reports.add(new int[]{request.get(8) & 0xFF, request.get(9) & 0xFF, request.getInt(10)});
                    break;
                default:
                    status = BfAbi.ST_BAD_OPCODE;
            }
        } catch (StaleHandle e) {
            status = BfAbi.ST_STALE_HANDLE;
            size = 0;
        } catch (NoMoreFields e) {
            status = BfAbi.ST_NOT_FOUND;
            size = 0;
        } catch (Throwable t) {
            if (DEBUG_ERRORS) {
                System.err.println("capability 0x" + Integer.toHexString(op) + " failed: " + t);
            }
            status = BfAbi.ST_HOST_ERROR;
            size = 0;
        }
        return writeResponse(dst, offset, op, status, size);
    }

    private int writeResponse(ByteBuffer dst, int offset, int op, int status, int size) {
        dst.put(offset, (byte) BfAbi.ABI_MAJOR);
        dst.put(offset + 1, (byte) BfAbi.ABI_MINOR);
        dst.put(offset + 2, (byte) (op | BfAbi.RESPONSE_BIT));
        dst.put(offset + 3, (byte) status);
        dst.putShort(offset + 4, (short) size);
        dst.putShort(offset + 6, (short) 0);
        if (size > 0) {
            ByteBuffer view = dst.duplicate();
            view.position(offset + BfAbi.HEADER_SIZE);
            view.put(payload, 0, size);
        }
        return BfAbi.HEADER_SIZE + size;
    }

    // ------------------------------------------------------------ objects

    private static final class NoMoreFields extends RuntimeException {
        NoMoreFields() {
            super(null, null, false, false);
        }
    }

    private static final class StaleHandle extends RuntimeException {
        StaleHandle() {
            super(null, null, false, false);
        }
    }

    private Object object(int handle) {
        if (!handles.valid(handle)) {
            throw new StaleHandle();
        }
        return handles.get(handle);
    }

    private int node(int handle) {
        describe(object(handle), payload, 0);
        return DESCRIPTOR_SIZE;
    }

    /** Descriptor: 10 kind flags, class facts memo, size[4], value[8]. */
    private void describe(Object value, byte[] out, int at) {
        for (int i = 0; i < DESCRIPTOR_SIZE; i++) {
            out[at + i] = 0;
        }
        if (value == null) {
            return;
        }
        Class<?> type = value.getClass();
        out[at] = bool(value instanceof CharSequence);
        out[at + 1] = bool(value instanceof Enum<?>);
        out[at + 2] = bool(value instanceof Number);
        out[at + 3] = bool(value instanceof Boolean);
        out[at + 4] = bool(value instanceof Character);
        out[at + 5] = bool(value instanceof Class<?>);
        out[at + 6] = bool(value instanceof Map<?, ?>);
        out[at + 7] = bool(value instanceof Collection<?>);
        out[at + 8] = bool(value instanceof List<?>);
        out[at + 9] = bool(type.isArray());
        byte[] memo = classMemo.get(type);
        out[at + 10] = memo == null ? 0 : memo[BfAbi.SLOT_FACTS];
        int size;
        try {
            if (value instanceof CharSequence) {
                size = ((CharSequence) value).length();
            } else if (value instanceof Map<?, ?>) {
                size = ((Map<?, ?>) value).size();
            } else if (value instanceof Collection<?>) {
                size = ((Collection<?>) value).size();
            } else if (type.isArray()) {
                size = Array.getLength(value);
            } else {
                size = reflector.instanceFields(type).length;
            }
        } catch (Throwable ignored) {
            size = 0;
        }
        putInt(out, at + 11, size);
        long number = 0;
        if (value instanceof Number) {
            number = ((Number) value).longValue();
        } else if (value instanceof Boolean) {
            number = (Boolean) value ? 1 : 0;
        }
        for (int i = 0; i < 8; i++) {
            out[at + 15 + i] = (byte) (number >>> (8 * i));
        }
        // Known Pando trees carry their cached verdict (class fact bit 3):
        // 1 not sponsored, 2 sponsored, 3 looked up and not cached. Their
        // class's JSON flag pair comes along too.
        if (memo != null && (memo[BfAbi.SLOT_FACTS] & 8) != 0) {
            byte verdict = treeVerdictGet(value);
            out[at + 23] = verdict == 0 ? 3 : verdict;
            out[at + 24] = memo[BfAbi.SLOT_PANDO_FLAGS];
        }
    }

    /**
     * CHILD response: child handle[4], descriptor, declared slot facts[3],
     * field-name memo, key handle[4].
     */
    /** Size of one CHILD record. */
    private static final int CHILD_RECORD = 4 + DESCRIPTOR_SIZE + 8;

    private int child(int handle, int index, int mode) {
        return child(handle, index, mode, 0);
    }

    /**
     * CHILDREN: up to `count` consecutive CHILD records from `start`. Stops
     * early (and the reader sees zeros) when an element cannot be read.
     */
    private int children(int handle, int start, int count, int mode) {
        int max = Math.min(count, payload.length / CHILD_RECORD);
        int at = 0;
        for (int i = 0; i < max; i++) {
            try {
                at += child(handle, start + i, mode, at);
            } catch (StaleHandle e) {
                throw e;
            } catch (Throwable unreadable) {
                break;
            }
        }
        return at;
    }

    private int child(int handle, int index, int mode, int at) {
        Object owner = object(handle);
        Object value;
        int childHandle;
        int keyHandle = 0;
        Class<?> declared = Object.class;
        int fieldFact = 0;
        switch (mode) {
            case BfAbi.MODE_LIST: {
                List<?> list = (List<?>) owner;
                value = list.get(index);
                childHandle = handles.putSlot(value, HandleTable.SLOT_LIST, list, null, index);
                break;
            }
            case BfAbi.MODE_ITER: {
                Iterator<?> iterator = handles.iteratorAt(handle, (Iterable<?>) owner, index);
                if (!iterator.hasNext()) {
                    throw new IndexOutOfBoundsException();
                }
                value = iterator.next();
                handles.advanced(handle);
                childHandle = handles.put(value);
                break;
            }
            case BfAbi.MODE_MAP_ENTRY: {
                Map<?, ?> map = (Map<?, ?>) owner;
                Iterator<?> iterator = handles.iteratorAt(handle, map.entrySet(), index);
                if (!iterator.hasNext()) {
                    throw new IndexOutOfBoundsException();
                }
                Map.Entry<?, ?> entry = (Map.Entry<?, ?>) iterator.next();
                handles.advanced(handle);
                // The key first, so releasing it also releases the value.
                keyHandle = handles.put(entry.getKey());
                value = entry.getValue();
                childHandle = handles.putSlot(value, HandleTable.SLOT_MAP_ENTRY, map, entry, 0);
                break;
            }
            case BfAbi.MODE_ARRAY: {
                value = Array.get(owner, index);
                declared = owner.getClass().getComponentType();
                childHandle = handles.putSlot(value, HandleTable.SLOT_ARRAY, owner, null, index);
                break;
            }
            case BfAbi.MODE_FIELD:
            case BfAbi.MODE_FIELD_NEXT: {
                Field[] fields = reflector.instanceFields(owner.getClass());
                if (mode == BfAbi.MODE_FIELD_NEXT) {
                    while (index < fields.length && readField(fields[index], owner) == null) {
                        index++;
                    }
                    if (index >= fields.length) {
                        throw new NoMoreFields();
                    }
                    keyHandle = index;
                }
                if (index >= fields.length) {
                    throw new IndexOutOfBoundsException();
                }
                Field field = fields[index];
                declared = field.getType();
                value = readField(field, owner);
                childHandle = handles.putSlot(value, HandleTable.SLOT_FIELD, owner, field, index);
                byte[] facts = fieldMemo.get(owner.getClass());
                fieldFact = facts == null || index >= facts.length ? 0 : facts[index];
                break;
            }
            default:
                throw new IllegalArgumentException("mode " + mode);
        }
        putInt(payload, at, childHandle);
        describe(value, payload, at + 4);
        Reflector.declaredFacts(declared, payload, at + 4 + DESCRIPTOR_SIZE);
        payload[at + 4 + DESCRIPTOR_SIZE + 3] = (byte) fieldFact;
        putInt(payload, at + 4 + DESCRIPTOR_SIZE + 4, keyHandle);
        return CHILD_RECORD;
    }

    /** A field value, or null when it cannot be read (skipped, as before). */
    private static Object readField(Field field, Object owner) {
        try {
            return field.get(owner);
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private int visit(int set, int mode, int handle) {
        Map<Object, Boolean> seen = visited[set & 1];
        if (mode == BfAbi.VISIT_CLEAR) {
            seen.clear();
            payload[0] = 0;
        } else {
            if (mode == BfAbi.VISIT_RESET_ADD) {
                seen.clear();
            }
            Object value = object(handle);
            payload[0] = bool(value != null && seen.put(value, Boolean.TRUE) == null);
        }
        return 1;
    }

    private byte memoGet(int handle, int slot, int index) {
        Class<?> type = object(handle).getClass();
        if (slot == BfAbi.SLOT_FIELD) {
            byte[] facts = fieldMemo.get(type);
            return facts == null || index >= facts.length ? 0 : facts[index];
        }
        byte[] memo = classMemo.get(type);
        return memo == null ? 0 : memo[slot];
    }

    private void memoPut(int handle, int slot, byte value, int index) {
        Class<?> type = object(handle).getClass();
        if (slot == BfAbi.SLOT_FIELD) {
            byte[] facts = fieldMemo.get(type);
            if (facts == null) {
                facts = new byte[reflector.instanceFields(type).length];
                fieldMemo.put(type, facts);
            }
            if (index < facts.length) {
                facts[index] = value;
            }
            return;
        }
        byte[] memo = classMemo.get(type);
        if (memo == null) {
            memo = new byte[4];
            classMemo.put(type, memo);
        }
        memo[slot] = value;
    }

    private int superclass(int handle, int k) {
        Object value = object(handle);
        Class<?> type = Reflector.superclass(value.getClass(), k);
        return handles.put(type);
    }

    private boolean findAccessor(ByteBuffer request) {
        Object value = object(request.getInt(8));
        int length = request.get(12) & 0xFF;
        char[] name = new char[length];
        for (int i = 0; i < length; i++) {
            name[i] = (char) (request.get(13 + i) & 0xFF);
        }
        return reflector.findAccessor(value.getClass(), new String(name));
    }

    // ------------------------------------------------------------ Pando JSON

    private int jsonLength(int handle, int flags) {
        String json = json(handle, flags);
        return json == null ? BfAbi.JSON_NULL : json.length();
    }

    private static final Object NO_JSON = new Object();

    /** The tree serialized with one flag pair, cached for this request. */
    private String json(int handle, int flags) {
        Object value = object(handle);
        Object[] cache = (Object[]) handles.extra(handle);
        if (cache == null) {
            cache = new Object[4];
            handles.setExtra(handle, cache);
        }
        if (cache[flags] == null) {
            Method accessor = reflector.jsonAccessor(value.getClass());
            Object text = NO_JSON;
            if (accessor != null) {
                try {
                    Object json = accessor.invoke(value, (flags & 1) != 0, (flags & 2) != 0);
                    if (json != null) {
                        text = json.toString();
                    }
                } catch (Throwable ignored) {
                    // Reported as absent, as PandoTrees.invoke did.
                }
            }
            cache[flags] = text;
        }
        return cache[flags] == NO_JSON ? null : (String) cache[flags];
    }

    private byte treeVerdictGet(int handle) {
        return treeVerdictGet(object(handle));
    }

    private byte treeVerdictGet(Object tree) {
        try {
            Boolean verdict = treeVerdicts.get(tree);
            return verdict == null ? 0 : (byte) (verdict ? 2 : 1);
        } catch (Throwable ignored) {
            // equals/hashCode over a native tree may throw: no cache.
            return 0;
        }
    }

    private void treeVerdictPut(int handle, int value) {
        try {
            treeVerdicts.put(object(handle), value == 2);
        } catch (StaleHandle e) {
            throw e;
        } catch (Throwable ignored) {
            // Detection still works without the cache.
        }
    }

    // ------------------------------------------------------------ list mutation

    /** Marks one entry; index -1 starts a fresh set of marks for the list. */
    private void mark(int handle, int index) {
        Object list = object(handle);
        if (index == -1 || list != markedList) {
            marks.clear();
            markedList = list;
        }
        if (index != -1) {
            marks.add(index);
        }
    }

    private int removeMarked(int handle) {
        @SuppressWarnings("unchecked")
        List<Object> list = (List<Object>) object(handle);
        int removed = 0;
        if (list != markedList) {
            return 0;
        }
        // Backwards, so shifts cannot skip entries; stop at the first
        // failure rather than leave a half-rebuilt list.
        for (int i = marks.size() - 1; i >= 0; i--) {
            try {
                list.remove((int) marks.get(i));
                removed++;
            } catch (Throwable ignored) {
                break;
            }
        }
        return removed;
    }

    private int filteredCopy(int handle) {
        List<?> list = (List<?>) object(handle);
        ArrayList<Object> kept = new ArrayList<>(list.size());
        int next = 0;
        List<Integer> marked = list == markedList ? marks : Collections.<Integer>emptyList();
        for (int i = 0; i < list.size(); i++) {
            if (next < marked.size() && marked.get(next) == i) {
                next++;
                continue;
            }
            kept.add(list.get(i));
        }
        return handles.put(kept);
    }

    private boolean writeBack(int handle, int replacementHandle) {
        object(handle);
        Object replacement = object(replacementHandle);
        Object owner = handles.slotOwner(handle);
        try {
            switch (handles.slotKind(handle)) {
                case HandleTable.SLOT_FIELD:
                    handles.slotField(handle).set(owner, replacement);
                    return true;
                case HandleTable.SLOT_LIST: {
                    @SuppressWarnings("unchecked")
                    List<Object> list = (List<Object>) owner;
                    list.set(handles.slotIndex(handle), replacement);
                    return true;
                }
                case HandleTable.SLOT_ARRAY:
                    Array.set(owner, handles.slotIndex(handle), replacement);
                    return true;
                case HandleTable.SLOT_MAP_ENTRY: {
                    @SuppressWarnings("unchecked")
                    Map.Entry<Object, Object> entry = (Map.Entry<Object, Object>) handles.slotKey(handle);
                    try {
                        entry.setValue(replacement);
                        return true;
                    } catch (Throwable unsupported) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> map = (Map<Object, Object>) owner;
                        map.put(entry.getKey(), replacement);
                        return true;
                    }
                }
                default:
                    return false;
            }
        } catch (Throwable ignored) {
            return false;
        }
    }

    private int copyOf(int keptHandle, int sourceHandle, int mode) {
        @SuppressWarnings("unchecked")
        List<Object> kept = (List<Object>) object(keptHandle);
        Object source = object(sourceHandle);
        if (source == null) {
            return 0;
        }
        Class<?> type = mode == BfAbi.COPY_DECLARED && source instanceof Class<?>
                ? (Class<?>) source : source.getClass();
        return handles.put(reflector.copyThrough(type, kept));
    }

    // ------------------------------------------------------------ hooked call

    private int arg(int index) {
        CallFrame call = frame;
        if (call == null) {
            throw new IllegalStateException("no call frame");
        }
        Object value;
        Class<?> declared;
        if (index == BfAbi.RESULT_INDEX) {
            value = call.result();
            declared = call.returnType();
        } else {
            if (index >= call.argumentCount()) {
                throw new IndexOutOfBoundsException();
            }
            value = call.argument(index);
            declared = call.parameterType(index);
        }
        putInt(payload, 0, handles.put(value));
        payload[4] = bool(value instanceof List<?>);
        Reflector.declaredFacts(declared, payload, 5);
        Reflector.declaredFacts(value == null ? null : value.getClass(), payload, 8);
        return 11;
    }

    private void setArg(int index, int handle) {
        Object value = object(handle);
        if (index == BfAbi.RESULT_INDEX) {
            frame.setResult(value);
        } else {
            frame.setArgument(index, value);
        }
    }

    // ------------------------------------------------------------ views

    private int scanNext(int rootHandle, int maxChildren, int reset) {
        if (reset != 0) {
            scanQueue.clear();
            scanLast = null;
            scanQueue.add(object(rootHandle));
            scanMaxChildren = maxChildren;
        } else if (scanLast != null) {
            int count = Math.min(views.childCount(scanLast), scanMaxChildren);
            for (int i = 0; i < count; i++) {
                Object child = views.childAt(scanLast, i);
                if (child != null) {
                    scanQueue.add(child);
                }
            }
        }
        Object view = scanQueue.poll();
        scanLast = view;
        putInt(payload, 0, handles.put(view));
        payload[4] = bool(view != null && views.isTextView(view));
        return 5;
    }

    // ------------------------------------------------------------ text streams

    /** Starts streaming a text source to the program about to run at `level`. */
    int fetch(int level, int handle, int source, int transform, ByteBuffer dst, int offset, int cap) {
        CharSequence text;
        try {
            text = source(handle, source, transform);
        } catch (Throwable t) {
            return -1;
        }
        if (text == null) {
            return -1;
        }
        if ((transform & 1) == BfAbi.TX_LOWER_ROOT) {
            text = TextEncoder.transform(text.toString(), BfAbi.TX_LOWER_ROOT);
        }
        streams[level] = text;
        streamPos[level] = 0;
        streamEnded[level] = false;
        return more(level, dst, offset, cap);
    }

    /** Continues the stream of `level`, finishing with a 0 byte. */
    int more(int level, ByteBuffer dst, int offset, int cap) {
        CharSequence text = streams[level];
        if (text == null || streamEnded[level]) {
            return 0;
        }
        int limit = Math.min(cap, encodeScratch.length);
        int next = TextEncoder.encode(text, streamPos[level], encodeScratch, 0, written, limit);
        int n = written[0];
        streamPos[level] = next;
        if (next >= text.length() && n < limit) {
            encodeScratch[n++] = 0;
            streamEnded[level] = true;
            streams[level] = null;
        }
        ByteBuffer view = dst.duplicate();
        view.position(offset);
        view.put(encodeScratch, 0, n);
        return streamEnded[level] ? n : n | MORE;
    }

    private CharSequence source(int handle, int source, int transform) {
        Object value = object(handle);
        switch (source) {
            case BfAbi.SRC_STRING:
                return String.valueOf(value);
            case BfAbi.SRC_ENUM_NAME:
                return ((Enum<?>) value).name();
            case BfAbi.SRC_CLASS_NAME:
                return value.getClass().getName();
            case BfAbi.SRC_TYPE_NAME:
                return ((Class<?>) value).getName();
            case BfAbi.SRC_FIELD_NAME: {
                Field field = handles.slotField(handle);
                return field == null ? null : field.getName();
            }
            case BfAbi.SRC_PANDO_JSON:
                return json(handle, (transform >> 1) & 3);
            case BfAbi.SRC_METHOD_SIGNATURE:
                return ((Method) value).toGenericString();
            case BfAbi.SRC_METHOD_SEMANTIC: {
                Method method = (Method) value;
                return method.getDeclaringClass().getName() + "." + method.getName();
            }
            case BfAbi.SRC_VIEW_TEXT:
                return views.text(value);
            case BfAbi.SRC_VIEW_DESCRIPTION:
                return views.contentDescription(value);
            default:
                return null;
        }
    }

    // ------------------------------------------------------------ helpers

    private static byte bool(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private void putInt(int at, int value) {
        putInt(payload, at, value);
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) value;
        out[at + 1] = (byte) (value >>> 8);
        out[at + 2] = (byte) (value >>> 16);
        out[at + 3] = (byte) (value >>> 24);
    }
}
