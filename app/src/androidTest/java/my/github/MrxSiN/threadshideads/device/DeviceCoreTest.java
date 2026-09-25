package my.github.MrxSiN.threadshideads.device;

import static org.junit.Assert.assertEquals;

import android.util.Log;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import java.util.List;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

import my.github.MrxSiN.threadshideads.bridge.NativeCore;
import my.github.MrxSiN.threadshideads.bridge.Policy;
import my.github.MrxSiN.threadshideads.bridge.ViewAccess;
import my.github.MrxSiN.threadshideads.legacy.ListSanitizer;
import my.github.MrxSiN.threadshideads.legacy.ReflectiveAdDetector;
import my.github.MrxSiN.threadshideads.legacy.SanitizedList;
import my.github.MrxSiN.threadshideads.parity.Graphs;
import my.github.MrxSiN.threadshideads.parity.HotPathBenchmark;

/**
 * The shipped libthreadsbf (NDK, device ABI) against the frozen legacy code,
 * plus the hot-path benchmark. Results are logged under tag ThreadsBfDevice.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceCoreTest {

    private static Policy policy;

    @BeforeClass
    public static void load() {
        policy = new Policy(NativeCore.load(new ViewAccess() {
            @Override
            public boolean isTextView(Object view) {
                return false;
            }

            @Override
            public CharSequence text(Object view) {
                return null;
            }

            @Override
            public CharSequence contentDescription(Object view) {
                return null;
            }

            @Override
            public int childCount(Object view) {
                return -1;
            }

            @Override
            public Object childAt(Object view, int index) {
                return null;
            }
        }));
        policy.configureHost();
    }

    @Test
    public void detectorAndSanitizerMatchLegacyOnDevice() {
        ReflectiveAdDetector detector = new ReflectiveAdDetector();
        ListSanitizer sanitizer = new ListSanitizer(detector);
        for (long seed = 1; seed <= 500; seed++) {
            assertEquals("detect " + seed, detector.looksLikeAd(Graphs.graph(seed)),
                    policy.looksLikeAd(Graphs.graph(seed)));
            List<Object> a = Graphs.feed(seed);
            List<Object> b = Graphs.feed(seed);
            SanitizedList expected = sanitizer.sanitize(a, List.class, true, true);
            Policy.Sanitized actual = policy.sanitizeList(b, List.class, true, true);
            assertEquals("sanitize " + seed, expected.removed, actual.removed);
            assertEquals("sanitize " + seed, Graphs.dump(expected.value), Graphs.dump(actual.value));
        }
    }

    @Test
    public void hotPathBenchmark() {
        String report = HotPathBenchmark.run(policy, 20);
        for (String line : report.split("\n")) {
            Log.i("ThreadsBfDevice", line);
        }
    }
}
