package my.github.MrxSiN.threadshideads.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.Diagnostics;
import my.github.MrxSiN.threadshideads.core.ModelProbes;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;

/**
 * The Pando response layer: list accessors of com.facebook.pando.TreeJNI,
 * where a GraphQL response first becomes Java objects. Their names come from
 * the native binding, so this layer needs neither DexKit nor its library.
 *
 * The Brainfuck core picks the accessors (OP_PANDO_ACCESSOR) and filters every
 * returned list (OP_PANDO_FILTER); this class hooks and assigns the result.
 */
public final class PandoResponseHooks {

    private static final Set<String> HOOKED_METHODS =
            Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());

    private PandoResponseHooks() {
    }

    public static int installedHookCount() {
        return HOOKED_METHODS.size();
    }

    public static boolean hasInstalledHooks() {
        return !HOOKED_METHODS.isEmpty();
    }

    public static int install(Policy policy, ClassLoader classLoader) {
        String treeClassName = policy.constString(Policy.TABLE_NAMES, Policy.NAME_PANDO_TREE);
        Class<?> treeClass = resolve(classLoader, treeClassName);
        if (treeClass == null) {
            ModuleLog.log("Pando response path unavailable; " + treeClassName + " is not loadable");
            return 0;
        }
        Method[] declared;
        try {
            declared = treeClass.getDeclaredMethods();
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to read Pando accessors: " + throwable);
            declared = new Method[0];
        }
        ModelProbes.traceDeclaredMethods(treeClass, declared);
        int installed = 0;
        for (Method method : declared) {
            if (!policy.pandoAccessor(method)) {
                continue;
            }
            try {
                if (hookListAccessor(policy, method)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook Pando accessor " + method + ": " + throwable);
            }
        }
        ModuleLog.log("Pando response hooks installed: " + installed);
        return installed;
    }

    private static boolean hookListAccessor(final Policy policy, final Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#pando")) {
            return false;
        }
        final Class<?> returnType = method.getReturnType();
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }
                try {
                    Object result = param.getResult();
                    long start = HotPathStats.ENABLED ? System.nanoTime() : 0;
                    Policy.PandoFilter filter = policy.pandoFilter(result, returnType);
                    if (HotPathStats.ENABLED) {
                        HotPathStats.PANDO.record(System.nanoTime() - start);
                    }
                    switch (filter.action) {
                        case Policy.PandoFilter.REPLACED:
                            param.setResult(filter.result);
                            ModuleStats.recordModelRemoval(filter.removed, "Pando response list " + method.getName());
                            break;
                        case Policy.PandoFilter.REPLACE_FAILED:
                            ModuleStats.recordReplacementFailure();
                            break;
                        case Policy.PandoFilter.BLOCKED:
                            if (result instanceof List<?>) {
                                Diagnostics.traceEmptyingBlocked((List<?>) result);
                            }
                            break;
                        default:
                            break;
                    }
                } catch (Throwable throwable) {
                    ModuleLog.log("Pando response filtering failed in " + method.getName() + ": " + throwable);
                }
            }
        });
        ModuleLog.log("Installed Pando response filter: " + method);
        return true;
    }

    private static Class<?> resolve(ClassLoader classLoader, String name) {
        if (name == null) {
            return null;
        }
        try {
            return classLoader.loadClass(name);
        } catch (Throwable ignored) {
            // A build without Pando is expected; the feed boundaries still cover it.
            return null;
        }
    }
}
