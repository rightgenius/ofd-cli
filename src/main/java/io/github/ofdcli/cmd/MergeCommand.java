package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import io.github.ofdcli.util.FilesUtil;
import org.ofdrw.tool.merge.OFDMerger;
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
 * Merge multiple OFD files into a single OFD.
 *
 * <p>Each input file's pages are appended in the order given. The
 * resulting OFD has one document body containing N pages, where N is
 * the sum of all input pages.
 */
@Command(
        name = "merge",
        mixinStandardHelpOptions = true,
        description = "Merge multiple OFD files into a single OFD.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd merge a.ofd b.ofd c.ofd -o merged.ofd",
                "  ofd merge ./ofd_folder/ -o merged.ofd"
        })
public class MergeCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0..*", paramLabel = "INPUT",
            description = "OFD files and/or directories to merge (in order).")
    List<Path> inputs;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", required = true,
            description = "Output merged OFD file path.")
    Path output;

    @Override
    public Integer call() throws Exception {
        if (inputs == null || inputs.isEmpty()) {
            System.err.println("Error: at least one INPUT is required");
            return ExitCode.USAGE_ERROR;
        }

        List<Path> ofdFiles = new ArrayList<>();
        for (Path in : inputs) {
            List<Path> collected = FilesUtil.collectOfdFiles(in);
            if (collected.isEmpty()) {
                System.err.println("Error: no OFD files at " + in);
                return ExitCode.USAGE_ERROR;
            }
            ofdFiles.addAll(collected);
        }
        if (ofdFiles.isEmpty()) {
            System.err.println("Error: no OFD files to merge");
            return ExitCode.USAGE_ERROR;
        }

        if (output.getParent() != null) FilesUtil.ensureDirectory(output.getParent());

        long t0 = System.currentTimeMillis();
        int totalPages = 0;
        try (OFDMerger merger = new OFDMerger(output)) {
            for (Path p : ofdFiles) {
                try {
                    int before = merger.pageArr.size();
                    merger.add(p);
                    int after = merger.pageArr.size();
                    int added = after - before;
                    totalPages += added;
                    if (!isJson()) {
                        System.out.printf("  + %s  (%d page%s)%n",
                                p.getFileName(), added, added == 1 ? "" : "s");
                    }
                } catch (Exception e) {
                    System.err.println("FAIL " + p.getFileName() + ": " + e.getMessage());
                    if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
                }
            }
        }
        long ms = System.currentTimeMillis() - t0;

        long size = -1;
        try { size = Files.size(output); } catch (Exception ignore) {}

        if (isJson()) {
            System.out.println(JsonWriter.object()
                    .field("output", output.toString())
                    .field("fileSize", size)
                    .field("inputCount", ofdFiles.size())
                    .field("totalPages", totalPages)
                    .field("elapsedMs", ms)
                    .toString());
        } else {
            System.out.printf("%nMerged %d file(s) -> %s (%d pages, %d KB, %dms)%n",
                    ofdFiles.size(), output, totalPages, size / 1024, ms);
        }
        return ExitCode.OK;
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
