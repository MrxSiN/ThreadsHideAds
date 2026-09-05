package my.github.MrxSiN.threadshideads.hook;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.luckypray.dexkit.DexKitBridge;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.detect.AdDetector;
import my.github.MrxSiN.threadshideads.detect.ReflectiveAdDetector;
import my.github.MrxSiN.threadshideads.discovery.FeedBoundaryFinder;
import my.github.MrxSiN.threadshideads.discovery.FeedCacheLocator;
import my.github.MrxSiN.threadshideads.model.ListSanitizer;
import my.github.MrxSiN.threadshideads.model.NestedCollectionSanitizer;

/**
 * Owns model-hook startup: when to try, how often to retry, and how to open
 * DexKit.
 *
 * Discovery runs from {@code Application.attach()} rather than from
 * {@code handleLoadPackage}, because only the former supplies the final Threads
 * application context and class loader. A transient class-loader, native
 * library, or DexKit failure is retried instead of leaving the process
 * permanently marked as initialized.
 */
public final class ModelHookInstaller {

    private static final long[] RETRY_DELAYS_MS = {
            250L,
            1000L,
            3000L
    };

    private static final AtomicBoolean INITIALIZING = new AtomicBoolean(false);
    private static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
    private static final AtomicBoolean NATIVE_LIBRARY_LOADED =
            new AtomicBoolean(false);
    private static final AtomicInteger ATTEMPTS = new AtomicInteger();

    private ModelHookInstaller() {
    }

    public static void request(
            Context context,
            ClassLoader classLoader,
            String trigger
    ) {
        request(context, classLoader, 0, trigger);
    }

