package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.util.FontSetup;
import org.ofdrw.converter.FontLoader;
import org.ofdrw.converter.ImageMaker;
import org.ofdrw.reader.OFDReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Convert OFD file(s) to PNG images.
 *
 * <p>Reuses the font-alias + scanFontDir pattern from
 * {@code OFD2PNGTest.java} so that Chinese-only OFDs (e.g. invoices from
 * various government / 出行 / 电商 platforms) render correctly on
 * macOS / Linux / Windows without manual font setup.
 */
@Command(
        name = "to-png",
        mixinStandardHelpOptions = true,
        description = "Convert OFD file(s) to PNG images.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd to-png invoice.ofd -o out/",
                "  ofd to-png ./ofd_folder/ -o ./png_folder/ --ppm 10",
                "  ofd to-png invoice.ofd -o out/ --font-dir /opt/fonts"
        })
public class ToPngCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file or directory containing OFD files.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR",
            description = "Output directory (default: current directory).",
            defaultValue = ".")
    Path outputDir;

    @Option(names = {"-p", "--ppm"}, paramLabel = "PX_PER_MM",
            description = "Pixels-per-mm for rendering (default: ${DEFAULT-VALUE} ≈ 192 dpi).",
            defaultValue = "7.56")
    double ppm;

    @Option(names = {"--font-dir"}, paramLabel = "DIR",
            description = "Additional font directory to scan (repeatable).")
    List<Path> extraFontDirs;

    @Option(names = {"--no-default-fonts"},
            description = "Skip auto-scanning of default system font directories.")
    boolean noDefaultFonts;

    @Override
    public Integer call() throws Exception {
        setupFontLoader();

        List<Path> ofdFiles = collectOfdFiles(input);
        if (ofdFiles.isEmpty()) {
            System.err.println("Error: no OFD files found at " + input.toAbsolutePath());
            return ExitCode.USAGE_ERROR;
        }
        if (!Files.exists(outputDir)) {
            Files.createDirectories(outputDir);
        }

        long t0 = System.currentTimeMillis();
        List<Map<String, Object>> results = new ArrayList<>();
        int ok = 0;
        int failed = 0;

        for (Path ofd : ofdFiles) {
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("file", ofd.getFileName().toString());
            long s = System.currentTimeMillis();
            try (OFDReader reader = new OFDReader(ofd)) {
                ImageMaker im = new ImageMaker(reader, ppm);
                im.config.setDrawBoundary(false);
                int pages = im.pageSize();
                List<String> written = new ArrayList<>();
                for (int i = 0; i < pages; i++) {
                    BufferedImage img = im.makePage(i);
                    String outName = ofd.getFileName().toString().replaceAll("(?i)\\.ofd$", "")
                            + "_p" + (i + 1) + ".png";
                    Path out = outputDir.resolve(outName);
                    ImageIO.write(img, "PNG", out.toFile());
                    written.add(outName);
                }
                r.put("status", "ok");
                r.put("pages", pages);
                r.put("outputs", written);
                r.put("elapsedMs", System.currentTimeMillis() - s);
                ok++;
                if (!isJson()) {
                    System.out.printf("OK   %s  ->  %d page(s)  %dms%n",
                            ofd.getFileName(), pages, System.currentTimeMillis() - s);
                }
            } catch (Exception e) {
                failed++;
                r.put("status", "error");
                r.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
                r.put("elapsedMs", System.currentTimeMillis() - s);
                System.err.println("FAIL " + ofd.getFileName() + ": " + e.getMessage());
                if (Boolean.getBoolean("ofd.verbose")) {
                    e.printStackTrace(System.err);
                }
            }
            results.add(r);
        }

        long elapsedMs = System.currentTimeMillis() - t0;

        if (isJson()) {
            printJson(ofdFiles.size(), ok, failed, elapsedMs, results);
        } else {
            System.out.printf("%nDone: %d/%d ok in %dms -> %s%n",
                    ok, ofdFiles.size(), elapsedMs, outputDir.toAbsolutePath());
        }

        if (failed == 0) return ExitCode.OK;
        if (failed == ofdFiles.size()) return ExitCode.IO_ERROR;
        return ExitCode.PARTIAL_FAILURE;
    }

    private void setupFontLoader() {
        // Same font bootstrap that ToPdf/ToHtml/ToSvg use. Without this call,
        // FontLoader.getInstance() would invoke init() which scans the system
        // font dirs and triggers java.awt.Toolkit.<clinit> ->
        // System.loadLibrary("awt") -> UnsatisfiedLinkError on native-image
        // (no AWT native library in the substrate VM).
        FontSetup.setup(extraFontDirs, noDefaultFonts);
        FontLoader fl = FontLoader.getInstance();
        // Common Chinese font aliases — OFDs from government / ride-hailing /
        // e-commerce platforms typically reference KaiTi/SimSun/Song/Hei
        // families that are absent on Mac/Linux.
        fl.addAliasMapping("KaiTi_GB2312", "楷体")
                .addAliasMapping("KaiTi", "楷体")
                .addSimilarFontReplaceRegexMapping(".*Kai.*", "楷体")
                .addSimilarFontReplaceRegexMapping(".*SimSun.*", "SimSun")
                .addSimilarFontReplaceRegexMapping(".*Song.*", "宋体")
                .addSimilarFontReplaceRegexMapping(".*Hei.*", "黑体")
                .addSimilarFontReplaceRegexMapping(".*MinionPro.*", "SimSun");
        FontLoader.setSimilarFontReplace(true);

        // Skip extra directory scan on native-image: NativeImageFontBootstrap has
        // already pre-populated the FontLoader. The JVM path benefits from
        // scanning additional dirs.
        if (!noDefaultFonts && !isNativeImage()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                safeScanFontDir("/System/Library/Fonts");
                safeScanFontDir("/System/Library/Fonts/Supplemental");
            } else if (os.contains("win")) {
                safeScanFontDir("C:/Windows/Fonts");
            } else {
                // Linux / others
                safeScanFontDir("/usr/share/fonts");
                safeScanFontDir("/usr/local/share/fonts");
            }
        }
        if (extraFontDirs != null) {
            for (Path d : extraFontDirs) {
                safeScanFontDir(d.toString());
            }
        }
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null
                || "Substrate VM".equals(System.getProperty("java.vm.name"));
    }

    private void safeScanFontDir(String dir) {
        File f = new File(dir);
        if (f.isDirectory()) {
            try {
                FontLoader.getInstance().scanFontDir(f);
            } catch (Exception e) {
                System.err.println("Warning: failed to scan font dir " + dir + ": " + e.getMessage());
            }
        }
    }

    private List<Path> collectOfdFiles(Path in) throws Exception {
        if (!Files.exists(in)) {
            return List.of();
        }
        if (Files.isRegularFile(in)) {
            return List.of(in);
        }
        try (Stream<Path> stream = Files.list(in)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ofd"))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }

    private void printJson(int total, int ok, int failed, long elapsedMs, List<Map<String, Object>> results) {
        // Hand-rolled JSON to avoid pulling in an ObjectMapper just for one call.
        StringBuilder sb = new StringBuilder(256 + results.size() * 128);
        sb.append("{");
        sb.append("\"total\":").append(total).append(',');
        sb.append("\"ok\":").append(ok).append(',');
        sb.append("\"failed\":").append(failed).append(',');
        sb.append("\"elapsedMs\":").append(elapsedMs).append(',');
        sb.append("\"outputDir\":\"").append(escape(outputDir.toAbsolutePath().toString())).append("\",");
        sb.append("\"results\":[");
        for (int i = 0; i < results.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(toJson(results.get(i)));
        }
        sb.append("]}");
        System.out.println(sb);
    }

    private static String toJson(Map<String, Object> m) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : m.entrySet()) {
            if (!first) sb.append(',');
            first = false;
            sb.append('"').append(escape(e.getKey())).append("\":");
            Object v = e.getValue();
            if (v == null) {
                sb.append("null");
            } else if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else if (v instanceof List) {
                sb.append('[');
                boolean f = true;
                for (Object item : (List<?>) v) {
                    if (!f) sb.append(',');
                    f = false;
                    sb.append('"').append(escape(String.valueOf(item))).append('"');
                }
                sb.append(']');
            } else {
                sb.append('"').append(escape(String.valueOf(v))).append('"');
            }
        }
        sb.append('}');
        return sb.toString();
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
