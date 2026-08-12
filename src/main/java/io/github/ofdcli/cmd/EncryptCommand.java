package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.crypto.OFDEncryptor;
import org.ofdrw.crypto.enryptor.UserPasswordEncryptor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Encrypt an OFD file with a user password.
 *
 * <p>Wraps the standard {@link OFDEncryptor} flow with a single
 * {@link UserPasswordEncryptor}. The encrypted output is a valid OFD
 * archive that can be opened with {@code ofd decrypt} using the same
 * password.
 */
@Command(
        name = "encrypt",
        mixinStandardHelpOptions = true,
        description = "Encrypt an OFD file with a user password.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret",
                "  ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret -t 1"
        })
public class EncryptCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file to encrypt.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", required = true,
            description = "Output encrypted OFD file path.")
    Path output;

    @Option(names = {"-u", "--user"}, paramLabel = "NAME", required = true,
            description = "Username / owner of the encryption key.")
    String user;

    @Option(names = {"-P", "--password"}, paramLabel = "PWD", required = true,
            description = "Password used to derive the encryption key.")
    String password;

    @Option(names = {"-t", "--user-type"}, paramLabel = "TYPE",
            description = "User type: 0=User, 1=Owner (default: ${DEFAULT-VALUE}).")
    int userType = 0;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: file not found: " + input);
            return ExitCode.USAGE_ERROR;
        }
        if (output.getParent() != null && !Files.isDirectory(output.getParent())) { Files.createDirectories(output.getParent()); }

        long t0 = System.currentTimeMillis();
        try (OFDEncryptor enc = new OFDEncryptor(input, output)) {
            UserPasswordEncryptor upe = new UserPasswordEncryptor(user, String.valueOf(userType), password);
            enc.addUser(upe).encrypt();
            long ms = System.currentTimeMillis() - t0;
            long outSize = -1;
            try { outSize = Files.size(output); } catch (Exception ignore) {}

            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("input", input.toString())
                        .field("output", output.toString())
                        .field("outputSize", outSize)
                        .field("user", user)
                        .field("userType", userType)
                        .field("elapsedMs", ms)
                        .toString());
            } else {
                System.out.printf("Encrypted %s -> %s (%d KB, %dms)%n",
                        input.getFileName(), output, outSize / 1024, ms);
            }
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error: encrypt failed: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
