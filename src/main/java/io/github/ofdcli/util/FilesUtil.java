package io.github.ofdcli.util;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * File enumeration utilities shared by every subcommand that accepts
 * an {@code INPUT} positional (file or directory).
 */
public final class FilesUtil {

    private FilesUtil() {}

    /**
     * Collect OFD files from {@code in}. If {@code in} is a regular file
     * with a {@code .ofd} suffix, returns it as a single-element list. If
     * it is a directory, returns all {@code *.ofd} regular files inside
     * (non-recursive, sorted by name). Returns an empty list if nothing
     * matches or the path doesn't exist.
     */
    public static List<Path> collectOfdFiles(Path in) {
        if (in == null || !Files.exists(in)) {
            return List.of();
        }
        if (Files.isRegularFile(in)) {
            if (in.getFileName().toString().toLowerCase().endsWith(".ofd")) {
                return List.of(in);
            }
            return List.of();
        }
        try (Stream<Path> stream = Files.list(in)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ofd"))
                    .sorted()
                    .collect(Collectors.toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Strip the trailing {@code .ofd} (case-insensitive) from a filename.
     */
    public static String stripOfdExt(String filename) {
        return filename.replaceAll("(?i)\\.ofd$", "");
    }

    /**
     * Compute a non-colliding output file path. If {@code dest} already
     * exists, append {@code -1}, {@code -2}, … before the extension.
     */
    public static Path uniquePath(Path dest) {
        if (!Files.exists(dest)) return dest;
        String name = dest.getFileName().toString();
        Path parent = dest.getParent();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            Path candidate = parent.resolve(base + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
        return dest;
    }

    /**
     * Check whether the JVM is a GraalVM native-image. Used by every
     * subcommand to decide whether to (a) re-scan system font directories
     * (JVM fat-jar path) or (b) trust the {@code NativeImageFontBootstrap}
     * that already ran in {@code Main.main()} (native binary).
     */
    public static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null
                || "Substrate VM".equals(System.getProperty("java.vm.name"));
    }

    public static boolean isDirectory(Path p) {
        return p != null && Files.isDirectory(p);
    }

    public static boolean isRegularFile(Path p) {
        return p != null && Files.isRegularFile(p);
    }

    public static void ensureDirectory(Path dir) throws java.io.IOException {
        if (dir != null && !Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    public static File toFile(Path p) {
        return p == null ? null : p.toFile();
    }
}
