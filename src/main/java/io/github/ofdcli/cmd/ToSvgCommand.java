package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import io.github.ofdcli.util.FilesUtil;
import io.github.ofdcli.util.FontSetup;
import org.ofdrw.converter.export.SVGExporter;
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
 * Convert OFD file(s) to per-page SVG files.
 *
 * <p>Each OFD becomes a sub-directory under the output, containing
 * {@code Page_1.svg}, {@code Page_2.svg}, … This mirrors how the
 * {@link SVGExporter} internally batches exports.
 */
@Command(
        name = "to-svg",
        mixinStandardHelpOptions = true,
        description = "Convert OFD file(s) to per-page SVG files.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd to-svg invoice.ofd -o out/",
                "  ofd to-svg ./ofd_folder/ -o ./svg_folder/"
        })
public class ToSvgCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file or directory containing OFD files.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR",
            description = "Output directory (default: current directory).")
    Path outputDir = Path.of(".");

    @Option(names = {"-p", "--ppm"}, paramLabel = "PX_PER_MM",
            description = "Pixels-per-mm for rendering (default: ${DEFAULT-VALUE} ≈ 192 dpi).")
    double ppm = 7.56;

    @Option(names = {"--font-dir"}, paramLabel = "DIR",
            description = "Additional font directory to scan (repeatable).")
    List<Path> extraFontDirs;

    @Option(names = {"--no-default-fonts"},
            description = "Skip auto-scanning of default system font directories.")
    boolean noDefaultFonts;

    @Override
    public Integer call() throws Exception {
        FontSetup.setup(extraFontDirs, noDefaultFonts);

        List<Path> ofdFiles = FilesUtil.collectOfdFiles(input);
        if (ofdFiles.isEmpty()) {
            System.err.println("Error: no OFD files found at " + input);
            return ExitCode.USAGE_ERROR;
        }
        FilesUtil.ensureDirectory(outputDir);

        long t0 = System.currentTimeMillis();
        List<String> outputs = new ArrayList<>();
        int ok = 0, failed = 0;

        for (Path ofd : ofdFiles) {
            String base = FilesUtil.stripOfdExt(ofd.getFileName().toString());
            Path subDir = FilesUtil.uniquePath(outputDir.resolve(base));
            FilesUtil.ensureDirectory(subDir);
            long s = System.currentTimeMillis();
            try (OFDReader reader = new OFDReader(ofd);
                 SVGExporter exporter = new SVGExporter(ofd, subDir, ppm)) {
                exporter.export();
                long ms = System.currentTimeMillis() - s;
                ok++;
                outputs.add(subDir.toString());
                if (!isJson()) {
                    System.out.printf("OK   %s  ->  %s/  %dms%n", ofd.getFileName(), subDir, ms);
                }
            } catch (Exception e) {
                failed++;
                System.err.println("FAIL " + ofd.getFileName() + ": " + e.getMessage());
                if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            }
        }

        long elapsedMs = System.currentTimeMillis() - t0;
        if (isJson()) {
            System.out.println(JsonWriter.object()
                    .field("total", ofdFiles.size())
                    .field("ok", ok)
                    .field("failed", failed)
                    .field("elapsedMs", elapsedMs)
                    .field("outputDir", outputDir.toAbsolutePath().toString())
                    .field("outputs", outputs)
                    .toString());
        } else {
            System.out.printf("%nDone: %d/%d ok in %dms -> %s%n", ok, ofdFiles.size(), elapsedMs, outputDir.toAbsolutePath());
        }
        if (failed == 0) return ExitCode.OK;
        if (failed == ofdFiles.size()) return ExitCode.IO_ERROR;
        return ExitCode.PARTIAL_FAILURE;
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
