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

    /**
     * The registry in framework types only, for the generation that replaces
     * this one on a hot reload: view to {visibility, height, marker ref}.
     */
    public static Map<View, Object[]> export() {
        Map<View, Object[]> out = new WeakHashMap<>();
        synchronized (HIDDEN_VIEWS) {
            for (Map.Entry<View, SavedViewState> entry : HIDDEN_VIEWS.entrySet()) {
                SavedViewState state = entry.getValue();
                out.put(entry.getKey(), new Object[]{state.originalVisibility, state.originalHeight, state.markerView});
            }
        }
        return out;
    }

    /** Takes over the collapses a previous generation made (see {@link #export}). */
    @SuppressWarnings("unchecked")
    public static void adopt(Object exported) {
        if (!(exported instanceof Map)) {
            return;
        }
        synchronized (HIDDEN_VIEWS) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) exported).entrySet()) {
                Object[] state = (Object[]) entry.getValue();
                HIDDEN_VIEWS.put((View) entry.getKey(), new SavedViewState(
                        (Integer) state[0], (Integer) state[1], (WeakReference<View>) state[2]));
            }
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
            this(originalVisibility, originalHeight, new WeakReference<>(markerView));
        }

        SavedViewState(int originalVisibility, int originalHeight, WeakReference<View> markerView) {
            this.originalVisibility = originalVisibility;
            this.originalHeight = originalHeight;
            this.markerView = markerView;
        }

        public boolean wasHiddenBy(View view) {
            return markerView.get() == view;
        }
    }
}
