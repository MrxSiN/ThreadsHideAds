package my.github.MrxSiN.threadshideads.legacy;

import java.util.ArrayList;
import java.util.List;


/**
 * Removes advertisement entries from a single feed list.
 *
 * Mutable lists are edited in place; a replacement copy is produced only when
 * the caller allows it and the declared type can accept an ArrayList.
 */
public final class ListSanitizer {

    private final AdDetector detector;

    public ListSanitizer(AdDetector detector) {
        this.detector = detector;
    }

    public SanitizedList sanitize(
            Object value,
            Class<?> declaredType,
            boolean allowReplacement
    ) {
        return sanitize(value, declaredType, allowReplacement, false);
    }

    /**
     * @param allowEmptying whether the list may end up empty. A top-level feed
     *                      list may: removing its only entry means the page
     *                      carried nothing but an advertisement. An inner list
     *                      owned by a feed item may not, because the app reads
     *                      its first element unconditionally.
     */
    public SanitizedList sanitize(
            Object value,
            Class<?> declaredType,
            boolean allowReplacement,
            boolean allowEmptying
    ) {
        if (!(value instanceof List<?>)) {
            return SanitizedList.unchanged(value);
        }

        List<?> source = (List<?>) value;
        ArrayList<Integer> adIndexes = new ArrayList<>();
        ArrayList<Object> filtered = new ArrayList<>(source.size());

        for (int i = 0; i < source.size(); i++) {
            Object item = source.get(i);

            /*
             * A nested list is a container, not itself a feed item. Keep it
             * here and let bounded nested sanitization remove its ad entries.
             */
            if (!(item instanceof List<?>) && detector.looksLikeAd(item)) {
                adIndexes.add(i);
            } else {
                filtered.add(item);
            }
        }

        if (adIndexes.isEmpty()) {
            return SanitizedList.unchanged(value);
        }

        /*
         * An inner list owned by a feed item is never emptied: the app reads
         * its first element unconditionally, and clearing one of those crashes
         * Threads with NoSuchElementException. A top-level feed list is
         * different, because Threads delivers items in batches of one, so
         * refusing to empty it would let every advertisement through.
         */
        if (filtered.isEmpty() && !allowEmptying) {
            Diagnostics
                    .traceEmptyingBlocked(source);
            return SanitizedList.unchanged(value);
        }

        int removedInPlace = removeIndexesInPlace(source, adIndexes);
        if (removedInPlace > 0) {
            return new SanitizedList(value, removedInPlace, true);
        }

        if (allowReplacement && canReplaceWithArrayList(declaredType)) {
            return new SanitizedList(filtered, adIndexes.size(), true);
        }

        return SanitizedList.unchanged(value);
    }

    private static int removeIndexesInPlace(
            List<?> source,
            List<Integer> adIndexes
    ) {
        int removed = 0;

        /*
         * Remove backwards so index shifts cannot skip entries. This is safer
         * than clear/addAll: an unsupported operation cannot leave the source
         * list cleared but only partly rebuilt.
         */
        for (int i = adIndexes.size() - 1; i >= 0; i--) {
            int index = adIndexes.get(i);
            try {
                source.remove(index);
                removed++;
            } catch (Throwable ignored) {
                break;
            }
        }

        return removed;
    }

    private static boolean canReplaceWithArrayList(Class<?> declaredType) {
        return declaredType == null
                || declaredType == Object.class
                || declaredType.isInterface()
                || declaredType.isAssignableFrom(ArrayList.class);
    }
}
