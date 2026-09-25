package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;

import org.junit.Test;

import X.AdModel;
import X.Node;
import X.UnitType;
import com.instagram.model.ImmutableList;
import my.github.MrxSiN.threadshideads.bridge.CallFrame;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.bridge.TestCore;
import my.github.MrxSiN.threadshideads.legacy.LegacyBoundary;
import my.github.MrxSiN.threadshideads.legacy.LegacyCounters;
import my.github.MrxSiN.threadshideads.legacy.ListSanitizer;
import my.github.MrxSiN.threadshideads.legacy.NestedCollectionSanitizer;
import my.github.MrxSiN.threadshideads.legacy.ReflectiveAdDetector;
import my.github.MrxSiN.threadshideads.legacy.SanitizedList;

/**
 * Legacy v1.2.1 vs the Brainfuck core on seeded random feed-model graphs.
 * Each side gets its own copy of the graph, and results are compared both as
 * verdicts and as canonical dumps of what each side left behind.
 */
public class FeedModelParityTest {

    private static final int GRAPHS = 1500;

    private static final Class<?>[] DECLARED = {
            List.class, Collection.class, Object.class, null, ArrayList.class, LinkedList.class,
            ImmutableList.class, Iterable.class
    };

    @Test
    public void detectorMatchesLegacy() {
        Policy policy = TestCore.policy();
        ReflectiveAdDetector legacy = new ReflectiveAdDetector();
        int ads = 0;
        for (long seed = 1; seed <= GRAPHS; seed++) {
            boolean expected = legacy.looksLikeAd(Graphs.graph(seed));
            boolean actual = policy.looksLikeAd(Graphs.graph(seed));
            assertEquals("seed " + seed, expected, actual);
            ads += expected ? 1 : 0;
        }
        // The corpus must exercise both outcomes.
        assertTrue(ads > GRAPHS / 10 && ads < GRAPHS * 9 / 10);
    }

    @Test
    public void knownUnitTypes() {
        Policy policy = TestCore.policy();
        for (UnitType type : UnitType.values()) {
            Node item = new Node();
            item.type = type;
            boolean expected = new ReflectiveAdDetector().looksLikeAd(item);
            assertEquals(type.name(), expected, policy.looksLikeAd(item));
        }
        Node ad = new Node();
        ad.type = UnitType.AD;
        assertTrue(policy.looksLikeAd(ad));
    }

    @Test
    public void listSanitizerMatchesLegacy() {
        Policy policy = TestCore.policy();
        ListSanitizer legacy = new ListSanitizer(new ReflectiveAdDetector());
        int changed = 0;
        for (long seed = 1; seed <= GRAPHS; seed++) {
            Class<?> declared = DECLARED[(int) (seed % DECLARED.length)];
            boolean replace = seed % 3 != 0;
            boolean empty = seed % 5 != 0;
            List<Object> a = Graphs.feed(seed);
            List<Object> b = Graphs.feed(seed);
            LegacyCounters.reset();
            SanitizedList expected = legacy.sanitize(a, declared, replace, empty);
            Policy.Sanitized actual = policy.sanitizeList(b, declared, replace, empty);
            String where = "seed " + seed;
            assertEquals(where, expected.changed, actual.changed);
            assertEquals(where, expected.removed, actual.removed);
            assertEquals(where, LegacyCounters.EMPTYING_BLOCKED.get() > 0, actual.emptyingBlocked);
            assertEquals(where, expected.value == a, actual.value == b);
            assertEquals(where, Graphs.dump(expected.value), Graphs.dump(actual.value));
            assertEquals(where, Graphs.dump(a), Graphs.dump(b));
            changed += expected.changed ? 1 : 0;
        }
        assertTrue(changed > 50);
    }

    @Test
    public void nestedSanitizerMatchesLegacy() {
        Policy policy = TestCore.policy();
        NestedCollectionSanitizer legacy = new NestedCollectionSanitizer(new ListSanitizer(new ReflectiveAdDetector()));
        int removed = 0;
        for (long seed = 1; seed <= GRAPHS; seed++) {
            Object a = Graphs.graph(seed * 7919);
            Object b = Graphs.graph(seed * 7919);
            LegacyCounters.reset();
            int expected = legacy.sanitize(a);
            int[] actual = policy.nestedSanitize(b);
            String where = "seed " + seed;
            assertEquals(where, expected, actual[0]);
            assertEquals(where, LegacyCounters.REPLACEMENT_FAILURES.get(), actual[1]);
            assertEquals(where, Graphs.dump(a), Graphs.dump(b));
            removed += expected;
        }
        assertTrue(removed > 20);
    }

