package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.libxposed.api.XposedInterface;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleRuntime;

/**
 * Rendered-label fallback: text, content-description and addView hooks. The
 * Brainfuck core decides what each event means (OP_UI_TEXT_EVENT,
 * OP_UI_ADD_VIEW, OP_UI_MARKER); this class posts the resulting work.
 *
 * Installed before any Threads code runs, so it also covers the first
 * cold-launch page, which can be bound before the model hooks see a list.
 */
public final class RenderedLabelHooks {

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final Map<View, Boolean> PENDING_SUBTREE_SCANS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private RenderedLabelHooks() {
    }

    public static void install(final Policy policy) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        ViewTrees.configure(policy);

        hookAll(TextView.class, "setText", chain -> {
            Object result = chain.proceed();
            onLabelChanged(policy, (View) chain.getThisObject(), false);
            return result;
        });
        hookAll(View.class, "setContentDescription", chain -> {
            Object result = chain.proceed();
            onLabelChanged(policy, (View) chain.getThisObject(), true);
            return result;
        });
        hookAll(ViewGroup.class, "addView", chain -> {
            Object result = chain.proceed();
            ViewGroup parent = (ViewGroup) chain.getThisObject();
            if (policy.uiAddView(ViewTrees.listContainerChain(policy, parent))) {
                for (Object argument : chain.getArgs()) {
                    if (argument instanceof View) {
                        scheduleSubtreeScan(policy, (View) argument);
                        break;
                    }
                }
            }
            return result;
        });
        ModuleLog.log("Installed rendered-label and attached-view fallbacks");
    }

    /** Every overload {@code type} declares under {@code name} (hookAllMethods). */
    private static void hookAll(Class<?> type, String name, XposedInterface.Hooker hooker) {
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                ModuleRuntime.hook(method, "ui:" + method.toGenericString(), hooker);
            }
        }
    }

    private static void onLabelChanged(Policy policy, View view, boolean description) {
        if (policy.uiTextEvent(description, view) == Policy.UI_HIDE) {
            scheduleHide(policy, view);
        } else {
            scheduleRestore(policy, view);
        }
    }

    private static void scheduleHide(final Policy policy, final View markerView) {
        markerView.post(new Runnable() {
            @Override
            public void run() {
                if (policy.uiMarker(markerView, markerView instanceof TextView)) {
                    FeedItemHider.hide(policy, markerView);
                } else {
                    FeedItemHider.restoreMarkedAncestor(policy, markerView);
                }
            }
        });
    }

    private static void scheduleRestore(final Policy policy, final View markerView) {
        if (!HiddenViewRegistry.hasHiddenAncestor(markerView)) {
            return;
        }
        markerView.post(new Runnable() {
            @Override
            public void run() {
                if (!policy.uiMarker(markerView, markerView instanceof TextView)) {
                    FeedItemHider.restoreMarkedAncestor(policy, markerView);
                }
            }
        });
    }

    private static void scheduleSubtreeScan(final Policy policy, final View root) {
        if (PENDING_SUBTREE_SCANS.put(root, Boolean.TRUE) != null) {
            return;
        }
        root.post(new Runnable() {
            @Override
            public void run() {
                PENDING_SUBTREE_SCANS.remove(root);
                Object label = policy.uiScan(root, false);
                if (label instanceof View) {
                    FeedItemHider.hide(policy, (View) label);
                }
            }
        });
    }
}
