package my.github.MrxSiN.threadshideads.parity;

import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.Test;

import my.github.MrxSiN.threadshideads.bridge.TextEncoder;

/**
 * Writes the text vectors as the exact byte streams the host sends, for
 * tests/compiler/test_text_parity.py:
 * {@code matcher <TAB> expected <TAB> hex(transformed modified UTF-8)}.
 */
public class TextVectorExportTest {

    @Test
    public void exportVectors() throws IOException {
        List<TextVectors.Vector> vectors = TextVectors.all();
        File out = new File("build/vectors/text.tsv");
        Files.createDirectories(out.getParentFile().toPath());
        try (Writer writer = new OutputStreamWriter(Files.newOutputStream(out.toPath()),
                StandardCharsets.US_ASCII)) {
            for (TextVectors.Vector v : vectors) {
                byte[] bytes = TextEncoder.encode(TextEncoder.transform(v.text, v.transform));
                StringBuilder hex = new StringBuilder();
                for (byte b : bytes) {
                    hex.append(String.format("%02x", b & 0xFF));
                }
                writer.write(v.matcher + "\t" + (v.expected ? 1 : 0) + "\t" + hex + "\n");
            }
        }
        assertTrue(vectors.size() > 1000);
    }
}
