package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewParent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * View hierarchy questions used by the rendered-label layer.
 */
public final class ViewTrees {

    /**
     * Depth limit for every upward walk. A feed card is only a handful of
     * levels below its list container.
     */
    public static final int MAX_ANCESTOR_DEPTH = 14;

    private static final Map<Class<?>, Boolean> LIST_CONTAINER_CLASS_CACHE =
            new ConcurrentHashMap<>();

    private ViewTrees() {
    }

    public static boolean isListContainer(View view) {
        Class<?> type = view.getClass();
        Boolean cached = LIST_CONTAINER_CLASS_CACHE.get(type);
        if (cached != null) {
            return cached;
        }

        Class<?> cursor = type;
        boolean result = false;

        while (cursor != null) {
            String name = cursor.getName();

            if (name.contains("RecyclerView")
                    || name.contains("ListView")
                    || name.contains("AbsListView")) {
                result = true;
                break;
            }

            cursor = cursor.getSuperclass();
        }

        Boolean existing = LIST_CONTAINER_CLASS_CACHE.putIfAbsent(type, result);
        return existing == null ? result : existing;
    }

    public static boolean isDecorView(View view) {
        Class<?> cursor = view.getClass();

        while (cursor != null) {
            if (cursor.getName().contains("DecorView")) {
                return true;
            }

            cursor = cursor.getSuperclass();
        }

        return false;
    }

    public static boolean isWithinListContainer(View view, int maxDepth) {
        View current = view;

        for (int depth = 0; depth <= maxDepth; depth++) {
            if (isListContainer(current)) {
                return true;
            }

            View parent = parentOf(current);
            if (parent == null) {
                return false;
            }
            current = parent;
        }

        return false;
    }

    public static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    public static boolean isAttached(View view) {
        return view.getWindowToken() != null;
    }
}
