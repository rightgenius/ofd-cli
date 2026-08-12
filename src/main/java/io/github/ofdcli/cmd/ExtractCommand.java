package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import io.github.ofdcli.util.FilesUtil;
import org.ofdrw.reader.ContentExtractor;
import org.ofdrw.reader.OFDReader;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Extract plain text from OFD file(s).
 *
 * <p>Uses {@link ContentExtractor} which walks the page tree and
 * collects {@code TextObject} content. Page breaks are encoded with
 * {@code \f} (form feed) for the text output; JSON output gets a
 * structured {@code pages:[…]} array.
 */
@Command(
        name = "extract",
        mixinStandardHelpOptions = true,
        description = "Extract plain text from OFD file(s).",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd extract invoice.ofd -o out.txt",
                "  ofd extract invoice.ofd --json",
                "  ofd extract ./ofd_folder/ -o ./text_folder/"
        })
public class ExtractCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file or directory containing OFD files.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "DIR_OR_FILE",
            description = "Output text file (single input) or output directory (multi-input). Default: stdout for single file, alongside input for dir.")
    Path output;

    @Option(names = {"--separator"}, paramLabel = "STRING",
            description = "Page separator for text output (default: form-feed '\\f').")
    String separator = "\f";

    @Override
    public Integer call() throws Exception {
        List<Path> ofdFiles = FilesUtil.collectOfdFiles(input);
        if (ofdFiles.isEmpty()) {
            System.err.println("Error: no OFD files found at " + input);
            return ExitCode.USAGE_ERROR;
        }

        long t0 = System.currentTimeMillis();
        int ok = 0, failed = 0;
        List<String> outputs = new ArrayList<>();

        for (Path ofd : ofdFiles) {
            long s = System.currentTimeMillis();
            try (OFDReader reader = new OFDReader(ofd)) {
                ContentExtractor extractor = new ContentExtractor(reader);
                int n = reader.getNumberOfPages();
                List<List<String>> pages = new ArrayList<>(n);
                for (int i = 1; i <= n; i++) {
                    pages.add(extractor.getPageContent(i));
                }
                long ms = System.currentTimeMillis() - s;
                ok++;

                if (isJson()) {
                    String pagesJson = pagesToJson(pages);
                    System.out.println(JsonWriter.object()
                            .field("file", ofd.getFileName().toString())
                            .field("pageCount", n)
                            .field("elapsedMs", ms)
                            .rawField("pages", pagesJson)
                            .toString());
                } else {
                    if (ofdFiles.size() == 1 && output == null) {
                        // Single file, no -o → stdout
                        writeTextTo(pages, java.io.OutputStream.nullOutputStream() == null
                                ? System.out : System.out, false);
                    } else {
                        Path out = resolveOutput(ofd, ofdFiles.size() == 1);
                        if (out.getParent() != null) FilesUtil.ensureDirectory(out.getParent());
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i < pages.size(); i++) {
                            if (i > 0) sb.append(separator);
                            for (String line : pages.get(i)) sb.append(line).append('\n');
                        }
                        Files.writeString(out, sb.toString(), StandardCharsets.UTF_8);
                        outputs.add(out.toString());
                        System.out.printf("OK   %s  ->  %s  %dms%n", ofd.getFileName(), out, ms);
                    }
                }
            } catch (Exception e) {
                failed++;
                System.err.println("FAIL " + ofd.getFileName() + ": " + e.getMessage());
                if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            }
        }

        if (!isJson() && ofdFiles.size() > 1) {
            System.out.printf("%nDone: %d/%d ok in %dms%n", ok, ofdFiles.size(), System.currentTimeMillis() - t0);
        }
        if (failed == 0) return ExitCode.OK;
        if (failed == ofdFiles.size()) return ExitCode.IO_ERROR;
        return ExitCode.PARTIAL_FAILURE;
    }

    private static void writeTextTo(List<List<String>> pages, java.io.OutputStream out, boolean close) throws IOException {
        java.io.PrintStream ps = out instanceof java.io.PrintStream ? (java.io.PrintStream) out : new java.io.PrintStream(out);
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) ps.print("\f");
            for (String line : pages.get(i)) ps.println(line);
        }
        if (close) ps.flush();
    }

    private String pagesToJson(List<List<String>> pages) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < pages.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('[');
            List<String> page = pages.get(i);
            for (int j = 0; j < page.size(); j++) {
                if (j > 0) sb.append(',');
                sb.append('"').append(escapeJson(page.get(j))).append('"');
            }
            sb.append(']');
        }
        sb.append(']');
        return sb.toString();
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': out.append("\\\\"); break;
                case '"':  out.append("\\\""); break;
                case '\n': out.append("\\n");  break;
                case '\r': out.append("\\r");  break;
                case '\t': out.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }

    private Path resolveOutput(Path ofd, boolean singleInput) throws IOException {
        String base = FilesUtil.stripOfdExt(ofd.getFileName().toString());
        if (output != null) {
            if (output.toString().toLowerCase().endsWith(".txt") && singleInput) {
                return output;
            }
            FilesUtil.ensureDirectory(output);
            return output.resolve(base + ".txt");
        }
        return ofd.getParent() == null
                ? Path.of(base + ".txt")
                : ofd.getParent().resolve(base + ".txt");
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