    @Test
    public void boundaryFlowMatchesLegacy() throws Exception {
        Policy policy = TestCore.policy();
        for (long seed = 1; seed <= 600; seed++) {
            LegacyBoundary legacy = new LegacyBoundary();
            Frame frame = new Frame();
            legacy.args = new Object[]{seed % 2, Graphs.feed(seed), Graphs.feed(seed + 1)};
            frame.args = new Object[]{seed % 2, Graphs.feed(seed), Graphs.feed(seed + 1)};
            Class<?> declared = DECLARED[(int) (seed % DECLARED.length)];
            legacy.parameterTypes = new Class<?>[]{Integer.class, declared == null ? List.class : declared, List.class};
            frame.types = legacy.parameterTypes;
            legacy.returnType = seed % 2 == 0 ? List.class : Object.class;
            frame.returnType = legacy.returnType;

            LegacyCounters.reset();
            legacy.before();
            List<Policy.Report> before = policy.boundaryBefore(frame);

            // The hooked method returns a list or a wrapper, and appends an
            // advertisement to its mutable argument (late insertion).
            legacy.result = seed % 2 == 0 ? Graphs.feed(seed + 2) : Graphs.graph(seed + 2);
            frame.result = seed % 2 == 0 ? Graphs.feed(seed + 2) : Graphs.graph(seed + 2);
            appendAd(legacy.args[1]);
            appendAd(frame.args[1]);

            legacy.after(seed % 11 == 0);
            List<Policy.Report> after = policy.boundaryAfter(frame, seed % 11 == 0);

            List<int[]> actual = new ArrayList<>();
            for (Policy.Report r : before) {
                actual.add(new int[]{r.site, r.index, r.count});
            }
            for (Policy.Report r : after) {
                actual.add(new int[]{r.site, r.index, r.count});
            }
            String where = "seed " + seed;
            assertEquals(where, render(legacy.removals), render(actual));
            assertEquals(where, Graphs.dump(Arrays.asList(legacy.args)), Graphs.dump(Arrays.asList(frame.args)));
            assertEquals(where, Graphs.dump(legacy.result), Graphs.dump(frame.result));
        }
    }

    @Test
    public void emptyingRules() {
        Policy policy = TestCore.policy();
        AdModel ad = new AdModel();
        ad.isSponsored = true;
        List<Object> top = new ArrayList<>(Arrays.asList((Object) ad));
        Policy.Sanitized emptied = policy.sanitizeList(top, List.class, true, true);
        assertTrue(emptied.changed);
        assertTrue(top.isEmpty());
        List<Object> inner = new ArrayList<>(Arrays.asList((Object) ad));
        Policy.Sanitized kept = policy.sanitizeList(inner, List.class, true, false);
        assertTrue(kept.emptyingBlocked);
        assertEquals(1, inner.size());
        assertSame(inner, kept.value);
    }

    private static void appendAd(Object list) {
        if (list instanceof ArrayList<?>) {
            AdModel ad = new AdModel();
            ad.isSponsored = true;
            @SuppressWarnings("unchecked")
            List<Object> writable = (List<Object>) list;
            writable.add(ad);
        }
    }

    private static String render(List<int[]> removals) {
        StringBuilder out = new StringBuilder();
        for (int[] r : removals) {
            out.append(Arrays.toString(r));
        }
        return out.toString();
    }

    static final class Frame implements CallFrame {
        Object[] args;
        Class<?>[] types;
        Class<?> returnType;
        Object result;

        @Override
        public int argumentCount() {
            return args.length;
        }

        @Override
        public Object argument(int index) {
            return args[index];
        }

        @Override
        public void setArgument(int index, Object value) {
            args[index] = value;
        }

        @Override
        public Class<?> parameterType(int index) {
            return types[index];
        }

        @Override
        public Class<?> returnType() {
            return returnType;
        }

        @Override
        public Object result() {
            return result;
        }

        @Override
        public void setResult(Object value) {
            result = value;
        }
    }
}
