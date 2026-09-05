package my.github.MrxSiN.threadshideads.core;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.TreeSet;

/**
 * Inspects an unknown Threads model so the marker that distinguishes an
 * advertisement can be found after an app update.
 *
 * Names, types, enum constants and JSON key names are reported; feed content is
 * not. Field reporting stops one level below the item, and every entry point is
 * gated on {@link Diagnostics#ENABLED}.
 */
public final class ModelProbes {

    private static final int MAX_REPORTED_TYPES = 60;
    private static final int MAX_REPORTED_FIELDS = 40;
    private static final int MAX_ENUM_TEXT_LENGTH = 40;

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

    private ModelProbes() {
    }

    /**
     * Reports how a model is built: its supertypes and the fields that actually
     * hold something, one level deep.
     */
    public static void traceClassShape(Object item) {
        traceClassShape(item, 0);
    }

    private static void traceClassShape(Object item, int depth) {
        Class<?> type = item.getClass();

        if (!Diagnostics.ENABLED
                || !Diagnostics.shouldTrace("shape-of " + type.getName())) {
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
            if (Modifier.isStatic(field.getModifiers())
                    || described >= MAX_REPORTED_FIELDS) {
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

        if (text != null && text.length() > MAX_ENUM_TEXT_LENGTH) {
            text = text.substring(0, MAX_ENUM_TEXT_LENGTH);
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
        if (!Diagnostics.ENABLED
                || !Diagnostics.shouldTrace("enum " + type.getName())) {
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
                + Diagnostics.join(described, 120));
    }

    /**
     * Lists what a class declares, so a hook layer that installed nothing can
     * be diagnosed from the log rather than from a decompiler.
     */
    public static void traceDeclaredMethods(Class<?> type, Method[] methods) {
        if (!Diagnostics.ENABLED
                || !Diagnostics.shouldTrace("declared " + type.getName())) {
            return;
        }

        TreeSet<String> described = new TreeSet<>();

        for (Method method : methods) {
            int modifiers = method.getModifiers();

            described.add(method.getName()
                    + (Modifier.isNative(modifiers) ? "/native" : "")
                    + (Modifier.isAbstract(modifiers) ? "/abstract" : "")
                    + " -> " + method.getReturnType().getSimpleName());
        }

        ModuleLog.log("declared on " + type.getName() + ": "
                + Diagnostics.join(described, MAX_REPORTED_TYPES));
    }

    /**
     * Reports which candidate sponsored keys a tree's JSON carries when the
     * vocabulary did not classify it, which is how a renamed marker is found.
     */
    public static void traceTreeKeys(Class<?> type, String json) {
        if (!Diagnostics.ENABLED || json == null
                || !Diagnostics.shouldTrace("keys " + type.getSimpleName())) {
            return;
        }

        TreeSet<String> keys = candidateKeys(json);

        if (keys.isEmpty()) {
            return;
        }

        ModuleLog.log("unmatched tree " + type.getName()
                + " [" + json.length() + " chars] carries "
                + Diagnostics.join(keys, 20));
    }

    private static TreeSet<String> candidateKeys(String json) {
        TreeSet<String> keys = new TreeSet<>();

        for (String key : CANDIDATE_KEYS) {
            if (json.contains("\"" + key + "\"")) {
                keys.add(key
                        + (json.contains("\"" + key + "\":true") ? "=true" : ""));
            }
        }

        return keys;
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

    private static boolean isAppModel(Class<?> type) {
        String className = type.getName();

        return className.startsWith("X.")
                || className.startsWith("com.instagram.")
                || className.startsWith("com.facebook.")
                || className.startsWith("com.meta.");
    }
}
