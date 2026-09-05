package my.github.MrxSiN.threadshideads;

import android.content.Context;

import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.hook.ModelHookInstaller;
import my.github.MrxSiN.threadshideads.ui.RenderedLabelHooks;

/**
 * Threads-only LSPosed entry point.
 *
 * The entry point installs the rendered-label fallback, observes
 * {@code Application.attach()}, and hands startup to
 * {@link ModelHookInstaller}. Detection, sanitization, discovery, and the
 * rendered-label layer each live in their own package.
 */
public final class MainHook implements IXposedHookLoadPackage {

    private static final String THREADS_PACKAGE = "com.instagram.barcelona";

    private static final AtomicBoolean ATTACH_HOOK_INSTALLED =
            new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        if (!THREADS_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        /*
         * Threads also starts secondary processes. Keep the expensive model
         * resolver in the main application process only.
         */
        if (lpparam.processName != null
                && !THREADS_PACKAGE.equals(lpparam.processName)) {
            return;
        }

        /*
         * This framework-level fallback is safe to install immediately and
         * remains available even if model discovery needs a later retry.
         */
        RenderedLabelHooks.install();

        if (!ATTACH_HOOK_INSTALLED.compareAndSet(false, true)) {
            return;
        }

        ModuleLog.log("Loading in process " + lpparam.processName);

        installAttachGuard(lpparam);
    }

    private static void installAttachGuard(
            final XC_LoadPackage.LoadPackageParam lpparam
    ) {
        try {
            XposedHelpers.findAndHookMethod(
                    "android.app.Application",
                    lpparam.classLoader,
                    "attach",
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = contextArgument(param);

                            ModuleLog.log(
                                    "Application.attach observed; starting hook resolution");

                            ModelHookInstaller.request(
                                    context,
                                    ModelHookInstaller.resolveAppClassLoader(
                                            context, lpparam.classLoader),
                                    "Application.attach"
                            );
                        }
                    }
            );

            ModuleLog.log("Application attach guard installed");
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to install Application.attach guard; "
                    + "trying load-package fallback: " + throwable);

            ModelHookInstaller.request(
                    null,
                    lpparam.classLoader,
                    "load-package fallback"
            );
        }
    }

    private static Context contextArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args != null
                && param.args.length > 0
                && param.args[0] instanceof Context) {
            return (Context) param.args[0];
        }

        return null;
    }
}
