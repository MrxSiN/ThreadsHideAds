package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;

import org.junit.Test;

import my.github.MrxSiN.threadshideads.legacy.AdFieldSignals;
import my.github.MrxSiN.threadshideads.legacy.AdLabelVocabulary;
import my.github.MrxSiN.threadshideads.legacy.LegacyRules;

/** Documented v1.2.1 behavior, pinned so the frozen copies cannot drift. */
public class LegacyBaselineTest {

    @Test
    public void unitTypesAreMatchedWhole() {
        assertTrue(AdFieldSignals.isAdUnitType(" AD4AD "));
        assertTrue(AdFieldSignals.isAdUnitType("intent_aware_ad_pivot"));
        assertFalse(AdFieldSignals.isAdUnitType("SUGGESTED_USERS"));
        assertFalse(AdFieldSignals.isAdUnitType("THREAD"));
    }

    @Test
    public void labelsAreMatchedWholeAfterNormalization() {
        assertTrue(AdLabelVocabulary.isAdLabel("Sponsored ·"));
        assertTrue(AdLabelVocabulary.isAdLabel("  PAID   partnership "));
        assertTrue(AdLabelVocabulary.isAdLabel("Ad "));
        assertFalse(AdLabelVocabulary.isAdLabel("an ad for shoes"));
    }

    @Test
    public void retryScheduleIsBounded() {
        LegacyRules.RetryModel model = new LegacyRules.RetryModel();
        for (int retry = 0; retry <= 3; retry++) {
            model.request(true, retry);
            model.finish(false, retry);
        }
        assertEquals(Arrays.asList(
                "attempt 1", "failed", "retry 1 in 250",
                "attempt 2", "failed", "retry 2 in 1000",
                "attempt 3", "failed", "retry 3 in 3000",
                "attempt 4", "failed", "exhausted"), model.events);
    }
}
