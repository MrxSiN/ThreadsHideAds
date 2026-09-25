package my.github.MrxSiN.threadshideads.legacy;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Bounded reflective inspection of a feed item.
 *
 * It does not blindly call toString(), because captions may contain ordinary
 * words such as "sponsored". Depth, breadth, and total field visits are all
 * capped so an unexpected model shape cannot stall the feed.
 */
public final class ReflectiveAdDetector implements AdDetector {

    /*
     * A feed item wraps its posts several levels down: item, container,
     * thread, post list, post, media. The advertisement evidence sits at the
     * bottom of that chain, so the traversal has to reach it from the item
     * that owns it, or the item cannot be dropped as a whole.
     */
    private static final int MAX_DEPTH = 8;

    /*
     * A feed item reaches its Pando tree several levels down, past ranking and
     * container models. The previous budget ran out before the tree was read,
     * so an advertisement was only recognized once the traversal reached its
     * inner posts, which is too late to drop the item cleanly.
     */
    private static final int MAX_BUDGET = 1200;
    private static final int MAX_ENTRIES_PER_CONTAINER = 32;

    @Override
    public boolean looksLikeAd(Object value) {
        try {
            return inspect(
                    value,
                    0,
                    new IdentityHashMap<Object, Boolean>(),
                    new int[]{0}
            );
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean inspect(
            Object value,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        if (value == null
                || depth > MAX_DEPTH
                || budget[0]++ > MAX_BUDGET) {
            return false;
        }

        if (value instanceof CharSequence) {
            return AdFieldSignals.isStructuredAdValue(value.toString());
        }

        if (value instanceof Enum<?>) {
            return AdFieldSignals.isStructuredAdValue(((Enum<?>) value).name());
        }

        if (value instanceof Number
                || value instanceof Boolean
                || value instanceof Character
                || value instanceof Class<?>) {
            return false;
        }

        if (visited.put(value, Boolean.TRUE) != null) {
            return false;
        }

        Class<?> type = value.getClass();

        if (AdFieldSignals.hasStrongAdClassToken(
                type.getName().toLowerCase(Locale.ROOT))) {
            return true;
        }

        /*
         * Pando models keep their values in a native tree, so its JSON view is
         * the only place the sponsored markers are visible. The verdict is
         * shared with the response hooks, which cache it per tree.
         */
        if (PandoAdSignals.isSponsoredTree(value)) {
            return true;
        }

        if (Reflection.hasStructuredToString(type)) {
            try {
                if (AdFieldSignals.isStructuredAdValue(String.valueOf(value))) {
                    return true;
                }
            } catch (Throwable ignored) {
                // Continue with field inspection.
            }
        }

        if (value instanceof Map<?, ?>) {
            return inspectMap((Map<?, ?>) value, depth, visited, budget);
        }

        if (value instanceof Collection<?>) {
            return inspectCollection((Collection<?>) value, depth, visited, budget);
        }

        if (type.isArray()) {
            return inspectArray(value, depth, visited, budget);
        }

        return inspectFields(value, type, depth, visited, budget);
    }

    private boolean inspectMap(
            Map<?, ?> map,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int inspected = 0;

        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (inspected++ >= MAX_ENTRIES_PER_CONTAINER) {
                break;
            }

            String key = String.valueOf(entry.getKey());
            Object mapValue = entry.getValue();

            if (AdFieldSignals.isStrongAdFieldName(key)
                    && AdFieldSignals.isAdFieldValue(mapValue)) {
                return true;
            }

            if (inspect(mapValue, depth + 1, visited, budget)) {
                return true;
            }
        }

        return false;
    }

    private boolean inspectCollection(
            Collection<?> collection,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int inspected = 0;

        for (Object item : collection) {
            if (inspected++ >= MAX_ENTRIES_PER_CONTAINER) {
                break;
            }

            if (inspect(item, depth + 1, visited, budget)) {
                return true;
            }
        }

        return false;
    }

    private boolean inspectArray(
            Object array,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        int length = Math.min(Array.getLength(array), MAX_ENTRIES_PER_CONTAINER);

        for (int i = 0; i < length; i++) {
            if (inspect(Array.get(array, i), depth + 1, visited, budget)) {
                return true;
            }
        }

        return false;
    }

    private boolean inspectFields(
            Object value,
            Class<?> type,
            int depth,
            IdentityHashMap<Object, Boolean> visited,
            int[] budget
    ) {
        for (Field field : Reflection.getInstanceFields(type)) {
            Object fieldValue;

            try {
                fieldValue = field.get(value);
            } catch (Throwable ignored) {
                continue;
            }

            if (AdFieldSignals.isStrongAdFieldName(field.getName())
                    && AdFieldSignals.isAdFieldValue(fieldValue)) {
                return true;
            }

            if (depth < MAX_DEPTH
                    && Reflection.shouldInspectChild(fieldValue)
                    && inspect(fieldValue, depth + 1, visited, budget)) {
                return true;
            }
        }

        return false;
    }
}
