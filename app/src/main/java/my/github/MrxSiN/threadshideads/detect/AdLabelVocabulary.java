package my.github.MrxSiN.threadshideads.detect;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * The vocabulary of rendered advertisement labels and the normalization applied
 * before matching.
 *
 * Matching is whole-label only. A caption that merely contains one of these
 * words is never treated as a label, which is what keeps very short entries
 * such as "ad" safe to include.
 */
public final class AdLabelVocabulary {

    /**
     * Separators Threads renders around a label, for example "Sponsored ·".
     */
    private static final String LABEL_SEPARATORS = "·•-–—|,:";

    private static final Set<String> AD_LABELS =
            Collections.unmodifiableSet(new LinkedHashSet<>(Arrays.asList(
                    // English. Threads renders the short "Ad" badge next to the
                    // author name on the first feed page after a cold launch.
                    "ad",
                    "ads",
                    "sponsored",
                    "promoted",
                    "advertisement",
                    "paid partnership",

                    // Malay
                    "iklan",
                    "ditaja",

                    // Indonesian
                    "disponsori",
                    "bersponsor",

                    // Common additional locales
                    "anuncio",
                    "patrocinado",
                    "patrocinada",
                    "publicidad",
                    "publicidade",
                    "annonce",
                    "annons",
                    "annonse",
                    "annuncio",
                    "anzeige",
                    "gesponsert",
                    "gesponsord",
                    "sponsrad",
                    "sponsorisé",
                    "sponsorizzato",
                    "sponsorowane",
                    "реклама",
                    "広告",
                    "광고",
                    "广告",
                    "赞助"
            )));

    private AdLabelVocabulary() {
    }

    public static boolean isAdLabel(CharSequence value) {
        if (value == null) {
            return false;
        }

        return AD_LABELS.contains(normalize(value));
    }

    /**
     * Lowercases, collapses whitespace, and strips decorative separators so
     * "Sponsored ·" and "  SPONSORED " both reduce to "sponsored".
     */
    public static String normalize(CharSequence value) {
        String input = value.toString().trim().toLowerCase(Locale.ROOT);
        StringBuilder output = new StringBuilder(input.length());
        boolean pendingSpace = false;

        for (int i = 0; i < input.length(); i++) {
            char character = input.charAt(i);

            if (Character.isWhitespace(character)) {
                pendingSpace = output.length() > 0;
                continue;
            }

            if (pendingSpace) {
                output.append(' ');
                pendingSpace = false;
            }

            output.append(character);
        }

        return trimSeparators(output);
    }

    private static String trimSeparators(StringBuilder value) {
        int start = 0;
        int end = value.length();

        while (start < end && isSeparator(value.charAt(start))) {
            start++;
        }

        while (end > start && isSeparator(value.charAt(end - 1))) {
            end--;
        }

        return value.substring(start, end).trim();
    }

    private static boolean isSeparator(char character) {
        return Character.isWhitespace(character)
                || LABEL_SEPARATORS.indexOf(character) >= 0;
    }
}