    /**
     * Prefers the class loader supplied by the application context, falling
     * back to the load-package loader.
     */
    public static ClassLoader resolveAppClassLoader(
            Context context,
            ClassLoader fallback
    ) {
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

    private static void request(
            final Context context,
            final ClassLoader classLoader,
            final int retryIndex,
            final String trigger
    ) {
        if (INITIALIZED.get()) {
            return;
        }

        if (classLoader == null) {
            ModuleLog.log("Initialization skipped because the app class loader is null");
            scheduleRetry(context, null, retryIndex, "missing class loader");
            return;
        }

        if (!INITIALIZING.compareAndSet(false, true)) {
            return;
        }

        boolean success = false;
        int attempt = ATTEMPTS.incrementAndGet();

        try {
            ModuleLog.log("Initialization attempt " + attempt + " via " + trigger);
            success = installHooks(context, classLoader);

            if (success) {
                INITIALIZED.set(true);
                ModuleLog.log("Hook initialization complete; installedHooks="
                        + FeedModelHooks.installedHookCount()
                        + ", pandoHooks="
                        + PandoResponseHooks.installedHookCount());
            } else {
                ModuleLog.log("Initialization attempt installed no model hooks");
            }
        } catch (Throwable throwable) {
            ModuleLog.log("Hook initialization attempt failed: " + throwable);
            XposedBridge.log(throwable);
        } finally {
            INITIALIZING.set(false);
        }

        if (!success && !INITIALIZED.get()) {
            scheduleRetry(
                    context,
                    classLoader,
                    retryIndex,
                    "previous attempt unsuccessful"
            );
        }
    }

    private static void scheduleRetry(
            final Context context,
            final ClassLoader previousClassLoader,
            final int retryIndex,
            final String reason
    ) {
        if (INITIALIZED.get() || retryIndex >= RETRY_DELAYS_MS.length) {
            if (!INITIALIZED.get()) {
                ModuleLog.log("Initialization retries exhausted; pandoHooks="
                        + PandoResponseHooks.installedHookCount()
                        + " and the rendered-label fallback remain active");
            }
            return;
        }

        final long delayMs = RETRY_DELAYS_MS[retryIndex];

        try {
            new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                @Override
                public void run() {
                    request(
                            context,
                            resolveAppClassLoader(context, previousClassLoader),
                            retryIndex + 1,
                            "delayed retry " + (retryIndex + 1)
                    );
                }
            }, delayMs);

            ModuleLog.log("Scheduled initialization retry " + (retryIndex + 1)
                    + " in " + delayMs + " ms; reason=" + reason);
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to schedule initialization retry: " + throwable);
        }
    }

    private static boolean installHooks(
            Context context,
            ClassLoader classLoader
    ) throws Throwable {
        /*
         * The Pando accessors are named by the native binding rather than by
         * the obfuscator, so this layer needs neither DexKit nor its native
         * library, and it covers the response before it reaches any feed
         * class. Install it first so a DexKit failure cannot take it down.
         */
        PandoResponseHooks.install(classLoader);

        ensureNativeLibraryLoaded();

        AdDetector detector = new ReflectiveAdDetector();
        ListSanitizer listSanitizer = new ListSanitizer(detector);
        FeedModelHooks hooks = new FeedModelHooks(
                listSanitizer,
                new NestedCollectionSanitizer(listSanitizer)
        );

        /*
         * Application.attach supplies the final app loader, including dex
         * paths that may not be ready during handleLoadPackage.
         */
        try (DexKitBridge bridge = createBridge(context, classLoader)) {
            ModuleLog.log("DexKit parsed dex count: " + bridge.getDexNum());

            FeedBoundaryFinder finder =
                    new FeedBoundaryFinder(bridge, classLoader);

            installFeedCacheHooks(classLoader, hooks);

            int primaryHooks = installPrimaryBoundaries(finder, hooks);

            if (primaryHooks > 0) {
                ModuleLog.log("Primary feed boundaries installed: " + primaryHooks
                        + "; broad discovery skipped");
            } else if (!FeedModelHooks.hasInstalledHooks()) {
                installDiscoveredCandidates(finder, hooks);
            } else {
                ModuleLog.log("Model hooks already present; duplicate discovery skipped");
            }
        }

        return FeedModelHooks.hasInstalledHooks()
                || PandoResponseHooks.hasInstalledHooks();
    }

    /**
     * Hooks the feed cache handler located by class name. This is independent
     * of the string-marker search, which stops matching whenever Threads
     * renames the boundary.
     */
    private static void installFeedCacheHooks(
            ClassLoader classLoader,
            FeedModelHooks hooks
    ) {
        FeedCacheLocator locator = new FeedCacheLocator(classLoader);

        int installed = 0;

        for (Method method : locator.findListBoundaries()) {
            try {
                if (hooks.hookListBoundary(method)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook feed cache boundary "
                        + method + ": " + throwable);
            }
        }

        Method injector = locator.findAdInjectionPredicate();

        if (injector != null) {
            try {
                if (hooks.hookAdInjectionPredicate(injector)) {
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to hook ad injector "
                        + injector + ": " + throwable);
            }
        }

        ModuleLog.log("Feed cache hooks installed: " + installed);
    }

    private static int installPrimaryBoundaries(
            FeedBoundaryFinder finder,
            FeedModelHooks hooks
    ) {
        int installed = 0;

        for (Method method : finder.findPrimaryFeedBoundaries()) {
            try {
                if (hooks.hookListBoundary(method)) {
                    ModuleLog.log("Installed confirmed feed boundary: " + method);
                    installed++;
                }
            } catch (Throwable throwable) {
                ModuleLog.log("Unable to install confirmed feed boundary "
                        + method + ": " + throwable);
            }
        }

        return installed;
    }

    private static void installDiscoveredCandidates(
            FeedBoundaryFinder finder,
            FeedModelHooks hooks
    ) {
        Map<Method, Set<String>> candidates = finder.discoverCandidates();

        int listHooks = 0;
        int booleanHooks = 0;
        int skipped = 0;

        for (Map.Entry<Method, Set<String>> entry : candidates.entrySet()) {
            Method method = entry.getKey();
            Set<String> markers = entry.getValue();

            try {
                if (FeedBoundaryFinder.isLikelyAdListBoundary(method, markers)) {
                    if (hooks.hookListBoundary(method)) {
                        listHooks++;
                    }
                } else if (FeedBoundaryFinder.isSafePositiveAdBoolean(
                        method, markers)) {
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
                + ", listHooks=" + listHooks
                + ", booleanHooks=" + booleanHooks
                + ", skipped=" + skipped);
    }

    /**
     * Falls back to the base APK path when DexKit cannot open the final
     * application class loader.
     */
    private static DexKitBridge createBridge(
            Context context,
            ClassLoader classLoader
    ) throws Throwable {
        try {
            return DexKitBridge.create(classLoader, true);
        } catch (Throwable classLoaderFailure) {
            String sourceDir = readSourceDir(context);

            if (sourceDir == null || sourceDir.trim().isEmpty()) {
                throw classLoaderFailure;
            }

            ModuleLog.log("Class-loader DexKit open failed; retrying base APK path");

            try {
                return DexKitBridge.create(sourceDir);
            } catch (Throwable sourcePathFailure) {
                sourcePathFailure.addSuppressed(classLoaderFailure);
                throw sourcePathFailure;
            }
        }
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

    /**
     * Loaded lazily rather than from a static initializer, so a temporary
     * failure stays recoverable on a later retry.
     */
    private static void ensureNativeLibraryLoaded() {
        if (NATIVE_LIBRARY_LOADED.get()) {
            return;
        }

        synchronized (ModelHookInstaller.class) {
            if (NATIVE_LIBRARY_LOADED.get()) {
                return;
            }

            System.loadLibrary("dexkit");
            NATIVE_LIBRARY_LOADED.set(true);
            ModuleLog.log("DexKit native library loaded");
        }
    }
}
