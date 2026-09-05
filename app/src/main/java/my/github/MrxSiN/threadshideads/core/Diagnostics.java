package my.github.MrxSiN.threadshideads.core;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, opt-in tracing for working out why a hooked boundary produces no
 * removals on a given Threads build.
 *
 * Only shapes are logged: method names, list sizes, model type names, and which
 * marker keywords matched. Feed content is never written to the log.
 */
public final class Diagnostics {

    /**
     * Enabled while a build is being investigated against a new Threads
     * version. Leave disabled for release builds.
     */
    public static final boolean ENABLED = true;

    private static final int MAX_EVENTS_PER_SITE = 8;
    private static final int MAX_ITEMS_PER_LIST = 12;

    private static final int MAX_WALK_DEPTH = 6;
    private static final int MAX_WALK_BUDGET = 1500;
    private static final int MAX_TEXT_LENGTH = 262144;
    private static final int MAX_REPORTED_TYPES = 60;

    /**
     * Keywords searched for in nested model type names and in nested text.
     */
    private static final String[] MARKER_KEYWORDS = {
            "sponsor",
            "netego",
            "advertis",
            "paid_partner",
            "paidpartner",
            "branded",
            "boost",
            "ad_id",
            "ad_type",
            "ad_label",
            "adchoices",
            "commercial",
            "promoted"
    };

    private static final Map<String, AtomicInteger> EVENT_COUNTS =
            new ConcurrentHashMap<>();

    private Diagnostics() {
    }

