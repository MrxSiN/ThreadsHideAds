package my.github.MrxSiN.threadshideads.bridge;

import java.util.Locale;

/**
 * Encodes text for the Brainfuck text ABI: the optional case transform the
 * program asked for, then Java modified UTF-8 (U+0000 as C0 80, so the stream
 * can end with a 0 byte).
 *
 * This is a mechanism only. Trimming, whitespace handling and every word list
 * live in brainfuck/src/vocabulary.bfa.
 */
public final class TextEncoder {

    private TextEncoder() {
    }

    public static String transform(String value, int transform) {
        return transform == BfAbi.TX_LOWER_ROOT ? value.toLowerCase(Locale.ROOT) : value;
    }

    /** Number of bytes {@link #encode} writes for {@code value}. */
    public static int encodedLength(CharSequence value) {
        int length = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != 0 && c < 0x80) {
                length += 1;
            } else if (c < 0x800) {
                length += 2;
            } else {
                length += 3;
            }
        }
        return length;
    }

    /**
     * Writes value[from..] into out starting at offset, stopping when out is
     * full. Returns the index of the first char not written.
     */
    public static int encode(CharSequence value, int from, byte[] out, int offset, int[] written) {
        int pos = offset;
        int i = from;
        for (; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c != 0 && c < 0x80) {
                if (pos + 1 > out.length) {
                    break;
                }
                out[pos++] = (byte) c;
            } else if (c < 0x800) {
                if (pos + 2 > out.length) {
                    break;
                }
                out[pos++] = (byte) (0xC0 | (c >> 6));
                out[pos++] = (byte) (0x80 | (c & 0x3F));
            } else {
                if (pos + 3 > out.length) {
                    break;
                }
                out[pos++] = (byte) (0xE0 | (c >> 12));
                out[pos++] = (byte) (0x80 | ((c >> 6) & 0x3F));
                out[pos++] = (byte) (0x80 | (c & 0x3F));
            }
        }
        written[0] = pos - offset;
        return i;
    }

    /** Whole-string convenience used by tests and cold paths. */
    public static byte[] encode(CharSequence value) {
        byte[] out = new byte[encodedLength(value)];
        encode(value, 0, out, 0, new int[1]);
        return out;
    }
}
