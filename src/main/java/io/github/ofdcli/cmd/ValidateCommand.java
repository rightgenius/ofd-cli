package io.github.ofdcli.cmd;

import io.github.ofdcli.ExitCode;
import io.github.ofdcli.Main;
import io.github.ofdcli.json.JsonWriter;
import org.ofdrw.crypto.integrity.GMProtectSignerLight;
import org.ofdrw.crypto.integrity.GMProtectVerifier;
import org.ofdrw.crypto.integrity.OFDIntegrity;
import org.ofdrw.crypto.integrity.OFDIntegrityVerifier;
import org.ofdrw.gm.cert.PKCS12ToolsLight;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;
import picocli.CommandLine.ParentCommand;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;

/**
 * Apply and verify OFD integrity protection (GB/T 0099 7.4.6).
 *
 * <p>Two modes:
 * <ul>
 *   <li>{@code --apply} — wrap an OFD with an {@code OFDEntries.xml}
 *       protection entry signed by SM2/SM3 (needs a PKCS#12 keystore).</li>
 *   <li>default — verify the integrity of an already-protected OFD.</li>
 * </ul>
 */
@Command(
        name = "validate",
        mixinStandardHelpOptions = true,
        description = "Apply or verify OFD integrity protection (GB/T 0099).",
        footerHeading = "%nExamples:%n",
        footer = {
                "  ofd validate input.ofd                       # verify integrity",
                "  ofd validate input.ofd -o protected.ofd --apply -p12 USER.p12 -P 777777"
        })
public class ValidateCommand implements Callable<Integer> {

    @ParentCommand
    Main parent;

    @Parameters(index = "0", paramLabel = "INPUT",
            description = "OFD file.")
    Path input;

    @Option(names = {"-o", "--output"}, paramLabel = "FILE",
            description = "Output path (only used with --apply).")
    Path output;

    @Option(names = {"--apply"},
            description = "Apply integrity protection instead of verifying.")
    boolean apply;

    @Option(names = {"-p12", "--pkcs12"}, paramLabel = "FILE",
            description = "PKCS#12 keystore (only used with --apply).")
    Path pkcs12;

    @Option(names = {"-P", "--password"}, paramLabel = "PWD",
            description = "PKCS#12 password (only used with --apply).")
    String password;

    @Option(names = {"--alias"}, paramLabel = "NAME",
            description = "Key alias inside the PKCS#12 (default: password).")
    String alias;

    @Override
    public Integer call() throws Exception {
        if (!Files.isRegularFile(input)) {
            System.err.println("Error: file not found: " + input);
            return ExitCode.USAGE_ERROR;
        }
        if (apply) {
            return applyProtection();
        }
        return verifyProtection();
    }

    private Integer applyProtection() throws Exception {
        if (output == null) {
            System.err.println("Error: --output is required with --apply");
            return ExitCode.USAGE_ERROR;
        }
        if (pkcs12 == null || password == null) {
            System.err.println("Error: --pkcs12 and --password are required with --apply");
            return ExitCode.USAGE_ERROR;
        }
        if (output.getParent() != null && !Files.isDirectory(output.getParent())) { Files.createDirectories(output.getParent()); }

        long t0 = System.currentTimeMillis();
        try {
            // 走 BC 轻量级 API：PKCS12ToolsLight 解 .p12 + GMProtectSignerLight 签 SM2+SM3
            // 跟 sign / verify 一样，不触发 JceSecurity.canUseProvider 校验，
            // native binary 也能跑 validate --apply
            PKCS12ToolsLight.Result p12 = PKCS12ToolsLight.read(pkcs12, password.toCharArray());
            GMProtectSignerLight signer = new GMProtectSignerLight(p12.certHolder, p12.privateKey);
            try (OFDIntegrity integ = new OFDIntegrity(input, output)) {
                integ.protect(signer);
            }
            long ms = System.currentTimeMillis() - t0;
            long outSize = -1;
            try { outSize = Files.size(output); } catch (Exception ignore) {}

            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("input", input.toString())
                        .field("output", output.toString())
                        .field("outputSize", outSize)
                        .field("operation", "apply")
                        .field("algorithm", "GM/T 0099 SM2withSM3")
                        .field("elapsedMs", ms)
                        .toString());
            } else {
                System.out.printf("Protected %s -> %s (%d KB, %dms)%n",
                        input.getFileName(), output, outSize / 1024, ms);
            }
            return ExitCode.OK;
        } catch (Exception e) {
            System.err.println("Error: protect failed: " + e.getMessage());
            if (Boolean.getBoolean("ofd.verbose")) e.printStackTrace(System.err);
            return ExitCode.INTERNAL_ERROR;
        }
    }

    private Integer verifyProtection() throws Exception {
        long t0 = System.currentTimeMillis();
        try {
            OFDIntegrityVerifier v = new OFDIntegrityVerifier();
            boolean ok;
            try {
                ok = v.integrity(input, new GMProtectVerifier());
            } catch (Exception e) {
                // No OFDEntries.xml → no integrity protection applied → report as "unprotected"
                if (isJson()) {
                    System.out.println(JsonWriter.object()
                            .field("file", input.toString())
                            .field("status", "unprotected")
                            .field("reason", e.getClass().getSimpleName() + ": " + e.getMessage())
                            .toString());
                } else {
                    System.out.println("UNPROTECTED " + input.getFileName() + " (" + e.getMessage() + ")");
                }
                return ExitCode.OK;
            }
            long ms = System.currentTimeMillis() - t0;
            if (isJson()) {
                System.out.println(JsonWriter.object()
                        .field("file", input.toString())
                        .field("status", ok ? "valid" : "invalid")
                        .field("elapsedMs", ms)
                        .toString());
            } else {
                System.out.printf("%s %s (%dms)%n",
                        ok ? "VALID" : "INVALID", input.getFileName(), ms);
            }
            return ok ? ExitCode.OK : ExitCode.PARTIAL_FAILURE;
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
