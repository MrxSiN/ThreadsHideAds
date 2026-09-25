package my.github.MrxSiN.threadshideads.core;

import my.github.MrxSiN.threadshideads.bridge.NativeCore;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.ui.AndroidViews;

/**
 * The process-wide Brainfuck core. Loaded once; when libthreadsbf cannot be
 * loaded every layer stays inactive rather than guessing (fail open).
 */
public final class Core {

    private static volatile Policy policy;
    private static volatile boolean failed;

    private Core() {
    }

    /** The loaded core, or null when it is unavailable. */
    public static Policy policy() {
        Policy current = policy;
        if (current != null || failed) {
            return current;
        }
        synchronized (Core.class) {
            if (policy == null && !failed) {
                try {
                    Policy loaded = new Policy(NativeCore.load(new AndroidViews()));
                    loaded.configureHost();
                    policy = loaded;
                    ModuleLog.log("Brainfuck core loaded; ABI " + loaded.version());
                } catch (Throwable throwable) {
                    failed = true;
                    ModuleLog.log("Brainfuck core unavailable; filtering disabled: " + throwable);
                }
            }
            return policy;
        }
    }
}
