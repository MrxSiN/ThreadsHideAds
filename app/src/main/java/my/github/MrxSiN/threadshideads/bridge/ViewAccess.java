package my.github.MrxSiN.threadshideads.bridge;

/**
 * Read-only access to rendered views for the text capabilities. The Android
 * implementation lives in the ui package; tests supply their own.
 */
public interface ViewAccess {

    boolean isTextView(Object view);

    /** TextView text, or null. */
    CharSequence text(Object view);

    CharSequence contentDescription(Object view);

    /** Child count of a ViewGroup, or -1 when the view is not a group. */
    int childCount(Object view);

    Object childAt(Object view, int index);
}
