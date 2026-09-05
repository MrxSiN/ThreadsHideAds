package my.github.MrxSiN.threadshideads.core;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Bounded, opt-in tracing for working out why a hooked boundary produces no
 * removals on a given Threads build.
 *
 * Only shapes are logged: method names, list sizes, and item class names. Feed
 * content is never written to the log.
 */
public final class Diagnostics {

    /**
     * Enabled while a build is being investigated against a new Threads
     * version. Leave disabled for release builds.
     */
    public static final boolean ENABLED = true;

    private static final int MAX_EVENTS_PER_SITE = 8;

    private static final Map<String, AtomicInteger> EVENT_COUNTS =
            new ConcurrentHashMap<>();

    private Diagnostics() {
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
     * Records that a list passed through untouched, naming the element types so
     * the detector can be pointed at the right model shape.
     */
    public static void traceUnfilteredList(String site, List<?> list) {
        if (!ENABLED || list == null || list.isEmpty() || !shouldTrace(site)) {
            return;
        }

        StringBuilder message = new StringBuilder("trace ")
                .append(site)
                .append(" kept ")
                .append(list.size())
                .append(" item(s); types=");

        int described = 0;
        for (Object item : list) {
            if (described++ >= 4) {
                break;
            }

            message.append(item == null ? "null" : item.getClass().getName())
                    .append(' ');
        }

        ModuleLog.log(message.toString());

        for (Object item : list) {
            traceItemShape(site, item);
        }
    }

    /**
     * Logs the runtime types of an item's populated fields.
     *
     * Feed items on obfuscated builds have neither readable field names nor a
     * structured toString, so the only distinguishing signal left is which
     * nested model types are present. Types are logged; values never are.
     */
    public static void traceItemShape(String site, Object item) {
        if (!ENABLED || item == null || !shouldTrace("shape " + site)) {
            return;
        }

        StringBuilder message = new StringBuilder("shape ")
                .append(site)
                .append(' ')
                .append(item.getClass().getName())
                .append(" populated=");

        int described = 0;

        for (java.lang.reflect.Field field : item.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Object value;
            try {
                field.setAccessible(true);
                value = field.get(item);
            } catch (Throwable ignored) {
                continue;
            }

            if (value == null || described++ >= 40) {
                continue;
            }

            message.append(field.getName())
                    .append(':')
                    .append(value.getClass().getSimpleName());

            if (value instanceof CharSequence) {
                CharSequence text = (CharSequence) value;
                message.append("[len=").append(text.length())
                        .append(text.length() > 0 && text.charAt(0) == '{'
                                ? " json]" : "]");
            } else if (isAppModel(value)) {
                message.append('(').append(populatedFieldTypes(value, 2)).append(')');
            }

            message.append(' ');
        }

        message.append("markers=").append(probeMarkers(item, 0));

        ModuleLog.log(message.toString());
    }

    private static boolean isAppModel(Object value) {
        String className = value.getClass().getName();
        return className.startsWith("X.")
                || className.startsWith("com.instagram.");
    }

    /**
     * One nested level of populated field types, so the model that actually
     * carries the post content can be identified.
     */
    private static String populatedFieldTypes(Object value, int levels) {
        StringBuilder types = new StringBuilder();
        int described = 0;

        for (java.lang.reflect.Field field : value.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Object child;
            try {
                field.setAccessible(true);
                child = field.get(value);
            } catch (Throwable ignored) {
                continue;
            }

            if (child == null || described++ >= 30) {
                continue;
            }

            types.append(field.getName())
                    .append(':')
                    .append(child.getClass().getSimpleName());

            if (levels > 1 && isAppModel(child)) {
                types.append('[')
                        .append(populatedFieldTypes(child, levels - 1))
                        .append(']');
            }

            types.append(' ');
        }

        return types.toString();
    }

    /**
     * Reports which advertisement markers appear anywhere in the item's nested
     * text, without logging any of that text.
     */
    private static String probeMarkers(Object value, int depth) {
        StringBuilder found = new StringBuilder();

        if (depth > 3 || value == null) {
            return "-";
        }

        if (value instanceof CharSequence) {
            String text = value.toString();

            if (text.length() > 262144) {
                return "-";
            }

            if (text.contains("is_sponsored")) {
                found.append("is_sponsored ");
            }
            if (text.contains("\"is_sponsored\":true")
                    || text.contains("\"is_sponsored\": true")) {
                found.append("is_sponsored=true ");
            }
            if (text.contains("sponsored_label")) {
                found.append("sponsored_label ");
            }
            if (text.contains("ad_id")) {
                found.append("ad_id ");
            }

            return found.length() == 0 ? "-" : found.toString();
        }

        String className = value.getClass().getName();

        if (!className.startsWith("X.")
                && !className.startsWith("com.instagram.")) {
            return "-";
        }

        for (java.lang.reflect.Field field : value.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
                continue;
            }

            Object child;
            try {
                field.setAccessible(true);
                child = field.get(value);
            } catch (Throwable ignored) {
                continue;
            }

            String nested = probeMarkers(child, depth + 1);

            if (!"-".equals(nested)) {
                found.append(field.getName()).append('{').append(nested).append("} ");
            }
        }

        return found.length() == 0 ? "-" : found.toString();
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
