package my.github.MrxSiN.threadshideads.model;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import my.github.MrxSiN.threadshideads.core.ModuleStats;
import my.github.MrxSiN.threadshideads.detect.Reflection;

/**
 * Finds lists nested inside a returned feed wrapper and sanitizes them.
 *
 * When a nested list is immutable, the replacement is written back through the
 * slot it was read from, so an unmodifiable list is still removable.
 */
public final class NestedCollectionSanitizer {

    private static final int MAX_DEPTH = 4;
    private static final int MAX_BUDGET = 192;
    private static final int MAX_ENTRIES_PER_CONTAINER = 48;

    private final ListSanitizer listSanitizer;

    public NestedCollectionSanitizer(ListSanitizer listSanitizer) {
        this.listSanitizer = listSanitizer;
    }

    public int sanitize(Object root) {
        if (root == null) {
            return 0;
        }

        try {
            return sanitize(
                    root,
                    root.getClass(),
                    null,
                    0,
                    new IdentityHashMap<Object, Boolean>(),
                    new int[]{0}
            );
        } catch (Throwable ignored) {
            return 0;
        }
    }

    private int sanitize(
            Object value,
            Class<?> declaredType,
            ValueReplacer replacer,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        if (value == null
                || depth > MAX_DEPTH
                || budget[0]++ > MAX_BUDGET
                || Reflection.isSimpleValue(value)) {
            return 0;
        }

        if (visited.put(value, Boolean.TRUE) != null) {
            return 0;
        }

        if (value instanceof List<?>) {
            return sanitizeList(
                    value, declaredType, replacer, depth, visited, budget);
        }

        if (value instanceof Map<?, ?>) {
            return sanitizeMap((Map<?, ?>) value, depth, visited, budget);
        }

        Class<?> type = value.getClass();
        if (type.isArray()) {
            return sanitizeArray(value, type, depth, visited, budget);
        }

        if (!Reflection.shouldInspectChild(value)) {
            return 0;
        }

        return sanitizeFields(value, type, depth, visited, budget);
    }

    private int sanitizeList(
            Object value,
            Class<?> declaredType,
            ValueReplacer replacer,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int removed = 0;

        SanitizedList sanitized =
                listSanitizer.sanitize(value, declaredType, replacer != null);

        if (sanitized.changed) {
            if (sanitized.value == value) {
                removed += sanitized.removed;
            } else if (replacer != null && replacer.replace(sanitized.value)) {
                value = sanitized.value;
                visited.put(value, Boolean.TRUE);
                removed += sanitized.removed;
            } else {
                ModuleStats.recordReplacementFailure();
            }
        }

        final List<?> list = (List<?>) value;
        int count = Math.min(list.size(), MAX_ENTRIES_PER_CONTAINER);

        for (int i = 0; i < count; i++) {
            final int index = i;
            Object child;
            try {
                child = list.get(index);
            } catch (Throwable ignored) {
                break;
            }

            removed += sanitize(
                    child,
                    Object.class,
                    new ValueReplacer() {
                        @Override
                        public boolean replace(Object replacement) {
                            try {
                                @SuppressWarnings("unchecked")
                                List<Object> writable = (List<Object>) list;
                                writable.set(index, replacement);
                                return true;
                            } catch (Throwable ignored) {
                                return false;
                            }
                        }
                    },
                    depth + 1,
                    visited,
                    budget
            );
        }

        return removed;
    }

    private int sanitizeMap(
            final Map<?, ?> map,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int removed = 0;
        int inspected = 0;

        for (final Map.Entry<?, ?> entry : map.entrySet()) {
            if (inspected++ >= MAX_ENTRIES_PER_CONTAINER) {
                break;
            }

            final Object key = entry.getKey();
            Object child = entry.getValue();

            removed += sanitize(
                    child,
                    Object.class,
                    new ValueReplacer() {
                        @Override
                        public boolean replace(Object replacement) {
                            try {
                                @SuppressWarnings("unchecked")
                                Map.Entry<Object, Object> writableEntry =
                                        (Map.Entry<Object, Object>) entry;
                                writableEntry.setValue(replacement);
                                return true;
                            } catch (Throwable ignored) {
                                try {
                                    @SuppressWarnings("unchecked")
                                    Map<Object, Object> writableMap =
                                            (Map<Object, Object>) map;
                                    writableMap.put(key, replacement);
                                    return true;
                                } catch (Throwable ignoredAgain) {
                                    return false;
                                }
                            }
                        }
                    },
                    depth + 1,
                    visited,
                    budget
            );
        }

        return removed;
    }

    private int sanitizeArray(
            final Object array,
            Class<?> type,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int removed = 0;
        final Class<?> componentType = type.getComponentType();
        int length = Math.min(Array.getLength(array), MAX_ENTRIES_PER_CONTAINER);

        for (int i = 0; i < length; i++) {
            final int index = i;
            Object child = Array.get(array, index);

            removed += sanitize(
                    child,
                    componentType,
                    new ValueReplacer() {
                        @Override
                        public boolean replace(Object replacement) {
                            try {
                                Array.set(array, index, replacement);
                                return true;
                            } catch (Throwable ignored) {
                                return false;
                            }
                        }
                    },
                    depth + 1,
                    visited,
                    budget
            );
        }

        return removed;
    }

    private int sanitizeFields(
            final Object owner,
            Class<?> type,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int removed = 0;

        for (final Field field : Reflection.getInstanceFields(type)) {
            Object child;
            try {
                child = field.get(owner);
            } catch (Throwable ignored) {
                continue;
            }

            removed += sanitize(
                    child,
                    field.getType(),
                    new ValueReplacer() {
                        @Override
                        public boolean replace(Object replacement) {
                            try {
                                field.set(owner, replacement);
                                return true;
                            } catch (Throwable ignored) {
                                return false;
                            }
                        }
                    },
                    depth + 1,
                    visited,
                    budget
            );
        }

        return removed;
    }
}
