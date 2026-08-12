package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import io.github.ofdcli.util.FilesUtil;
import io.github.ofdcli.util.FontSetup;
import org.ofdrw.converter.HtmlMaker;
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
 * Convert OFD file(s) to HTML.
 *
 * <p>{@link HtmlMaker#parse()} writes an HTML file plus an
 * {@code ofd-svg/} directory of per-page SVGs next to the output.
 * We keep that directory layout because the HTML embeds relative SVG
 * references.
 */
@Command(
        name = "to-html",
        mixinStandardHelpOptions = true,
        description = "Convert OFD file(s) to HTML (with per-page SVG assets in a sibling dir).",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd to-html invoice.ofd -o out/invoice.html",
                "  ofd to-html ./ofd_folder/ -o ./html_folder/"
        })
public class ToHtmlCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file or directory containing OFD files.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR_OR_FILE",
            description = "Output HTML file (single input) or output directory (multi-input). Default: alongside input.")
    Path output;

    @Option(names = {"-w", "--width"}, paramLabel = "PX",
            description = "Browser viewport width in pixels (default: ${DEFAULT-VALUE}).")
    int screenWidth = 1000;

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

        long t0 = System.currentTimeMillis();
        List<String> outputs = new ArrayList<>();
        int ok = 0, failed = 0;

        for (Path ofd : ofdFiles) {
            Path outFile = resolveOutput(ofd, ofdFiles.size() == 1);
            if (outFile.getParent() != null) FilesUtil.ensureDirectory(outFile.getParent());
            long s = System.currentTimeMillis();
            try (OFDReader reader = new OFDReader(ofd)) {
                HtmlMaker maker = new HtmlMaker(reader, outFile, screenWidth);
                maker.parse();
                long ms = System.currentTimeMillis() - s;
                ok++;
                outputs.add(outFile.toString());
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
            System.out.println(JsonWriter.object()
                    .field("total", ofdFiles.size())
                    .field("ok", ok)
                    .field("failed", failed)
                    .field("elapsedMs", elapsedMs)
                    .field("outputs", outputs)
                    .toString());
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
            if (output.toString().toLowerCase().endsWith(".html") && singleInput) {
                return output;
            }
            FilesUtil.ensureDirectory(output);
            return output.resolve(base + ".html");
        }
        return ofd.getParent() == null
                ? Path.of(base + ".html")
                : ofd.getParent().resolve(base + ".html");
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
