package my.github.MrxSiN.threadshideads.detect;

/**
 * Decides whether a single feed model object is an advertisement.
 *
 * The list sanitizers depend on this abstraction rather than on the reflective
 * implementation, so a cheaper or a more specific detector can be substituted
 * without touching the sanitizers.
 */
public interface AdDetector {

    boolean looksLikeAd(Object value);
}
