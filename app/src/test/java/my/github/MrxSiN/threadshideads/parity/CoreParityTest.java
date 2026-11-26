package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.Test;

import X.MessagingFeed;
import X.Shapes;
import X.TreeModel;
import com.facebook.pando.TreeJNI;
import com.instagram.model.ImmutableList;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.bridge.TestCore;
import my.github.MrxSiN.threadshideads.legacy.LegacyRules;

/**
 * Lifecycle, retry, discovery, Pando, statistics and rendered-label decisions:
 * legacy v1.2.1 (LegacyRules and the frozen classes) vs the Brainfuck core.
 */
public class CoreParityTest {

    // ------------------------------------------------------------ scope

    @Test
    public void loadPackageScopeAndOnce() {
        Policy policy = TestCore.policy();
        assertArrayEquals(new boolean[]{false, false, false}, policy.loadPackage("com.instagram.android", null));
        assertArrayEquals(new boolean[]{false, false, false},
                policy.loadPackage("com.instagram.barcelona", "com.instagram.barcelona:mqtt"));
        assertArrayEquals(new boolean[]{true, true, true},
                policy.loadPackage("com.instagram.barcelona", null));
        assertArrayEquals(new boolean[]{true, false, false},
                policy.loadPackage("com.instagram.barcelona", "com.instagram.barcelona"));
        for (String p : new String[]{"com.instagram.barcelona", "x", "com.instagram.barcelona2"}) {
            for (String q : new String[]{null, "com.instagram.barcelona", "com.instagram.barcelona:x"}) {
                assertEquals(p + "/" + q, LegacyRules.inScope(p, q), policy.loadPackage(p, q)[0]);
            }
        }
        assertTrue(policy.attachGuardFailed());
    }

    // ------------------------------------------------------------ retry

    @Test
    public void retryStateMachineMatchesLegacy() {
        Random random = new Random(42);
        for (int run = 0; run < 400; run++) {
            Policy policy = TestCore.policy();
            LegacyRules.RetryModel legacy = new LegacyRules.RetryModel();
            List<String> actual = new ArrayList<>();
            int retry = 0;
            for (int step = 0; step < 8; step++) {
                boolean loader = random.nextInt(5) != 0;
                int started = legacy.request(loader, retry);
                Policy.Init decision = policy.initRequest(loader, retry);
                record(actual, decision);
                if (random.nextInt(6) == 0) {
                    // A second trigger while the attempt runs is dropped.
                    legacy.request(true, retry);
                    record(actual, policy.initRequest(true, retry));
                }
                if (started > 0) {
                    boolean success = random.nextInt(3) == 0;
                    legacy.finish(success, retry);
                    Policy.Init after = policy.initFinish(success, retry);
                    record(actual, after);
                    if (after.action == Policy.INIT_RETRY) {
                        retry = after.nextRetry;
                    }
                } else if (decision.action == Policy.INIT_RETRY) {
                    retry = decision.nextRetry;
                }
            }
            assertEquals("run " + run, legacy.events, actual);
        }
    }

    private static void record(List<String> events, Policy.Init d) {
        switch (d.action) {
            case Policy.INIT_START:
                events.add("attempt " + d.attempt);
                break;
            case Policy.INIT_COMPLETE:
                events.add("complete");
                break;
            case Policy.INIT_RETRY:
                if (d.reason == 1) {
                    events.add("skipped-null-loader");
                } else {
                    events.add("failed");
                }
                events.add("retry " + d.nextRetry + " in " + d.delayMs);
                break;
            case Policy.INIT_EXHAUSTED:
                events.add(d.reason == 1 ? "skipped-null-loader" : "failed");
                events.add("exhausted");
                break;
            default:
                events.add(d.reason == 3 ? "ignored-busy" : "ignored-initialized");
        }
    }

    // ------------------------------------------------------------ install order

