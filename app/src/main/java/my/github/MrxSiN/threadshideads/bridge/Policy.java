package my.github.MrxSiN.threadshideads.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Typed entry points for every top-level request of the Brainfuck core.
 *
 * Each method gathers facts, sends one request and returns the decision. When
 * the core cannot answer (not loaded, nested too deep, malformed response)
 * every method fails open: nothing is removed, hidden or installed beyond what
 * the default says.
 *
 * Thread-safe. The stateless programs run on pooled engines without a shared
 * lock, because their capabilities execute app code that may take app locks.
 * The core program keeps process state, so its requests are serialized.
 */
public final class Policy {

    // Values below mirror brainfuck/src/core.bfa and are part of the ABI.
    public static final int INIT_IGNORE = 0;
    public static final int INIT_START = 1;
    public static final int INIT_RETRY = 2;
    public static final int INIT_EXHAUSTED = 3;
    public static final int INIT_COMPLETE = 4;

    public static final int STEP_BEGIN = 0;
    public static final int STEP_PANDO = 1;
    public static final int STEP_LOAD_NATIVE = 2;
    public static final int STEP_OPEN_LOADER = 3;
    public static final int STEP_OPEN_APK = 4;
    public static final int STEP_FEED_CACHE = 5;
    public static final int STEP_PRIMARY = 6;
    public static final int STEP_BROAD = 7;
    public static final int STEP_DONE = 254;
    public static final int STEP_ABORT = 255;

    public static final int DECISION_NONE = 0;
    public static final int DECISION_TOO_BROAD = 1;
    public static final int DECISION_INSTALL = 2;
    public static final int DECISION_AMBIGUOUS = 3;

    public static final int BROAD_SKIP = 0;
    public static final int BROAD_LIST = 1;
    public static final int BROAD_BOOLEAN = 2;

    public static final int STAT_MODEL_REMOVAL = 1;
    public static final int STAT_UI_COLLAPSE = 2;
    public static final int STAT_REPLACEMENT_FAILURE = 3;

    public static final int UI_HIDE = 1;
    public static final int UI_RESTORE = 2;
    public static final int HIDE_NONE = 0;
    public static final int HIDE_CARD = 1;
    public static final int HIDE_RETRY = 2;
    public static final int HIDE_LABEL = 3;
    public static final int CARD_NONE = 0xFE;

    public static final int TABLE_FEED_OWNERS = 0;
    public static final int TABLE_DEX_MARKERS = 1;
    public static final int TABLE_NAMES = 2;
    public static final int NAME_PRIMARY_MARKER = 0;
    public static final int NAME_PANDO_TREE = 1;
    public static final int NAME_APPLICATION = 2;
    public static final int NAME_ATTACH = 3;
    public static final int NAME_DEXKIT_LIBRARY = 4;
    public static final int NAME_COPY_FACTORY = 5;
    public static final int NAME_SPOOL_HANDLER = 6;

    /** Runs the core program, whose persistent cells are shared state. */
    private final NativeCore coreEngine;
    private final ReentrantLock coreLock = new ReentrantLock();

    /** Idle engines for the stateless programs; no lock is held while they run. */
    private final ConcurrentLinkedQueue<NativeCore> idle = new ConcurrentLinkedQueue<>();

    /** Nested requests a thread may start from inside a capability. */
    private static final int MAX_NESTING = 2;
    private static final int MAX_IDLE = 8;
    private final ThreadLocal<int[]> nesting = new ThreadLocal<int[]>() {
        @Override
        protected int[] initialValue() {
            return new int[1];
        }
    };

    public Policy(NativeCore coreEngine) {
        this.coreEngine = coreEngine;
    }

    // ------------------------------------------------------------ value types

    /** ModelHookInstaller decisions. */
    public static final class Init {
        public final int action;
        public final int reason;
        public final long attempt;
        public final long delayMs;
        public final int nextRetry;

        Init(int action, int reason, long attempt, long delayMs, int nextRetry) {
            this.action = action;
            this.reason = reason;
            this.attempt = attempt;
            this.delayMs = delayMs;
            this.nextRetry = nextRetry;
        }
    }

    /** ListSanitizer.sanitize result. */
    public static final class Sanitized {
        public final boolean changed;
        public final Object value;
        public final int removed;
        public final boolean emptyingBlocked;

