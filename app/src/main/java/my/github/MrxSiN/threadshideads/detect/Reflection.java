package my.github.MrxSiN.threadshideads.detect;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflection helpers shared by the detector and the model sanitizers.
 *
 * Both walk the same object graphs, so the traversal rules live here once
 * instead of being duplicated on either side.
 */
public final class Reflection {

    private static final int MAX_CLASS_DEPTH = 6;

    private static final Map<Class<?>, Field[]> INSTANCE_FIELD_CACHE =
            new ConcurrentHashMap<>();

    private static final Map<Class<?>, Boolean> STRUCTURED_TOSTRING_CACHE =
            new ConcurrentHashMap<>();

    private Reflection() {
    }

    public static Field[] getInstanceFields(Class<?> type) {
        Field[] cached = INSTANCE_FIELD_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        ArrayList<Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        int classDepth = 0;

        while (cursor != null
                && cursor != Object.class
                && classDepth++ < MAX_CLASS_DEPTH) {
            try {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers())
                            || field.isSynthetic()) {
                        continue;
                    }

                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                        // Skip inaccessible fields once and cache the result.
                    }
                }
            } catch (Throwable ignored) {
                break;
            }

            cursor = cursor.getSuperclass();
        }

        Field[] resolved = fields.toArray(new Field[0]);
        Field[] existing = INSTANCE_FIELD_CACHE.putIfAbsent(type, resolved);
        return existing == null ? resolved : existing;
    }

    /**
     * True when the type is an app model class that overrides toString, so its
     * structured representation is worth reading.
     */
    public static boolean hasStructuredToString(Class<?> type) {
        Boolean cached = STRUCTURED_TOSTRING_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        boolean result = false;

        if (isAppModelClass(type.getName())) {
            try {
                result = type.getMethod("toString")
                        .getDeclaringClass() != Object.class;
            } catch (Throwable ignored) {
                result = false;
            }
        }

        Boolean existing = STRUCTURED_TOSTRING_CACHE.putIfAbsent(type, result);
        return existing == null ? result : existing;
    }

    public static boolean shouldInspectChild(Object value) {
        if (value == null) {
            return false;
        }

        Class<?> type = value.getClass();

        if (type.isArray()
                || value instanceof Collection<?>
                || value instanceof Map<?, ?>
                || value instanceof Enum<?>) {
            return true;
        }

        return isAppModelClass(type.getName());
    }

    public static boolean isSimpleValue(Object value) {
        return value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Enum<?>
                || value instanceof Class<?>;
    }

    private static boolean isAppModelClass(String className) {
        return className.startsWith("X.")
                || className.startsWith("p000X.")
                || className.startsWith("com.instagram.")
                || className.startsWith("com.facebook.")
                || className.startsWith("com.meta.");
    }
}