    @Test
    public void installOrderMatchesLegacy() {
        Policy policy = TestCore.policy();
        for (int mask = 0; mask < 64; mask++) {
            boolean nativeOk = (mask & 1) != 0;
            boolean loaderOk = (mask & 2) != 0;
            boolean sourceDir = (mask & 4) != 0;
            boolean apkOk = (mask & 8) != 0;
            int feedAfterCache = (mask & 16) != 0 ? 3 : 0;
            int primary = (mask & 32) != 0 ? 2 : 0;
            int pando = mask % 3 == 0 ? 0 : 5;
            List<Integer> expected = legacyInstall(nativeOk, loaderOk, sourceDir, apkOk, feedAfterCache, primary, pando);

            List<Integer> actual = new ArrayList<>();
            int step = Policy.STEP_BEGIN;
            boolean ok = false;
            int aux = 0;
            int feed = 0;
            for (int guard = 0; guard < 12; guard++) {
                int[] next = policy.installStep(step, ok, aux, feed, pando);
                step = next[0];
                actual.add(step);
                if (step == Policy.STEP_DONE) {
                    actual.add(next[1]);
                    break;
                }
                if (step == Policy.STEP_ABORT) {
                    break;
                }
                ok = true;
                aux = 0;
                switch (step) {
                    case Policy.STEP_LOAD_NATIVE:
                        ok = nativeOk;
                        break;
                    case Policy.STEP_OPEN_LOADER:
                        ok = loaderOk;
                        aux = sourceDir ? 1 : 0;
                        break;
                    case Policy.STEP_OPEN_APK:
                        ok = apkOk;
                        break;
                    case Policy.STEP_FEED_CACHE:
                        feed = feedAfterCache;
                        break;
                    case Policy.STEP_PRIMARY:
                        aux = primary;
                        feed += primary;
                        break;
                    case Policy.STEP_BROAD:
                        feed += 1;
                        break;
                    default:
                        break;
                }
            }
            assertEquals("mask " + mask, expected, actual);
        }
    }

    /** ModelHookInstaller.installHooks of v1.2.1, as the step sequence it walks. */
    private static List<Integer> legacyInstall(boolean nativeOk, boolean loaderOk, boolean sourceDir,
                                               boolean apkOk, int feedAfterCache, int primary, int pando) {
        List<Integer> steps = new ArrayList<>(Arrays.asList(Policy.STEP_PANDO, Policy.STEP_LOAD_NATIVE));
        if (!nativeOk) {
            steps.add(Policy.STEP_ABORT);
            return steps;
        }
        steps.add(Policy.STEP_OPEN_LOADER);
        if (!loaderOk) {
            if (!sourceDir) {
                steps.add(Policy.STEP_ABORT);
                return steps;
            }
            steps.add(Policy.STEP_OPEN_APK);
            if (!apkOk) {
                steps.add(Policy.STEP_ABORT);
                return steps;
            }
        }
        steps.add(Policy.STEP_FEED_CACHE);
        steps.add(Policy.STEP_PRIMARY);
        int feed = feedAfterCache + primary;
        if (primary == 0 && feedAfterCache == 0) {
            steps.add(Policy.STEP_BROAD);
            feed += 1;
        }
        steps.add(Policy.STEP_DONE);
        steps.add(feed > 0 || pando > 0 ? 1 : 0);
        return steps;
    }

    // ------------------------------------------------------------ discovery

