package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import my.github.MrxSiN.threadshideads.bridge.BfAbi;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.bridge.TestCore;

public class CoreSmokeTest {

    @Test
    public void versionAndText() {
        Policy policy = TestCore.policy();
        assertEquals(BfAbi.ABI_MAJOR, policy.version());
        assertTrue(policy.textMatch(BfAbi.MT_UNIT_TYPE, BfAbi.TX_LOWER_ROOT, " AD4AD "));
        assertFalse(policy.textMatch(BfAbi.MT_UNIT_TYPE, BfAbi.TX_LOWER_ROOT, "THREAD"));
        assertEquals("Sponsored", policy.constString(Policy.TABLE_NAMES, Policy.NAME_PRIMARY_MARKER));
        assertEquals(15, policy.constTable(Policy.TABLE_FEED_OWNERS).size());
    }
}
