package my.github.MrxSiN.threadshideads.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.Core;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.discovery.FeedBoundaryFinder;
import my.github.MrxSiN.threadshideads.discovery.FeedCacheLocator;

/**
 * Runs model-hook startup as the Brainfuck core directs it.
 *
 * The core owns the initialization state machine, the retry schedule
 * (OP_INIT_REQUEST, OP_INIT_FINISH) and the install order (OP_INSTALL_STEP).
 * This class performs each step: Handler scheduling, the DexKit native
 * library, bridge creation, and hook installation.
 */
public final class ModelHookInstaller {

    private static final int REASON_NULL_LOADER = 1;
    private static final int DONE_PRIMARY = 1;
    private static final int DONE_DUPLICATE_SKIPPED = 3;

    private static final AtomicBoolean NATIVE_LIBRARY_LOADED = new AtomicBoolean(false);

    private ModelHookInstaller() {
    }

    public static void request(Context context, ClassLoader classLoader, String trigger) {
        request(context, classLoader, 0, trigger);
    }

    /** The application context's loader, else the load-package loader. */
    public static ClassLoader resolveAppClassLoader(Context context, ClassLoader fallback) {
        if (context != null) {
            try {
                ClassLoader classLoader = context.getClassLoader();
                if (classLoader != null) {
                    return classLoader;
                }
            } catch (Throwable ignored) {
                // Fall through to the load-package class loader.
            }
        }
        return fallback;
    }

