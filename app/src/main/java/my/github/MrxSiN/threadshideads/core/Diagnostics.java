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
    public static final boolean ENABLED = false;

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
     * Reports a Pando tree list seen by the response hooks, so a build that
     * removes nothing can be told apart from one whose hooks never fire.
     */
    public static void traceTreeList(String site, int size, Object first) {
        if (!ENABLED || !shouldTrace("treelist " + site)) {
            return;
        }

        ModuleLog.log("pando list " + site + " size=" + size
                + " of " + (first == null ? "null" : first.getClass().getName()));
    }

    /**
     * Reports how a model is built: its supertypes, its declared fields, and
     * which of those fields hold a Pando tree. Names and types only.
     */
    public static void traceClassShape(Object item) {
        traceClassShape(item, 0);
    }

    private static void traceClassShape(Object item, int depth) {
        Class<?> type = item.getClass();

        if (!ENABLED || !shouldTrace("shape-of " + type.getName())) {
            return;
        }

        StringBuilder message = new StringBuilder("class ")
                .append(type.getName())
                .append(" extends");

        Class<?> cursor = type.getSuperclass();

        while (cursor != null && cursor != Object.class) {
            message.append(' ').append(cursor.getName());
            cursor = cursor.getSuperclass();
        }

        message.append(" fields=");

        int described = 0;

        /*
         * Only fields that actually hold something are reported: a feed item
         * declares dozens of optional unit types and all but a few are null.
         */
        for (Field field : declaredFields(type)) {
            if (Modifier.isStatic(field.getModifiers()) || described >= 40) {
                continue;
            }

            Object child = readField(field, item);

            if (child == null) {
                continue;
            }

            described++;

            message.append(field.getName())
                    .append(':')
                    .append(field.getType().getSimpleName())
                    .append('=')
                    .append(describeChild(child))
                    .append(' ');
        }

        if (described == 0) {
            message.append('-');
        }

        ModuleLog.log(message.toString());

        if (depth > 0) {
            return;
        }

        /*
         * A feed item carries its payload one level down, and that payload is
         * where the unit type lives.
         */
        for (Field field : declaredFields(type)) {
            if (Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Object child = readField(field, item);

            if (child != null
                    && !(child instanceof Enum<?>)
                    && !(child instanceof CharSequence)
                    && !(child instanceof Number)
                    && !(child instanceof Boolean)
                    && isAppModel(child.getClass())) {
                traceClassShape(child, depth + 1);
            }
        }
    }

    /**
     * An enum is reported by its constant name and by its text, because an
     * obfuscated constant name says nothing while its server value names the
     * unit type.
     */
    private static String describeChild(Object child) {
        if (!(child instanceof Enum<?>)) {
            return child.getClass().getSimpleName();
        }

        traceEnumConstants(child.getClass());

        String text;

        try {
            text = String.valueOf(child);
        } catch (Throwable ignored) {
            text = "?";
        }

        if (text != null && text.length() > 40) {
            text = text.substring(0, 40);
        }

        return child.getClass().getSimpleName()
                + "." + ((Enum<?>) child).name() + "(" + text + ")";
    }

    /**
     * Lists an enum's constants once. Unit-type enums keep readable constant
     * names on this build, so the constant that marks an advertisement can be
     * read straight from the log.
     */
    public static void traceEnumConstants(Class<?> type) {
        if (!ENABLED || !shouldTrace("enum " + type.getName())) {
            return;
        }

        Object[] constants;

        try {
            constants = type.getEnumConstants();
        } catch (Throwable ignored) {
            return;
        }

        if (constants == null) {
            return;
        }

        TreeSet<String> described = new TreeSet<>();

        for (Object constant : constants) {
            described.add(((Enum<?>) constant).name()
                    + "(" + String.valueOf(constant) + ")");
        }

        ModuleLog.log("enum " + type.getName() + " = "
                + join(described, 120));
    }

    private static Field[] declaredFields(Class<?> type) {
        try {
            return type.getDeclaredFields();
        } catch (Throwable ignored) {
            return new Field[0];
        }
    }

    private static Object readField(Field field, Object item) {
        try {
            field.setAccessible(true);
            return field.get(item);
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Reports the first row of a response page: its length, whether the word
     * sponsor appears anywhere in it, and its top-level key names.
     */
    public static void tracePageRow(String site, Object row) {
        if (!ENABLED || row == null || !shouldTrace("page " + site)) {
            return;
        }

        /*
         * Read the tree only once the rate limit has allowed this line, because
         * serializing it is the expensive part.
         */
        String json = my.github.MrxSiN.threadshideads.detect.PandoTrees
                .readJson(row);

        if (json == null) {
            ModuleLog.log("page row " + site + " exposes no JSON");
            return;
        }

        ModuleLog.log("page row " + site + " [" + json.length()
                + " chars] sponsorWord="
                + json.toLowerCase(Locale.ROOT).contains("sponsor")
                + " topKeys=" + join(topLevelKeys(json), 60));
    }

    /**
     * Reports the shape of a tree the response hooks inspected: whether its
     * JSON could be read at all, how long it is, and which top-level keys it
     * carries. Key names only; no values are logged.
     */
    public static void traceTreeShape(Class<?> type, String json) {
        if (!ENABLED || !shouldTrace("shape " + type.getSimpleName())) {
            return;
        }

        if (json == null) {
            ModuleLog.log("tree " + type.getName() + " exposes no JSON");
            return;
        }

        ModuleLog.log("tree " + type.getName() + " [" + json.length()
                + " chars] sponsorWord="
                + json.toLowerCase(Locale.ROOT).contains("sponsor")
                + " topKeys=" + join(topLevelKeys(json), 40));
    }

    /**
     * Key names declared directly on the JSON object, found by tracking brace
     * and bracket depth so nested objects are not reported.
     */
    private static TreeSet<String> topLevelKeys(String json) {
        TreeSet<String> keys = new TreeSet<>();

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        StringBuilder current = new StringBuilder();

        for (int i = 0; i < json.length(); i++) {
            char character = json.charAt(i);

            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;

                    if (depth == 1 && isKeyPosition(json, i)) {
                        keys.add(current.toString());
                    }
                } else if (depth == 1) {
                    current.append(character);
                }

                continue;
            }

            if (character == '"') {
                inString = true;
                current.setLength(0);
            } else if (character == '{' || character == '[') {
                depth++;
            } else if (character == '}' || character == ']') {
                depth--;
            }
        }

        return keys;
    }

    /**
     * A string is a key when the next non-space character after it is a colon.
     */
    private static boolean isKeyPosition(String json, int closingQuote) {
        for (int i = closingQuote + 1; i < json.length(); i++) {
            char character = json.charAt(i);

            if (character == ' ') {
                continue;
            }

            return character == ':';
        }

        return false;
    }

    /**
     * Reports which candidate sponsored keys a tree's JSON carries when the
     * vocabulary did not classify it, which is how a renamed marker is found.
     */
    public static void traceTreeKeys(Class<?> type, String json) {
        if (!ENABLED || json == null
                || !shouldTrace("keys " + type.getSimpleName())) {
            return;
        }

        TreeSet<String> keys = new TreeSet<>();

        for (String key : CANDIDATE_KEYS) {
            if (json.contains("\"" + key + "\"")) {
                keys.add(key
                        + (json.contains("\"" + key + "\":true") ? "=true" : ""));
            }
        }

        if (keys.isEmpty()) {
            return;
        }

        ModuleLog.log("unmatched tree " + type.getName()
                + " [" + json.length() + " chars] carries " + join(keys, 20));
    }

    /**
     * Lists what a class declares, so a hook layer that installed nothing can
     * be diagnosed from the log rather than from a decompiler.
     */
    public static void traceDeclaredMethods(
            Class<?> type,
            java.lang.reflect.Method[] methods
    ) {
        if (!ENABLED || !shouldTrace("declared " + type.getName())) {
            return;
        }

        TreeSet<String> described = new TreeSet<>();

        for (java.lang.reflect.Method method : methods) {
            int modifiers = method.getModifiers();

            described.add(method.getName()
                    + (Modifier.isNative(modifiers) ? "/native" : "")
                    + (Modifier.isAbstract(modifiers) ? "/abstract" : "")
                    + " -> " + method.getReturnType().getSimpleName());
        }

        ModuleLog.log("declared on " + type.getName() + ": "
                + join(described, MAX_REPORTED_TYPES));
    }

    /**
     * Records a Pando tree that carried a sponsored marker but was too large to
     * be a single feed item, so it was left for the boundary layer instead of
     * being dropped whole.
     */
    public static void traceOversizedTree(Class<?> type, int jsonLength) {
        if (!ENABLED || !shouldTrace("oversized " + type.getSimpleName())) {
            return;
        }

        ModuleLog.log("skipped sponsored tree " + type.getName()
                + " of " + jsonLength + " JSON chars; it owns more than one item");
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

        traceClassShape(item);

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
