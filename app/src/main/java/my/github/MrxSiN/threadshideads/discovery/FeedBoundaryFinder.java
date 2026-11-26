package my.github.MrxSiN.threadshideads.discovery;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.ClassData;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;

/**
 * Runs the DexKit searches. The markers come from the Brainfuck core, which
 * also judges every resulting method (OP_PRIMARY_CANDIDATE, OP_PRIMARY_DECISION,
 * OP_BROAD_CANDIDATE). Hook installation is not performed here.
 */
public final class FeedBoundaryFinder {

    private final Policy policy;
    private final DexKitBridge bridge;
    private final ClassLoader classLoader;

    public FeedBoundaryFinder(Policy policy, DexKitBridge bridge, ClassLoader classLoader) {
        this.policy = policy;
        this.bridge = bridge;
        this.classLoader = classLoader;
    }

    /** Structural matches of the primary marker, when the core accepts them. */
    public List<Method> findPrimaryFeedBoundaries() {
        String marker = policy.constString(Policy.TABLE_NAMES, Policy.NAME_PRIMARY_MARKER);
        LinkedHashSet<Method> found = new LinkedHashSet<>();
        try {
            for (MethodData data : findByMarker(marker)) {
                try {
                    found.add(data.getMethodInstance(classLoader));
                } catch (Throwable ignored) {
                    // Broad fallback will handle unresolved candidates.
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Primary feed-boundary search failed: " + throwable);
            policy.primaryDecision();
            return Collections.emptyList();
        }
        List<Method> accepted = new ArrayList<>();
        for (Method method : found) {
            if (policy.primaryCandidate(method)) {
                accepted.add(method);
            }
        }
        int[] decision = policy.primaryDecision();
        if (decision[0] == Policy.DECISION_NONE) {
            ModuleLog.log("Primary feed-boundary search found no structural match; using broad fallback");
            return Collections.emptyList();
        }
        if (decision[0] == Policy.DECISION_TOO_BROAD) {
            ModuleLog.log("Primary feed-boundary search found " + decision[1]
                    + " structural matches, which is too broad to trust; using broad fallback");
            return Collections.emptyList();
        }
        ModuleLog.log("Primary feed-boundary search found " + accepted.size() + " structural match(es)");
        return accepted;
    }

    /** Every method using any DEX marker, with the markers it uses by index. */
    public Map<Method, boolean[]> discoverCandidates() {
        List<String> markers = policy.constTable(Policy.TABLE_DEX_MARKERS);
        Map<Method, boolean[]> candidates = new LinkedHashMap<>();
        for (int index = 0; index < markers.size(); index++) {
            String marker = markers.get(index);
            try {
                MethodDataList matches = findByMarker(marker);
                ModuleLog.log("Marker '" + marker + "' produced " + matches.size() + " method matches");
                for (MethodData data : matches) {
                    try {
                        Method method = data.getMethodInstance(classLoader);
                        boolean[] flags = candidates.get(method);
                        if (flags == null) {
                            flags = new boolean[markers.size()];
                            candidates.put(method, flags);
                        }
                        flags[index] = true;
                    } catch (Throwable ignored) {
                        // Unresolvable in this class loader.
                    }
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Search failed for marker '" + marker + "': " + throwable);
            }
        }
        return candidates;
    }

    /**
     * Threads' own ad check on Media: a method of {@code mediaClass} calling a
     * static one-argument boolean check that uses the hash of the injected key.
     * The core judges each and installs only exactly one (OP_NATIVE_AD_*).
     */
    public Method findNativeAdCheck(String mediaClass, String injectedKey) {
        Method found = null;
        try {
            ClassData media = bridge.getClassData(mediaClass);
            if (media != null) {
                // Scoped to Media: an unscoped invoke query builds DexKit's
                // whole-app cross-reference index first (about 0.6 s here).
                MethodDataList matches = bridge.findMethod(FindMethod.create()
                        .searchInClass(Collections.singletonList(media))
                        .matcher(MethodMatcher.create()
                                .paramCount(0)
                                .returnType("boolean")
                                .addInvoke(MethodMatcher.create()
                                        .modifiers(java.lang.reflect.Modifier.STATIC)
                                        .returnType("boolean")
                                        .paramCount(1)
                                        .usingNumbers(injectedKey.hashCode()))));
                for (MethodData data : matches) {
                    try {
                        Method method = data.getMethodInstance(classLoader);
                        if (policy.nativeAdMethod(method) && found == null) {
                            found = method;
                        }
                    } catch (Throwable ignored) {
                        // Unresolvable in this class loader.
                    }
                }
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Native ad check search failed: " + throwable);
        }
        int decision = policy.nativeAdDecision();
        if (decision != Policy.DECISION_INSTALL) {
            ModuleLog.log("Threads' own ad check not used; decision " + decision);
            return null;
        }
        return found;
    }

    private MethodDataList findByMarker(String marker) {
        return bridge.findMethod(FindMethod.create().matcher(
                MethodMatcher.create().usingStrings(Collections.singletonList(marker), StringMatchType.Contains)));
    }
}
