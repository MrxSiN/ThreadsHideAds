package my.github.MrxSiN.threadshideads;

import android.content.Context;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.XposedModule;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.Core;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleRuntime;
import my.github.MrxSiN.threadshideads.hook.ModelHookInstaller;
import my.github.MrxSiN.threadshideads.ui.HiddenViewRegistry;
import my.github.MrxSiN.threadshideads.ui.RenderedLabelHooks;

/**
 * Modern Xposed API entry point (libxposed API 102, Vector). The Brainfuck
 * core decides whether a package is in scope and which layers to install
 * (OP_LOAD_PACKAGE); this class installs them: the rendered-label hooks right
 * away, and an Application.attach guard that starts model-hook resolution
 * once the final class loader and the application context exist.
 *
 * Hot reload (autoHotReload in module.prop): when a new build is installed,
 * the running generation retires its core and hands over what it learned in
 * framework types only ({@link #onHotReloading}); the new generation installs
 * its hooks again under the same ids, so they replace the old ones in place,
 * takes the rest of the old hooks off, and resumes where the old one was
 * ({@link #onHotReloaded}).
 */
public final class ModuleMain extends XposedModule {

    private static final String KEY_PACKAGE = "package";
    private static final String KEY_CLASS_LOADER = "classLoader";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_HIDDEN_VIEWS = "hiddenViews";

    private volatile String processName = "";

    /** The scoped package this generation loaded into, once it has. */
    private volatile String packageName;
    private volatile ClassLoader classLoader;
    /** The application context, once Application.attach has run. */
    private volatile Context context;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        ModuleRuntime.attach(this);
        processName = param.getProcessName();
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        load(param.getPackageName(), param.getClassLoader());
    }

    @Override
    public boolean onHotReloading(HotReloadingParam param) {
        Map<String, Object> state = new HashMap<>();
        state.put(KEY_PACKAGE, packageName);
        state.put(KEY_CLASS_LOADER, classLoader);
        state.put(KEY_CONTEXT, context);
        state.put(KEY_HIDDEN_VIEWS, HiddenViewRegistry.export());
        param.setSavedInstanceState(state);
        ModuleLog.log("Hot reloading; retiring the Brainfuck core of this generation");
        Core.shutdown();
        return true;
    }

    @Override
    public void onHotReloaded(HotReloadedParam param) {
        ModuleRuntime.attach(this);
        processName = param.getProcessName();
        Object saved = param.getSavedInstanceState();
        if (saved instanceof Map) {
            Map<?, ?> state = (Map<?, ?>) saved;
            HiddenViewRegistry.adopt(state.get(KEY_HIDDEN_VIEWS));
            context = (Context) state.get(KEY_CONTEXT);
            Object pkg = state.get(KEY_PACKAGE);
            if (pkg instanceof String) {
                load((String) pkg, (ClassLoader) state.get(KEY_CLASS_LOADER));
            }
        }
        int retired = 0;
        for (XposedInterface.HookHandle handle : param.getOldHookHandles()) {
            if (!ModuleRuntime.owns(handle.getId())) {
                handle.unhook();
                retired++;
            }
        }
        ModuleLog.log("Hot reloaded; " + retired + " hook(s) of the previous generation taken off");
    }

    private void load(String pkg, ClassLoader loader) {
        Policy policy = Core.policy();
        if (policy == null) {
            return;
        }
        boolean[] actions = policy.loadPackage(pkg, processName);
        if (!actions[0]) {
            return;
        }
        if (actions[1]) {
            RenderedLabelHooks.install(policy);
        }
        if (!actions[2]) {
            return;
        }
        packageName = pkg;
        classLoader = loader;
        ModuleLog.log("Threads Hide Ads " + BuildConfig.VERSION_NAME + " loading in process " + processName
                + " (" + getFrameworkName() + " "
                + getFrameworkVersion() + ", API " + getApiVersion() + ")");
        Context attached = context;
        if (attached != null) {
            // Hot reload: Application.attach already ran in this process.
            ModelHookInstaller.request(attached,
                    ModelHookInstaller.resolveAppClassLoader(attached, loader), "hot reload");
        } else {
            installAttachGuard(policy, loader);
        }
    }

    private void installAttachGuard(Policy policy, final ClassLoader loader) {
        try {
            Method attach = Class.forName(policy.constString(Policy.TABLE_NAMES, Policy.NAME_APPLICATION),
                    false, loader).getDeclaredMethod(
                    policy.constString(Policy.TABLE_NAMES, Policy.NAME_ATTACH), Context.class);
            ModuleRuntime.hook(attach, "attach:" + attach.toGenericString(), chain -> {
                Object result = chain.proceed();
                Object argument = chain.getArgs().isEmpty() ? null : chain.getArg(0);
                Context attached = argument instanceof Context ? (Context) argument : null;
                context = attached;
                ModuleLog.log("Application.attach observed; starting hook resolution");
                ModelHookInstaller.request(attached,
                        ModelHookInstaller.resolveAppClassLoader(attached, loader), "Application.attach");
                return result;
            });
            ModuleLog.log("Application attach guard installed");
        } catch (Throwable throwable) {
            ModuleLog.log("Unable to install Application.attach guard; trying load-package fallback: "
                    + throwable);
            if (policy.attachGuardFailed()) {
                ModelHookInstaller.request(null, loader, "load-package fallback");
            }
        }
    }
}
