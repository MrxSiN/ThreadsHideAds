package my.github.MrxSiN.threadshideads.core;

/**
 * Single logging entry point for the module.
 *
 * Keeping the call in one place means no other class needs to know how the
 * framework log is reached (ModuleRuntime).
 */
public final class ModuleLog {

    private ModuleLog() {
    }

    public static void log(String message) {
        ModuleRuntime.log(message, null);
    }

    public static void log(String message, Throwable throwable) {
        ModuleRuntime.log(message, throwable);
    }
}
