package my.github.MrxSiN.threadshideads.discovery;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import my.github.MrxSiN.threadshideads.core.ModuleLog;

/**
 * Locates and classifies candidate advertisement methods in the Threads dex
 * files. Hook installation is not performed here.
 */
public final class FeedBoundaryFinder {

    /*
     * Candidate descriptors are intentionally not logged one-by-one. Threads
     * currently yields hundreds of broad matches and the noise obscures the
     * feed hook activity that matters during testing.
     */
    private static final boolean VERBOSE_DISCOVERY = false;

    /*
     * Avoid overly broad strings such as just "ad".
     * Those match thousands of unrelated methods.
     */
    private static final String PRIMARY_BOUNDARY_MARKER = "Sponsored";

    /*
     * The boundary is an interface method with a small number of concrete
     * implementations. More matches than this means the shape stopped being
     * specific on that build, so broad discovery is safer.
     */
    private static final int MAX_PRIMARY_BOUNDARIES = 8;

    private static final String[] DEX_MARKERS = {
            "is_sponsored",
            "isSponsored",
            "sponsored_label",
            "Sponsored",
            "sponsored",
            "direct_ad",
            "directAd",
            "ad_type",
            "ad_id",
            "adchoices",
            "AdChoices",
            "multi_ads",
            "MultiAds"
    };

    private static final Set<String> STRONG_BOOLEAN_MARKERS =
            new LinkedHashSet<>(Arrays.asList(
                    "is_sponsored",
                    "isSponsored",
                    "direct_ad",
                    "directAd",
                    "sponsored_label"
            ));

    private static final Set<String> CORE_LIST_MARKERS =
            new LinkedHashSet<>(Arrays.asList(DEX_MARKERS));

    private static final String[] NON_FEED_SIGNATURE_TOKENS = {
            "bugreport",
            "notification",
            "armadillo",
            "direct.store",
            "directsqlite",
            "message",
            "messaging",
            "exoplayer",
            "heroplayer",
            "music",
            "quickpromotion",
            "upload"
    };

    private final DexKitBridge bridge;
    private final ClassLoader classLoader;

    public FeedBoundaryFinder(DexKitBridge bridge, ClassLoader classLoader) {
        this.bridge = bridge;
        this.classLoader = classLoader;
    }

    /**
     * Returns every implementable {@code (Integer, Integer, List) -> non-void}
     * feed boundary that also carries the sponsored marker.
     *
     * <p>The boundary is an interface method, so a build can expose it through
     * several classes at once. Threads 445 declares it on one interface with
     * two concrete implementations, and every implementation feeds the same
     * list to the UI. Requiring a single unique match therefore rejected the
     * real boundary and fell through to broad discovery; each concrete match is
     * now sanitized instead. Abstract and interface methods are skipped because
     * they cannot be hooked.
     */
    public List<Method> findPrimaryFeedBoundaries() {
        LinkedHashSet<Method> matches = new LinkedHashSet<>();

        try {
            for (MethodData data : findByMarker(PRIMARY_BOUNDARY_MARKER)) {
                try {
                    Method method = data.getMethodInstance(classLoader);
                    if (isPrimaryFeedBoundaryShape(method)) {
                        matches.add(method);
                    }
                } catch (Throwable ignored) {
                    // Broad fallback will handle unresolved candidates.
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Primary feed-boundary search failed: " + throwable);
            return Collections.emptyList();
        }

        if (matches.isEmpty()) {
            ModuleLog.log("Primary feed-boundary search found no structural match; "
                    + "using broad fallback");
            return Collections.emptyList();
        }

        if (matches.size() > MAX_PRIMARY_BOUNDARIES) {
            ModuleLog.log("Primary feed-boundary search found " + matches.size()
                    + " structural matches, which is too broad to trust; "
                    + "using broad fallback");
            return Collections.emptyList();
        }

        ModuleLog.log("Primary feed-boundary search found " + matches.size()
                + " structural match(es)");

        return new ArrayList<>(matches);
    }

    public Map<Method, Set<String>> discoverCandidates() {
        Map<Method, Set<String>> candidates = new LinkedHashMap<>();

        for (String marker : DEX_MARKERS) {
            try {
                MethodDataList matches = findByMarker(marker);

                ModuleLog.log("Marker '" + marker + "' produced "
                        + matches.size() + " method matches");

                for (MethodData data : matches) {
                    collectCandidate(candidates, data, marker);
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Search failed for marker '" + marker + "': "
                        + throwable);
            }
        }

        return candidates;
    }

    private void collectCandidate(
            Map<Method, Set<String>> candidates,
            MethodData data,
            String marker
    ) {
        try {
            Method method = data.getMethodInstance(classLoader);

            Set<String> methodMarkers = candidates.get(method);
            if (methodMarkers == null) {
                methodMarkers = new LinkedHashSet<>();
                candidates.put(method, methodMarkers);
            }

            methodMarkers.add(marker);

            if (VERBOSE_DISCOVERY) {
                ModuleLog.log("Candidate: " + data.getDescriptor()
                        + " marker=" + marker);
            }
        } catch (Throwable throwable) {
            if (VERBOSE_DISCOVERY) {
                ModuleLog.log("Cannot resolve candidate "
                        + data.getDescriptor() + ": "
                        + throwable.getClass().getSimpleName());
            }
        }
    }

    private MethodDataList findByMarker(String marker) {
        return bridge.findMethod(
                FindMethod.create().matcher(
                        MethodMatcher.create().usingStrings(
                                Collections.singletonList(marker),
                                StringMatchType.Contains
                        )
                )
        );
    }

    public static boolean isLikelyAdListBoundary(
            Method method,
            Set<String> markers
    ) {
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

    /**
     * Only replace booleans that appear to be positive ad predicates.
     *
     * Methods containing "hide", "remove" or "filter" are deliberately
     * excluded because returning false could enable ads.
     */
    public static boolean isSafePositiveAdBoolean(
            Method method,
            Set<String> markers
    ) {
        if (method.getReturnType() != boolean.class
                && method.getReturnType() != Boolean.class) {
            return false;
        }

        String semanticName = (
                method.getDeclaringClass().getName() + "." + method.getName()
        ).toLowerCase(Locale.ROOT);

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

    private static boolean isPrimaryFeedBoundaryShape(Method method) {
        Class<?>[] parameters = method.getParameterTypes();
        int modifiers = method.getModifiers();

        return !Modifier.isStatic(modifiers)
                && !Modifier.isAbstract(modifiers)
                && !method.getDeclaringClass().isInterface()
                && parameters.length == 3
                && isIntegerType(parameters[0])
                && isIntegerType(parameters[1])
                && List.class.isAssignableFrom(parameters[2])
                && method.getReturnType() != void.class;
    }

    private static boolean isIntegerType(Class<?> type) {
        return type == Integer.class || type == int.class;
    }

    /**
     * Feed boundaries commonly accept or return java.util.List.
     */
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
        return List.class.isAssignableFrom(type)
                || Collection.class.isAssignableFrom(type);
    }

    private static boolean containsAny(Set<String> markers, Set<String> wanted) {
        for (String marker : markers) {
            if (wanted.contains(marker)) {
                return true;
            }
        }

        return false;
    }
}
