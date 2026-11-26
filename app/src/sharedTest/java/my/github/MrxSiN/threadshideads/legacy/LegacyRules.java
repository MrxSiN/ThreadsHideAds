package my.github.MrxSiN.threadshideads.legacy;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Legacy decisions that were embedded in Xposed, DexKit or View code,
 * transcribed as pure functions. Each method body keeps the v1.2.1 logic line
 * for line; only the platform object is replaced by the facts it was read from.
 */
public final class LegacyRules {

    private LegacyRules() {
    }

    // ------------------------------------------------------------ MainHook

    public static final String THREADS_PACKAGE = "com.instagram.barcelona";

    /** handleLoadPackage: whether this process gets the module at all. */
    public static boolean inScope(String packageName, String processName) {
        if (!THREADS_PACKAGE.equals(packageName)) {
            return false;
        }
        return processName == null || THREADS_PACKAGE.equals(processName);
    }

    // ------------------------------------------------------------ FeedBoundaryFinder

    public static final String PRIMARY_BOUNDARY_MARKER = "Sponsored";
    public static final int MAX_PRIMARY_BOUNDARIES = 8;

    public static final String[] DEX_MARKERS = {
            "is_sponsored", "isSponsored", "sponsored_label", "Sponsored", "sponsored",
            "direct_ad", "directAd", "ad_type", "ad_id", "adchoices", "AdChoices",
            "multi_ads", "MultiAds"
    };

    public static final Set<String> STRONG_BOOLEAN_MARKERS = new LinkedHashSet<>(Arrays.asList(
            "is_sponsored", "isSponsored", "direct_ad", "directAd", "sponsored_label"));

    private static final Set<String> CORE_LIST_MARKERS = new LinkedHashSet<>(Arrays.asList(DEX_MARKERS));

    public static final String[] NON_FEED_SIGNATURE_TOKENS = {
            "bugreport", "notification", "armadillo", "direct.store", "directsqlite", "message",
            "messaging", "exoplayer", "heroplayer", "music", "quickpromotion", "upload"
    };

    /** findPrimaryFeedBoundaries: the structural matches are installed only for 1..8. */
    public static boolean acceptPrimaryCount(int matches) {
        return matches > 0 && matches <= MAX_PRIMARY_BOUNDARIES;
    }

    public static boolean isPrimaryFeedBoundaryShape(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        int modifiers = method.getModifiers();

        return !Modifier.isStatic(modifiers)
                && !Modifier.isAbstract(modifiers)
                && !method.getDeclaringClass().isInterface()
                && parameters.length >= 3
                && isIntegerType(parameters[0])
                && isIntegerType(parameters[1])
                && List.class.isAssignableFrom(parameters[2])
                && method.getReturnType() != void.class;
    }

    public static boolean isLikelyAdListBoundary(Method method, Set<String> markers) {
        if (!hasListBoundary(method)) {
            return false;
        }
        if (!containsAny(markers, CORE_LIST_MARKERS)) {
            return false;
        }
        String signature = method.toGenericString().toLowerCase(Locale.ROOT);
        for (String token : NON_FEED_SIGNATURE_TOKENS) {
            if (signature.contains(token)) {
                return false;
            }
        }
        return true;
    }

    public static boolean isSafePositiveAdBoolean(Method method, Set<String> markers) {
        if (method.getReturnType() != boolean.class && method.getReturnType() != Boolean.class) {
            return false;
        }
        String semanticName = (method.getDeclaringClass().getName() + "." + method.getName())
                .toLowerCase(Locale.ROOT);
        if (semanticName.contains("hide")
                || semanticName.contains("remove")
                || semanticName.contains("filter")
                || semanticName.contains("block")) {
            return false;
        }
        if (semanticName.contains("issponsored")
                || semanticName.contains("isdirectad")
                || semanticName.contains("shouldshowad")
                || semanticName.contains("shouldinsertad")
                || semanticName.contains("caninsertad")
                || semanticName.contains("adsenabled")
                || semanticName.contains("adeligible")) {
            return true;
        }
        return containsAny(markers, STRONG_BOOLEAN_MARKERS);
    }

