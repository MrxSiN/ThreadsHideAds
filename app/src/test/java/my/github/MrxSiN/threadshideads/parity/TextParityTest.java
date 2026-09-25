package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.bridge.TestCore;

/** Every text vector through the JNI core against the frozen v1.2.1 verdict. */
public class TextParityTest {

    @Test
    public void allMatchersMatchLegacy() {
        Policy policy = TestCore.policy();
        List<String> failures = new ArrayList<>();
        List<TextVectors.Vector> vectors = TextVectors.all();
        for (TextVectors.Vector v : vectors) {
            boolean actual = policy.textMatch(v.matcher, v.transform, v.text);
            if (actual != v.expected) {
                failures.add(v.matcher + ":" + v.text);
            }
        }
        assertEquals(vectors.size() + " vectors", "[]", failures.toString());
    }
}