        Sanitized(boolean changed, Object value, int removed, boolean emptyingBlocked) {
            this.changed = changed;
            this.value = value;
            this.removed = removed;
            this.emptyingBlocked = emptyingBlocked;
        }
    }

    /** PandoResponseHooks.filterResult outcome. */
    public static final class PandoFilter {
        public static final int NONE = 0;
        public static final int REPLACED = 1;
        public static final int BLOCKED = 2;
        public static final int REPLACE_FAILED = 3;

        public final int action;
        public final Object result;
        public final int removed;

        PandoFilter(int action, Object result, int removed) {
            this.action = action;
            this.result = result;
            this.removed = removed;
        }
    }

    /** One removal reported by a boundary request: site, argument index, count. */
    public static final class Report {
        public final int site;
        public final int index;
        public final int count;

        Report(int site, int index, int count) {
            this.site = site;
            this.index = index;
            this.count = count;
        }
    }

    // ------------------------------------------------------------ plumbing

    /**
     * An engine for a stateless program: an idle one or a new one. Requests
     * never wait for each other; app code a capability runs may itself fire a
     * hook, which gets its own engine up to MAX_NESTING deep, then fails open.
     */
    private NativeCore pooled() {
        int[] depth = nesting.get();
        if (depth[0] >= MAX_NESTING) {
            return null;
        }
        depth[0]++;
        NativeCore engine = idle.poll();
        return engine != null ? engine : coreEngine.spawn();
    }

    /** The core engine, or null when this thread is already inside it. */
    private NativeCore core(boolean wait) {
        if (coreLock.isHeldByCurrentThread()) {
            return null;
        }
        try {
            if (wait) {
                coreLock.lock();
            } else if (!coreLock.tryLock(STATS_WAIT_MS, TimeUnit.MILLISECONDS)) {
                return null;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return coreEngine;
    }

    private void release(NativeCore engine) {
        engine.end();
        if (engine == coreEngine) {
            coreLock.unlock();
            return;
        }
        nesting.get()[0]--;
        if (idle.size() < MAX_IDLE) {
            idle.offer(engine);
        }
    }

    /**
     * Statistics come from any thread, possibly one holding app locks; they
     * never wait long for the core, and skip the log line when they cannot.
     */
    private static final long STATS_WAIT_MS = 50;

    private static int handle(Host host, Object value) {
        return host.handles.put(value);
    }

    private static byte bool(boolean value) {
        return (byte) (value ? 1 : 0);
    }

    private static void putDeclared(ByteBuffer out, Class<?> type) {
        byte[] facts = new byte[3];
        Reflector.declaredFacts(type, facts, 0);
        out.put(facts);
    }

    // ------------------------------------------------------------ text

    /** One vocabulary matcher over a string (tests and cold paths). */
    public boolean textMatch(int matcher, int transform, String value) {
        final NativeCore core = pooled();
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_TEXT_MATCH);
            out.put(2, (byte) BfAbi.OP_TEXT_MATCH);
            out.put(3, (byte) BfAbi.FLAG_STREAM);
            out.put((byte) matcher);
            byte[] bytes = TextEncoder.encode(TextEncoder.transform(value, transform));
            out.put(bytes);
            out.put((byte) 0);
            // Stream programs read to the 0 terminator and ignore the header len.
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    // ------------------------------------------------------------ feed model

    /** ReflectiveAdDetector.looksLikeAd. */
    public boolean looksLikeAd(Object value) {
        final NativeCore core = pooled();
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_DETECT);
            out.putInt(handle(host, value));
            out.put((byte) 0);
            out.putShort((short) 0);
            out.put((byte) 0);
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    /** ListSanitizer.sanitize. */
    public Sanitized sanitizeList(Object list, Class<?> declaredType,
                                               boolean allowReplacement, boolean allowEmptying) {
        final NativeCore core = pooled();
        if (core == null) {
            return new Sanitized(false, list, 0, false);
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_SANITIZE_LIST);
            out.putInt(handle(host, list));
            putDeclared(out, declaredType);
            out.put(bool(allowReplacement));
            out.put(bool(allowEmptying));
            if (core.send() != BfAbi.ST_OK) {
                return new Sanitized(false, list, 0, false);
            }
            ByteBuffer in = core.payload();
            boolean changed = in.get(0) != 0;
            Object value = host.handles.get(in.getInt(1));
            return new Sanitized(changed, changed ? value : list, in.getInt(5), in.get(9) != 0);
        } finally {
            release(core);
        }
    }

    /** NestedCollectionSanitizer.sanitize: {removed, replacement failures}. */
    public int[] nestedSanitize(Object root) {
        final NativeCore core = pooled();
        if (core == null) {
            return new int[2];
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_NESTED_SANITIZE);
            out.putInt(handle(host, root));
            if (core.send() != BfAbi.ST_OK) {
                return new int[2];
            }
            ByteBuffer in = core.payload();
            return new int[]{in.getInt(0), in.getInt(4)};
        } finally {
            release(core);
        }
    }

