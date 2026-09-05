package my.github.MrxSiN.threadshideads.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Rate-limited counters for removed model items and collapsed views.
 *
 * Counting and reporting are deliberately separated from the detection and
 * hooking logic so those classes have no reason to change when the reporting
 * policy changes.
 */
public final class ModuleStats {

    private static final AtomicInteger MODEL_REMOVAL_TOTAL = new AtomicInteger();
    private static final AtomicInteger MODEL_REMOVAL_EVENTS = new AtomicInteger();
    private static final AtomicInteger UI_COLLAPSE_TOTAL = new AtomicInteger();
    private static final AtomicInteger REPLACEMENT_FAILURES = new AtomicInteger();

    private ModuleStats() {
    }

    public static void recordModelRemoval(int count, String context) {
        if (count <= 0) {
            return;
        }

        int total = MODEL_REMOVAL_TOTAL.addAndGet(count);
        int event = MODEL_REMOVAL_EVENTS.incrementAndGet();

        if (event <= 12 || event % 25 == 0) {
            ModuleLog.log("Removed " + count + " ad item(s) from " + context
                    + " [modelTotal=" + total
                    + ", event=" + event + "]");
        }
    }

    public static void recordUiCollapse(String context) {
        int total = UI_COLLAPSE_TOTAL.incrementAndGet();

        if (total <= 8 || total % 25 == 0) {
            ModuleLog.log("Collapsed sponsored " + context
                    + " [uiTotal=" + total + "]");
        }
    }

    public static void recordReplacementFailure() {
        int failures = REPLACEMENT_FAILURES.incrementAndGet();

        if (failures <= 3 || failures % 10 == 0) {
            ModuleLog.log("Could not replace an immutable nested list"
                    + " [replacementFailures=" + failures + "]");
        }
    }
}
