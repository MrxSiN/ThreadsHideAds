package my.github.MrxSiN.threadshideads.parity;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import X.Node;
import X.UnitType;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.legacy.ListSanitizer;
import my.github.MrxSiN.threadshideads.legacy.ReflectiveAdDetector;

/**
 * Hot-path timings, legacy Java vs the AOT Brainfuck core, on the same inputs.
 * Shared by the host benchmark (JUnit) and the on-device benchmark
 * (androidTest). Reports per-call p50/p95 in microseconds and the native
 * program runs and host callbacks per call.
 */
public final class HotPathBenchmark {

    public interface Clock {
        long now();
    }

    private HotPathBenchmark() {
    }

    /** A Threads 445-style feed item: unit-type enum plus a payload wrapper. */
    static Node feedItem(int i) {
        Node payload = new Node();
        payload.caption = i % 3 == 0 ? "organic post " + i : null;
        payload.type = UnitType.THREAD;
        Node item = new Node();
        item.a = payload;
        item.type = i % 7 == 0 ? UnitType.AD : UnitType.THREAD;
        return item;
    }

    public static String run(Policy policy, int rounds) {
        StringBuilder out = new StringBuilder();
        ReflectiveAdDetector legacyDetector = new ReflectiveAdDetector();
        ListSanitizer legacySanitizer = new ListSanitizer(legacyDetector);

        List<Object> items = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            items.add(feedItem(i));
        }
        List<Object> graphs = new ArrayList<>();
        for (long seed = 1; seed <= 200; seed++) {
            graphs.add(Graphs.graph(seed));
        }

        // Warm-up: JIT for the legacy side, caches (class facts) for both.
        for (int r = 0; r < 3; r++) {
            for (Object item : items) {
                legacyDetector.looksLikeAd(item);
                policy.looksLikeAd(item);
            }
        }

        out.append(measure("feed item: legacy looksLikeAd", items, rounds, v -> legacyDetector.looksLikeAd(v), null));
        out.append(measure("feed item: BF looksLikeAd", items, rounds, v -> policy.looksLikeAd(v), policy));
        out.append(measure("random graph: legacy looksLikeAd", graphs, rounds, v -> legacyDetector.looksLikeAd(v), null));
        out.append(measure("random graph: BF looksLikeAd", graphs, rounds, v -> policy.looksLikeAd(v), policy));

        List<Object> batches = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            batches.add(i);
        }
        out.append(measure("batch of 1 item: legacy sanitize", batches, rounds,
                v -> legacySanitizer.sanitize(new ArrayList<Object>(Arrays.asList(feedItem((Integer) v))),
                        List.class, true, true), null));
        out.append(measure("batch of 1 item: BF sanitize", batches, rounds,
                v -> policy.sanitizeList(new ArrayList<Object>(Arrays.asList(feedItem((Integer) v))),
                        List.class, true, true), policy));
        // Pando accessors return string/number lists far more often than trees.
        final List<Object> strings = java.util.Collections.unmodifiableList(Arrays.<Object>asList("a", "b", "c"));
        final List<Object> trees = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            trees.add(new X.TreeModel(new String[]{"{}", "{\"fields\":{\"id\":" + i + "}}", "{}", null}));
        }
        final List<Object> treeList = java.util.Collections.unmodifiableList(trees);
        out.append(measure("pando string list: legacy filter", batches, rounds,
                v -> my.github.MrxSiN.threadshideads.legacy.LegacyRules.filterPandoResult(strings, List.class), null));
        out.append(measure("pando string list: BF filter", batches, rounds,
                v -> policy.pandoFilter(strings, List.class), policy));
        out.append(measure("pando tree list (5): legacy filter", batches, rounds,
                v -> my.github.MrxSiN.threadshideads.legacy.LegacyRules.filterPandoResult(treeList, List.class), null));
        out.append(measure("pando tree list (5): BF filter", batches, rounds,
                v -> policy.pandoFilter(treeList, List.class), policy));
        out.append(measure("label: legacy isAdLabel", batches, rounds,
                v -> my.github.MrxSiN.threadshideads.legacy.AdLabelVocabulary.isAdLabel("Sponsored ·"), null));
        out.append(measure("label: BF AD_LABEL matcher", batches, rounds,
                v -> policy.textMatch(my.github.MrxSiN.threadshideads.bridge.BfAbi.MT_AD_LABEL,
                        my.github.MrxSiN.threadshideads.bridge.BfAbi.TX_LOWER_ROOT, "Sponsored ·"), policy));
        StringBuilder json = new StringBuilder("{\"fields\":{");
        while (json.length() < 3000) {
            json.append("\"text\":\"organic post body with some words\",\"id\":12345,");
        }
        final String organicJson = json.append("\"end\":1}}").toString();
        out.append(measure("json 3KB: legacy structured check", batches, rounds,
                v -> my.github.MrxSiN.threadshideads.legacy.AdFieldSignals.isStructuredAdValue(organicJson), null));
        out.append(measure("json 3KB: BF structured matcher", batches, rounds,
                v -> policy.textMatch(my.github.MrxSiN.threadshideads.bridge.BfAbi.MT_STRUCTURED_VALUE,
                        my.github.MrxSiN.threadshideads.bridge.BfAbi.TX_LOWER_ROOT, organicJson), policy));
        out.append(measure("text: BF unit-type matcher", batches, rounds,
                v -> policy.textMatch(my.github.MrxSiN.threadshideads.bridge.BfAbi.MT_UNIT_TYPE,
                        my.github.MrxSiN.threadshideads.bridge.BfAbi.TX_LOWER_ROOT, "INTENT_AWARE_AD_PIVOT"), policy));
        return out.toString();
    }

    interface Body {
        Object apply(Object value);
    }

    private static String measure(String name, List<Object> inputs, int rounds, Body body, Policy policy) {
        long[] samples = new long[inputs.size() * rounds];
        long[] before = policy == null ? null : policy.counters();
        int n = 0;
        for (int r = 0; r < rounds; r++) {
            for (Object input : inputs) {
                long start = System.nanoTime();
                body.apply(input);
                samples[n++] = System.nanoTime() - start;
            }
        }
        Arrays.sort(samples);
        String counters = "";
        if (policy != null) {
            long[] after = policy.counters();
            counters = String.format(Locale.ROOT, "  programs/call %.1f  callbacks/call %.1f",
                    (after[0] - before[0]) / (double) n, (after[1] - before[1]) / (double) n);
        }
        return String.format(Locale.ROOT, "%-36s p50 %8.1f us  p95 %8.1f us%s%n", name,
                samples[n / 2] / 1000.0, samples[n * 95 / 100] / 1000.0, counters);
    }
}