    private static boolean isIntegerType(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    private static boolean hasListBoundary(Method method) {
        if (isListLike(method.getReturnType())) {
            return true;
        }
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (isListLike(parameterType)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isListLike(Class<?> type) {
        return List.class.isAssignableFrom(type) || Collection.class.isAssignableFrom(type);
    }

    private static boolean containsAny(Set<String> markers, Set<String> wanted) {
        for (String marker : markers) {
            if (wanted.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ FeedCacheLocator

    public static final String SPOOL_CACHE_HANDLER =
            "com.instagram.barcelona.feed.data.cache.BarcelonaSpoolFeedCacheHandler";

    public static final String[] FEED_OWNER_CLASSES = {
            SPOOL_CACHE_HANDLER,
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheHandler",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheHandlerBase",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCache",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedCacheRoom",
            "com.instagram.barcelona.feed.common.data.InMemoryFeedCache",
            "com.instagram.barcelona.feed.common.data.BcnFeedRepository",
            "com.instagram.barcelona.feed.data.repository.BarcelonaFeedRepository",
            "com.instagram.barcelona.feed.data.repository.ForYouFeedRepository",
            "com.instagram.barcelona.feed.data.spool.ForYouFeedSpoolStateMapper",
            "com.instagram.barcelona.feed.data.networksource.BarcelonaFeedNetworkSource",
            "com.instagram.barcelona.feed.data.networksource.BarcelonaSpoolFeedNetworkSource",
            "com.instagram.barcelona.feed.data.networksource.FollowingFeedNetworkSource",
            "com.instagram.barcelona.feed.data.prefetch.BcnInjectedPostPrefetcher",
            "com.instagram.barcelona.feed.data.cache.BarcelonaFeedItemDatabase"
    };

    /** findListBoundaries: every non-abstract declared method carrying a List. */
    public static boolean isFeedOwnerListBoundary(Method method) {
        if (Modifier.isAbstract(method.getModifiers())) {
            return false;
        }
        if (List.class.isAssignableFrom(method.getReturnType())) {
            return true;
        }
        for (Class<?> parameterType : method.getParameterTypes()) {
            if (List.class.isAssignableFrom(parameterType)) {
                return true;
            }
        }
        return false;
    }

    public static boolean isAdInjectionShape(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        return !Modifier.isAbstract(method.getModifiers())
                && method.getReturnType() == boolean.class
                && parameters.length == 2
                && !parameters[0].isPrimitive()
                && parameters[1] == int.class;
    }

    /** findAdInjectionPredicate: installed only when exactly one method matches. */
    public static Method selectInjector(Method[] declared) {
        Method found = null;
        for (Method method : declared) {
            if (!isAdInjectionShape(method)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    // ------------------------------------------------------------ PandoResponseHooks

    public static final String PANDO_TREE_CLASS = "com.facebook.pando.TreeJNI";
    public static final int PANDO_MAX_LIST_SIZE = 256;

    public static boolean isPandoListAccessor(Method method) {
        int modifiers = method.getModifiers();
        return !Modifier.isAbstract(modifiers)
                && !Modifier.isNative(modifiers)
                && List.class.isAssignableFrom(method.getReturnType());
    }

    /** Outcome of filterResult. */
    public static final class PandoOutcome {
        public final Object result;
        public final int removed;
        public final boolean replacementFailed;
        public final boolean emptyingBlocked;

        PandoOutcome(Object result, int removed, boolean replacementFailed, boolean emptyingBlocked) {
            this.result = result;
            this.removed = removed;
            this.replacementFailed = replacementFailed;
            this.emptyingBlocked = emptyingBlocked;
        }
    }

    public static PandoOutcome filterPandoResult(Object result, Class<?> declaredReturnType) {
        if (!(result instanceof List<?>)) {
            return new PandoOutcome(result, 0, false, false);
        }
        List<?> source = (List<?>) result;
        int size = source.size();
        if (size == 0 || size > PANDO_MAX_LIST_SIZE) {
            return new PandoOutcome(result, 0, false, false);
        }
        if (!PandoTrees.isPandoTree(source.get(0))) {
            return new PandoOutcome(result, 0, false, false);
        }
        ArrayList<Object> kept = new ArrayList<>(size);
        int removed = 0;
        for (Object item : source) {
            if (PandoAdSignals.isSponsoredTree(item)) {
                removed++;
            } else {
                kept.add(item);
            }
        }
        if (removed == 0) {
            return new PandoOutcome(result, 0, false, false);
        }
        if (kept.isEmpty()) {
            return new PandoOutcome(result, 0, false, true);
        }
        Object replacement = replacementFor(kept, declaredReturnType, source);
        if (replacement == null) {
            return new PandoOutcome(result, 0, true, false);
        }
        return new PandoOutcome(replacement, removed, false, false);
    }

    private static Object replacementFor(ArrayList<Object> kept, Class<?> declaredType, List<?> original) {
        if (declaredType == null
                || declaredType == Object.class
                || declaredType.isAssignableFrom(ArrayList.class)) {
            return kept;
        }
        Object copy = copyThrough(declaredType, kept);
        return copy != null ? copy : copyThrough(original.getClass(), kept);
    }

    private static Object copyThrough(Class<?> type, ArrayList<Object> kept) {
        Class<?> cursor = type;
        while (cursor != null && cursor != Object.class) {
            Object copy = copyThroughDeclared(cursor, kept);
            if (copy != null) {
                return copy;
            }
            cursor = cursor.getSuperclass();
        }
        return null;
    }

    private static Object copyThroughDeclared(Class<?> type, ArrayList<Object> kept) {
        Class<?>[] parameterShapes = {Collection.class, Iterable.class};
        for (Class<?> parameterType : parameterShapes) {
            try {
                Method factory = type.getDeclaredMethod("copyOf", parameterType);
                if (!Modifier.isStatic(factory.getModifiers())) {
                    continue;
                }
                factory.setAccessible(true);
                Object copy = factory.invoke(null, kept);
                if (copy instanceof List<?>) {
                    return copy;
                }
            } catch (Throwable ignored) {
                // Try the next parameter shape, then the superclass.
            }
        }
        return null;
    }

    // ------------------------------------------------------------ ModelHookInstaller

    public static final long[] RETRY_DELAYS_MS = {250L, 1000L, 3000L};

    /**
     * The legacy retry controller with the Handler replaced by a queue of
     * scheduled delays. Events are recorded in the order the legacy code logged
     * them.
     */
    public static final class RetryModel {
        public boolean initializing;
        public boolean initialized;
        public int attempts;
        public final List<String> events = new ArrayList<>();

        /** Returns the attempt number started, or 0 when none started. */
        public int request(boolean classLoaderPresent, int retryIndex) {
            if (initialized) {
                events.add("ignored-initialized");
                return 0;
            }
            if (!classLoaderPresent) {
                events.add("skipped-null-loader");
                scheduleRetry(retryIndex);
                return 0;
            }
            if (initializing) {
                events.add("ignored-busy");
                return 0;
            }
            initializing = true;
            attempts++;
            events.add("attempt " + attempts);
            return attempts;
        }

        /** Called once the attempt started by request() has finished. */
        public void finish(boolean success, int retryIndex) {
            if (success) {
                initialized = true;
                events.add("complete");
            } else {
                events.add("failed");
            }
            initializing = false;
            if (!success && !initialized) {
                scheduleRetry(retryIndex);
            }
        }

        private void scheduleRetry(int retryIndex) {
            if (initialized || retryIndex >= RETRY_DELAYS_MS.length) {
                if (!initialized) {
                    events.add("exhausted");
                }
                return;
            }
            events.add("retry " + (retryIndex + 1) + " in " + RETRY_DELAYS_MS[retryIndex]);
        }
    }

    // ------------------------------------------------------------ UI fallback

    public static final int MAX_ANCESTOR_DEPTH = 14;
    public static final int MAX_LAYOUT_RETRIES = 4;
    public static final int MAX_SCAN_VIEWS = 320;
    public static final int MAX_RESCAN_VIEWS = 160;
    public static final int MAX_CHILDREN_PER_GROUP = 64;
    public static final int MAX_LIST_CONTAINER_DEPTH = 8;

    /** One ancestor of the label, nearest first. */
    public static final class Ancestor {
        public final boolean listContainer;
        public final int width;
        public final int height;
        public final boolean rootOrDecor;

        public Ancestor(boolean listContainer, int width, int height, boolean rootOrDecor) {
            this.listContainer = listContainer;
            this.width = width;
            this.height = height;
            this.rootOrDecor = rootOrDecor;
        }
    }

    /**
     * FeedItemHider.findFeedCard. Returns the index into {@code chain} of the
     * card, -1 for the label itself (never returned), or -2 when no card.
     */
    public static int findFeedCard(List<Ancestor> chain, int screenWidth, int screenHeight, float density) {
        int minimumHeight = Math.max(80, (int) (80 * density));
        int current = -1;
        int fallback = -2;
        for (int depth = 0; depth < MAX_ANCESTOR_DEPTH; depth++) {
            if (depth >= chain.size()) {
                break;
            }
            Ancestor parent = chain.get(depth);
            if (parent.listContainer && current != -1) {
                return current;
            }
            if (depth >= 2
                    && parent.width >= screenWidth * 0.70f
                    && parent.height >= minimumHeight
                    && parent.height <= screenHeight * 0.80f) {
                fallback = depth;
            }
            if (parent.rootOrDecor) {
                break;
            }
            current = depth;
        }
        return fallback;
    }

    /** FeedItemHider.hide after the card search. */
    public static String hideAction(boolean currentMarker, boolean cardFound, int attempt, boolean retryArmable) {
        if (!currentMarker) {
            return "none";
        }
        if (cardFound) {
            return "collapse-card";
        }
        if (attempt < MAX_LAYOUT_RETRIES && retryArmable) {
            return "retry " + (attempt + 1);
        }
        return "collapse-label";
    }

    /** FeedItemHider.restoreMarkedAncestor at the first saved ancestor. */
    public static boolean restoreAllowed(boolean hiddenBySameLabel, boolean subtreeStillLabelled) {
        return hiddenBySameLabel && !subtreeStillLabelled;
    }

    /** A view for AdLabelScanner. */
    public static final class ViewNode {
        public final CharSequence text;       // non-null only for a TextView
        public final CharSequence description;
        public final List<ViewNode> children; // null when not a ViewGroup

        public ViewNode(CharSequence text, CharSequence description, List<ViewNode> children) {
            this.text = text;
            this.description = description;
            this.children = children;
        }
    }

    public static boolean isCurrentAdMarker(ViewNode view) {
        return (view.text != null && AdLabelVocabulary.isAdLabel(view.text))
                || AdLabelVocabulary.isAdLabel(view.description);
    }

    /** AdLabelScanner.findAdLabel: breadth-first, bounded. */
    public static ViewNode findAdLabel(ViewNode root, int maxViews) {
        ArrayList<ViewNode> pending = new ArrayList<>();
        pending.add(root);
        int cursor = 0;
        while (cursor < pending.size() && cursor < maxViews) {
            ViewNode view = pending.get(cursor++);
            if (isCurrentAdMarker(view)) {
                return view;
            }
            if (view.children != null) {
                int count = Math.min(view.children.size(), MAX_CHILDREN_PER_GROUP);
                for (int i = 0; i < count; i++) {
                    ViewNode child = view.children.get(i);
                    if (child != null) {
                        pending.add(child);
                    }
                }
            }
        }
        return null;
    }

    // ------------------------------------------------------------ ModuleStats

    public static boolean logModelRemoval(int event) {
        return event <= 12 || event % 25 == 0;
    }

    public static boolean logUiCollapse(int total) {
        return total <= 8 || total % 25 == 0;
    }

    public static boolean logReplacementFailure(int failures) {
        return failures <= 3 || failures % 10 == 0;
    }
}
