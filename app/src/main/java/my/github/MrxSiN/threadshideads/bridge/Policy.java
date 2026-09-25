package my.github.MrxSiN.threadshideads.bridge;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Typed entry points for every top-level request of the Brainfuck core.
 *
 * Each method gathers facts, sends one request and returns the decision. When
 * the core cannot answer (not loaded, re-entered, malformed response) every
 * method fails open: nothing is removed, hidden or installed beyond what the
 * default says.
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

    private final NativeCore core;
    private final Host host;

    public Policy(NativeCore core) {
        this.core = core;
        this.host = core.host();
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

    private boolean ready() {
        return !core.busy();
    }

    private int handle(Object value) {
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
    public synchronized boolean textMatch(int matcher, int transform, String value) {
        if (!ready()) {
            return false;
        }
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
            core.end();
        }
    }

    // ------------------------------------------------------------ feed model

    /** ReflectiveAdDetector.looksLikeAd. */
    public synchronized boolean looksLikeAd(Object value) {
        if (!ready()) {
            return false;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_DETECT);
            out.putInt(handle(value));
            out.put((byte) 0);
            out.putShort((short) 0);
            out.put((byte) 0);
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    /** ListSanitizer.sanitize. */
    public synchronized Sanitized sanitizeList(Object list, Class<?> declaredType,
                                               boolean allowReplacement, boolean allowEmptying) {
        if (!ready()) {
            return new Sanitized(false, list, 0, false);
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_SANITIZE_LIST);
            out.putInt(handle(list));
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
            core.end();
        }
    }

    /** NestedCollectionSanitizer.sanitize: {removed, replacement failures}. */
    public synchronized int[] nestedSanitize(Object root) {
        if (!ready()) {
            return new int[2];
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_NESTED_SANITIZE);
            out.putInt(handle(root));
            if (core.send() != BfAbi.ST_OK) {
                return new int[2];
            }
            ByteBuffer in = core.payload();
            return new int[]{in.getInt(0), in.getInt(4)};
        } finally {
            core.end();
        }
    }

    /** FeedModelHooks.beforeHookedMethod. */
    public synchronized List<Report> boundaryBefore(CallFrame call) {
        return boundary(call, BfAbi.OP_BOUNDARY_BEFORE, false);
    }

    /** FeedModelHooks.afterHookedMethod. */
    public synchronized List<Report> boundaryAfter(CallFrame call, boolean threw) {
        return boundary(call, BfAbi.OP_BOUNDARY_AFTER, threw);
    }

    private List<Report> boundary(CallFrame call, int opcode, boolean threw) {
        List<Report> reports = new ArrayList<>();
        if (!ready()) {
            return reports;
        }
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
            core.end();
        }
    }

    /** PandoResponseHooks.filterResult. */
    public synchronized PandoFilter pandoFilter(Object result, Class<?> declaredType) {
        if (!ready()) {
            return new PandoFilter(PandoFilter.NONE, result, 0);
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_FILTER);
            out.putInt(handle(result));
            putDeclared(out, declaredType);
            out.putInt(handle(declaredType));
            if (core.send() != BfAbi.ST_OK) {
                return new PandoFilter(PandoFilter.NONE, result, 0);
            }
            ByteBuffer in = core.payload();
            int action = in.get(0);
            Object value = action == PandoFilter.REPLACED ? host.handles.get(in.getInt(1)) : result;
            return new PandoFilter(action, value, in.getInt(5));
        } finally {
            core.end();
        }
    }

    /** PandoAdSignals.isSponsoredTree. */
    public synchronized boolean pandoSponsored(Object tree) {
        if (!ready()) {
            return false;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_VERDICT);
            out.putInt(handle(tree));
            out.put((byte) 0);
            out.put((byte) 0);
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    // ------------------------------------------------------------ lifecycle

    /** MainHook: {in scope, install the rendered-label hooks, install the attach guard}. */
    public synchronized boolean[] loadPackage(String packageName, String processName) {
        boolean[] none = new boolean[3];
        if (!ready()) {
            return none;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_LOAD_PACKAGE);
            out.putInt(handle(packageName));
            out.putInt(handle(processName));
            if (core.send() != BfAbi.ST_OK) {
                return none;
            }
            ByteBuffer in = core.payload();
            return new boolean[]{in.get(0) != 0, in.get(1) != 0, in.get(2) != 0};
        } finally {
            core.end();
        }
    }

    public synchronized boolean attachGuardFailed() {
        return simpleFlag(BfAbi.OP_ATTACH_GUARD_FAILED);
    }

    public synchronized Init initRequest(boolean loaderPresent, int retryIndex) {
        return init(BfAbi.OP_INIT_REQUEST, loaderPresent, retryIndex);
    }

    public synchronized Init initFinish(boolean success, int retryIndex) {
        return init(BfAbi.OP_INIT_FINISH, success, retryIndex);
    }

    private Init init(int opcode, boolean flag, int retryIndex) {
        if (!ready()) {
            return new Init(INIT_IGNORE, 0, 0, 0, 0);
        }
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
            core.end();
        }
    }

    /** ModelHookInstaller.installHooks order: {next step, success, detail}. */
    public synchronized int[] installStep(int step, boolean ok, int aux, int feedHooks, int pandoHooks) {
        if (!ready()) {
            return new int[]{STEP_ABORT, 0, 0};
        }
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
            core.end();
        }
    }

    // ------------------------------------------------------------ discovery

    public synchronized boolean primaryCandidate(Method method) {
        if (!ready()) {
            return false;
        }
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
            core.end();
        }
    }

    private static boolean isInteger(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    /** {decision, accepted count}; the count is reset afterwards. */
    public synchronized int[] primaryDecision() {
        if (!ready()) {
            return new int[]{DECISION_NONE, 0};
        }
        try {
            core.begin(BfAbi.OP_PRIMARY_DECISION);
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{DECISION_NONE, 0};
            }
            ByteBuffer in = core.payload();
            return new int[]{in.get(0), in.getShort(1) & 0xFFFF};
        } finally {
            core.end();
        }
    }

    /** Broad discovery: markers[i] = the method matched DEX marker i. */
    public synchronized int broadCandidate(Method method, boolean[] markers) {
        if (!ready()) {
            return BROAD_SKIP;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_BROAD_CANDIDATE);
            out.put(bool(hasListBoundary(method)));
            Class<?> returned = method.getReturnType();
            out.put(bool(returned == boolean.class || returned == Boolean.class));
            out.putInt(handle(method));
            for (int i = 0; i < 13; i++) {
                out.put(bool(i < markers.length && markers[i]));
            }
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : BROAD_SKIP;
        } finally {
            core.end();
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

    public synchronized boolean feedOwnerMethod(Method method) {
        if (!ready()) {
            return false;
        }
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
            core.end();
        }
    }

    public synchronized boolean injectorMethod(Method method) {
        if (!ready()) {
            return false;
        }
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
            core.end();
        }
    }

    public synchronized int injectorDecision() {
        return simpleByte(BfAbi.OP_INJECTOR_DECISION, DECISION_NONE);
    }

    public synchronized boolean pandoAccessor(Method method) {
        if (!ready()) {
            return false;
        }
        try {
            int modifiers = method.getModifiers();
            ByteBuffer out = core.begin(BfAbi.OP_PANDO_ACCESSOR);
            out.put(bool(Modifier.isAbstract(modifiers)));
            out.put(bool(Modifier.isNative(modifiers)));
            out.put(bool(List.class.isAssignableFrom(method.getReturnType())));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    /** A name from one of the core's tables, or null past the end. */
    public synchronized String constString(int table, int index) {
        if (!ready()) {
            return null;
        }
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
            core.end();
        }
    }

    public synchronized List<String> constTable(int table) {
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
    public synchronized int[] limits() {
        if (!ready()) {
            return new int[]{6, 14, 9};
        }
        try {
            core.begin(BfAbi.OP_LIMITS);
            if (core.send() != BfAbi.ST_OK) {
                return new int[]{6, 14, 9};
            }
            ByteBuffer in = core.payload();
            int[] limits = {in.get(0), in.get(1), in.get(2)};
            host.reflector.fieldClassDepth = limits[0];
            return limits;
        } finally {
            core.end();
        }
    }

    /** Applies names and limits the host's mechanisms need from the core. */
    public synchronized void configureHost() {
        limits();
        String factory = constString(TABLE_NAMES, NAME_COPY_FACTORY);
        if (factory != null) {
            host.reflector.copyFactory = factory;
        }
    }

    // ------------------------------------------------------------ statistics

    /** ModuleStats: {log (0/1), total, event}. */
    public synchronized long[] stats(int kind, int count) {
        if (!ready()) {
            return new long[3];
        }
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
            core.end();
        }
    }

    // ------------------------------------------------------------ rendered labels

    /** RenderedLabelHooks: UI_HIDE or UI_RESTORE for a text/description update. */
    public synchronized int uiTextEvent(boolean description, Object view) {
        if (!ready()) {
            return 0;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_TEXT_EVENT);
            out.put(bool(description));
            out.putInt(handle(view));
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : 0;
        } finally {
            core.end();
        }
    }

    /** AdLabelScanner.isCurrentAdMarker. */
    public synchronized boolean uiMarker(Object view, boolean textView) {
        if (!ready()) {
            return false;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_MARKER);
            out.putInt(handle(view));
            out.put(bool(textView));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    /**
     * FeedItemHider.findFeedCard over the ancestor chain, nearest first:
     * listContainer[i], rootOrDecor[i], width[i], height[i]. Returns the
     * chain index of the card, or CARD_NONE.
     */
    public synchronized int uiCard(boolean[] listContainer, boolean[] rootOrDecor, int[] width,
                                   int[] height, int screenWidth, int screenHeight, float density) {
        if (!ready()) {
            return CARD_NONE;
        }
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
            core.end();
        }
    }

    /** FeedItemHider.hide: {action, next attempt}. */
    public synchronized int[] uiHide(boolean marker, boolean cardFound, int attempt, boolean armFailed) {
        if (!ready()) {
            return new int[]{HIDE_NONE, 0};
        }
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
            core.end();
        }
    }

    public synchronized boolean uiRestore(boolean hiddenBySameLabel, boolean stillLabelled) {
        if (!ready()) {
            return false;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_RESTORE);
            out.put(bool(hiddenBySameLabel));
            out.put(bool(stillLabelled));
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    /** AdLabelScanner.findAdLabel: the first labelled view, or null. */
    public synchronized Object uiScan(Object root, boolean rescan) {
        if (!ready()) {
            return null;
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_SCAN);
            out.putInt(handle(root));
            out.put(bool(rescan));
            return core.send() == BfAbi.ST_OK ? host.handles.get(core.payload().getInt(0)) : null;
        } finally {
            core.end();
        }
    }

    /** ViewTrees: {list container, decor view} for the view's class. */
    public synchronized boolean[] uiViewClass(Object view) {
        if (!ready()) {
            return new boolean[2];
        }
        try {
            ByteBuffer out = core.begin(BfAbi.OP_UI_VIEW_CLASS);
            out.putInt(handle(view));
            if (core.send() != BfAbi.ST_OK) {
                return new boolean[2];
            }
            ByteBuffer in = core.payload();
            return new boolean[]{in.get(0) != 0, in.get(1) != 0};
        } finally {
            core.end();
        }
    }

    /** RenderedLabelHooks addView: list-container flags of the parent chain. */
    public synchronized boolean uiAddView(boolean[] listContainerChain) {
        if (!ready()) {
            return false;
        }
        try {
            int count = Math.min(listContainerChain.length, 255);
            ByteBuffer out = core.begin(BfAbi.OP_UI_ADD_VIEW);
            out.put((byte) count);
            for (int i = 0; i < count; i++) {
                out.put(bool(listContainerChain[i]));
            }
            return core.send() == BfAbi.ST_OK && core.payload().get(0) != 0;
        } finally {
            core.end();
        }
    }

    // ------------------------------------------------------------ misc

    public synchronized void reset() {
        if (!ready()) {
            return;
        }
        try {
            core.begin(BfAbi.OP_RESET);
            core.send();
        } finally {
            core.end();
        }
    }

    public synchronized int version() {
        return simpleByte(BfAbi.OP_VERSION, 0);
    }

    private boolean simpleFlag(int opcode) {
        return simpleByte(opcode, 0) != 0;
    }

    private int simpleByte(int opcode, int fallback) {
        if (!ready()) {
            return fallback;
        }
        try {
            core.begin(opcode);
            return core.send() == BfAbi.ST_OK ? core.payload().get(0) : fallback;
        } finally {
            core.end();
        }
    }

    /** {programs run, host callbacks} since load, for benchmarks. */
    public synchronized long[] counters() {
        return core.counters();
    }
}
