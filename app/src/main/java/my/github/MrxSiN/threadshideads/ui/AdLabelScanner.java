package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;

import my.github.MrxSiN.threadshideads.detect.AdLabelVocabulary;

/**
 * Reads rendered advertisement labels out of a view or a view subtree.
 */
public final class AdLabelScanner {

    private static final int MAX_SCAN_VIEWS = 320;
    private static final int MAX_RESCAN_VIEWS = 160;
    private static final int MAX_CHILDREN_PER_GROUP = 64;

    private AdLabelScanner() {
    }

    /**
     * True when this exact view currently renders an advertisement label.
     * Re-read on every use so a stale callback cannot act on old text.
     */
    public static boolean isCurrentAdMarker(View view) {
        return (view instanceof TextView
                && AdLabelVocabulary.isAdLabel(((TextView) view).getText()))
                || AdLabelVocabulary.isAdLabel(view.getContentDescription());
    }

    /**
     * Returns the first labelled view in the subtree, or null.
     */
    public static View findAdLabel(View root, int maxViews) {
        ArrayList<View> pending = new ArrayList<>();
        pending.add(root);
        int cursor = 0;

        while (cursor < pending.size() && cursor < maxViews) {
            View view = pending.get(cursor++);

            if (isCurrentAdMarker(view)) {
                return view;
            }

            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                int count = Math.min(group.getChildCount(), MAX_CHILDREN_PER_GROUP);

                for (int i = 0; i < count; i++) {
                    View child = group.getChildAt(i);
                    if (child != null) {
                        pending.add(child);
                    }
                }
            }
        }

        return null;
    }

    public static View findAdLabel(View root) {
        return findAdLabel(root, MAX_SCAN_VIEWS);
    }

    public static boolean containsAdLabel(View root) {
        return findAdLabel(root, MAX_RESCAN_VIEWS) != null;
    }
}