    @Test
    public void discoveryClassificationMatchesLegacy() throws Exception {
        Policy policy = TestCore.policy();
        List<Method> methods = new ArrayList<>();
        methods.addAll(Arrays.asList(Shapes.class.getDeclaredMethods()));
        methods.addAll(Arrays.asList(MessagingFeed.class.getDeclaredMethods()));
        methods.addAll(Arrays.asList(X.ShapesApi.class.getDeclaredMethods()));
        methods.addAll(Arrays.asList(TreeJNI.class.getDeclaredMethods()));
        Collections.sort(methods, (a, b) -> a.toGenericString().compareTo(b.toGenericString()));
        Random random = new Random(7);
        for (Method method : methods) {
            String where = method.toGenericString();
            assertEquals(where, LegacyRules.isPrimaryFeedBoundaryShape(method), policy.primaryCandidate(method));
            assertEquals(where, LegacyRules.isFeedOwnerListBoundary(method), policy.feedOwnerMethod(method));
            assertEquals(where, LegacyRules.isPandoListAccessor(method), policy.pandoAccessor(method));
            for (int trial = 0; trial < 12; trial++) {
                boolean[] flags = new boolean[13];
                Set<String> markers = new LinkedHashSet<>();
                for (int i = 0; i < 13; i++) {
                    if (random.nextInt(trial < 4 ? 20 : 5) == 0) {
                        flags[i] = true;
                        markers.add(LegacyRules.DEX_MARKERS[i]);
                    }
                }
                int expected = LegacyRules.isLikelyAdListBoundary(method, markers) ? Policy.BROAD_LIST
                        : LegacyRules.isSafePositiveAdBoolean(method, markers) ? Policy.BROAD_BOOLEAN
                        : Policy.BROAD_SKIP;
                assertEquals(where + " " + markers, expected, policy.broadCandidate(method, flags));
            }
        }
        policy.primaryDecision();
        for (int n = 0; n <= 10; n++) {
            for (int i = 0; i < n; i++) {
                assertTrue(policy.primaryCandidate(Shapes.class.getDeclaredMethod("DuY",
                        Integer.class, Integer.class, List.class)));
            }
            int[] decision = policy.primaryDecision();
            assertEquals("n " + n, LegacyRules.acceptPrimaryCount(n), decision[0] == Policy.DECISION_INSTALL);
            assertEquals(n, decision[1]);
        }
        // Injector: exactly one method with the (object, int) -> boolean shape.
        Method[] declared = Shapes.class.getDeclaredMethods();
        Arrays.sort(declared, (a, b) -> a.toGenericString().compareTo(b.toGenericString()));
        for (Method method : declared) {
            assertEquals(method.toGenericString(), LegacyRules.isAdInjectionShape(method), policy.injectorMethod(method));
        }
        assertEquals(LegacyRules.selectInjector(declared) != null,
                policy.injectorDecision() == Policy.DECISION_INSTALL);
        policy.injectorMethod(Shapes.class.getDeclaredMethod("inject", X.Node.class, int.class));
        assertEquals(Policy.DECISION_INSTALL, policy.injectorDecision());
        assertEquals(Policy.DECISION_NONE, policy.injectorDecision());
    }

    /** Shapes of PermalinkAdsUseCase: only the static suspend fetch step matches. */
    static final class AdsUseCase {
        static Object fetch(Object state, AdsUseCase owner, Object continuation) { return null; }
        static void store(AdsUseCase owner, List<?> ads) { }
        static Object other(Object state, Object continuation) { return null; }
        Object instance(AdsUseCase owner) { return null; }
    }

    @Test
    public void permalinkAdsLoaderExactlyOne() throws Exception {
        Policy policy = TestCore.policy();
        assertEquals("com.instagram.barcelona.permalink.adsusecase.PermalinkAdsUseCase",
                policy.constString(Policy.TABLE_NAMES, Policy.NAME_PERMALINK_ADS));
        for (Method method : AdsUseCase.class.getDeclaredMethods()) {
            assertEquals(method.toGenericString(), method.getName().equals("fetch"), policy.permalinkAdsMethod(method));
        }
        assertEquals(Policy.DECISION_INSTALL, policy.permalinkAdsDecision());
        assertEquals(Policy.DECISION_NONE, policy.permalinkAdsDecision());
        Method fetch = AdsUseCase.class.getDeclaredMethod("fetch", Object.class, AdsUseCase.class, Object.class);
        policy.permalinkAdsMethod(fetch);
        policy.permalinkAdsMethod(fetch);
        assertEquals(Policy.DECISION_AMBIGUOUS, policy.permalinkAdsDecision());
        policy.permalinkAdsMethod(fetch);
        policy.reset();
        assertEquals(Policy.DECISION_NONE, policy.permalinkAdsDecision());
    }

    /** Shapes of the feed cache, its handler, a feed item and Media (Threads 457). */
    static final class Item {
        Media A06() { return null; }
        static Media wrap(Item item) { return null; }
        Media withArg(int index) { return null; }
        Object other() { return null; }
    }

