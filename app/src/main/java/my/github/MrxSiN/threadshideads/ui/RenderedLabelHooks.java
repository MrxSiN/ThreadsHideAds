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

import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.detect.AdLabelVocabulary;

/**
 * Rendered-label fallback: watches text, content descriptions, and views
 * entering a list container, and hands any advertisement label to the hider.
 *
 * These hooks are installed before any Threads code runs, so they also cover
 * the first cold-launch feed page, which can be bound from cache before the
 * model-level hooks see any list.
 */
public final class RenderedLabelHooks {

    private static final int MAX_LIST_CONTAINER_DEPTH = 8;

    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private static final Map<View, Boolean> PENDING_SUBTREE_SCANS =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private RenderedLabelHooks() {
    }

    public static void install() {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }

        hookTextUpdates();
        hookContentDescriptions();
        hookListAttachments();

        ModuleLog.log("Installed rendered-label and attached-view fallbacks");
    }

    private static void hookTextUpdates() {
        XposedBridge.hookAllMethods(
                TextView.class,
                "setText",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        TextView textView = (TextView) param.thisObject;

                        if (!AdLabelVocabulary.isAdLabel(textView.getText())) {
                            scheduleRestore(textView);
                            return;
                        }

                        scheduleHide(textView);
                    }
                }
        );
    }

    private static void hookContentDescriptions() {
        XposedBridge.hookAllMethods(
                View.class,
                "setContentDescription",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View view = (View) param.thisObject;

                        if (!AdLabelVocabulary.isAdLabel(
                                view.getContentDescription())) {
                            scheduleRestore(view);
                            return;
                        }

                        scheduleHide(view);
                    }
                }
        );
    }

    private static void hookListAttachments() {
        XposedBridge.hookAllMethods(
                ViewGroup.class,
                "addView",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        ViewGroup parent = (ViewGroup) param.thisObject;

                        if (!ViewTrees.isWithinListContainer(
                                parent, MAX_LIST_CONTAINER_DEPTH)) {
                            return;
                        }

                        for (Object argument : param.args) {
                            if (argument instanceof View) {
                                scheduleSubtreeScan((View) argument);
                                break;
                            }
                        }
                    }
                }
        );
    }

    private static void scheduleHide(final View markerView) {
        markerView.post(new Runnable() {
            @Override
            public void run() {
                if (AdLabelScanner.isCurrentAdMarker(markerView)) {
                    FeedItemHider.hide(markerView);
                } else {
                    FeedItemHider.restoreMarkedAncestor(markerView);
                }
            }
        });
    }

    private static void scheduleRestore(final View markerView) {
        if (!HiddenViewRegistry.hasHiddenAncestor(markerView)) {
            return;
        }

        markerView.post(new Runnable() {
            @Override
            public void run() {
                if (!AdLabelScanner.isCurrentAdMarker(markerView)) {
                    FeedItemHider.restoreMarkedAncestor(markerView);
                }
            }
        });
    }

    private static void scheduleSubtreeScan(final View root) {
        if (PENDING_SUBTREE_SCANS.put(root, Boolean.TRUE) != null) {
            return;
        }

        root.post(new Runnable() {
            @Override
            public void run() {
                PENDING_SUBTREE_SCANS.remove(root);

                View label = AdLabelScanner.findAdLabel(root);
                if (label != null) {
                    FeedItemHider.hide(label);
                }
            }
        });
    }
}