    /** FeedModelHooks.beforeHookedMethod. */
    public List<Report> boundaryBefore(CallFrame call) {
        return boundary(call, BfAbi.OP_BOUNDARY_BEFORE, false);
    }

    /** FeedModelHooks.afterHookedMethod. */
    public List<Report> boundaryAfter(CallFrame call, boolean threw) {
        return boundary(call, BfAbi.OP_BOUNDARY_AFTER, threw);
    }

    private List<Report> boundary(CallFrame call, int opcode, boolean threw) {
        List<Report> reports = new ArrayList<>();
        final NativeCore core = pooled();
        if (core == null) {
            return reports;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(opcode);
            host.frame = call;
            out.put((byte) Math.min(call.argumentCount(), 254));
            if (opcode == BfAbi.OP_BOUNDARY_AFTER) {
                out.put(bool(threw));
            }
            if (core.send() == BfAbi.ST_OK) {
                for (int[] r : host.reports) {
                    reports.add(new Report(r[0], r[1], r[2]));
                }
            }
            return reports;
        } finally {
            release(core);
        }
    }

    /** PandoResponseHooks.filterResult. */
    public PandoFilter pandoFilter(Object result, Class<?> declaredType) {
        final NativeCore core = pooled();
        if (core == null) {
            return new PandoFilter(PandoFilter.NONE, result, 0);
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_FILTER);
            out.putInt(handle(host, result));
            putDeclared(out, declaredType);
            out.putInt(handle(host, declaredType));
            if (core.send() != BfAbi.ST_OK) {
                return new PandoFilter(PandoFilter.NONE, result, 0);
            }
            ByteBuffer in = core.payload();
            int action = in.get(0);
            Object value = action == PandoFilter.REPLACED ? host.handles.get(in.getInt(1)) : result;
            return new PandoFilter(action, value, in.getInt(5));
        } finally {
            release(core);
        }
    }

    /** PandoAdSignals.isSponsoredTree. */
    public boolean pandoSponsored(Object tree) {
        final NativeCore core = pooled();
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_VERDICT);
            out.putInt(handle(host, tree));
            out.put((byte) 0);
            out.put((byte) 0);
            out.put((byte) 0);
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** MainHook: {in scope, install the rendered-label hooks, install the attach guard}. */
    public boolean[] loadPackage(String packageName, String processName) {
        boolean[] none = new boolean[3];
        final NativeCore core = core(true);
        if (core == null) {
            return none;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_LOAD_PACKAGE);
            out.putInt(handle(host, packageName));
            out.putInt(handle(host, processName));
            if (core.send() != BfAbi.ST_OK) {
                return none;
            }
            ByteBuffer in = core.payload();
            return new boolean[]{in.get(0) != 0, in.get(1) != 0, in.get(2) != 0};
        } finally {
            release(core);
        }
    }

    public boolean attachGuardFailed() {
        return simpleFlag(BfAbi.OP_ATTACH_GUARD_FAILED);
    }

    public Init initRequest(boolean loaderPresent, int retryIndex) {
        return init(BfAbi.OP_INIT_REQUEST, loaderPresent, retryIndex);
    }

    public Init initFinish(boolean success, int retryIndex) {
        return init(BfAbi.OP_INIT_FINISH, success, retryIndex);
    }

    private Init init(int opcode, boolean flag, int retryIndex) {
        final NativeCore core = core(true);
        if (core == null) {
            return new Init(INIT_IGNORE, 0, 0, 0, 0);
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(opcode);
            out.put(bool(flag));
            out.put((byte) retryIndex);
            if (core.send() != BfAbi.ST_OK) {
                return new Init(INIT_IGNORE, 0, 0, 0, 0);
            }
            ByteBuffer in = core.payload();
            return new Init(in.get(0), in.get(1), in.getInt(2) & 0xFFFFFFFFL,
                    in.getInt(6) & 0xFFFFFFFFL, in.get(10) & 0xFF);
        } finally {
            release(core);
        }
    }

    /** ModelHookInstaller.installHooks order: {next step, success, detail}. */
    public int[] installStep(int step, boolean ok, int aux, int feedHooks, int pandoHooks) {
        final NativeCore core = core(true);
        if (core == null) {
            return new int[]{STEP_ABORT, 0, 0};
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_INSTALL_STEP);
            out.put((byte) step);
            out.put(bool(ok));
            out.put((byte) Math.min(aux, 255));
            out.putShort((short) Math.min(feedHooks, 0xFFFF));
            out.putShort((short) Math.min(pandoHooks, 0xFFFF));
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{STEP_ABORT, 0, 0};
            }
            ByteBuffer in = core.payload();
            return new int[]{in.get(0) & 0xFF, in.get(1), in.get(2)};
        } finally {
            release(core);
        }
    }

    // ------------------------------------------------------------ discovery

    public boolean primaryCandidate(Method method) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            Class<?>[] p = method.getParameterTypes();
            int modifiers = method.getModifiers();
            ByteBuffer out = core.begin(BfAbi.OP_PRIMARY_CANDIDATE);
            out.put(bool(Modifier.isStatic(modifiers)));
            out.put(bool(Modifier.isAbstract(modifiers)));
            out.put(bool(method.getDeclaringClass().isInterface()));
            out.put((byte) Math.min(p.length, 255));
            out.put(bool(p.length > 0 && isInteger(p[0])));
            out.put(bool(p.length > 1 && isInteger(p[1])));
            out.put(bool(p.length > 2 && List.class.isAssignableFrom(p[2])));
            out.put(bool(method.getReturnType() == void.class));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    private static boolean isInteger(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    /** {decision, accepted count}; the count is reset afterwards. */
    public int[] primaryDecision() {
        final NativeCore core = core(true);
        if (core == null) {
            return new int[]{DECISION_NONE, 0};
        }
        final Host host = core.host();
        try {
            core.begin(BfAbi.OP_PRIMARY_DECISION);
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{DECISION_NONE, 0};
            }
            ByteBuffer in = core.payload();
            return new int[]{in.get(0), in.getShort(1) & 0xFFFF};
        } finally {
            release(core);
        }
    }

    /** Broad discovery: markers[i] = the method matched DEX marker i. */
    public int broadCandidate(Method method, boolean[] markers) {
        final NativeCore core = core(true);
        if (core == null) {
            return BROAD_SKIP;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_BROAD_CANDIDATE);
            out.put(bool(hasListBoundary(method)));
            Class<?> returned = method.getReturnType();
            out.put(bool(returned == boolean.class || returned == Boolean.class));
            out.putInt(handle(host, method));
            for (int i = 0; i < 13; i++) {
                out.put(bool(i < markers.length && markers[i]));
            }
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : BROAD_SKIP;
        } finally {
            release(core);
        }
    }

    private static boolean hasListBoundary(Method method) {
        if (isListLike(method.getReturnType())) {
            return true;
        }
        for (Class<?> parameter : method.getParameterTypes()) {
            if (isListLike(parameter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isListLike(Class<?> type) {
        return List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }

    public boolean feedOwnerMethod(Method method) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_FEED_OWNER_METHOD);
            out.put(bool(Modifier.isAbstract(method.getModifiers())));
            out.put(bool(List.class.isAssignableFrom(method.getReturnType())));
            boolean takesList = false;
            for (Class<?> parameter : method.getParameterTypes()) {
                takesList |= List.class.isAssignableFrom(parameter);
            }
            out.put(bool(takesList));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    public boolean injectorMethod(Method method) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            Class<?>[] p = method.getParameterTypes();
            ByteBuffer out = core.begin(BfAbi.OP_INJECTOR_METHOD);
            out.put(bool(Modifier.isAbstract(method.getModifiers())));
            out.put(bool(method.getReturnType() == boolean.class));
            out.put((byte) Math.min(p.length, 255));
            out.put(bool(p.length > 0 && p[0].isPrimitive()));
            out.put(bool(p.length > 1 && p[1] == int.class));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    public int injectorDecision() {
        return simpleByte(BfAbi.OP_INJECTOR_DECISION, DECISION_NONE);
    }

    public boolean pandoAccessor(Method method) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            int modifiers = method.getModifiers();
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_ACCESSOR);
            out.put(bool(Modifier.isAbstract(modifiers)));
            out.put(bool(Modifier.isNative(modifiers)));
            out.put(bool(List.class.isAssignableFrom(method.getReturnType())));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    /** A name from one of the core's tables, or null past the end. */
    public String constString(int table, int index) {
        final NativeCore core = core(true);
        if (core == null) {
            return null;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_CONST_STRING);
            out.put((byte) table);
            out.put((byte) index);
            if (core.send() != BfAbi.ST_OK) {
                return null;
            }
            ByteBuffer in = core.payload();
            byte[] bytes = new byte[core.payloadLength()];
            in.get(bytes);
            return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            release(core);
        }
    }

    public List<String> constTable(int table) {
        List<String> values = new ArrayList<>();
        for (int i = 0; i < 255; i++) {
            String value = constString(table, i);
            if (value == null) {
                break;
            }
            values.add(value);
        }
        return values;
    }

    /** {field class depth, max ancestor depth, list-container walk length}. */
    public int[] limits() {
        final NativeCore core = core(true);
        if (core == null) {
            return new int[]{6, 14, 9};
        }
        final Host host = core.host();
        try {
            core.begin(BfAbi.OP_LIMITS);
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{6, 14, 9};
            }
            ByteBuffer in = core.payload();
            int[] limits = {in.get(0), in.get(1), in.get(2)};
            coreEngine.host().reflector.fieldClassDepth = limits[0];
            return limits;
        } finally {
            release(core);
        }
    }

    /** Applies names and limits the host's mechanisms need from the core. */
    public void configureHost() {
        limits();
        String factory = constString(TABLE_NAMES, NAME_COPY_FACTORY);
        if (factory != null) {
            coreEngine.host().reflector.copyFactory = factory;
        }
    }

    // ------------------------------------------------------------ statistics

    /** ModuleStats: {log (0/1), total, event}. */
    public long[] stats(int kind, int count) {
        final NativeCore core = core(false);
        if (core == null) {
            return new long[3];
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_STATS);
            out.put((byte) kind);
            out.putInt(count);
            if (core.send() != BfAbi.ST_OK) {
                return new long[3];
            }
            ByteBuffer in = core.payload();
            return new long[]{in.get(0), in.getInt(1) & 0xFFFFFFFFL, in.getInt(5) & 0xFFFFFFFFL};
        } finally {
            release(core);
        }
    }

    // ------------------------------------------------------------ rendered labels

    /** RenderedLabelHooks: UI_HIDE or UI_RESTORE for a text/description update. */
    public int uiTextEvent(boolean description, Object view) {
        final NativeCore core = core(true);
        if (core == null) {
            return 0;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_TEXT_EVENT);
            out.put(bool(description));
            out.putInt(handle(host, view));
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : 0;
        } finally {
            release(core);
        }
    }

    /** AdLabelScanner.isCurrentAdMarker. */
    public boolean uiMarker(Object view, boolean textView) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_MARKER);
            out.putInt(handle(host, view));
            out.put(bool(textView));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    /**
     * FeedItemHider.findFeedCard over the ancestor chain, nearest first:
     * listContainer[i], rootOrDecor[i], width[i], height[i]. Returns the
     * chain index of the card, or CARD_NONE.
     */
    public int uiCard(boolean[] listContainer, boolean[] rootOrDecor, int[] width,
                                   int[] height, int screenWidth, int screenHeight, float density) {
        final NativeCore core = core(true);
        if (core == null) {
            return CARD_NONE;
        }
        final Host host = core.host();
        try {
            int count = Math.min(listContainer.length, 255);
            ByteBuffer out = core.begin(BfAbi.OP_UI_CARD);
            out.put((byte) count);
            out.putShort((short) Math.min(screenWidth, 0xFFFF));
            out.putShort((short) Math.min(screenHeight, 0xFFFF));
            out.putInt(Math.round(density * 65536f));
            for (int i = 0; i < count; i++) {
                out.put(bool(listContainer[i]));
                out.put(bool(rootOrDecor[i]));
                out.putShort((short) Math.max(0, Math.min(width[i], 0xFFFF)));
                out.putShort((short) Math.max(0, Math.min(height[i], 0xFFFF)));
            }
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) & 0xFF : CARD_NONE;
        } finally {
            release(core);
        }
    }

    /** FeedItemHider.hide: {action, next attempt}. */
    public int[] uiHide(boolean marker, boolean cardFound, int attempt, boolean armFailed) {
        final NativeCore core = core(true);
        if (core == null) {
            return new int[]{HIDE_NONE, 0};
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_HIDE);
            out.put(bool(marker));
            out.put(bool(cardFound));
            out.put((byte) attempt);
            out.put(bool(armFailed));
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{HIDE_NONE, 0};
            }
            ByteBuffer in = core.payload();
            return new int[]{in.get(0), in.get(1)};
        } finally {
            release(core);
        }
    }

    public boolean uiRestore(boolean hiddenBySameLabel, boolean stillLabelled) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_RESTORE);
            out.put(bool(hiddenBySameLabel));
            out.put(bool(stillLabelled));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    /** AdLabelScanner.findAdLabel: the first labelled view, or null. */
    public Object uiScan(Object root, boolean rescan) {
        final NativeCore core = core(true);
        if (core == null) {
            return null;
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_SCAN);
            out.putInt(handle(host, root));
            out.put(bool(rescan));
            return core.send() == BfAbi.ST_OK ? host.handles.get(core.payload().getInt(0)) : null;
        } finally {
            release(core);
        }
    }

    /** ViewTrees: {list container, decor view} for the view's class. */
    public boolean[] uiViewClass(Object view) {
        final NativeCore core = core(true);
        if (core == null) {
            return new boolean[2];
        }
        final Host host = core.host();
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_VIEW_CLASS);
            out.putInt(handle(host, view));
            if (core.send() != BfAbi.ST_OK) {
                return new boolean[2];
            }
            ByteBuffer in = core.payload();
            return new boolean[]{in.get(0) != 0, in.get(1) != 0};
        } finally {
            release(core);
        }
    }

    /** RenderedLabelHooks addView: list-container flags of the parent chain. */
    public boolean uiAddView(boolean[] listContainerChain) {
        final NativeCore core = core(true);
        if (core == null) {
            return false;
        }
        final Host host = core.host();
        try {
            int count = Math.min(listContainerChain.length, 255);
            ByteBuffer out = core.begin(BfAbi.OP_UI_ADD_VIEW);
            out.put((byte) count);
            for (int i = 0; i < count; i++) {
                out.put(bool(listContainerChain[i]));
            }
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            release(core);
        }
    }

    // ------------------------------------------------------------ misc

    public void reset() {
        final NativeCore core = core(true);
        if (core == null) {
            return;
        }
        final Host host = core.host();
        try {
            core.begin(BfAbi.OP_RESET);
            core.send();
        } finally {
            release(core);
        }
    }

    public int version() {
        return simpleByte(BfAbi.OP_VERSION, 0);
    }

    private boolean simpleFlag(int opcode) {
        return simpleByte(opcode, 0) != 0;
    }

    private int simpleByte(int opcode, int fallback) {
        final NativeCore core = core(true);
        if (core == null) {
            return fallback;
        }
        final Host host = core.host();
        try {
            core.begin(opcode);
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : fallback;
        } finally {
            release(core);
        }
    }

    /** {programs run, host callbacks} since load, over every engine. */
    public long[] counters() {
        long[] total = coreEngine.counters();
        for (NativeCore engine : idle) {
            long[] c = engine.counters();
            total[0] += c[0];
            total[1] += c[1];
        }
        return total;
    }
}