    static final class Media {
        boolean isAd() { return false; }
        static boolean isAdStatic() { return false; }
        boolean takesArg(Object o) { return false; }
        Boolean boxed() { return null; }
    }

    abstract static class Handler {
        Integer DKX(Item item, int position) { return null; }
        abstract Integer declared(Item item, int position);
        Integer primitiveFirst(int item, int position) { return null; }
        Integer three(Item item, int position, int more) { return null; }
        Boolean wrongAnswer(Item item, int position) { return null; }
        Integer longPosition(Item item, long position) { return null; }
    }

    static final class Cache {
        Object A0U(Item item, Object continuation) { return null; }
        public Object A0R(Item item, Object a, Object b, String c, Object continuation) { return null; }
        static Object staticWrite(Item item, Object continuation) { return null; }
        private Object privateWrite(Item item, Object continuation) { return null; }
        void voidWrite(Item item) { }
        Object listWrite(List<?> items, Object continuation) { return null; }
        Object noArgs() { return null; }
    }

    @Test
    public void itemInsertWritesAndNativeCheck() throws Exception {
        Policy policy = TestCore.policy();
        assertEquals("com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheHandler",
                policy.constString(Policy.TABLE_NAMES, Policy.NAME_FEED_CACHE_HANDLER));
        assertEquals("com.instagram.barcelona.feed.data.cache.BarcelonaFeedCache",
                policy.constString(Policy.TABLE_NAMES, Policy.NAME_FEED_CACHE));
        assertEquals("com.instagram.feed.media.Media", policy.constString(Policy.TABLE_NAMES, Policy.NAME_MEDIA));
        assertEquals("injected", policy.constString(Policy.TABLE_NAMES, Policy.NAME_INJECTED_KEY));

        for (Method method : Handler.class.getDeclaredMethods()) {
            assertEquals(method.toGenericString(), method.getName().equals("DKX"), policy.itemInsertMethod(method));
        }
        assertEquals(Policy.DECISION_INSTALL, policy.itemInsertDecision());
        assertEquals(Policy.DECISION_NONE, policy.itemInsertDecision());
        Method insert = Handler.class.getDeclaredMethod("DKX", Item.class, int.class);
        policy.itemInsertMethod(insert);
        policy.itemInsertMethod(insert);
        assertEquals(Policy.DECISION_AMBIGUOUS, policy.itemInsertDecision());

        for (Method method : Cache.class.getDeclaredMethods()) {
            boolean expected = method.getName().equals("A0U") || method.getName().equals("A0R");
            assertEquals(method.toGenericString(), expected, policy.itemWriteMethod(method, Item.class));
            assertEquals(method.toGenericString(), false, policy.itemWriteMethod(method, Media.class));
        }

        for (Method method : Item.class.getDeclaredMethods()) {
            assertEquals(method.toGenericString(), method.getName().equals("A06"),
                    policy.mediaGetterMethod(method, Media.class));
        }
        assertEquals(Policy.DECISION_INSTALL, policy.mediaGetterDecision());
        for (Method method : Media.class.getDeclaredMethods()) {
            assertEquals(method.toGenericString(), method.getName().equals("isAd"), policy.nativeAdMethod(method));
        }
        assertEquals(Policy.DECISION_INSTALL, policy.nativeAdDecision());
        assertEquals(Policy.DECISION_NONE, policy.nativeAdDecision());

        // Counters are persistent and cleared by reset.
        policy.itemInsertMethod(insert);
        policy.mediaGetterMethod(Item.class.getDeclaredMethod("A06"), Media.class);
        policy.nativeAdMethod(Media.class.getDeclaredMethod("isAd"));
        policy.reset();
        assertEquals(Policy.DECISION_NONE, policy.itemInsertDecision());
        assertEquals(Policy.DECISION_NONE, policy.mediaGetterDecision());
        assertEquals(Policy.DECISION_NONE, policy.nativeAdDecision());

        assertEquals(false, policy.itemAction(false, false));
        assertEquals(true, policy.itemAction(true, false));
        assertEquals(true, policy.itemAction(false, true));
        assertEquals(true, policy.itemAction(true, true));
    }

