package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.core.basicStructure.ofd.DocBody;
import org.ofdrw.core.basicStructure.ofd.docInfo.CT_DocInfo;
import org.ofdrw.core.basicType.ST_Box;
import org.ofdrw.core.basicType.ST_Loc;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.reader.PageInfo;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Inspect an OFD file and emit a human-readable or JSON summary.
 *
 * <p>Pulls metadata straight from {@link OFDReader}: page count, page
 * dimensions, document info (title, author, dates), signature presence,
 * attachment list, and stamp annotations. No font setup needed — info
 * only reads XML, never renders.
 */
@Command(
        name = "info",
        mixinStandardHelpOptions = true,
        description = "Show OFD document metadata: page count, dimensions, doc info, signatures, attachments.")
public class InfoCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file (or directory; only the first .ofd is inspected).")
    Path input;

    @Option(names = {"--page"},
            description = "Show per-page size info (default: ${DEFAULT-VALUE}).")
    boolean showPages = true;

    @Option(names = {"--attachments"},
            description = "List attachments (default: ${DEFAULT-VALUE}).")
    boolean showAttachments = true;

    @Override
    public Integer call() throws Exception {
        if (input == null || !Files.exists(input)) {
            System.err.println("Error: file not found: " + input);
            return ExitCode.USAGE_ERROR;
        }
        // If user passed a directory, take the first OFD inside.
        Path ofdFile = input;
        if (Files.isDirectory(input)) {
            try (var stream = Files.list(input)) {
                ofdFile = stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".ofd"))
                        .sorted()
                        .findFirst()
                        .orElse(null);
            }
            if (ofdFile == null) {
                System.err.println("Error: no OFD files in " + input);
                return ExitCode.USAGE_ERROR;
            }
        }

        try (OFDReader reader = new OFDReader(ofdFile)) {
            if (isJson()) {
                emitJson(reader, ofdFile);
            } else {
                emitHuman(reader, ofdFile);
            }
            return ExitCode.OK;
        } catch (IOException e) {
            System.err.println("Error: failed to read OFD: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.IO_ERROR;
        } catch (org.dom4j.DocumentException e) {
            System.err.println("Error: failed to parse OFD: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.IO_ERROR;
        } catch (Exception e) {
            System.err.println("Error: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private void emitHuman(OFDReader reader, Path ofdFile) throws Exception {
        System.out.println("OFD: " + ofdFile.toAbsolutePath());
        System.out.println("File size: " + humanBytes(fileSize(ofdFile)));

        DocBody docBody = reader.getOFDDir().getOfd().getDocBody();
        if (docBody != null) {
            CT_DocInfo info = docBody.getDocInfo();
            if (info != null) {
                System.out.println();
                System.out.println("[Document Info]");
                printField("Title", info.getTile());
                printField("Author", info.getAuthor());
                printField("Subject", info.getSubject());
                printField("Creator", info.getCreator());
                printField("CreatorVersion", info.getCreatorVersion());
                printField("DocID", info.getDocID());
                printField("CreationDate", info.getCreationDate());
                printField("ModDate", info.getModDate());
                if (info.getKeywords() != null && info.getKeywords().getKeywords() != null) {
                    printField("Keywords", String.join(", ", info.getKeywords().getKeywords()));
                }
            }
        }

        int pageCount = reader.getNumberOfPages();
        System.out.println();
        System.out.println("[Pages] count=" + pageCount);

        if (showPages && pageCount > 0) {
            List<PageInfo> pages = reader.getPageList();
            for (int i = 0; i < Math.min(pages.size(), 5); i++) {
                PageInfo p = pages.get(i);
                ST_Box box = p.getSize();
                if (box == null) {
                    try { box = reader.getPageSize(i + 1); } catch (Exception ignore) {}
                }
                System.out.printf("  page %d: %s mm  (%.0f x %.0f px @ 7.56 ppm)%n",
                        i + 1,
                        box == null ? "?" : String.format("%.1fx%.1f", box.getWidth(), box.getHeight()),
                        box == null ? 0 : box.getWidth() * 7.56,
                        box == null ? 0 : box.getHeight() * 7.56);
            }
            if (pages.size() > 5) {
                System.out.println("  ... " + (pages.size() - 5) + " more");
            }
        }

        System.out.println();
        System.out.println("[Signatures] present=" + reader.hasSignature());
        if (reader.hasSignature()) {
            ST_Loc sigLoc = reader.getDefaultDocSignaturesPath();
            printField("  path", sigLoc == null ? null : sigLoc.getLoc());
        }

        if (showAttachments) {
            var attachments = reader.getAttachmentList();
            System.out.println();
            System.out.println("[Attachments] count=" + attachments.size());
            for (var att : attachments) {
                System.out.printf("  %s  (format=%s)%n",
                        att.getAttachmentName(),
                        att.getFormat() == null ? "?" : att.getFormat());
            }
        }
    }

    private void emitJson(OFDReader reader, Path ofdFile) throws Exception {
        JsonWriter w = JsonWriter.object()
                .field("file", ofdFile.toAbsolutePath().toString())
                .field("fileSize", fileSize(ofdFile));

        DocBody docBody = reader.getOFDDir().getOfd().getDocBody();
        if (docBody != null && docBody.getDocInfo() != null) {
            CT_DocInfo info = docBody.getDocInfo();
            JsonWriter infoJson = JsonWriter.object()
                    .field("title", info.getTile())
                    .field("author", info.getAuthor())
                    .field("subject", info.getSubject())
                    .field("creator", info.getCreator())
                    .field("creatorVersion", info.getCreatorVersion())
                    .field("docId", info.getDocID())
                    .field("creationDate", info.getCreationDate() == null ? null : info.getCreationDate().toString())
                    .field("modDate", info.getModDate() == null ? null : info.getModDate().toString());
            w.rawField("docInfo", infoJson.toString());
        } else {
            w.rawField("docInfo", "null");
        }

        int pageCount = reader.getNumberOfPages();
        w.field("pageCount", pageCount);

        // Per-page sizes
        StringBuilder pagesArr = new StringBuilder("[");
        if (showPages) {
            List<PageInfo> pages = reader.getPageList();
            for (int i = 0; i < pages.size(); i++) {
                if (i > 0) pagesArr.append(',');
                PageInfo p = pages.get(i);
                ST_Box box = p.getSize();
                if (box == null) {
                    try { box = reader.getPageSize(i + 1); } catch (Exception ignore) {}
                }
                JsonWriter pj = JsonWriter.object()
                        .field("index", i + 1);
                if (box != null) {
                    pj.field("widthMm", box.getWidth())
                      .field("heightMm", box.getHeight());
                } else {
                    pj.fieldNull("widthMm").fieldNull("heightMm");
                }
                pagesArr.append(pj.toString());
            }
        }
        pagesArr.append(']');
        w.rawField("pages", pagesArr.toString());

        w.field("hasSignature", reader.hasSignature());
        ST_Loc sigLoc = reader.getDefaultDocSignaturesPath();
        w.field("signaturesPath", sigLoc == null ? null : sigLoc.getLoc());

        var attachments = reader.getAttachmentList();
        StringBuilder attArr = new StringBuilder("[");
        for (int i = 0; i < attachments.size(); i++) {
            if (i > 0) attArr.append(',');
            var att = attachments.get(i);
            attArr.append(JsonWriter.object()
                    .field("name", att.getAttachmentName())
                    .field("format", att.getFormat())
                    .toString());
        }
        attArr.append(']');
        w.rawField("attachments", attArr.toString());

        System.out.println(w.toString());
    }

    private static long fileSize(Path p) {
        try { return Files.size(p); } catch (Exception e) { return -1; }
    }

    private static String humanBytes(long n) {
        if (n < 1024) return n + " B";
        if (n < 1024 * 1024) return String.format("%.1f KB", n / 1024.0);
        return String.format("%.1f MB", n / 1024.0 / 1024.0);
    }

    private static void printField(String name, Object value) {
        if (value == null) return;
        String s = value.toString();
        if (s.isEmpty()) return;
        System.out.printf("  %-16s %s%n", name + ":", s);
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
