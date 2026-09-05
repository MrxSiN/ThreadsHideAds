package my.github.MrxSiN.threadshideads.core;

import de.robv.android.xposed.XposedBridge;

/**
 * Single logging entry point for the module.
 *
 * Keeping the tag and the bridge call in one place means no other class needs
 * to know how LSPosed logging is performed.
 */
public final class ModuleLog {

    private static final String TAG = "ThreadsHideAds";

    private ModuleLog() {
    }

    public static void log(String message) {
        XposedBridge.log(TAG + ": " + message);
    }

    public static void log(String message, Throwable throwable) {
        XposedBridge.log(TAG + ": " + message);
        XposedBridge.log(throwable);
    }
}
