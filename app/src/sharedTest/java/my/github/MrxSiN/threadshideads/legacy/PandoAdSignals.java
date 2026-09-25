package my.github.MrxSiN.threadshideads.legacy;

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;


/**
 * Decides whether a single Pando tree is a sponsored feed item.
 *
 * The tree JSON is the only Java-side view of a Pando model, and producing it
 * serializes the whole subtree, so every verdict is cached against the tree
 * that produced it. The same trees are handed out repeatedly by the response
 * accessors, so the cache removes most of the cost.
 */
public final class PandoAdSignals {

    /*
     * A single feed item serializes well below this. A whole feed page, or a
     * ranking container that owns many items, serializes far above it, and
     * dropping one of those would take organic posts with it. Oversized trees
     * are therefore reported as not sponsored and left to the boundary layer.
     */
    private static final int MAX_ITEM_JSON_LENGTH = 65536;

    /**
     * Weak keys, so caching a verdict never keeps a feed model alive.
     */
    private static final Map<Object, Boolean> VERDICTS =
            Collections.synchronizedMap(new WeakHashMap<Object, Boolean>());

    private PandoAdSignals() {
    }

    public static boolean isSponsoredTree(Object value) {
        if (!PandoTrees.isPandoTree(value)) {
            return false;
        }

        Boolean cached = readCache(value);

        if (cached != null) {
            return cached;
        }

        boolean sponsored = evaluate(value);

        writeCache(value, sponsored);

        return sponsored;
    }

    private static boolean evaluate(Object value) {
        String json = PandoTrees.readJson(value);

        if (json == null) {
            return false;
        }

        if (json.length() > MAX_ITEM_JSON_LENGTH) {
            Diagnostics.traceOversizedTree(value.getClass(), json.length());
            return false;
        }

        if (!AdFieldSignals.isStructuredAdValue(json)) {
            /*
             * Report any sponsored-looking key the vocabulary did not match, so
             * a renamed marker shows up in the log instead of silently letting
             * an advertisement through.
             */
            ModelProbes.traceTreeKeys(value.getClass(), json);
            return false;
        }

        Diagnostics.traceAdMatch(value.getClass(), "pando tree json");
        return true;
    }

    private static Boolean readCache(Object value) {
        try {
            return VERDICTS.get(value);
        } catch (Throwable ignored) {
            /*
             * A model may implement equals or hashCode over its native tree.
             * An unusable cache must not stop detection.
             */
            return null;
        }
    }

    private static void writeCache(Object value, boolean sponsored) {
        try {
            VERDICTS.put(value, sponsored);
        } catch (Throwable ignored) {
            // Detection still works without the cache.
        }
    }
}
