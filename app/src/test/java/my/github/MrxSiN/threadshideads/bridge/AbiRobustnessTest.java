package my.github.MrxSiN.threadshideads.bridge;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import org.junit.Test;

import X.AdModel;

/** Malformed frames, fuzzed payloads, stale handles and re-entrancy. */
public class AbiRobustnessTest {

    private static NativeCore core() {
        Policy policy = TestCore.policy();
        try {
            java.lang.reflect.Field field = Policy.class.getDeclaredField("coreEngine");
            field.setAccessible(true);
            return (NativeCore) field.get(policy);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** Sends a raw frame; send() rewrites only the length field. */
    private static int send(NativeCore core, byte[] frame) {
        ByteBuffer out = core.begin(frame[2] & 0xFF);
        out.position(0);
        out.put(frame);
        try {
            return core.send();
        } finally {
            core.end();
        }
    }

    @Test
    public void rejectsBadVersionAndOpcode() {
        NativeCore core = core();
        int[] opcodes = {BfAbi.OP_VERSION, BfAbi.OP_DETECT, BfAbi.OP_SANITIZE_LIST, BfAbi.OP_NESTED_SANITIZE,
                BfAbi.OP_PANDO_FILTER, BfAbi.OP_TEXT_MATCH};
        for (int opcode : opcodes) {
            byte[] frame = {2, 0, (byte) opcode, 0, 0, 0, 1, 0};
            assertEquals("op " + opcode, BfAbi.ST_BAD_VERSION, send(core, frame));
        }
        // An opcode the program does not serve.
        assertEquals(BfAbi.ST_BAD_OPCODE, send(core, new byte[]{1, 0, 0x2D, 0, 0, 0, 1, 0}));
        // No program serves this opcode at all: the runtime refuses it.
        assertEquals(-1, send(core, new byte[]{1, 0, 0x7F, 0, 0, 0, 1, 0}));
    }

    @Test
    public void fuzzedFramesNeverBreakTheCore() {
        NativeCore core = core();
        Random random = new Random(99);
        int[] opcodes = new int[0x3A];
        for (int i = 0; i < opcodes.length; i++) {
            opcodes[i] = i + 1;
        }
        Object[] objects = {new ArrayList<>(Arrays.asList("a", 1)), "text", 5, new AdModel(), new int[3],
                new Object(), null, Arrays.asList(new AdModel())};
        for (int run = 0; run < 4000; run++) {
            int opcode = opcodes[random.nextInt(opcodes.length)];
            if (opcode == BfAbi.OP_RESET) {
                continue;
            }
            ByteBuffer out = core.begin(opcode);
            int handles = random.nextInt(3);
            for (int i = 0; i < handles; i++) {
                out.putInt(core.host().handles.put(objects[random.nextInt(objects.length)]));
            }
            int noise = random.nextInt(40);
            for (int i = 0; i < noise; i++) {
                // Random handles too: most are stale or out of range.
                out.put((byte) random.nextInt(256));
            }
            if (opcode == BfAbi.OP_TEXT_MATCH) {
                out.put(3, (byte) BfAbi.FLAG_STREAM);
                out.put((byte) 0);
            }
            if (opcode == BfAbi.OP_BOUNDARY_BEFORE || opcode == BfAbi.OP_BOUNDARY_AFTER) {
                core.host().frame = new FixedFrame(objects[random.nextInt(objects.length)]);
            }
            try {
                int status = core.send();
                assertTrue("op " + opcode + " status " + status, status >= -1 && status <= BfAbi.ST_NOT_FOUND);
            } finally {
                core.end();
            }
        }
        // Still healthy afterwards.
        Policy policy = TestCore.policy();
        assertEquals(BfAbi.ABI_MAJOR, policy.version());
        AdModel ad = new AdModel();
        ad.isSponsored = true;
        assertTrue(policy.looksLikeAd(ad));
    }

    @Test
    public void staleHandlesAreRejected() {
        HandleTable table = new HandleTable();
        int first = table.put("a");
        assertTrue(table.valid(first));
        table.reset();
        assertFalse(table.valid(first));
        int second = table.put("b");
        // Same index, new generation: the old handle cannot name the new object.
        assertEquals(first & 0xFFFF, second & 0xFFFF);
        assertFalse(first == second);
        int child = table.put("c");
        table.releaseFrom(child);
        assertFalse(table.valid(child));
        assertTrue(table.valid(second));
        assertEquals(0, table.put(null));
        assertNull(table.get(0));
    }

    @Test
    public void nestedRequestsRunOnTheirOwnEngine() {
        final Policy policy = TestCore.policy();
        final boolean[] inner = new boolean[1];
        final int[] depth = new int[1];
        final AdModel ad = new AdModel();
        ad.isSponsored = true;
        // Reading the argument runs app code that fires another hook, which
        // itself fires a third one: two nested levels run, deeper fails open.
        FixedFrame frame = new FixedFrame(new ArrayList<Object>(Arrays.asList(ad, "organic"))) {
            @Override
            public Object argument(int index) {
                inner[0] = policy.looksLikeAd(new com.instagram.model.Computed(() -> {
                    depth[0]++;
                    return policy.looksLikeAd(ad) ? "{\"is_sponsored\":true}" : "organic";
                }));
                return super.argument(index);
            }
        };
        List<Policy.Report> reports = policy.boundaryBefore(frame);
        // Level 2 (looksLikeAd from argument()) ran and called toString; level 3
        // (from inside toString) exceeded MAX_NESTING and failed open.
        assertEquals("the second level ran", 1, depth[0]);
        assertFalse("the third level fails open", inner[0]);
        assertEquals(1, reports.size());
        assertEquals(1, ((List<?>) frame.value).size());
        assertTrue(policy.looksLikeAd(ad));
    }

    @Test
    public void concurrentRequestsDoNotBlockEachOther() throws Exception {
        final Policy policy = TestCore.policy();
        final AdModel ad = new AdModel();
        ad.isSponsored = true;
        final java.util.concurrent.CountDownLatch inside = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        // Thread A sits inside a capability (app code) until thread B finishes.
        Thread a = new Thread(() -> policy.looksLikeAd(new com.instagram.model.Computed(() -> {
            inside.countDown();
            done.await(5, java.util.concurrent.TimeUnit.SECONDS);
            return "organic";
        })));
        a.start();
        assertTrue(inside.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("B must not wait for A", policy.looksLikeAd(ad));
        done.countDown();
        a.join(5000);
    }

    @Test
    public void largeTextStreamsAcrossChunks() {
        Policy policy = TestCore.policy();
        StringBuilder big = new StringBuilder();
        while (big.length() < 60000) {
            big.append("organic text é中 ");
        }
        String tail = big + "{\"is_sponsored\":true}";
        com.instagram.model.Structured s = new com.instagram.model.Structured(tail);
        assertTrue(policy.looksLikeAd(s));
        assertFalse(policy.looksLikeAd(new com.instagram.model.Structured(big.toString())));
    }

    static class FixedFrame implements CallFrame {
        Object value;
        Object result;

        FixedFrame(Object value) {
            this.value = value;
        }

        @Override
        public int argumentCount() {
            return 1;
        }

        @Override
        public Object argument(int index) {
            return value;
        }

        @Override
        public void setArgument(int index, Object value) {
            this.value = value;
        }

        @Override
        public Class<?> parameterType(int index) {
            return List.class;
        }

        @Override
        public Class<?> returnType() {
            return Object.class;
        }

        @Override
        public Object result() {
            return result;
        }

        @Override
        public void setResult(Object value) {
            result = value;
        }
    }
}
