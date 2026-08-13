package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import io.github.ofdcli.util.FilesUtil;
import io.github.ofdcli.util.FontSetup;
import org.ofdrw.converter.GeneralConvertException;
import org.ofdrw.converter.export.PDFExporterOpenPDF;
import org.ofdrw.reader.OFDReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Convert OFD file(s) to PDF.
 *
 * <p>Backed by {@link PDFExporterOpenPDF} (OpenPDF 1.3.39, LGPL fork of
 * iText 4). The OpenPDF path was chosen over PDFBox because OpenPDF
 * does not depend on {@code java.awt.image.ColorModel} which would
 * otherwise call {@code System.loadLibrary("awt")} and fail on
 * GraalVM native-image (no AWT native library in the substrate VM).
 *
 * <p>Requires ofdrw-converter {@code 2.4.0-openpdf.1} (the user-maintained
 * OpenPDF-enabled variant of ofdrw — see
 * <a href="https://github.com/rightgenius/ofdrw">rightgenius/ofdrw</a>).
 * The upstream ofdrw 2.4.0 ships only PDFBox-backed
 * {@code PDFExporterPDFBox}; that one triggers an {@code UnsatisfiedLinkError}
 * at native runtime and is therefore unsuitable for the native binary.
 */
@Command(
        name = "to-pdf",
        mixinStandardHelpOptions = true,
        description = "Convert OFD file(s) to PDF.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd to-pdf invoice.ofd -o out/invoice.pdf",
                "  ofd to-pdf ./ofd_folder/ -o ./pdf_folder/"
        })
public class ToPdfCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file or directory containing OFD files.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR_OR_FILE",
            description = "Output PDF file (single-input) or output directory (multi-input). Default: alongside input.")
    Path output;

    @Option(names = {"-p", "--ppm"}, paramLabel = "PX_PER_MM",
            description = "Pixels-per-mm for rendering (default: ${DEFAULT-VALUE} ≈ 192 dpi).")
    double ppm = 7.56;

    @Option(names = {"--font-dir"}, paramLabel = "DIR",
            description = "Additional font directory to scan (repeatable).")
    List<Path> extraFontDirs;

    @Option(names = {"--no-default-fonts"},
            description = "Skip auto-scanning of default system font directories.")
    boolean noDefaultFonts;

    @Option(names = {"--pages"}, paramLabel = "RANGE",
            description = "Page range to export, e.g. '1-3' or '1,3,5' (default: all).")
    String pages;

    @Override
    public Integer call() throws Exception {
        FontSetup.setup(extraFontDirs, noDefaultFonts);

        List<Path> ofdFiles = FilesUtil.collectOfdFiles(input);
        if (ofdFiles.isEmpty()) {
            System.err.println("Error: no OFD files found at " + input);
            return ExitCode.USAGE_ERROR;
        }

        int[] selectedPages = parsePageRange(pages);

        long t0 = System.currentTimeMillis();
        List<String> results = new ArrayList<>();
        int ok = 0, failed = 0;

        for (Path ofd : ofdFiles) {
            Path outFile = resolveOutput(ofd, ofdFiles.size() == 1);
            if (outFile.getParent() != null) {
                FilesUtil.ensureDirectory(outFile.getParent());
            }
            long s = System.currentTimeMillis();
            try (OFDReader reader = new OFDReader(ofd);
                 PDFExporterOpenPDF exporter = new PDFExporterOpenPDF(ofd, outFile)) {
                if (selectedPages == null) {
                    exporter.export();
                } else {
                    exporter.export(toZeroBased(selectedPages));
                }
                long ms = System.currentTimeMillis() - s;
                ok++;
                results.add(outFile.toString());
                if (!isJson()) {
                    System.out.printf("OK   %s  ->  %s  %dms%n", ofd.getFileName(), outFile, ms);
                }
            } catch (Exception e) {
                failed++;
                System.err.println("FAIL " + ofd.getFileName() + ": " + e.getMessage());
                if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            }
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        if (isJson()) {
            JsonWriter w = JsonWriter.object()
                    .field("total", ofdFiles.size())
                    .field("ok", ok)
                    .field("failed", failed)
                    .field("elapsedMs", elapsedMs)
                    .field("outputs", results);
            System.out.println(w.toString());
        } else {
            System.out.printf("%nDone: %d/%d ok in %dms%n", ok, ofdFiles.size(), elapsedMs);
        }
        if (failed == 0) return ExitCode.OK;
        if (failed == ofdFiles.size()) return ExitCode.IO_ERROR;
        return ExitCode.PARTIAL_FAILURE;
    }

    private Path resolveOutput(Path ofd, boolean singleInput) throws IOException {
        String base = FilesUtil.stripOfdExt(ofd.getFileName().toString());
        if (output != null) {
            // If user gave a file ending in .pdf, treat as file (single input);
            // otherwise as a directory.
            if (output.toString().toLowerCase().endsWith(".pdf") && singleInput) {
                return output;
            }
            FilesUtil.ensureDirectory(output);
            return output.resolve(base + ".pdf");
        }
        // Default: alongside the OFD file.
        return ofd.getParent() == null
                ? Path.of(base + ".pdf")
                : ofd.getParent().resolve(base + ".pdf");
    }

    private static int[] parsePageRange(String spec) {
        if (spec == null || spec.isBlank()) return null;
        List<Integer> pages = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.contains("-")) {
                String[] ab = part.split("-", 2);
                int a = Integer.parseInt(ab[0].trim());
                int b = Integer.parseInt(ab[1].trim());
                for (int i = a; i <= b; i++) pages.add(i);
            } else {
                pages.add(Integer.parseInt(part));
            }
        }
        int[] out = new int[pages.size()];
        for (int i = 0; i < out.length; i++) out[i] = pages.get(i);
        return out;
    }

    private static int[] toZeroBased(int[] oneBased) {
        int[] z = new int[oneBased.length];
        for (int i = 0; i < z.length; i++) z[i] = oneBased[i] - 1;
        return z;
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
