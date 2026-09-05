package my.github.MrxSiN.threadshideads.detect;

import java.util.Locale;

/**
 * Structural advertisement signals found inside feed model objects: class name
 * tokens, field names, field values, and structured toString/JSON fragments.
 */
public final class AdFieldSignals {

    private AdFieldSignals() {
    }

    public static boolean hasStrongAdClassToken(String className) {
        return className.contains("multiadsfeedunit")
                || className.contains("quickpromotion")
                || className.contains("sponsored")
                || className.contains("adfeedunit")
                || className.contains("advertisementunit")
                || className.contains("directad");
    }

    public static boolean isStrongAdFieldName(String fieldName) {
        String normalized = fieldName
                .toLowerCase(Locale.ROOT)
                .replace("_", "")
                .replace("-", "")
                .replace("$", "");

        return normalized.equals("issponsored")
                || normalized.equals("sponsoredlabel")
                || normalized.equals("directad")
                || normalized.equals("adid")
                || normalized.equals("adtype")
                || normalized.equals("adunit")
                || normalized.equals("adchoices")
                || normalized.equals("isadvertisement")
                || normalized.equals("admetadata")
                || normalized.equals("sponsoredmetadata")
                || normalized.equals("commercialcontent");
    }

    public static boolean isAdFieldValue(Object value) {
        if (value == null) {
            return false;
        }

        if (value instanceof Boolean) {
            return (Boolean) value;
        }

        if (value instanceof Number) {
            return ((Number) value).longValue() != 0L;
        }

        if (value instanceof CharSequence) {
            String normalized = value.toString()
                    .trim()
                    .toLowerCase(Locale.ROOT);

            return !normalized.isEmpty()
                    && !normalized.equals("false")
                    && !normalized.equals("none")
                    && !normalized.equals("null")
                    && !normalized.equals("organic")
                    && !normalized.equals("unknown")
                    && !normalized.equals("0");
        }

        if (value instanceof Enum<?>) {
            return isStructuredAdValue(((Enum<?>) value).name());
        }

        /*
         * Non-null ad-unit/model field.
         */
        return true;
    }

    public static boolean isStructuredAdValue(String value) {
        if (value == null) {
            return false;
        }

        String normalized = value.trim().toLowerCase(Locale.ROOT);
        String compact = normalized.replace(" ", "");

        return normalized.equals("sponsored")
                || normalized.equals("promotion")
                || normalized.equals("advertisement")
                || normalized.equals("direct_ad")
                || normalized.equals("directad")
                || normalized.equals("multi_ads")
                || normalized.equals("multiads")
                || normalized.equals("quick_promotion")
                || normalized.equals("quickpromotion")
                || compact.contains("\"is_sponsored\":true")
                || compact.contains("is_sponsored=true")
                || compact.contains("issponsored=true")
                || compact.contains("\"direct_ad\":true")
                || compact.contains("direct_ad=true")
                || compact.contains("\"sponsored_label\":")
                || compact.contains("sponsored_label=")
                || compact.contains("\"ad_metadata\":{")
                || compact.contains("\"sponsored_metadata\":{")
                || compact.contains("\"ad_id\":\"")
                || compact.contains("ad_id=")
                || compact.contains("\"is_paid_partnership\":true");
    }
}
