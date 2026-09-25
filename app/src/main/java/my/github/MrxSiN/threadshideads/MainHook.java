package my.github.MrxSiN.threadshideads;

import android.content.Context;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.Core;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.hook.ModelHookInstaller;
import my.github.MrxSiN.threadshideads.ui.RenderedLabelHooks;

/**
 * LSPosed entry point. The Brainfuck core decides whether this process is in
 * scope and which layers to install (OP_LOAD_PACKAGE); this class installs
 * them: the rendered-label hooks right away, and an Application.attach guard
 * that starts model-hook resolution once the final class loader exists.
 */
public final class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        Policy policy = Core.policy();
        if (policy == null) {
            return;
        }
        boolean[] actions = policy.loadPackage(lpparam.packageName, lpparam.processName);
        if (!actions[0]) {
            return;
        }
        if (actions[1]) {
            RenderedLabelHooks.install(policy);
        }
        if (!actions[2]) {
            return;
        }
        ModuleLog.log("Loading in process " + lpparam.processName);
        installAttachGuard(policy, lpparam);
    }

    private static void installAttachGuard(Policy policy, final XC_LoadPackage.LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                    policy.constString(Policy.TABLE_NAMES, Policy.NAME_APPLICATION),
                    lpparam.classLoader,
                    policy.constString(Policy.TABLE_NAMES, Policy.NAME_ATTACH),
                    Context.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            Context context = contextArgument(param);
                            ModuleLog.log("Application.attach observed; starting hook resolution");
                            ModelHookInstaller.request(context,
                                    ModelHookInstaller.resolveAppClassLoader(context, lpparam.classLoader),
                                    "Application.attach");
                        }
                    });
            ModuleLog.log("Application attach guard installed");
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to install Application.attach guard; trying load-package fallback: "
                    + throwable);
            if (policy.attachGuardFailed()) {
                ModelHookInstaller.request(null, lpparam.classLoader, "load-package fallback");
            }
        }
    }

    private static Context contextArgument(XC_MethodHook.MethodHookParam param) {
        if (param.args != null && param.args.length > 0 && param.args[0] instanceof Context) {
            return (Context) param.args[0];
        }
        return null;
    }
}
