package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.gm.cert.PKCS12Tools;
import org.ofdrw.reader.OFDReader;
import org.ofdrw.sign.OFDSigner;
import org.ofdrw.sign.signContainer.GBT35275DSContainer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.util.concurrent.Callable;

/**
 * Sign an OFD file with a PKCS#12 (.p12) certificate using the
 * GB/T 35275 SM2-with-SM3 signature scheme.
 *
 * <p>The default behaviour keeps the source OFD intact and writes the
 * signed result to a new file (use {@code -o}). For test fixtures we
 * ship {@code USER.p12} under {@code src/test/resources} with alias
 * {@code "private"} and password {@code "777777"}.
 */
@Command(
        name = "sign",
        mixinStandardHelpOptions = true,
        description = "Sign an OFD file with a PKCS#12 certificate (GB/T 35275 SM2/SM3).",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd sign input.ofd -p12 USER.p12 -P 777777 -o signed.ofd",
                "  ofd sign input.ofd -p12 USER.p12 -P 777777 --alias private -o signed.ofd"
        })
public class SignCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file to sign.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE", required = true,
            description = "Output signed OFD file path.")
    Path output;

    @Option(names = {"-p12", "--pkcs12"}, paramLabel = "FILE", required = true,
            description = "PKCS#12 (.p12) keystore containing the signing cert and private key.")
    Path pkcs12;

    @Option(names = {"-P", "--password"}, paramLabel = "PWD", required = true,
            description = "Password for the PKCS#12 keystore (and for the alias if not given).")
    String password;

    @Option(names = {"--alias"}, paramLabel = "NAME",
            description = "Alias of the key entry inside the keystore (default: keystore password).")
    String alias;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: input not found: " + input);
            return ExitCode.USAGE_ERROR;
        }
        if (!Files.isRegularFile(pkcs12)) {
            System.err.println("Error: PKCS#12 not found: " + pkcs12);
            return ExitCode.USAGE_ERROR;
        }
        if (output.getParent() != null && !Files.isDirectory(output.getParent())) {
            Files.createDirectories(output.getParent());
        }

        long t0 = System.currentTimeMillis();
        try {
            String aliasName = alias != null ? alias : password;
            PrivateKey prvKey = PKCS12Tools.ReadPrvKey(pkcs12, aliasName, password);
            Certificate cert = PKCS12Tools.ReadUserCert(pkcs12, aliasName, password);
            GBT35275DSContainer container = new GBT35275DSContainer(cert, prvKey);

            try (OFDReader reader = new OFDReader(input);
                 OFDSigner signer = new OFDSigner(reader, output)) {
                signer.setSignContainer(container);
                signer.exeSign();
            }

            long ms = System.currentTimeMillis() - t0;
            long outSize = -1;
            try { outSize = Files.size(output); } catch (Exception ignore) {}

            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("input", input.toString())
                        .field("output", output.toString())
                        .field("outputSize", outSize)
                        .field("elapsedMs", ms)
                        .field("algorithm", "GB/T 35275 SM2withSM3")
                        .toString());
            } else {
                System.out.printf("Signed %s -> %s (%d KB, %dms)%n",
                        input.getFileName(), output, outSize / 1024, ms);
            }
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error: sign failed: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private boolean isJson() {
        return parent != null && parent.isJson();
    }
}
