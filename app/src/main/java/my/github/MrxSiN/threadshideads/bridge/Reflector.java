package my.github.MrxSiN.threadshideads.bridge;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Cached reflection primitives. These answer "what is there" questions about
 * classes and perform the operations Brainfuck asks for; none of them decides
 * whether something is an advertisement.
 */
final class Reflector {

    private static final Method NO_METHOD;

    static {
        try {
            NO_METHOD = Object.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Map<Class<?>, Field[]> instanceFields = new ConcurrentHashMap<>();
    private final Map<Class<?>, Boolean> overridesToString = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<String, Method>> accessors = new ConcurrentHashMap<>();
    private final Map<Class<?>, Method> chosenAccessor = new ConcurrentHashMap<>();

    /** Walk depth over superclasses, from OP_LIMITS. */
    volatile int fieldClassDepth = 6;

    /** Name of the static list factory, from OP_CONST_STRING. */
    volatile String copyFactory = "copyOf";

    /** Non-static, non-synthetic, accessible fields over the class chain. */
    Field[] instanceFields(Class<?> type) {
        Field[] cached = instanceFields.get(type);
        if (cached != null) {
            return cached;
        }
        ArrayList<Field> fields = new ArrayList<>();
        Class<?> cursor = type;
        int depth = 0;
        while (cursor != null && cursor != Object.class && depth++ < fieldClassDepth) {
            try {
                for (Field field : cursor.getDeclaredFields()) {
                    if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
                        continue;
                    }
                    try {
                        field.setAccessible(true);
                        fields.add(field);
                    } catch (Throwable ignored) {
                        // Inaccessible: skipped once and cached.
                    }
                }
            } catch (Throwable ignored) {
                break;
            }
            cursor = cursor.getSuperclass();
        }
        Field[] resolved = fields.toArray(new Field[0]);
        Field[] existing = ((ConcurrentHashMap<Class<?>, Field[]>) instanceFields).putIfAbsent(type, resolved);
        return existing == null ? resolved : existing;
    }

    boolean overridesToString(Class<?> type) {
        Boolean cached = overridesToString.get(type);
        if (cached == null) {
            boolean result;
            try {
                result = type.getMethod("toString").getDeclaringClass() != Object.class;
            } catch (Throwable ignored) {
                result = false;
            }
            overridesToString.put(type, result);
            cached = result;
        }
        return cached;
    }

    /**
     * Looks up {@code name(boolean, boolean)} on the class or a superclass.
     * The first one found for a class becomes its JSON accessor.
     */
    boolean findAccessor(Class<?> type, String name) {
        Map<String, Method> byName = accessors.get(type);
        if (byName == null) {
            byName = new ConcurrentHashMap<>();
            accessors.put(type, byName);
        }
        Method method = byName.get(name);
        if (method == null) {
            method = lookUp(type, name);
            byName.put(name, method == null ? NO_METHOD : method);
        }
        if (method == NO_METHOD || method == null) {
            return false;
        }
        chosenAccessor.putIfAbsent(type, method);
        return true;
    }

    Method jsonAccessor(Class<?> type) {
        return chosenAccessor.get(type);
    }

    private static Method lookUp(Class<?> type, String name) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            try {
                Method method = cursor.getDeclaredMethod(name, boolean.class, boolean.class);
                method.setAccessible(true);
                return method;
            } catch (Throwable ignored) {
                cursor = cursor.getSuperclass();
            }
        }
        return null;
    }

    /** The k-th class of the chain starting at {@code type}, or null. */
    static Class<?> superclass(Class<?> type, int k) {
        Class<?> cursor = type;
        for (int i = 0; i < k && cursor != null; i++) {
            cursor = cursor.getSuperclass();
        }
        return cursor;
    }

    /**
     * Facts about a declared slot type: {null or Object, interface,
     * accepts an ArrayList}.
     */
    static void declaredFacts(Class<?> type, byte[] out, int offset) {
        out[offset] = (byte) (type == null || type == Object.class ? 1 : 0);
        out[offset + 1] = (byte) (type != null && type.isInterface() ? 1 : 0);
        out[offset + 2] = (byte) (type != null && type.isAssignableFrom(ArrayList.class) ? 1 : 0);
    }

    /** Rebuilds a list through a static factory taking a Collection or an Iterable. */
    Object copyThrough(Class<?> type, List<Object> kept) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            for (Class<?> parameter : new Class<?>[]{Collection.class, Iterable.class}) {
                try {
                    Method factory = cursor.getDeclaredMethod(copyFactory, parameter);
                    if (!Modifier.isStatic(factory.getModifiers())) {
                        continue;
                    }
                    factory.setAccessible(true);
                    Object copy = factory.invoke(null, kept);
                    if (copy instanceof List<?>) {
                        return copy;
                    }
                } catch (Throwable ignored) {
                    // Next parameter shape, then the superclass.
                }
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }
}
