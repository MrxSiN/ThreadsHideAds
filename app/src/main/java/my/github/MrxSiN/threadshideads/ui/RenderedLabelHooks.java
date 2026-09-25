package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;

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

        XposedBridge.hookAllMethods(TextView.class, "setText", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onLabelChanged(policy, (View) param.thisObject, false);
            }
        });
        XposedBridge.hookAllMethods(View.class, "setContentDescription", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                onLabelChanged(policy, (View) param.thisObject, true);
            }
        });
        XposedBridge.hookAllMethods(ViewGroup.class, "addView", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                ViewGroup parent = (ViewGroup) param.thisObject;
                if (!policy.uiAddView(ViewTrees.listContainerChain(policy, parent))) {
                    return;
                }
                for (Object argument : param.args) {
                    if (argument instanceof View) {
                        scheduleSubtreeScan(policy, (View) argument);
                        break;
                    }
                }
            }
        });
        ModuleLog.log("Installed rendered-label and attached-view fallbacks");
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
