package io.github.ofdcli;

import io.github.ofdcli.util.FilesUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link FilesUtil}.
 */
class FilesUtilTest {

    @Test
    void collectFromDirectory(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("a.ofd"));
        Files.createFile(tmp.resolve("b.ofd"));
        Files.createFile(tmp.resolve("c.txt"));
        Files.createDirectory(tmp.resolve("subdir"));

        List<Path> collected = FilesUtil.collectOfdFiles(tmp);
        assertEquals(2, collected.size());
        assertTrue(collected.stream().allMatch(p -> p.getFileName().toString().endsWith(".ofd")));
    }

    @Test
    void collectSingleFile(@TempDir Path tmp) throws IOException {
        Path f = tmp.resolve("single.ofd");
        Files.createFile(f);
        List<Path> collected = FilesUtil.collectOfdFiles(f);
        assertEquals(List.of(f), collected);
    }

    @Test
    void collectFromMissingPath(@TempDir Path tmp) {
        assertTrue(FilesUtil.collectOfdFiles(tmp.resolve("does-not-exist")).isEmpty());
    }

    @Test
    void collectIgnoresNonOfdFiles(@TempDir Path tmp) throws IOException {
        Files.createFile(tmp.resolve("readme.txt"));
        Files.createFile(tmp.resolve("image.png"));
        assertTrue(FilesUtil.collectOfdFiles(tmp).isEmpty());
    }

    @Test
    void stripOfdExt() {
        assertEquals("invoice", FilesUtil.stripOfdExt("invoice.ofd"));
        assertEquals("invoice", FilesUtil.stripOfdExt("invoice.OFD"));
        assertEquals("no-ext", FilesUtil.stripOfdExt("no-ext"));
    }

    @Test
    void uniquePath(@TempDir Path tmp) throws IOException {
        Path first = tmp.resolve("x.pdf");
        Files.createFile(first);
        Path second = FilesUtil.uniquePath(first);
        assertEquals(tmp.resolve("x-1.pdf"), second);
    }

    @Test
    void nativeImageDetection() {
        // We're either on JVM (no flag) or native-image (flag set). Either
        // way the method must not throw and must return a boolean.
        boolean isNative = FilesUtil.isNativeImage();
        // Sanity: JVM mode is the only path we run this test under (mvn test).
        assertFalse(isNative, "Expected JVM test environment, got native-image");
    }
}
