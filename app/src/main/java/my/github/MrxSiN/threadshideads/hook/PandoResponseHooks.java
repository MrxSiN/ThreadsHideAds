package my.github.MrxSiN.threadshideads.hook;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.core.Diagnostics;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;
import my.github.MrxSiN.threadshideads.detect.PandoAdSignals;
import my.github.MrxSiN.threadshideads.detect.PandoTrees;

/**
 * Filters sponsored items where the GraphQL response first becomes readable
 * Java objects: the Pando tree accessors.
 *
 * Every feed model on this build is a {@code com.facebook.pando.TreeJNI}
 * subtree, and the app reaches its child lists through the accessors declared
 * on that base class. Those accessors sit above the obfuscated feed
 * boundaries, so hooking them keeps working when Threads renames the feed
 * classes, and a sponsored item is removed before any cache, repository, or
 * client-side ad injector has seen it.
 *
 * The accessors are found by shape rather than by name, and native methods are
 * skipped because they cannot be hooked.
 */
public final class PandoResponseHooks {

    private static final String TREE_CLASS = "com.facebook.pando.TreeJNI";

    /*
     * A feed page holds tens of items. A far longer list is a bulk value list
     * rather than a response row list, and inspecting one is not worth the
     * cost on a UI thread.
     */
    private static final int MAX_LIST_SIZE = 256;

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

    /**
     * Hooks every list-returning Pando tree accessor. Returns how many were
     * installed, or 0 when this build exposes no Pando runtime.
     */
    public static int install(ClassLoader classLoader) {
        Class<?> treeClass = resolve(classLoader);

        if (treeClass == null) {
            ModuleLog.log("Pando response path unavailable; " + TREE_CLASS
                    + " is not loadable");
            return 0;
        }

        Method[] declared = declaredMethods(treeClass);

        /*
         * When nothing installs, the log has to say whether the accessors are
         * missing, native, or shaped differently on this build.
         */
        Diagnostics.traceDeclaredMethods(treeClass, declared);

        int installed = 0;

        for (Method method : declared) {
            if (!isListAccessor(method)) {
                continue;
            }

            try {
                if (hookListAccessor(method)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook Pando accessor " + method
                        + ": " + throwable);
            }
        }

        ModuleLog.log("Pando response hooks installed: " + installed);
        return installed;
    }

    private static boolean hookListAccessor(final Method method) {
        if (!HOOKED_METHODS.add(method.toGenericString() + "#pando")) {
            return false;
        }

        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (param.hasThrowable()) {
                    return;
                }

                try {
                    filterResult(method, param);
                } catch (Throwable throwable) {
                    ModuleLog.log("Pando response filtering failed in "
                            + method.getName() + ": " + throwable);
                }
            }
        });

        ModuleLog.log("Installed Pando response filter: " + method);
        return true;
    }

    private static void filterResult(
            Method method,
            XC_MethodHook.MethodHookParam param
    ) {
        Object result = param.getResult();

        if (!(result instanceof List<?>)) {
            return;
        }

        List<?> source = (List<?>) result;
        int size = source.size();

        if (size == 0 || size > MAX_LIST_SIZE) {
            return;
        }

        /*
         * String, number, and enum lists share these accessors. Rejecting them
         * on the first element keeps the common case cheap.
         */
        if (!PandoTrees.isPandoTree(source.get(0))) {
            return;
        }

        Diagnostics.traceTreeList(method.getName(), size, source.get(0));

        /*
         * A list this long is a response page rather than a single-valued
         * field, so its first row is worth reporting on its own site key; the
         * per-class trace is consumed by the small leaf fragments otherwise.
         */
        if (size >= 5) {
            Diagnostics.tracePageRow(
                    method.getName() + "[" + size + "]", source.get(0));
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
            return;
        }

        /*
         * The app reads the first entry of some inner lists unconditionally,
         * so emptying one crashes Threads. When every entry is sponsored, the
         * item that owns this list is what should be dropped, one level above.
         */
        if (kept.isEmpty()) {
            Diagnostics.traceEmptyingBlocked(source);
            return;
        }

        Object replacement = replacementFor(kept, method.getReturnType(), source);

        if (replacement == null) {
            ModuleStats.recordReplacementFailure();
            return;
        }

        param.setResult(replacement);

        ModuleStats.recordModelRemoval(
                removed,
                "Pando response list " + method.getName()
        );
    }

    /**
     * Produces a list the caller can still use. Pando hands out immutable
     * lists, so a plain ArrayList is only safe when the declared return type
     * accepts one; otherwise the list type has to rebuild itself.
     */
    private static Object replacementFor(
            ArrayList<Object> kept,
            Class<?> declaredType,
            List<?> original
    ) {
        if (declaredType == null
                || declaredType == Object.class
                || declaredType.isAssignableFrom(ArrayList.class)) {
            return kept;
        }

        Object copy = copyThrough(declaredType, kept);

        return copy != null ? copy : copyThrough(original.getClass(), kept);
    }

    /**
     * Invokes a static {@code copyOf} factory declared on the given type or one
     * of its supertypes, which is how ImmutableList-style classes rebuild
     * themselves.
     */
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

    private static Object copyThroughDeclared(
            Class<?> type,
            ArrayList<Object> kept
    ) {
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

    private static boolean isListAccessor(Method method) {
        int modifiers = method.getModifiers();

        return !Modifier.isAbstract(modifiers)
                && !Modifier.isNative(modifiers)
                && List.class.isAssignableFrom(method.getReturnType());
    }

    private static Method[] declaredMethods(Class<?> type) {
        try {
            return type.getDeclaredMethods();
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to read Pando accessors: " + throwable);
            return new Method[0];
        }
    }

    private static Class<?> resolve(ClassLoader classLoader) {
        try {
            return classLoader.loadClass(TREE_CLASS);
        } catch (Throwable ignored) {
            /*
             * A build without Pando is expected rather than an error; the feed
             * boundary hooks still cover it.
             */
            return null;
        }
    }
}
