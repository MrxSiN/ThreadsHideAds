package my.github.MrxSiN.threadshideads.model;

/**
 * Result of sanitizing one list: the value the caller should now use, how many
 * ad entries were removed, and whether anything changed at all.
 */
public final class SanitizedList {

    public final Object value;
    public final int removed;
    public final boolean changed;

    public SanitizedList(Object value, int removed, boolean changed) {
        this.value = value;
        this.removed = removed;
        this.changed = changed;
    }

    public static SanitizedList unchanged(Object value) {
        return new SanitizedList(value, 0, false);
    }
}
