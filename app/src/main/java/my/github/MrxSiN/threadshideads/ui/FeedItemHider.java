package my.github.MrxSiN.threadshideads.ui;

import android.util.DisplayMetrics;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

import my.github.MrxSiN.threadshideads.core.ModuleLog;
import my.github.MrxSiN.threadshideads.core.ModuleStats;

/**
 * Collapses the feed card that owns a rendered advertisement label, and
 * restores it when RecyclerView rebinds the card to organic content.
 *
 * <p>Cold launch needs the deferred retry below. The first feed page binds its
 * labels while the cards are still detached and unmeasured, so neither the list
 * container nor the size heuristic can identify the card yet. Collapsing on
 * that first pass would hide only the "Ad" badge and leave the advertisement
 * on screen. The hider therefore waits for attach and for the first layout
 * pass before deciding, and only falls back to the label itself once the
 * hierarchy has had a real chance to settle.
 */
public final class FeedItemHider {

    private static final int MAX_LAYOUT_RETRIES = 4;

    private static final float MIN_CARD_WIDTH_RATIO = 0.70f;
    private static final float MAX_CARD_HEIGHT_RATIO = 0.80f;
    private static final int MIN_CARD_HEIGHT_DP = 80;

    private static final Map<View, Boolean> PENDING_RETRIES =
            Collections.synchronizedMap(new WeakHashMap<View, Boolean>());

    private FeedItemHider() {
    }

    public static void hide(View labelView) {
        hide(labelView, 0);
    }

    private static void hide(final View labelView, final int attempt) {
        if (!AdLabelScanner.isCurrentAdMarker(labelView)) {
            return;
        }

        View card = findFeedCard(labelView);

        if (card != null) {
            collapse(card, labelView);
            ModuleStats.recordUiCollapse(
                    "feed item " + card.getClass().getName());
            return;
        }

        if (attempt < MAX_LAYOUT_RETRIES
                && retryWhenHierarchyIsReady(labelView, attempt + 1)) {
            return;
        }

        /*
         * Last resort: hide only the label, rather than risking hiding the
         * complete activity.
         */
        collapse(labelView, labelView);
        ModuleStats.recordUiCollapse("label-only fallback");
    }

    /**
     * Returns the card to collapse, or null while the hierarchy cannot yet
     * answer the question.
     */
    private static View findFeedCard(View labelView) {
        DisplayMetrics metrics = labelView.getResources().getDisplayMetrics();
        int screenWidth = metrics.widthPixels;
        int screenHeight = metrics.heightPixels;
        int minimumHeight = Math.max(
                MIN_CARD_HEIGHT_DP,
                (int) (MIN_CARD_HEIGHT_DP * metrics.density)
        );

        View current = labelView;
        View fallbackCandidate = null;

        for (int depth = 0; depth < ViewTrees.MAX_ANCESTOR_DEPTH; depth++) {
            View parent = ViewTrees.parentOf(current);

            if (parent == null) {
                break;
            }

            /*
             * current is the direct child/card when its parent is a
             * RecyclerView or ListView.
             */
            if (ViewTrees.isListContainer(parent) && current != labelView) {
                return current;
            }

            if (depth >= 2
                    && parent.getWidth() >= screenWidth * MIN_CARD_WIDTH_RATIO
                    && parent.getHeight() >= minimumHeight
                    && parent.getHeight() <= screenHeight * MAX_CARD_HEIGHT_RATIO) {
                fallbackCandidate = parent;
            }

            if (parent == labelView.getRootView()
                    || ViewTrees.isDecorView(parent)) {
                break;
            }

            current = parent;
        }

        return fallbackCandidate;
    }

    /**
     * Arms a single deferred retry: on attach when the label is still detached,
     * otherwise after the next layout pass.
     *
     * @return true when a retry was armed and the caller should stop.
     */
    private static boolean retryWhenHierarchyIsReady(
            final View labelView,
            final int nextAttempt
    ) {
        if (PENDING_RETRIES.put(labelView, Boolean.TRUE) != null) {
            // A retry for this label is already armed.
            return true;
        }

        if (!ViewTrees.isAttached(labelView)) {
            labelView.addOnAttachStateChangeListener(
                    new View.OnAttachStateChangeListener() {
                        @Override
                        public void onViewAttachedToWindow(View view) {
                            view.removeOnAttachStateChangeListener(this);
                            PENDING_RETRIES.remove(view);
                            hide(view, nextAttempt);
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
                hide(labelView, nextAttempt);
                return true;
            }
        });

        return true;
    }

    private static void collapse(View view, View markerView) {
        ViewGroup.LayoutParams params = view.getLayoutParams();

        HiddenViewRegistry.rememberIfAbsent(
                view,
                view.getVisibility(),
                params == null ? HiddenViewRegistry.UNSET_HEIGHT : params.height,
                markerView
        );

        if (params != null && params.height != 0) {
            params.height = 0;
            view.setLayoutParams(params);
        }

        view.setVisibility(View.GONE);
        view.requestLayout();
    }

    /**
     * Restores a previously hidden card once RecyclerView rebinds it.
     *
     * Only the exact label view that hid the card may restore it. A sponsored
     * card keeps binding its author, caption, and timestamp after the label has
     * already collapsed it, and any of those updates would otherwise bring the
     * advertisement back.
     */
    public static void restoreMarkedAncestor(View child) {
        View current = child;

        for (int depth = 0; depth < ViewTrees.MAX_ANCESTOR_DEPTH; depth++) {
            HiddenViewRegistry.SavedViewState savedState =
                    HiddenViewRegistry.get(current);

            if (savedState != null) {
                if (!savedState.wasHiddenBy(child)) {
                    return;
                }

                if (AdLabelScanner.containsAdLabel(current)) {
                    return;
                }

                restore(current, savedState);
                return;
            }

            View parent = ViewTrees.parentOf(current);
            if (parent == null) {
                return;
            }
            current = parent;
        }
    }

    private static void restore(
            View view,
            HiddenViewRegistry.SavedViewState savedState
    ) {
        HiddenViewRegistry.forget(view);

        ViewGroup.LayoutParams params = view.getLayoutParams();

        if (params != null
                && savedState.originalHeight != HiddenViewRegistry.UNSET_HEIGHT) {
            params.height = savedState.originalHeight;
            view.setLayoutParams(params);
        }

        view.setVisibility(savedState.originalVisibility);
        view.requestLayout();

        ModuleLog.log(
                "Restored recycled feed item after sponsored marker was rebound");
    }
}