    /**
     * Records where an advertisement was recognized, so detection can be moved
     * up to the item that owns it.
     */
    public static void traceAdMatch(Class<?> type, String reason) {
        if (!ENABLED || !shouldTrace("match " + type.getSimpleName())) {
            return;
        }

        ModuleLog.log("match " + type.getName() + " via " + reason);
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
     * Records that a list passed through untouched, then fingerprints its items
     * so the model type that distinguishes an advertisement can be identified.
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

            traceItemFingerprint(site + " #" + described, item);
            described++;
        }
    }

    /**
     * Walks an item's object graph and reports the model types it contains plus
     * any marker keyword that appeared, so an advertisement can be told apart
     * from an organic post by shape alone.
     */
    public static void traceItemFingerprint(String site, Object item) {
        if (!ENABLED || item == null) {
            return;
        }

        TreeSet<String> types = new TreeSet<>();
        TreeSet<String> markers = new TreeSet<>();

        try {
            walk(item, 0, new IdentityHashMap<Object, Boolean>(), new int[]{0},
                    types, markers);
        } catch (Throwable ignored) {
            // A partial fingerprint is still useful.
        }

        StringBuilder message = new StringBuilder("fingerprint ")
                .append(site)
                .append(" markers=")
                .append(markers.isEmpty() ? "-" : join(markers, MAX_REPORTED_TYPES))
                .append(" types=")
                .append(join(types, MAX_REPORTED_TYPES));

        ModuleLog.log(message.toString());
    }

    private static void walk(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget,
            TreeSet<String> types,
            TreeSet<String> markers
    ) {
        if (value == null
                || depth > MAX_WALK_DEPTH
                || budget[0]++ > MAX_WALK_BUDGET) {
            return;
        }

        if (value instanceof CharSequence) {
            collectMarkers(value.toString(), markers);
            return;
        }

        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character) {
            return;
        }

        if (value instanceof Enum<?>) {
            collectMarkers(((Enum<?>) value).name(), markers);
            return;
        }

        if (visited.put(value, Boolean.TRUE) != null) {
            return;
        }

        Class<?> type = value.getClass();

        if (isAppModel(type)) {
            types.add(type.getSimpleName());
            collectMarkers(type.getName(), markers);
            probeStructuredText(value, type, budget, markers);
            probePandoTree(value, type, markers);
        }

        if (value instanceof Map<?, ?>) {
            int seen = 0;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (seen++ >= 32) {
                    break;
                }
                collectMarkers(String.valueOf(entry.getKey()), markers);
                walk(entry.getValue(), depth + 1, visited, budget, types, markers);
            }
            return;
        }

        if (value instanceof Collection<?>) {
            int seen = 0;
            for (Object element : (Collection<?>) value) {
                if (seen++ >= 32) {
                    break;
                }
                walk(element, depth + 1, visited, budget, types, markers);
            }
            return;
        }

        if (type.isArray()) {
            int length = Math.min(Array.getLength(value), 32);
            for (int i = 0; i < length; i++) {
                walk(Array.get(value, i), depth + 1, visited, budget, types, markers);
            }
            return;
        }

        if (!isAppModel(type)) {
            return;
        }

        for (Field field : type.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Object child;
            try {
                field.setAccessible(true);
                child = field.get(value);
            } catch (Throwable ignored) {
                continue;
            }

            walk(child, depth + 1, visited, budget, types, markers);
        }
    }

    /**
     * Pando models keep their values in a native tree, so field reflection sees
     * nothing. Their toString, when overridden, is the only Java-side view of
     * the tree. Matches are attributed to the declaring type.
     */
    private static void probeStructuredText(
            Object value,
            Class<?> type,
            int[] budget,
            TreeSet<String> markers
    ) {
        if (budget[0] > MAX_WALK_BUDGET) {
            return;
        }

        try {
            if (type.getMethod("toString").getDeclaringClass() == Object.class) {
                return;
            }
        } catch (Throwable ignored) {
            return;
        }

        String text;
        try {
            text = String.valueOf(value);
        } catch (Throwable ignored) {
            return;
        }

        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            return;
        }

        TreeSet<String> found = new TreeSet<>();
        collectMarkers(text, found);

        for (String marker : found) {
            markers.add(marker + "@" + type.getSimpleName());
        }
    }

    /**
     * Candidate JSON keys for the sponsored signal. Only the key name is
     * reported when present, never the surrounding content.
     */
    private static final String[] CANDIDATE_KEYS = {
            "is_sponsored",
            "sponsored",
            "sponsor_tags",
            "sponsored_label",
            "is_paid_partnership",
            "ad_id",
            "ad_type",
            "ad_label",
            "ad_metadata",
            "is_ad",
            "netego",
            "commerciality",
            "boosted_status",
            "ad_action"
    };

    /**
     * Reports which candidate keys appear in a Pando model's tree JSON, so the
     * sponsored marker used by this build can be identified.
     */
    private static void probePandoTree(
            Object value,
            Class<?> type,
            TreeSet<String> markers
    ) {
        String json = my.github.MrxSiN.threadshideads.detect.PandoTrees
                .readJson(value);

        if (json == null) {
            return;
        }

        markers.add("tree:" + type.getSimpleName() + "[" + json.length() + "]");

        for (String key : CANDIDATE_KEYS) {
            if (json.contains("\"" + key + "\"")) {
                markers.add("key:" + key
                        + (json.contains("\"" + key + "\":true") ? "=true" : ""));
            }
        }
    }

    private static void collectMarkers(String text, TreeSet<String> markers) {
        if (text == null || text.length() > MAX_TEXT_LENGTH) {
            return;
        }

        String normalized = text.toLowerCase(Locale.ROOT);

        for (String keyword : MARKER_KEYWORDS) {
            if (normalized.contains(keyword)) {
                markers.add(keyword);
            }
        }
    }

    private static boolean isAppModel(Class<?> type) {
        String className = type.getName();

        return className.startsWith("X.")
                || className.startsWith("com.instagram.")
                || className.startsWith("com.facebook.")
                || className.startsWith("com.meta.");
    }

    private static String join(TreeSet<String> values, int limit) {
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

    private static boolean shouldTrace(String site) {
        AtomicInteger counter = EVENT_COUNTS.get(site);

        if (counter == null) {
            AtomicInteger created = new AtomicInteger();
            AtomicInteger existing = EVENT_COUNTS.putIfAbsent(site, created);
            counter = existing == null ? created : existing;
        }

        return counter.incrementAndGet() <= MAX_EVENTS_PER_SITE;
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
