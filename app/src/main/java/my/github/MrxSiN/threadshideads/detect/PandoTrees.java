package my.github.MrxSiN.threadshideads.detect;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads Threads feed models that store their values in a Pando native tree.
 *
 * Those models keep nothing in Java fields, so field reflection and toString
 * both see an empty object. {@code com.facebook.pando.TreeJNI} does expose the
 * tree as JSON through a method whose name survives obfuscation because it is
 * bound to native code, and that JSON is the only Java-side view of the values.
 */
public final class PandoTrees {

    private static final String TREE_CLASS = "com.facebook.pando.TreeJNI";

    private static final String JSON_METHOD = "asJSONNative";
    private static final String JSON_OBJECT_METHOD = "asJSON";

    private static final int MAX_JSON_LENGTH = 524288;

    private static final Boolean[][] FLAG_COMBINATIONS = {
            {Boolean.FALSE, Boolean.FALSE},
            {Boolean.TRUE, Boolean.FALSE},
            {Boolean.FALSE, Boolean.TRUE},
            {Boolean.TRUE, Boolean.TRUE}
    };

    /**
     * Cached per model class: the accessor, or NONE when the class is not a
     * Pando tree. Feed lists repeat the same few classes constantly.
     */
    private static final Map<Class<?>, Object> ACCESSOR_CACHE =
            new ConcurrentHashMap<>();

    private static final Object NONE = new Object();

    /**
     * The flag pair that produced the longest JSON for a model class.
     */
    private static final Map<Class<?>, Boolean[]> FLAG_CACHE =
            new ConcurrentHashMap<>();

    private PandoTrees() {
    }

    public static boolean isPandoTree(Object value) {
        return value != null && resolveAccessor(value.getClass()) != null;
    }

    /**
     * Returns the model's tree as JSON, or null when it is not a Pando tree or
     * the tree cannot be read.
     */
    public static String readJson(Object value) {
        if (value == null) {
            return null;
        }

        Method accessor = resolveAccessor(value.getClass());

        if (accessor == null) {
            return null;
        }

        Class<?> type = value.getClass();
        Boolean[] known = FLAG_CACHE.get(type);

        if (known != null) {
            return invoke(accessor, value, known);
        }

        /*
         * The two flags select how much of the tree is serialized, and which
         * combination yields the whole subtree differs by model: several
         * classes return "{}" for the default pair. Combinations are tried
         * once per class and the most productive one is remembered, because
         * every attempt serializes the tree again.
         */
        String best = null;
        Boolean[] bestFlags = FLAG_COMBINATIONS[0];

        for (Boolean[] flags : FLAG_COMBINATIONS) {
            String text = invoke(accessor, value, flags);

            if (text != null && (best == null || text.length() > best.length())) {
                best = text;
                bestFlags = flags;
            }
        }

        FLAG_CACHE.putIfAbsent(type, bestFlags);

        return best;
    }

    private static String invoke(Method accessor, Object value, Boolean[] flags) {
        Object json;

        try {
            json = accessor.invoke(value, flags[0], flags[1]);
        } catch (Throwable ignored) {
            return null;
        }

        if (json == null) {
            return null;
        }

        String text = json.toString();

        return text.length() > MAX_JSON_LENGTH ? null : text;
    }

    private static Method resolveAccessor(Class<?> type) {
        Object cached = ACCESSOR_CACHE.get(type);

        if (cached != null) {
            return cached == NONE ? null : (Method) cached;
        }

        Method accessor = findAccessor(type);

        ACCESSOR_CACHE.putIfAbsent(type, accessor == null ? NONE : accessor);
        return accessor;
    }

    private static Method findAccessor(Class<?> type) {
        if (!isPandoTreeClass(type)) {
            return null;
        }

        Method accessor = lookUp(type, JSON_METHOD);

        if (accessor == null) {
            accessor = lookUp(type, JSON_OBJECT_METHOD);
        }

        return accessor;
    }

    private static Method lookUp(Class<?> type, String name) {
        Class<?> cursor = type;

        while (cursor != null && cursor != Object.class) {
            try {
                Method method = cursor.getDeclaredMethod(
                        name, boolean.class, boolean.class);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                cursor = cursor.getSuperclass();
            }
        }

        return null;
    }

    private static boolean isPandoTreeClass(Class<?> type) {
        Class<?> cursor = type;

        while (cursor != null) {
            if (TREE_CLASS.equals(cursor.getName())) {
                return true;
            }

            cursor = cursor.getSuperclass();
        }

        return false;
    }
}
