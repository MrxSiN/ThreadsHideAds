package my.github.MrxSiN.threadshideads.ui;

import android.view.View;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Remembers which feed cards were collapsed, the state to restore, and the
 * exact label view that caused each collapse.
 */
public final class HiddenViewRegistry {

    public static final int UNSET_HEIGHT = Integer.MIN_VALUE;

    private static final Map<View, SavedViewState> HIDDEN_VIEWS =
            Collections.synchronizedMap(new WeakHashMap<View, SavedViewState>());

    private HiddenViewRegistry() {
    }

    public static void rememberIfAbsent(
            View view,
            int originalVisibility,
            int originalHeight,
            View markerView
    ) {
        synchronized (HIDDEN_VIEWS) {
            if (!HIDDEN_VIEWS.containsKey(view)) {
                HIDDEN_VIEWS.put(view, new SavedViewState(
                        originalVisibility, originalHeight, markerView));
            }
        }
    }

    public static SavedViewState get(View view) {
        synchronized (HIDDEN_VIEWS) {
            return HIDDEN_VIEWS.get(view);
        }
    }

    public static void forget(View view) {
        synchronized (HIDDEN_VIEWS) {
            HIDDEN_VIEWS.remove(view);
        }
    }

    public static boolean hasHiddenAncestor(View child) {
        View current = child;

        for (int depth = 0; depth < ViewTrees.maxAncestorDepth; depth++) {
            synchronized (HIDDEN_VIEWS) {
                if (HIDDEN_VIEWS.containsKey(current)) {
                    return true;
                }
            }

            View parent = ViewTrees.parentOf(current);
            if (parent == null) {
                return false;
            }
            current = parent;
        }

        return false;
    }

    public static final class SavedViewState {

        public final int originalVisibility;
        public final int originalHeight;

        private final WeakReference<View> markerView;

        SavedViewState(
                int originalVisibility,
                int originalHeight,
                View markerView
        ) {
            this.originalVisibility = originalVisibility;
            this.originalHeight = originalHeight;
            this.markerView = new WeakReference<>(markerView);
        }

        public boolean wasHiddenBy(View view) {
            return markerView.get() == view;
        }
    }
}
