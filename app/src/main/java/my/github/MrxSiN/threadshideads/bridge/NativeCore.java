package my.github.MrxSiN.threadshideads.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * The AOT-compiled Brainfuck core (libthreadsbf) and the frames exchanged
 * with it.
 *
 * One request runs at a time. A request issued while another is running on
 * the same thread (an app callback re-entering a hook from inside a
 * capability) is refused, and the caller fails open.
 */
public final class NativeCore {

    static final int LEVELS = BfAbi.RUNTIME_LEVELS;
    static final int LEVEL_IN_CAP = BfAbi.RUNTIME_LEVEL_IN_CAP;

    private static final int REQUEST_CAP = 4096;
    private static final int RESPONSE_CAP = 4096;

    private final long engine;
    private final Host host;
    private final ViewAccess views;
    private final SharedFacts shared;
    private final ByteBuffer request;
    private final ByteBuffer response;
    private final ByteBuffer[] inputs = new ByteBuffer[LEVELS];
    private final ByteBuffer[] outputs = new ByteBuffer[LEVELS];
    private boolean busy;
    private int responseLength;
    private int nextRequestId = 1;

    private NativeCore(ViewAccess views, SharedFacts shared) {
        this.views = views;
        this.shared = shared;
        this.host = new Host(shared);
        host.views = views;
        this.engine = nativeCreate(this);
        if (engine == 0) {
            throw new IllegalStateException("libthreadsbf could not create an engine");
        }
        this.request = ByteBuffer.allocateDirect(REQUEST_CAP).order(ByteOrder.LITTLE_ENDIAN);
        this.response = ByteBuffer.allocateDirect(RESPONSE_CAP).order(ByteOrder.LITTLE_ENDIAN);
        for (int level = 0; level < LEVELS; level++) {
            inputs[level] = nativeBuffer(engine, level, true).order(ByteOrder.LITTLE_ENDIAN);
            outputs[level] = nativeBuffer(engine, level, false).order(ByteOrder.LITTLE_ENDIAN);
        }
    }

    /** Loads libthreadsbf from the module's native library directory. */
    public static NativeCore load(ViewAccess views) {
        System.loadLibrary("threadsbf");
        return create(views);
    }

    /** For host-side tests: the library was loaded with System.load. */
    public static NativeCore create(ViewAccess views) {
        return new NativeCore(views, new SharedFacts());
    }

    /** Another engine sharing this one's caches. */
    NativeCore spawn() {
        return new NativeCore(views, shared);
    }

    Host host() {
        return host;
    }

    /** True while a request runs; a hook firing now must fail open. */
    boolean busy() {
        return busy;
    }

    // ------------------------------------------------------------ requests

    /**
     * Starts a request frame; register handles, then put the payload into the
     * returned buffer. Ends whatever the previous request left behind.
     */
    ByteBuffer begin(int opcode) {
        if (busy) {
            throw new IllegalStateException("request already running; check busy() first");
        }
        host.endRequest();
        request.clear();
        request.put((byte) BfAbi.ABI_MAJOR);
        request.put((byte) BfAbi.ABI_MINOR);
        request.put((byte) opcode);
        request.put((byte) 0);
        request.putShort((short) 0);
        request.putShort((short) nextRequestId);
        nextRequestId = (nextRequestId + 1) & 0xFFFF;
        return request;
    }

    /**
     * Sends the frame started by {@link #begin}. Returns the response status,
     * or -1 when the core could not run (then the caller fails open). The
     * response payload is read with {@link #payload()}.
     */
    int send() {
        int length = request.position();
        request.putShort(4, (short) (length - BfAbi.HEADER_SIZE));
        int opcode = request.get(2) & 0xFF;
        int program = nativeRoute(opcode);
        if (program < 0 || busy) {
            return -1;
        }
        busy = true;
        try {
            responseLength = nativeRequest(engine, program, request, length, response);
        } finally {
            busy = false;
        }
        if (responseLength < BfAbi.HEADER_SIZE
                || (response.get(2) & 0xFF) != (opcode | BfAbi.RESPONSE_BIT)
                || response.getShort(6) != request.getShort(6)) {
            return -1;
        }
        return response.get(3) & 0xFF;
    }

    /** Releases the objects the finished request referenced. */
    void end() {
        if (!busy) {
            host.endRequest();
        }
    }

    ByteBuffer payload() {
        response.position(BfAbi.HEADER_SIZE);
        response.limit(responseLength);
        ByteBuffer view = response.slice().order(ByteOrder.LITTLE_ENDIAN);
        response.clear();
        return view;
    }

    int payloadLength() {
        return responseLength - BfAbi.HEADER_SIZE;
    }

    /** Programs run and host callbacks made so far, for benchmarks. */
    long[] counters() {
        long packed = nativeCounters(engine);
        return new long[]{packed >>> 32, packed & 0xFFFFFFFFL};
    }

    void reset() {
        nativeReset(engine);
    }

    // ------------------------------------------------------------ callbacks from libthreadsbf

    @SuppressWarnings("unused")
    private int onCapability(int level, int requestOffset, int requestLength, int dstOffset, int cap) {
        ByteBuffer frame = outputs[level];
        if (requestOffset != 0) {
            frame = sliceAt(frame, requestOffset);
        }
        return host.capability(frame, requestLength, inputs[level], dstOffset);
    }

    @SuppressWarnings("unused")
    private int onFetch(int level, int handle, int source, int transform, int dstOffset, int cap) {
        return host.fetch(level, handle, source, transform, inputs[level], dstOffset, cap);
    }

    @SuppressWarnings("unused")
    private int onMore(int level, int dstOffset, int cap) {
        return host.more(level, inputs[level], dstOffset, cap);
    }

    private static ByteBuffer sliceAt(ByteBuffer buffer, int offset) {
        ByteBuffer copy = buffer.duplicate();
        copy.position(offset);
        return copy.slice().order(ByteOrder.LITTLE_ENDIAN);
    }

    // ------------------------------------------------------------ JNI

    private static native long nativeCreate(Object host);

    private static native ByteBuffer nativeBuffer(long engine, int level, boolean input);

    private static native int nativeRequest(long engine, int program, ByteBuffer request, int length,
                                            ByteBuffer response);

    private static native int nativeRoute(int opcode);

    private static native void nativeReset(long engine);

    private static native long nativeCounters(long engine);
}