    private static void request(Context context, ClassLoader classLoader, int retryIndex, String trigger) {
        Policy policy = Core.policy();
        if (policy == null) {
            return;
        }
        Policy.Init decision = policy.initRequest(classLoader != null, retryIndex);
        if (decision.action == Policy.INIT_IGNORE) {
            return;
        }
        if (decision.action != Policy.INIT_START) {
            if (decision.reason == REASON_NULL_LOADER) {
                ModuleLog.log("Initialization skipped because the app class loader is null");
            }
            follow(decision, context, null, "missing class loader");
            return;
        }

        boolean success = false;
        try {
            ModuleLog.log("Initialization attempt " + decision.attempt + " via " + trigger);
            success = installHooks(policy, context, classLoader);
            if (!success) {
                ModuleLog.log("Initialization attempt installed no model hooks");
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Hook initialization attempt failed: " + throwable, throwable);
        }
        Policy.Init finished = policy.initFinish(success, retryIndex);
        if (finished.action == Policy.INIT_COMPLETE) {
            ModuleLog.log("Hook initialization complete; installedHooks="
                    + FeedModelHooks.installedHookCount()
                    + ", pandoHooks=" + PandoResponseHooks.installedHookCount());
            return;
        }
        follow(finished, context, classLoader, "previous attempt unsuccessful");
    }

    /** Acts on a retry or exhaustion decision. */
    private static void follow(final Policy.Init decision, final Context context,
                               final ClassLoader previousClassLoader, String reason) {
        if (decision.action == Policy.INIT_EXHAUSTED) {
            ModuleLog.log("Initialization retries exhausted; pandoHooks="
                    + PandoResponseHooks.installedHookCount()
                    + " and the rendered-label fallback remain active");
            return;
        }
        if (decision.action != Policy.INIT_RETRY) {
            return;
        }
        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    request(context, resolveAppClassLoader(context, previousClassLoader),
                            decision.nextRetry, "delayed retry " + decision.nextRetry);
                }
            }, decision.delayMs);
            ModuleLog.log("Scheduled initialization retry " + decision.nextRetry
                    + " in " + decision.delayMs + " ms; reason=" + reason);
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to schedule initialization retry: " + throwable);
        }
    }

    /** Performs the install steps the core hands out, in its order. */
    private static boolean installHooks(Policy policy, Context context, ClassLoader classLoader)
            throws Throwable {
        FeedModelHooks hooks = new FeedModelHooks(policy);
        DexKitBridge bridge = null;
        FeedBoundaryFinder finder = null;
        Throwable failure = null;
        int step = Policy.STEP_BEGIN;
        boolean ok = false;
        int aux = 0;
        int primaryInstalled = 0;
        try {
            while (true) {
                int[] next = policy.installStep(step, ok, aux,
                        FeedModelHooks.installedHookCount(), PandoResponseHooks.installedHookCount());
                step = next[0];
                ok = true;
                aux = 0;
                switch (step) {
                    case Policy.STEP_PANDO:
                        // Needs neither DexKit nor its library, so it goes first.
                        PandoResponseHooks.install(policy, classLoader);
                        break;
                    case Policy.STEP_LOAD_NATIVE:
                        try {
                            ensureNativeLibraryLoaded(policy);
                        } catch (Throwable throwable) {
                            ok = false;
                            failure = throwable;
                        }
                        break;
                    case Policy.STEP_OPEN_LOADER:
                        try {
                            bridge = DexKitBridge.create(classLoader, true);
                        } catch (Throwable throwable) {
                            ok = false;
                            failure = throwable;
                            String sourceDir = readSourceDir(context);
                            aux = sourceDir == null || sourceDir.trim().isEmpty() ? 0 : 1;
                        }
                        break;
                    case Policy.STEP_OPEN_APK:
                        ModuleLog.log("Class-loader DexKit open failed; retrying base APK path");
                        try {
                            bridge = DexKitBridge.create(readSourceDir(context));
                        } catch (Throwable throwable) {
                            ok = false;
                            if (failure != null) {
                                throwable.addSuppressed(failure);
                            }
                            failure = throwable;
                        }
                        break;
                    case Policy.STEP_FEED_CACHE:
                        ModuleLog.log("DexKit parsed dex count: " + bridge.getDexNum());
                        finder = new FeedBoundaryFinder(policy, bridge, classLoader);
                        installFeedCacheHooks(policy, classLoader, hooks);
                        break;
                    case Policy.STEP_PRIMARY:
                        primaryInstalled = installPrimaryBoundaries(finder, hooks);
                        aux = primaryInstalled;
                        break;
                    case Policy.STEP_BROAD:
                        installDiscoveredCandidates(policy, finder, hooks);
                        break;
                    case Policy.STEP_DONE:
                        if (next[2] == DONE_PRIMARY) {
                            ModuleLog.log("Primary feed boundaries installed: "
                                    + primaryInstalled + "; broad discovery skipped");
                        } else if (next[2] == DONE_DUPLICATE_SKIPPED) {
                            ModuleLog.log("Model hooks already present; duplicate discovery skipped");
                        }
                        return next[1] != 0;
                    default:
                        // STEP_ABORT: the attempt failed with the recorded error.
                        throw failure != null ? failure : new IllegalStateException("install aborted");
                }
            }
        } finally {
            if (bridge != null) {
                bridge.close();
            }
        }
    }

    private static void installFeedCacheHooks(Policy policy, ClassLoader classLoader, FeedModelHooks hooks) {
        FeedCacheLocator locator = new FeedCacheLocator(policy, classLoader);
        int installed = 0;
        for (Method method : locator.findListBoundaries()) {
            try {
                if (hooks.hookListBoundary(method)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook feed cache boundary " + method + ": " + throwable);
            }
        }
        Method injector = locator.findAdInjectionPredicate();
        if (injector != null) {
            try {
                if (hooks.hookAdInjectionPredicate(injector)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook ad injector " + injector + ": " + throwable);
            }
        }
        ModuleLog.log("Feed cache hooks installed: " + installed);
    }

    private static int installPrimaryBoundaries(FeedBoundaryFinder finder, FeedModelHooks hooks) {
        int installed = 0;
        for (Method method : finder.findPrimaryFeedBoundaries()) {
            try {
                if (hooks.hookListBoundary(method)) {
                    ModuleLog.log("Installed confirmed feed boundary: " + method);
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to install confirmed feed boundary " + method + ": " + throwable);
            }
        }
        return installed;
    }

    private static void installDiscoveredCandidates(Policy policy, FeedBoundaryFinder finder,
                                                    FeedModelHooks hooks) {
        Map<Method, boolean[]> candidates = finder.discoverCandidates();
        int listHooks = 0;
        int booleanHooks = 0;
        int skipped = 0;
        for (Map.Entry<Method, boolean[]> entry : candidates.entrySet()) {
            Method method = entry.getKey();
            try {
                int action = policy.broadCandidate(method, entry.getValue());
                if (action == Policy.BROAD_LIST) {
                    if (hooks.hookListBoundary(method)) {
                        listHooks++;
                    }
                } else if (action == Policy.BROAD_BOOLEAN) {
                    if (hooks.hookBooleanPredicate(method)) {
                        booleanHooks++;
                    }
                } else {
                    skipped++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook " + method + ": " + throwable);
            }
        }
        ModuleLog.log("Fallback discovery complete: candidates=" + candidates.size()
                + ", listHooks=" + listHooks + ", booleanHooks=" + booleanHooks + ", skipped=" + skipped);
    }

    private static String readSourceDir(Context context) {
        if (context == null) {
            return null;
        }
        try {
            if (context.getApplicationInfo() != null) {
                return context.getApplicationInfo().sourceDir;
            }
        } catch (Throwable ignored) {
            // The class-loader failure remains the primary error.
        }
        return null;
    }

    /** Loaded lazily, so a temporary failure stays recoverable on a retry. */
    private static void ensureNativeLibraryLoaded(Policy policy) {
        if (NATIVE_LIBRARY_LOADED.get()) {
            return;
        }
        synchronized (ModelHookInstaller.class) {
            if (NATIVE_LIBRARY_LOADED.get()) {
                return;
            }
            System.loadLibrary(policy.constString(Policy.TABLE_NAMES, Policy.NAME_DEXKIT_LIBRARY));
            NATIVE_LIBRARY_LOADED.set(true);
            ModuleLog.log("DexKit native library loaded");
        }
    }
}
