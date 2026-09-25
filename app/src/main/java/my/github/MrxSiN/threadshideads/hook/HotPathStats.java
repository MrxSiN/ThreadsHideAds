package my.github.MrxSiN.threadshideads.hook;

import java.util.concurrent.atomic.AtomicLong;

import my.github.MrxSiN.threadshideads.BuildConfig;
import my.github.MrxSiN.threadshideads.core.ModuleLog;

/**
 * Debug-build timing of the hottest hooks, logged every 10 seconds: calls and
 * total time spent in the Brainfuck core. Off in release builds unless built
 * with -PhotPathStats.
 */
final class HotPathStats {

    static final boolean ENABLED = BuildConfig.DEBUG || BuildConfig.HOT_PATH_STATS;

    static final HotPathStats PANDO = new HotPathStats("Pando accessor");
    static final HotPathStats BOUNDARY = new HotPathStats("feed boundary (before)");

    private static final long WINDOW_NS = 10_000_000_000L;

    private final String name;
    private final AtomicLong calls = new AtomicLong();
    private final AtomicLong nanos = new AtomicLong();
    private final AtomicLong windowStart = new AtomicLong(System.nanoTime());

    private HotPathStats(String name) {
        this.name = name;
    }

    void record(long elapsed) {
        calls.incrementAndGet();
        nanos.addAndGet(elapsed);
        long now = System.nanoTime();
        long start = windowStart.get();
        if (now - start >= WINDOW_NS && windowStart.compareAndSet(start, now)) {
            long n = calls.getAndSet(0);
            long total = nanos.getAndSet(0);
            ModuleLog.log(String.format(java.util.Locale.ROOT,
                    "hot path %s: %d calls in %.1f s, %.2f ms in core, %.1f us/call",
                    name, n, (now - start) / 1e9, total / 1e6, n == 0 ? 0 : total / 1e3 / n));
        }
    }
}
