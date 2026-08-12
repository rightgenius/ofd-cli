package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.crypto.OFDDecryptor;
import org.ofdrw.crypto.decryptor.UserPasswordDecryptor;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Decrypt an OFD file with a user password.
 *
 * <p>Counterpart of {@code EncryptCommand}. The {@link UserPasswordDecryptor}
 * walks the OFD's user list, picks the entry that matches the supplied
 * password, and unwraps the file encryption key.
 */
@Command(
        name = "decrypt",
        mixinStandardHelpOptions = true,
        description = "Decrypt an OFD file with a user password.",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd decrypt encrypted.ofd -o plain.ofd -u alice -P s3cret"
        })
public class DecryptCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "Encrypted OFD file.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", required = true,
            description = "Output decrypted OFD file path.")
    Path output;

    @Option(names = {"-u", "--user"}, paramLabel = "NAME",
            description = "Username (if omitted, decrypt with password only).")
    String user;

    @Option(names = {"-P", "--password"}, paramLabel = "PWD", required = true,
            description = "Password for the user key.")
    String password;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: file not found: " + input);
            return ExitCode.USAGE_ERROR;
        }
        if (output.getParent() != null && !Files.isDirectory(output.getParent())) { Files.createDirectories(output.getParent()); }

        long t0 = System.currentTimeMillis();
        try (OFDDecryptor dec = new OFDDecryptor(input, output)) {
            UserPasswordDecryptor upd = user != null
                    ? new UserPasswordDecryptor(user, password)
                    : new UserPasswordDecryptor(password);
            dec.addUser(upd).decrypt();
            long ms = System.currentTimeMillis() - t0;
            long outSize = -1;
            try { outSize = Files.size(output); } catch (Exception ignore) {}

            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("input", input.toString())
                        .field("output", output.toString())
                        .field("outputSize", outSize)
                        .field("user", user)
                        .field("elapsedMs", ms)
                        .toString());
            } else {
                System.out.printf("Decrypted %s -> %s (%d KB, %dms)%n",
                        input.getFileName(), output, outSize / 1024, ms);
            }
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error: decrypt failed: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
