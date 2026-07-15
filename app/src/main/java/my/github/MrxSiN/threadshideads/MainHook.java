package my.github.MrxSiN.threadshideads;

import org.luckypray.dexkit.DexKitBridge;
import org.luckypray.dexkit.query.FindMethod;
import org.luckypray.dexkit.query.enums.StringMatchType;
import org.luckypray.dexkit.query.matchers.MethodMatcher;
import org.luckypray.dexkit.result.MethodData;
import org.luckypray.dexkit.result.MethodDataList;

import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Threads-only LSPosed entry point.
 * Exhaustive discovery for ad-injection logic.
 */
public final class MainHook implements IXposedHookLoadPackage {
    private static final String TAG = "ThreadsHideAds";
    private static final String THREADS_PACKAGE = "com.instagram.barcelona";

    static {
        System.loadLibrary("dexkit");
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (!THREADS_PACKAGE.equals(loadPackageParam.packageName)
                || !loadPackageParam.isFirstApplication) {
            return;
        }

        log("Initializing Threads Ad Blocker (Exhaustive Scan)...");

        try (DexKitBridge bridge = DexKitBridge.create(loadPackageParam.appInfo.sourceDir)) {
            Set<Method> targets = new HashSet<>();

            // Search for boolean methods containing these ad-related strings
            findAndAddTargets(bridge, loadPackageParam.classLoader, "is_sponsored", targets);
            findAndAddTargets(bridge, loadPackageParam.classLoader, "sponsored", targets);
            findAndAddTargets(bridge, loadPackageParam.classLoader, "direct_ad", targets);
            findAndAddTargets(bridge, loadPackageParam.classLoader, "sponsored_label", targets);

            // Fallback: search for potential injector methods by shape and class name keywords
            if (targets.isEmpty()) {
                log("No candidates found via string heuristics. Searching for injector shape...");
                MethodDataList injectorMatches = bridge.findMethod(FindMethod.create()
                        .matcher(MethodMatcher.create()
                                .returnType("boolean")
                                .paramCount(2, 3)
                                .declaredClass("Controller", StringMatchType.Contains)
                        )
                );
                for (MethodData data : injectorMatches) {
                    try {
                        targets.add(data.getMethodInstance(loadPackageParam.classLoader));
                    } catch (Throwable ignored) {}
                }
            }

            if (!targets.isEmpty()) {
                int hooksApplied = 0;
                for (Method target : targets) {
                    try {
                        XposedBridge.hookMethod(target, XC_MethodReplacement.returnConstant(false));
                        log("Hooked candidate: " + target);
                        hooksApplied++;
                    } catch (Throwable t) {
                        log("Failed to hook " + target.getName() + ": " + t.getMessage());
                    }
                }
                log("Successfully applied " + hooksApplied + " hooks.");
            } else {
                log("FAILED: No ad-injector candidates found. Threads may have a completely new architecture.");
            }
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": Critical initialization failure");
            XposedBridge.log(throwable);
        }
    }

    private void findAndAddTargets(DexKitBridge bridge, ClassLoader classLoader, String searchString, Set<Method> targetSet) {
        MethodDataList matches = bridge.findMethod(FindMethod.create()
                .matcher(MethodMatcher.create()
                        .returnType("boolean")
                        .usingStrings(searchString)
                )
        );

        for (MethodData data : matches) {
            try {
                Method m = data.getMethodInstance(classLoader);
                if (targetSet.add(m)) {
                    log("Found candidate via '" + searchString + "': " + data.getDescriptor());
                }
            } catch (Throwable ignored) {}
        }
    }

    private static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }
}
