package my.github.MrxSiN.threadshideads.core;

import my.github.MrxSiN.threadshideads.bridge.Policy;

/**
 * Removal and collapse counters. The Brainfuck core keeps the totals and
 * decides which events are logged (OP_STATS); this class only words the line.
 */
public final class ModuleStats {

    private ModuleStats() {
    }

    public static void recordModelRemoval(int count, String context) {
        Policy policy = Core.policy();
        if (policy == null || count <= 0) {
            return;
        }
        long[] r = policy.stats(Policy.STAT_MODEL_REMOVAL, count);
        if (r[0] != 0) {
            ModuleLog.log("Removed " + count + " ad item(s) from " + context
                    + " [modelTotal=" + r[1] + ", event=" + r[2] + "]");
        }
    }

    public static void recordUiCollapse(String context) {
        Policy policy = Core.policy();
        if (policy == null) {
            return;
        }
        long[] r = policy.stats(Policy.STAT_UI_COLLAPSE, 1);
        if (r[0] != 0) {
            ModuleLog.log("Collapsed sponsored " + context + " [uiTotal=" + r[1] + "]");
        }
    }

    public static void recordReplacementFailure() {
        Policy policy = Core.policy();
        if (policy == null) {
            return;
        }
        long[] r = policy.stats(Policy.STAT_REPLACEMENT_FAILURE, 1);
        if (r[0] != 0) {
            ModuleLog.log("Could not replace an immutable nested list [replacementFailures=" + r[1] + "]");
        }
    }
}
