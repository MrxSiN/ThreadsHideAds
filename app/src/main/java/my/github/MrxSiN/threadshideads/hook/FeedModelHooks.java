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