    @Test
    public void namesMatchLegacyConstants() {
        Policy policy = TestCore.policy();
        assertEquals(Arrays.asList(LegacyRules.FEED_OWNER_CLASSES), policy.constTable(Policy.TABLE_FEED_OWNERS));
        assertEquals(Arrays.asList(LegacyRules.DEX_MARKERS), policy.constTable(Policy.TABLE_DEX_MARKERS));
        assertEquals(LegacyRules.PRIMARY_BOUNDARY_MARKER, policy.constString(Policy.TABLE_NAMES, Policy.NAME_PRIMARY_MARKER));
        assertEquals(LegacyRules.PANDO_TREE_CLASS, policy.constString(Policy.TABLE_NAMES, Policy.NAME_PANDO_TREE));
        assertEquals(LegacyRules.SPOOL_CACHE_HANDLER, policy.constString(Policy.TABLE_NAMES, Policy.NAME_SPOOL_HANDLER));
        assertEquals("android.app.Application", policy.constString(Policy.TABLE_NAMES, Policy.NAME_APPLICATION));
        assertEquals("attach", policy.constString(Policy.TABLE_NAMES, Policy.NAME_ATTACH));
        assertEquals("dexkit", policy.constString(Policy.TABLE_NAMES, Policy.NAME_DEXKIT_LIBRARY));
        assertEquals("copyOf", policy.constString(Policy.TABLE_NAMES, Policy.NAME_COPY_FACTORY));
        assertArrayEquals(new int[]{6, LegacyRules.MAX_ANCESTOR_DEPTH, LegacyRules.MAX_LIST_CONTAINER_DEPTH + 1},
                policy.limits());
    }

    // ------------------------------------------------------------ Pando

    @Test
    public void pandoFilterMatchesLegacy() {
        Policy policy = TestCore.policy();
        Class<?>[] declared = {List.class, ImmutableList.class, Object.class, null, ArrayList.class,
                Collections.unmodifiableList(new ArrayList<>()).getClass()};
        int replaced = 0;
        for (long seed = 1; seed <= 800; seed++) {
            Class<?> type = declared[(int) (seed % declared.length)];
            List<Object> a = pandoList(seed);
            List<Object> b = pandoList(seed);
            LegacyRules.PandoOutcome expected = LegacyRules.filterPandoResult(a, type);
            Policy.PandoFilter actual = policy.pandoFilter(b, type);
            String where = "seed " + seed;
            int expectedAction = expected.emptyingBlocked ? Policy.PandoFilter.BLOCKED
                    : expected.replacementFailed ? Policy.PandoFilter.REPLACE_FAILED
                    : expected.result != a ? Policy.PandoFilter.REPLACED : Policy.PandoFilter.NONE;
            assertEquals(where, expectedAction, actual.action);
            assertEquals(where, expected.removed, actual.removed);
            assertEquals(where, Graphs.dump(expected.result), Graphs.dump(actual.result));
            replaced += expectedAction == Policy.PandoFilter.REPLACED ? 1 : 0;
        }
        assertTrue(replaced > 50);
    }

