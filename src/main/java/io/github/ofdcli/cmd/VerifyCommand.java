package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.sign.verify.OFDValidator;
import org.ofdrw.sign.verify.container.GBT35275ValidateContainer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Verify the digital signatures on a signed OFD file.
 *
 * <p>Uses {@link GBT35275ValidateContainer} (the SM2/SM3 path that
 * matches {@code SignCommand}). Returns exit code {@code 0} if every
 * signature is valid, {@code 1} if verification failed, {@code 2} if the
 * file is unsigned.
 */
@Command(
        name = "verify",
        mixinStandardHelpOptions = true,
        description = "Verify digital signatures on an OFD file (GB/T 35275 SM2/SM3).",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd verify signed.ofd",
                "  ofd verify signed.ofd --json"
        })
public class VerifyCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file to verify.")
    Path input;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: file not found: " + input);
            return ExitCode.USAGE_ERROR;
        }

        long t0 = System.currentTimeMillis();
        try (OFDReader reader = new OFDReader(input)) {
            boolean hasSignature = reader.hasSignature();
            if (!hasSignature) {
                if (isJson()) {
                    System.out.println(JsonWriter.object()
                            .field("file", input.toString())
                            .field("hasSignature", false)
                            .field("status", "unsigned")
                            .toString());
                } else {
                    System.out.println("UNSIGNED " + input.getFileName() + " (no signatures present)");
                }
                return ExitCode.OK;
            }

            try (OFDValidator validator = new OFDValidator(reader)) {
                validator.setValidator(new GBT35275ValidateContainer());
                validator.exeValidate();
            }

            long ms = System.currentTimeMillis() - t0;
            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("file", input.toString())
                        .field("hasSignature", true)
                        .field("status", "valid")
                        .field("elapsedMs", ms)
                        .toString());
            } else {
                System.out.printf("VALID %s (%dms)%n", input.getFileName(), ms);
            }
            return ExitCode.OK;
        } catch (org.ofdrw.sign.verify.exceptions.OFDVerifyException e) {
            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("file", input.toString())
                        .field("status", "invalid")
                        .field("error", e.getMessage())
                        .toString());
            } else {
                System.err.println("INVALID " + input.getFileName() + ": " + e.getMessage());
            }
            return ExitCode.PARTIAL_FAILURE;
        } catch (Exception e) {
            System.err.println("Error: verify failed: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
