package my.github.MrxSiN.threadshideads.core;

import android.util.Log;

import java.lang.reflect.Executable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.libxposed.api.XposedInterface;

/**
 * The Xposed framework as this module generation sees it: hook registration
 * and the log. Bound by ModuleMain when a generation starts.
 *
 * Every hook carries a stable id. A hot-reloaded generation installing a hook
 * with the same id on the same method replaces the old one atomically, and
 * {@link #owns} tells which of the previous generation's hooks it did not
 * take over.
 */
public final class ModuleRuntime {

    private static final String TAG = "ThreadsHideAds";

    private static volatile XposedInterface framework;

    private static final Set<String> HOOK_IDS = ConcurrentHashMap.newKeySet();

    private ModuleRuntime() {
    }

    public static void attach(XposedInterface xposed) {
        framework = xposed;
    }

    /** Hooks {@code target} under {@code id}; throws when the framework refuses. */
    public static void hook(Executable target, String id, XposedInterface.Hooker hooker) {
        framework.hook(target).setId(id).intercept(hooker);
        HOOK_IDS.add(id);
    }

    /** True when this generation installed a hook with that id. */
    public static boolean owns(String id) {
        return id != null && HOOK_IDS.contains(id);
    }

    static void log(String message, Throwable throwable) {
        XposedInterface xposed = framework;
        if (xposed != null) {
            xposed.log(Log.INFO, TAG, message, throwable);
        }
    }
}
