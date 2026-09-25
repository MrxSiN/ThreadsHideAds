package my.github.MrxSiN.threadshideads.ui;

import android.view.View;
import android.view.ViewParent;

import my.github.MrxSiN.threadshideads.bridge.Policy;

/**
 * View-hierarchy mechanics. Walk bounds come from the Brainfuck core
 * (OP_LIMITS); which classes count as list containers or decor views is its
 * decision too (OP_UI_VIEW_CLASS, memoized per class).
 */
final class ViewTrees {

    /** Upward walk bound; a feed card sits a handful of levels below its list. */
    static volatile int maxAncestorDepth = 14;

    /** Views checked for a list container above an added view: itself plus ancestors. */
    static volatile int listContainerWalk = 9;

    private ViewTrees() {
    }

    static void configure(Policy policy) {
        int[] limits = policy.limits();
        maxAncestorDepth = limits[1];
        listContainerWalk = limits[2];
    }

    static View parentOf(View view) {
        ViewParent parent = view.getParent();
        return parent instanceof View ? (View) parent : null;
    }

    static boolean isAttached(View view) {
        return view.getWindowToken() != null;
    }

    /** List-container flags for the view and its ancestors, nearest first. */
    static boolean[] listContainerChain(Policy policy, View view) {
        boolean[] chain = new boolean[listContainerWalk];
        int count = 0;
        View current = view;
        while (current != null && count < chain.length) {
            chain[count++] = policy.uiViewClass(current)[0];
            current = parentOf(current);
        }
        boolean[] result = new boolean[count];
        System.arraycopy(chain, 0, result, 0, count);
        return result;
    }
}
