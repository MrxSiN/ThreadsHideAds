package my.github.MrxSiN.threadshideads.legacy;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Stand-ins for the legacy logging classes, so the frozen policy copies in this
 * package run on a plain JVM. Tracing was disabled in release builds, so the
 * stubs only count the events the parity tests compare.
 */
final class Diagnostics {

    private Diagnostics() {
    }

    static void traceEmptyingBlocked(List<?> list) {
        LegacyCounters.EMPTYING_BLOCKED.incrementAndGet();
    }

    static void traceOversizedTree(Class<?> type, int length) {
        LegacyCounters.OVERSIZED_TREES.incrementAndGet();
    }

    static void traceAdMatch(Class<?> type, String reason) {
    }
}

final class ModelProbes {

    private ModelProbes() {
    }

    static void traceTreeKeys(Class<?> type, String json) {
    }
}

final class ModuleStats {

    private ModuleStats() {
    }

    static void recordReplacementFailure() {
        LegacyCounters.REPLACEMENT_FAILURES.incrementAndGet();
    }
}
