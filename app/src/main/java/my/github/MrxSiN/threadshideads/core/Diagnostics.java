package my.github.MrxSiN.threadshideads.core;

import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, opt-in tracing of what the hooks did: which boundaries were called,
 * which lists passed through, and where an advertisement was recognized.
 *
 * Only shapes are logged: method names, list sizes and model type names. Feed
 * content is never written to the log. Inspecting an unknown model is a
 * separate concern and lives in {@link ModelProbes}.
 */
public final class Diagnostics {

    /**
     * Enabled while a build is being investigated against a new Threads
     * version. Leave disabled for release builds.
     */
    public static final boolean ENABLED = false;

    private static final int MAX_EVENTS_PER_SITE = 8;
    private static final int MAX_ITEMS_PER_LIST = 12;

    private static final Map<String, AtomicInteger> EVENT_COUNTS =
            new ConcurrentHashMap<>();

    private Diagnostics() {
    }

    /**
     * Records a list that was left alone because filtering would have emptied
     * it, naming the element type that should be dropped one level higher.
     */
    public static void traceEmptyingBlocked(List<?> list) {
        if (!ENABLED || list == null || list.isEmpty()) {
            return;
        }

        Object first = list.get(0);
        String type = first == null ? "null" : first.getClass().getName();

        if (!shouldTrace("blocked " + type)) {
            return;
        }

        ModuleLog.log("blocked emptying a list of " + list.size()
                + " " + type + "; the owning item should be dropped instead");
    }

    public static void traceBoundaryCall(
            String site,
            Object[] arguments,
            Object result
    ) {
        if (!ENABLED || !shouldTrace(site)) {
            return;
        }

        StringBuilder message = new StringBuilder("trace ").append(site);

        if (arguments != null) {
            for (int i = 0; i < arguments.length; i++) {
                message.append(" arg").append(i).append('=')
                        .append(describe(arguments[i]));
            }
        }

        message.append(" result=").append(describe(result));

        ModuleLog.log(message.toString());
    }

    /**
     * Records that a list passed through untouched, then reports the shape of
     * its items so the model type that distinguishes an advertisement can be
     * identified.
     */
    public static void traceUnfilteredList(String site, List<?> list) {
        if (!ENABLED || list == null || list.isEmpty() || !shouldTrace(site)) {
            return;
        }

        ModuleLog.log("trace " + site + " kept " + list.size() + " item(s)");

        int described = 0;

        for (Object item : list) {
            if (described >= MAX_ITEMS_PER_LIST) {
                break;
            }

            if (item != null) {
                ModelProbes.traceClassShape(item);
            }

            described++;
        }
    }

    static boolean shouldTrace(String site) {
        AtomicInteger counter = EVENT_COUNTS.get(site);

        if (counter == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = EVENT_COUNTS.putIfAbsent(site, created);
            counter = existing == null ? created : existing;
        }

        return counter.incrementAndGet() <= MAX_EVENTS_PER_SITE;
    }

    static String join(TreeSet<String> values, int limit) {
        StringBuilder joined = new StringBuilder();
        int written = 0;

        for (String value : values) {
            if (written++ >= limit) {
                joined.append("...");
                break;
            }

            joined.append(value).append(' ');
        }

        return joined.length() == 0 ? "-" : joined.toString();
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }

        if (value instanceof List<?>) {
            List<?> list = (List<?>) value;
            String elementType = list.isEmpty() || list.get(0) == null
                    ? "?"
                    : list.get(0).getClass().getName();

            return "List[" + list.size() + " of " + elementType + "]";
        }

        return value.getClass().getName();
    }
}
