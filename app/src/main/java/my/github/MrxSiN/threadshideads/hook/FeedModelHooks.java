package my.github.MrxSiN.threadshideads.hook;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import my.github.MrxSiN.threadshideads.bridge.BfAbi;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleRuntime;
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
        String id = method.toGenericString() + "#list";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        final Class<?>[] parameterTypes = method.getParameterTypes();
        final Class<?> returnType = method.getReturnType();

        ModuleRuntime.hook(method, id, chain -> {
            Object[] args = chain.getArgs().toArray();
            ChainCallFrame frame = new ChainCallFrame(args, parameterTypes, returnType);
            before(method, frame);
            try {
                frame.setResult(chain.proceed(args));
            } catch (Throwable throwable) {
                log(method, policy.boundaryAfter(frame, true));
                throw throwable;
            }
            log(method, policy.boundaryAfter(frame, false));
            return frame.result();
        });

        ModuleLog.log("Installed list sanitizer: " + method);
        return true;
    }

    private void before(Method method, ChainCallFrame frame) {
        long start = HotPathStats.ENABLED ? System.nanoTime() : 0;
        List<Policy.Report> reports = policy.boundaryBefore(frame);
        if (HotPathStats.ENABLED) {
            HotPathStats.BOUNDARY.record(System.nanoTime() - start);
        }
        log(method, reports);
    }

    /** Neutralizes the client-side ad injector: it reports "nothing injected". */
    public boolean hookAdInjectionPredicate(final Method method) {
        String id = method.toGenericString() + "#injector";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        ModuleRuntime.hook(method, id, chain -> {
            chain.proceed();
            return Boolean.FALSE;
        });
        ModuleLog.log("Neutralized ad injector: " + method);
        return true;
    }

    /**
     * Skips the permalink ad fetch: the suspend step completes at once with
     * Unit, as it does after a failed fetch, so no ad joins the reply list.
     */
    public boolean hookPermalinkAdsLoader(Method method) throws ReflectiveOperationException {
        String id = method.toGenericString() + "#permalinkAds";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        final Object unit = Class.forName("kotlin.Unit", false, method.getDeclaringClass().getClassLoader())
                .getField("INSTANCE").get(null);
        ModuleRuntime.hook(method, id, chain -> unit);
        ModuleLog.log("Disabled permalink ad loader: " + method);
        return true;
    }

    /**
     * Uses Threads' own ad check as a second signal for one-item writes and
     * inserts: the item's Media getter and Media's check, both found unique.
     */
    public void useNativeAdCheck(Method mediaGetter, Method check) {
        nativeMediaGetter = mediaGetter;
        nativeCheck = check;
        if (mediaGetter != null && check != null) {
            ModuleLog.log("Using Threads' own ad check: " + check);
        }
    }

    private volatile Method nativeMediaGetter;
    private volatile Method nativeCheck;

    private boolean nativeAd(Object item) {
        Method getter = nativeMediaGetter;
        Method check = nativeCheck;
        if (getter == null || check == null) {
            return false;
        }
        try {
            Object media = getter.invoke(item, (Object[]) null);
            return media != null && Boolean.TRUE.equals(check.invoke(media, (Object[]) null));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean keepOut(Object item) {
        return item != null && policy.itemAction(policy.looksLikeAd(item), nativeAd(item));
    }

    /**
     * A feed cache write of one item (add, insert after a post, replace): an
     * item the core keeps out is not written; the suspend write completes at
     * once with Unit, as it does when it is done.
     */
    public boolean hookItemWrite(Method method) throws ReflectiveOperationException {
        String id = method.toGenericString() + "#itemWrite";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        final Object unit = Class.forName("kotlin.Unit", false, method.getDeclaringClass().getClassLoader())
                .getField("INSTANCE").get(null);
        ModuleRuntime.hook(method, id, chain -> {
            Object item = chain.getArgs().get(0);
            if (keepOut(item)) {
                ModuleStats.recordModelRemoval(1, "one-item write " + method);
                return unit;
            }
            return chain.proceed();
        });
        ModuleLog.log("Installed one-item write filter: " + method);
        return true;
    }

    /**
     * For You's insert of one item at a position: an item the core keeps out
     * goes in at position -1, which the insert declines without writing
     * (a negative position, or IndexOutOfBounds, answers a not-inserted code
     * that the sponsored controller logs and does not retry).
     */
    public boolean hookItemInsert(Method method) {
        String id = method.toGenericString() + "#itemInsert";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        ModuleRuntime.hook(method, id, chain -> {
            if (!keepOut(chain.getArgs().get(0))) {
                return chain.proceed();
            }
            ModuleStats.recordModelRemoval(1, "item insert " + method);
            Object[] args = chain.getArgs().toArray();
            args[1] = -1;
            return chain.proceed(args);
        });
        ModuleLog.log("Installed item insert filter: " + method);
        return true;
    }

    public boolean hookBooleanPredicate(Method method) {
        String id = method.toGenericString() + "#boolean";
        if (!HOOKED_METHODS.add(id)) {
            return false;
        }
        // Replaces the predicate: the original never runs (returnConstant(false)).
        ModuleRuntime.hook(method, id, chain -> Boolean.FALSE);
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
