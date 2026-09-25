package my.github.MrxSiN.threadshideads.legacy;

import java.util.concurrent.atomic.AtomicInteger;

/** Events counted by the legacy logging stubs. */
public final class LegacyCounters {

    public static final AtomicInteger EMPTYING_BLOCKED = new AtomicInteger();
    public static final AtomicInteger OVERSIZED_TREES = new AtomicInteger();
    public static final AtomicInteger REPLACEMENT_FAILURES = new AtomicInteger();

    private LegacyCounters() {
    }

    public static void reset() {
        EMPTYING_BLOCKED.set(0);
        OVERSIZED_TREES.set(0);
        REPLACEMENT_FAILURES.set(0);
    }
}
