package my.github.MrxSiN.threadshideads.parity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

import my.github.MrxSiN.threadshideads.bridge.BfAbi;
import my.github.MrxSiN.threadshideads.legacy.AdFieldSignals;
import my.github.MrxSiN.threadshideads.legacy.AdLabelVocabulary;
import my.github.MrxSiN.threadshideads.legacy.LegacyRules;
import my.github.MrxSiN.threadshideads.legacy.Reflection;

/**
 * Text behavior vectors: a corpus of strings per matcher with the verdict the
 * frozen v1.2.1 code gives. Shared by the JVM parity tests and, through
 * build/vectors/text.tsv, by the Python reference/AOT differential tests.
 */
public final class TextVectors {

    public static final class Vector {
        public final int matcher;
        public final int transform;
        public final String text;
        public final boolean expected;

        Vector(int matcher, int transform, String text, boolean expected) {
            this.matcher = matcher;
            this.transform = transform;
            this.text = text;
            this.expected = expected;
        }
    }

    private static final String DECORATION_CHARS =
            " \t\n\u000b\f\r\u001c\u0000\u0001\u0008\u000e\u001b ·•-–—|,: 　  _$.=\"{}";

    private TextVectors() {
    }

    public static List<Vector> all() {
        List<Vector> vectors = new ArrayList<>();
        Random random = new Random(20260925L);

        add(vectors, BfAbi.MT_UNIT_TYPE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "ad", "ad4ad", "intent_aware_ad_pivot", "stand_alone_multi_ad_pivot",
                "ads_feedback_interface", "ads_feedback_interface_repetition",
                "ads_feedback_interface_interests_picker", "THREAD", "SUGGESTED_USERS", "NETEGO_ad")));
        add(vectors, BfAbi.MT_STRUCTURED_VALUE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "ad", "sponsored", "promotion", "advertisement", "direct_ad", "directad",
                "multi_ads", "multiads", "quick_promotion", "quickpromotion",
                "{\"is_sponsored\":true}", "x is_sponsored = true y", "IsSponsored=True",
                "{\"direct_ad\" : true}", "direct_ad=true", "\"sponsored_label\":\"x\"",
                "sponsored_label=1", "{\"ad_metadata\":{}}", "{\"sponsored_metadata\": {",
                "{\"ad_id\":\"9\"}", "ad_id=4", "{\"is_paid_partnership\":true}",
                "{\"is_sponsored\":false}", "a caption about sponsored things", "ad_i d=")));
        add(vectors, BfAbi.MT_FIELD_NAME, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "isSponsored", "is_sponsored", "sponsoredLabel", "directAd", "adId", "ad_id",
                "adType", "adUnit", "adChoices", "isAdvertisement", "adMetadata",
                "sponsoredMetadata", "commercialContent", "$adid$", "a_d_i_d", "adIdx", "id")));
        add(vectors, BfAbi.MT_STOP_VALUE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "", "false", "none", "null", "organic", "unknown", "0", "true", "1", "00",
                "FALSE", "nonee", "promoted")));
        add(vectors, BfAbi.MT_CLASS_TOKEN, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "X.MultiAdsFeedUnit", "com.instagram.QuickPromotion$1", "X.SponsoredItem",
                "X.AdFeedUnit", "X.AdvertisementUnit", "X.DirectAdModel", "X.0hJ", "X.Adfeed")));
        add(vectors, BfAbi.MT_APP_MODEL, BfAbi.TX_RAW, corpus(random, words(
                "X.0hJ", "p000X.Foo", "com.instagram.barcelona.Feed", "com.facebook.pando.TreeJNI",
                "com.meta.Thing", "x.lower", "java.util.ArrayList", "X", "com.instagramx.A",
                "com.meta")));
        add(vectors, BfAbi.MT_PANDO_TREE_CLASS, BfAbi.TX_RAW, corpus(random, words(
                "com.facebook.pando.TreeJNI", "com.facebook.pando.TreeJNIx",
                "com.facebook.pando.TreeWithGraphQL")));
        add(vectors, BfAbi.MT_AD_LABEL, BfAbi.TX_LOWER_ROOT, corpus(random, labelWords()));
        add(vectors, BfAbi.MT_NON_FEED_SIGNATURE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "public java.util.List X.A.b(java.util.List)", "void X.Messaging.a(java.util.List)",
                "X.ExoPlayer", "X.direct.store.Foo", "X.Upload", "X.Music", "X.feed")));
        add(vectors, BfAbi.MT_BOOLEAN_NEGATIVE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "X.A.hideAd", "X.Remove.b", "X.filter", "X.Block", "X.A.isSponsored")));
        add(vectors, BfAbi.MT_BOOLEAN_POSITIVE, BfAbi.TX_LOWER_ROOT, corpus(random, words(
                "X.A.isSponsored", "X.isDirectAd", "X.shouldShowAd", "X.shouldInsertAd",
                "X.canInsertAd", "X.adsEnabled", "X.adEligible", "X.isAd")));
        add(vectors, BfAbi.MT_THREADS_PACKAGE, BfAbi.TX_RAW, corpus(random, words(
                "com.instagram.barcelona", "com.instagram.barcelona:mqtt", "com.instagram.android")));
        add(vectors, BfAbi.MT_LIST_CONTAINER, BfAbi.TX_RAW, corpus(random, words(
                "androidx.recyclerview.widget.RecyclerView", "android.widget.ListView",
                "android.widget.AbsListView", "android.widget.FrameLayout", "X.recyclerview")));
        add(vectors, BfAbi.MT_DECOR_VIEW, BfAbi.TX_RAW, corpus(random, words(
                "com.android.internal.policy.DecorView", "X.Decorview", "android.view.View")));
        return vectors;
    }

    /** The v1.2.1 verdict for one matcher. */
    public static boolean legacy(int matcher, String text) {
        switch (matcher) {
            case BfAbi.MT_UNIT_TYPE:
                return AdFieldSignals.isAdUnitType(text);
            case BfAbi.MT_STRUCTURED_VALUE:
                return AdFieldSignals.isStructuredAdValue(text);
            case BfAbi.MT_FIELD_NAME:
                return AdFieldSignals.isStrongAdFieldName(text);
            case BfAbi.MT_STOP_VALUE:
                return !AdFieldSignals.isAdFieldValue(text);
            case BfAbi.MT_CLASS_TOKEN:
                return AdFieldSignals.hasStrongAdClassToken(text.toLowerCase(Locale.ROOT));
            case BfAbi.MT_APP_MODEL:
                return isAppModelClass(text);
            case BfAbi.MT_PANDO_TREE_CLASS:
                return LegacyRules.PANDO_TREE_CLASS.equals(text);
            case BfAbi.MT_AD_LABEL:
                return AdLabelVocabulary.isAdLabel(text);
            case BfAbi.MT_NON_FEED_SIGNATURE:
                return containsAny(text.toLowerCase(Locale.ROOT), LegacyRules.NON_FEED_SIGNATURE_TOKENS);
            case BfAbi.MT_BOOLEAN_NEGATIVE:
                return containsAny(text.toLowerCase(Locale.ROOT), "hide", "remove", "filter", "block");
            case BfAbi.MT_BOOLEAN_POSITIVE:
                return containsAny(text.toLowerCase(Locale.ROOT), "issponsored", "isdirectad",
                        "shouldshowad", "shouldinsertad", "caninsertad", "adsenabled", "adeligible");
            case BfAbi.MT_THREADS_PACKAGE:
                return LegacyRules.THREADS_PACKAGE.equals(text);
            case BfAbi.MT_LIST_CONTAINER:
                return text.contains("RecyclerView") || text.contains("ListView")
                        || text.contains("AbsListView");
            case BfAbi.MT_DECOR_VIEW:
                return text.contains("DecorView");
            default:
                throw new IllegalArgumentException("matcher " + matcher);
        }
    }

    private static boolean isAppModelClass(String name) {
        try {
            Method method = Reflection.class.getDeclaredMethod("isAppModelClass", String.class);
            method.setAccessible(true);
            return (Boolean) method.invoke(null, name);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static boolean containsAny(String text, String... tokens) {
        for (String token : tokens) {
            if (text.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static void add(List<Vector> out, int matcher, int transform, Set<String> corpus) {
        for (String text : corpus) {
            out.add(new Vector(matcher, transform, text, legacy(matcher, text)));
        }
    }

    private static List<String> words(String... words) {
        return Arrays.asList(words);
    }

    private static List<String> labelWords() {
        return words("ad", "Ads", "Sponsored", "promoted", "advertisement", "paid partnership",
                "Paid  Partnership", "paid\tpartnership", "iklan", "ditaja", "disponsori",
                "bersponsor", "anuncio", "patrocinado", "patrocinada", "publicidad", "publicidade",
                "annonce", "annons", "annonse", "annuncio", "anzeige", "gesponsert", "gesponsord",
                "sponsrad", "sponsorisé", "SPONSORISÉ", "sponsorizzato", "sponsorowane",
                "РЕКЛАМА", "広告", "광고",
                "广告", "赞助", "an ad for shoes", "adx", "paid  partner");
    }

    /**
     * Every base word, the base word inside decorations that exercise the
     * normalization rules, and seeded random strings over the same alphabet.
     */
    private static Set<String> corpus(Random random, List<String> base) {
        Set<String> out = new LinkedHashSet<>();
        String[] wraps = {
                "%s", " %s ", "\t%s\n", "%s ·", "· %s", "%s x", "x%s", "%sx",
                "%s\u0000", "\u0000%s", " %s ", "%s ", "-%s-", "%s\u0001·",
                "·\u0001%s", "%s·\u0001·", "\u0001 \u0001%s", " \u0001%s",
                "|%s|", "%s:", ", %s ,", "— %s —", "%s ", "　%s", "%s\u0008",
                "_%s_", "$%s", "\"%s\"", "%s%s"
        };
        for (String word : base) {
            for (String wrap : wraps) {
                out.add(String.format(Locale.ROOT, wrap, word, word));
            }
            out.add(word.toUpperCase(Locale.ROOT));
            out.add(word.replace(' ', ' '));
            out.add(word.replace("_", ""));
            out.add(word.replace("\"", ""));
            if (word.length() > 1) {
                out.add(word.substring(1));
                out.add(word.substring(0, word.length() - 1));
                out.add(word.charAt(0) + " " + word.substring(1));
            }
        }
        StringBuilder alphabet = new StringBuilder(DECORATION_CHARS);
        for (String word : base) {
            alphabet.append(word);
        }
        for (int i = 0; i < 300; i++) {
            StringBuilder text = new StringBuilder();
            if (random.nextBoolean()) {
                text.append(base.get(random.nextInt(base.size())));
            }
            int extra = random.nextInt(8);
            for (int k = 0; k < extra; k++) {
                int at = random.nextInt(text.length() + 1);
                text.insert(at, alphabet.charAt(random.nextInt(alphabet.length())));
            }
            out.add(text.toString());
        }
        return out;
    }
}
