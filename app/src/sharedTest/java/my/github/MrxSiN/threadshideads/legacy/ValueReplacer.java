package my.github.MrxSiN.threadshideads.legacy;

/**
 * Writes a replacement value back into the slot a value was read from: an
 * object field, a map entry, a list index, or an array index.
 *
 * The nested sanitizer only needs this one capability from each container kind,
 * so each traversal supplies its own tiny implementation.
 */
public interface ValueReplacer {

    boolean replace(Object replacement);
}
