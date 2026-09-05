package my.github.MrxSiN.threadshideads.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.core.Diagnostics;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;
import my.github.MrxSiN.threadshideads.model.ListSanitizer;
import my.github.MrxSiN.threadshideads.model.NestedCollectionSanitizer;
import my.github.MrxSiN.threadshideads.model.SanitizedList;

/**
 * Installs the model-level hooks: list sanitizers on feed boundaries and
 * constant-false replacements on positive ad predicates.
 */
public final class FeedModelHooks {

    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private final ListSanitizer listSanitizer;
    private final NestedCollectionSanitizer nestedSanitizer;

    public FeedModelHooks(
            ListSanitizer listSanitizer,
            NestedCollectionSanitizer nestedSanitizer
    ) {
        this.listSanitizer = listSanitizer;
        this.nestedSanitizer = nestedSanitizer;
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

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                sanitizeArguments(method, param, parameterTypes);
            }

            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }

                Diagnostics.traceBoundaryCall(
                        method.getDeclaringClass().getSimpleName()
                                + "." + method.getName(),
                        param.args,
                        param.getResult()
                );

                sanitizeArgumentsAfterCall(method, param);
                sanitizeResult(method, param);
            }
        });

        ModuleLog.log("Installed list sanitizer: " + method);
        return true;
    }

    /**
     * Neutralizes the client-side ad injector: the call is traced and then
     * reported as "nothing injected".
     */
    public boolean hookAdInjectionPredicate(final Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#injector")) {
            return false;
        }

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Diagnostics.traceBoundaryCall(
                        "injector " + method.getName(),
                        param.args,
                        param.getResult()
                );

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

        XposedBridge.hookMethod(
                method,
                XC_MethodReplacement.returnConstant(false)
        );

        ModuleLog.log("Disabled positive ad predicate: " + method);
        return true;
    }

    private void sanitizeArguments(
            Method method,
            XC_MethodHook.MethodHookParam param,
            Class<?>[] parameterTypes
    ) {
        for (int i = 0; i < param.args.length; i++) {
            Object argument = param.args[i];

            if (!(argument instanceof List<?>)) {
                continue;
            }

            SanitizedList sanitized =
                    listSanitizer.sanitize(
                            argument, parameterTypes[i], true, true);

            if (!sanitized.changed) {
                Diagnostics.traceUnfilteredList(
                        method.getDeclaringClass().getSimpleName()
                                + "." + method.getName() + " arg" + i,
                        (List<?>) argument
                );
                continue;
            }

            if (sanitized.value != argument) {
                param.args[i] = sanitized.value;
            }

            ModuleStats.recordModelRemoval(
                    sanitized.removed,
                    "argument " + i + " of " + method
            );
        }
    }

    /*
     * Some Threads builds append or wrap sponsored entries inside the boundary
     * method. Re-sanitize argument lists after the call, then inspect
     * collection fields in the returned wrapper.
     */
    private void sanitizeArgumentsAfterCall(
            Method method,
            XC_MethodHook.MethodHookParam param
    ) {
        for (int i = 0; i < param.args.length; i++) {
            Object argument = param.args[i];
            if (!(argument instanceof List<?>)) {
                continue;
            }

            SanitizedList postCall =
                    listSanitizer.sanitize(
                            argument, argument.getClass(), false, true);

            if (postCall.changed) {
                ModuleStats.recordModelRemoval(
                        postCall.removed,
                        "late argument " + i + " of " + method
                );
            }
        }
    }

    private void sanitizeResult(
            Method method,
            XC_MethodHook.MethodHookParam param
    ) {
        Object result = param.getResult();

        if (result instanceof List<?>) {
            SanitizedList sanitized =
                    listSanitizer.sanitize(
                            result, method.getReturnType(), true, true);

            if (sanitized.changed) {
                if (sanitized.value != result) {
                    param.setResult(sanitized.value);
                    result = sanitized.value;
                }

                ModuleStats.recordModelRemoval(
                        sanitized.removed,
                        "result of " + method
                );
            }
        }

        int nestedRemoved = nestedSanitizer.sanitize(result);
        if (nestedRemoved > 0) {
            ModuleStats.recordModelRemoval(
                    nestedRemoved,
                    "nested result collections of " + method
            );
        }
    }
}