    private static List<Object> pandoList(long seed) {
        Random random = new Random(seed);
        int size = random.nextInt(8) == 0 ? 257 + random.nextInt(3) : random.nextInt(6);
        List<Object> items = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            if (i == 0 && random.nextInt(10) == 0) {
                items.add("not a tree");
                continue;
            }
            String body;
            switch (random.nextInt(5)) {
                case 0:
                    body = "{\"is_sponsored\":true,\"id\":" + i + "}";
                    break;
                case 1:
                    body = "{\"ad_id\":\"" + i + "\",\"pad\":\"xxxxxxxx\"}";
                    break;
                default:
                    body = "{\"fields\":{\"id\":" + i + "}}";
            }
            items.add(new TreeModel(new String[]{"{}", body, "{\"a\":1}", null}));
        }
        switch (random.nextInt(3)) {
            case 0:
                return Collections.unmodifiableList(items);
            case 1:
                return ImmutableList.copyOf(items);
            default:
                return items;
        }
    }

    // ------------------------------------------------------------ statistics

    @Test
    public void statisticsRateLimitsMatchLegacy() {
        Policy policy = TestCore.policy();
        long total = 0;
        for (int event = 1; event <= 120; event++) {
            int count = 1 + event % 3;
            total += count;
            long[] r = policy.stats(Policy.STAT_MODEL_REMOVAL, count);
            assertEquals("event " + event, LegacyRules.logModelRemoval(event), r[0] == 1);
            assertEquals(total, r[1]);
            assertEquals(event, r[2]);
        }
        assertEquals(0, policy.stats(Policy.STAT_MODEL_REMOVAL, 0)[0]);
        for (int n = 1; n <= 80; n++) {
            assertEquals("ui " + n, LegacyRules.logUiCollapse(n), policy.stats(Policy.STAT_UI_COLLAPSE, 1)[0] == 1);
            assertEquals("replace " + n, LegacyRules.logReplacementFailure(n),
                    policy.stats(Policy.STAT_REPLACEMENT_FAILURE, 1)[0] == 1);
        }
    }

    // ------------------------------------------------------------ rendered labels

    @Test
    public void cardSearchMatchesLegacy() {
        Policy policy = TestCore.policy();
        Random random = new Random(3);
        float[] densities = {0.75f, 1f, 1.33125f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f, 2.8125f};
        for (int run = 0; run < 3000; run++) {
            int screenWidth = 320 + random.nextInt(1200);
            int screenHeight = 480 + random.nextInt(2400);
            float density = densities[random.nextInt(densities.length)];
            int count = random.nextInt(17);
            boolean[] list = new boolean[count];
            boolean[] root = new boolean[count];
            int[] w = new int[count];
            int[] h = new int[count];
            List<LegacyRules.Ancestor> chain = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                list[i] = random.nextInt(8) == 0;
                root[i] = random.nextInt(12) == 0;
                w[i] = random.nextInt(3) == 0 ? (int) (screenWidth * (0.6 + random.nextDouble() * 0.2))
                        : random.nextInt(screenWidth + 1);
                h[i] = random.nextInt(4) == 0 ? (int) (80 * density) + random.nextInt(3) - 1
                        : random.nextInt(screenHeight + 1);
                chain.add(new LegacyRules.Ancestor(list[i], w[i], h[i], root[i]));
            }
            int expected = LegacyRules.findFeedCard(chain, screenWidth, screenHeight, density);
            int actual = policy.uiCard(list, root, w, h, screenWidth, screenHeight, density);
            assertEquals("run " + run, expected == -2 ? Policy.CARD_NONE : expected, actual);
        }
    }

    @Test
    public void hideAndRestoreMatchLegacy() {
        Policy policy = TestCore.policy();
        for (int mask = 0; mask < 4; mask++) {
            for (int attempt = 0; attempt <= 5; attempt++) {
                for (int arm = 0; arm < 2; arm++) {
                    boolean marker = (mask & 1) != 0;
                    boolean card = (mask & 2) != 0;
                    String expected = LegacyRules.hideAction(marker, card, attempt, arm == 0);
                    int[] actual = policy.uiHide(marker, card, attempt, arm == 1);
                    String rendered = actual[0] == Policy.HIDE_NONE ? "none"
                            : actual[0] == Policy.HIDE_CARD ? "collapse-card"
                            : actual[0] == Policy.HIDE_RETRY ? "retry " + actual[1] : "collapse-label";
                    assertEquals(mask + "/" + attempt + "/" + arm, expected, rendered);
                }
            }
        }
        for (int mask = 0; mask < 4; mask++) {
            boolean same = (mask & 1) != 0;
            boolean labelled = (mask & 2) != 0;
            assertEquals(LegacyRules.restoreAllowed(same, labelled), policy.uiRestore(same, labelled));
        }
    }

    @Test
    public void labelScanMatchesLegacy() {
        Policy policy = TestCore.policy();
        for (long seed = 1; seed <= 400; seed++) {
            Random a = new Random(seed);
            Random b = new Random(seed);
            LegacyRules.ViewNode legacyRoot = legacyTree(a, 0);
            TestCore.FakeView root = fakeTree(b, 0);
            for (boolean rescan : new boolean[]{false, true}) {
                LegacyRules.ViewNode expected = LegacyRules.findAdLabel(legacyRoot,
                        rescan ? LegacyRules.MAX_RESCAN_VIEWS : LegacyRules.MAX_SCAN_VIEWS);
                Object actual = policy.uiScan(root, rescan);
                assertEquals("seed " + seed, expected == null ? null : expected.description + "|" + expected.text,
                        actual == null ? null : ((TestCore.FakeView) actual).description + "|"
                                + ((TestCore.FakeView) actual).text);
            }
            // Marker check on the root, and the text/description events.
            assertEquals(LegacyRules.isCurrentAdMarker(legacyRoot), policy.uiMarker(root, root.textView));
        }
    }

    private static final String[] LABELS = {"Sponsored", "Ad ", "hello", "an ad", "Ads ·", "", null, "广告", "author"};

    private static LegacyRules.ViewNode legacyTree(Random r, int depth) {
        boolean textView = r.nextInt(3) == 0;
        CharSequence text = textView ? LABELS[r.nextInt(LABELS.length)] : null;
        CharSequence description = r.nextInt(4) == 0 ? LABELS[r.nextInt(LABELS.length)] : null;
        List<LegacyRules.ViewNode> children = null;
        if (!textView && depth < 6) {
            children = new ArrayList<>();
            int n = r.nextInt(depth == 0 ? 90 : 6);
            for (int i = 0; i < n; i++) {
                children.add(legacyTree(r, depth + 1));
            }
        }
        return new LegacyRules.ViewNode(text, description, children);
    }

    private static TestCore.FakeView fakeTree(Random r, int depth) {
        boolean textView = r.nextInt(3) == 0;
        CharSequence text = textView ? LABELS[r.nextInt(LABELS.length)] : null;
        CharSequence description = r.nextInt(4) == 0 ? LABELS[r.nextInt(LABELS.length)] : null;
        List<TestCore.FakeView> children = null;
        if (!textView && depth < 6) {
            children = new ArrayList<>();
            int n = r.nextInt(depth == 0 ? 90 : 6);
            for (int i = 0; i < n; i++) {
                children.add(fakeTree(r, depth + 1));
            }
        }
        return new TestCore.FakeView(textView, text, description, children);
    }

    @Test
    public void textEventsAndViewClassesMatchLegacy() {
        Policy policy = TestCore.policy();
        for (String label : LABELS) {
            TestCore.FakeView view = new TestCore.FakeView(true, label, label, null);
            boolean ad = label != null && my.github.MrxSiN.threadshideads.legacy.AdLabelVocabulary.isAdLabel(label);
            assertEquals(label, ad ? Policy.UI_HIDE : Policy.UI_RESTORE, policy.uiTextEvent(false, view));
            assertEquals(label, ad ? Policy.UI_HIDE : Policy.UI_RESTORE, policy.uiTextEvent(true, view));
        }
        Object[] views = {new X.FeedRecyclerView(), new X.InnerList(), new X.PhoneDecorView(), new X.PlainView()};
        for (Object view : views) {
            boolean list = false;
            boolean decor = false;
            for (Class<?> c = view.getClass(); c != null; c = c.getSuperclass()) {
                list |= c.getName().contains("RecyclerView") || c.getName().contains("ListView");
                decor |= c.getName().contains("DecorView");
            }
            assertArrayEquals(view.getClass().getName(), new boolean[]{list, decor}, policy.uiViewClass(view));
            // Memoized per class: the second answer comes from the cache.
            assertArrayEquals(new boolean[]{list, decor}, policy.uiViewClass(view));
        }
        Random random = new Random(11);
        for (int run = 0; run < 300; run++) {
            boolean[] chain = new boolean[random.nextInt(14)];
            boolean expected = false;
            for (int i = 0; i < chain.length; i++) {
                chain[i] = random.nextInt(9) == 0;
                expected |= chain[i] && i <= LegacyRules.MAX_LIST_CONTAINER_DEPTH;
            }
            assertEquals("run " + run, expected, policy.uiAddView(chain));
        }
    }
}
