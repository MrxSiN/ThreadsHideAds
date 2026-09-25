package my.github.MrxSiN.threadshideads.parity;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

import my.github.MrxSiN.threadshideads.bridge.TestCore;

/** Writes build/benchmark/host.txt; see docs/PERFORMANCE.md. */
public class HostBenchmarkTest {

    @Test
    public void hotPath() throws Exception {
        String report = HotPathBenchmark.run(TestCore.policy(), 20);
        File out = new File("build/benchmark/host.txt");
        Files.createDirectories(out.getParentFile().toPath());
        Files.write(out.toPath(), report.getBytes(StandardCharsets.UTF_8));
        System.out.print(report);
    }
}
