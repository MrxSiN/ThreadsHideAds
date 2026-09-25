package my.github.MrxSiN.threadshideads.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.bridge.BfAbi;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.Diagnostics;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;

/**
 * Installs model-level hooks. A feed boundary's callbacks hand the call to the
 * Brainfuck core (OP_BOUNDARY_BEFORE / OP_BOUNDARY_AFTER), which reads the
 * arguments and result through capabilities, decides what to remove and has
 * the host apply it. This class only registers hooks and words the log.
 */
public final class FeedModelHooks {

    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final Policy policy;

    public FeedModelHooks(Policy policy) {
        this.policy = policy;
    }

    public static int installedHookCount() {
        return HOOKED_METHODS.size();
    }

    public static boolean hasInstalledHooks() {
        return !HOOKED_METHODS.isEmpty();
    }

    public boolean hookListBoundary(final Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#list")) {
            return false;
        }
        final Class<?>[] parameterTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                long start = HotPathStats.ENABLED ? System.nanoTime() : 0;
                log(method, policy.boundaryBefore(new XposedCallFrame(param, parameterTypes, returnType)));
                if (HotPathStats.ENABLED) {
                    HotPathStats.BOUNDARY.record(System.nanoTime() - start);
                }
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!param.hasThrowable()) {
                    Diagnostics.traceBoundaryCall(method.getDeclaringClass().getSimpleName()
                            + "." + method.getName(), param.args, param.getResult());
                }
                log(method, policy.boundaryAfter(new XposedCallFrame(param, parameterTypes, returnType),
                        param.hasThrowable()));
            }
        });

        ModuleLog.log("Installed list sanitizer: " + method);
        return true;
    }

    /** Neutralizes the client-side ad injector: it reports "nothing injected". */
    public boolean hookAdInjectionPredicate(final Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#injector")) {
            return false;
        }
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Diagnostics.traceBoundaryCall("injector " + method.getName(), param.args, param.getResult());
                param.setResult(Boolean.FALSE);
            }
        });
        ModuleLog.log("Neutralized ad injector: " + method);
        return true;
    }

    public boolean hookBooleanPredicate(Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#boolean")) {
            return false;
        }
        XposedBridge.hookMethod(method, XC_MethodReplacement.returnConstant(false));
        ModuleLog.log("Disabled positive ad predicate: " + method);
        return true;
    }

    private static void log(Method method, List<Policy.Report> reports) {
        for (Policy.Report report : reports) {
            switch (report.site) {
                case BfAbi.SITE_ARG:
                    ModuleStats.recordModelRemoval(report.count, "argument " + report.index + " of " + method);
                    break;
                case BfAbi.SITE_LATE_ARG:
                    ModuleStats.recordModelRemoval(report.count, "late argument " + report.index + " of " + method);
                    break;
                case BfAbi.SITE_RESULT:
                    ModuleStats.recordModelRemoval(report.count, "result of " + method);
                    break;
                case BfAbi.SITE_NESTED:
                    ModuleStats.recordModelRemoval(report.count, "nested result collections of " + method);
                    break;
                case BfAbi.SITE_REPLACEMENT_FAILURE:
                    for (int i = 0; i < report.count; i++) {
                        ModuleStats.recordReplacementFailure();
                    }
                    break;
                default:
                    break;
            }
        }
    }
}
