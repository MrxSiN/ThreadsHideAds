package my.github.MrxSiN.threadshideads.ui;

import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;

/**
 * Collapses the card that owns a rendered advertisement label and restores it
 * when the card is rebound to organic content.
 *
 * The Brainfuck core picks the card from the measured ancestor chain
 * (OP_UI_CARD), chooses between collapsing, waiting for layout and the
 * label-only fallback (OP_UI_HIDE), and decides whether a card may come back
 * (OP_UI_RESTORE). Listener registration, layout parameters and visibility
 * are handled here.
 */
public final class FeedItemHider {

    private static final Map<View, Boolean> PENDING_RETRIES =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private FeedItemHider() {
    }

    public static void hide(Policy policy, View labelView) {
        hide(policy, labelView, 0);
    }

    private static void hide(final Policy policy, final View labelView, int attempt) {
        boolean marker = policy.uiMarker(labelView, labelView instanceof TextView);
        View card = marker ? findFeedCard(policy, labelView) : null;
        int[] decision = policy.uiHide(marker, card != null, attempt, false);
        if (decision[0] == Policy.HIDE_RETRY && !retryWhenHierarchyIsReady(policy, labelView, decision[1])) {
            decision = policy.uiHide(marker, false, attempt, true);
        }
        if (decision[0] == Policy.HIDE_CARD) {
            collapse(card, labelView);
            ModuleStats.recordUiCollapse("feed item " + card.getClass().getName());
        } else if (decision[0] == Policy.HIDE_LABEL) {
            // Hide only the label rather than risk the whole activity.
            collapse(labelView, labelView);
            ModuleStats.recordUiCollapse("label-only fallback");
        }
    }

    /** Measures the ancestor chain and lets the core pick the card, or null. */
    private static View findFeedCard(Policy policy, View labelView) {
        DisplayMetrics metrics = labelView.getResources().getDisplayMetrics();
        int limit = ViewTrees.maxAncestorDepth;
        View[] chain = new View[limit];
        boolean[] listContainer = new boolean[limit];
        boolean[] rootOrDecor = new boolean[limit];
        int[] width = new int[limit];
        int[] height = new int[limit];
        View root = labelView.getRootView();
        int count = 0;
        View current = labelView;
        while (count < limit) {
            View parent = ViewTrees.parentOf(current);
            if (parent == null) {
                break;
            }
            boolean[] facts = policy.uiViewClass(parent);
            chain[count] = parent;
            listContainer[count] = facts[0];
            rootOrDecor[count] = parent == root || facts[1];
            width[count] = parent.getWidth();
            height[count] = parent.getHeight();
            count++;
            current = parent;
        }
        int index = policy.uiCard(trim(listContainer, count), trim(rootOrDecor, count), trim(width, count),
                trim(height, count), metrics.widthPixels, metrics.heightPixels, metrics.density);
        return index < count ? chain[index] : null;
    }

    /**
     * Arms one deferred retry: on attach while the label is detached, else
     * after the next layout pass. Returns false when none could be armed.
     */
    private static boolean retryWhenHierarchyIsReady(final Policy policy, final View labelView,
                                                     final int nextAttempt) {
        if (PENDING_RETRIES.put(labelView, Boolean.TRUE) != null) {
            return true;
        }
        if (!ViewTrees.isAttached(labelView)) {
            labelView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(View view) {
                    view.removeOnAttachStateChangeListener(this);
                    PENDING_RETRIES.remove(view);
                    hide(policy, view, nextAttempt);
                }

                @Override
                public void onViewDetachedFromWindow(View view) {
                }
            });
            return true;
        }
        ViewTreeObserver observer = labelView.getViewTreeObserver();
        if (!observer.isAlive()) {
            PENDING_RETRIES.remove(labelView);
            return false;
        }
        observer.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver current = labelView.getViewTreeObserver();
                if (current.isAlive()) {
                    current.removeOnPreDrawListener(this);
                }
                PENDING_RETRIES.remove(labelView);
                hide(policy, labelView, nextAttempt);
                return true;
            }
        });
        return true;
    }

    private static void collapse(View view, View markerView) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        HiddenViewRegistry.rememberIfAbsent(view, view.getVisibility(),
                params == null ? HiddenViewRegistry.UNSET_HEIGHT : params.height, markerView);
        if (params != null && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }
        view.setVisibility(View.GONE);
        view.requestLayout();
    }

    /** Restores the nearest hidden ancestor when the core allows it. */
    public static void restoreMarkedAncestor(Policy policy, View child) {
        View current = child;
        for (int depth = 0; depth < ViewTrees.maxAncestorDepth; depth++) {
            HiddenViewRegistry.SavedViewState savedState = HiddenViewRegistry.get(current);
            if (savedState != null) {
                boolean stillLabelled = policy.uiScan(current, true) != null;
                if (policy.uiRestore(savedState.wasHiddenBy(child), stillLabelled)) {
                    restore(current, savedState);
                }
                return;
            }
            View parent = ViewTrees.parentOf(current);
            if (parent == null) {
                return;
            }
            current = parent;
        }
    }

    private static void restore(View view, HiddenViewRegistry.SavedViewState savedState) {
        HiddenViewRegistry.forget(view);
        ViewGroup.LayoutParams params = view.getLayoutParams();
        if (params != null && savedState.originalHeight != HiddenViewRegistry.UNSET_HEIGHT) {
            params.height = savedState.originalHeight;
            view.setLayoutParams(params);
        }
        view.setVisibility(savedState.originalVisibility);
        view.requestLayout();
        ModuleLog.log("Restored recycled feed item after sponsored marker was rebound");
    }

    private static boolean[] trim(boolean[] values, int count) {
        boolean[] out = new boolean[count];
        System.arraycopy(values, 0, out, 0, count);
        return out;
    }

    private static int[] trim(int[] values, int count) {
        int[] out = new int[count];
        System.arraycopy(values, 0, out, 0, count);
        return out;
    }
}
