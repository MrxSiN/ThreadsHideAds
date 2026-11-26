package my.github.MrxSiN.threadshideads.bridge;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * List items the detector already found organic (CAP_ITEM_VERDICT_*), by
 * identity: Threads' feed items override equals/hashCode, and the same item
 * reaches several nested cache boundaries per merge. Lock-free; a slot holds
 * one weak reference, so it pins nothing.
 */
final class OrganicItems {

    // ponytail: direct-mapped, a colliding item evicts the slot and is walked again; grow if hit rate drops.
    private static final int SLOTS = 4096;

    private final AtomicReferenceArray<WeakReference<Object>> slots = new AtomicReferenceArray<>(SLOTS);

    boolean contains(Object item) {
        if (item == null) {
            return false;
        }
        WeakReference<Object> ref = slots.get(slot(item));
        return ref != null && ref.get() == item;
    }

    void add(Object item) {
        if (item != null) {
            slots.set(slot(item), new WeakReference<>(item));
        }
    }

    private static int slot(Object item) {
        int h = System.identityHashCode(item);
        return (h ^ (h >>> 16)) & (SLOTS - 1);
    }
}
