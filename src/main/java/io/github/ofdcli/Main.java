package io.github.ofdcli;

import io.github.ofdcli.cmd.DecryptCommand;
import io.github.ofdcli.cmd.EncryptCommand;
import io.github.ofdcli.cmd.ExtractCommand;
import io.github.ofdcli.cmd.InfoCommand;
import io.github.ofdcli.cmd.MergeCommand;
import io.github.ofdcli.cmd.SignCommand;
import io.github.ofdcli.cmd.ToHtmlCommand;
import io.github.ofdcli.cmd.ToPdfCommand;
import io.github.ofdcli.cmd.ToPngCommand;
import io.github.ofdcli.cmd.ToSvgCommand;
import io.github.ofdcli.cmd.ValidateCommand;
import io.github.ofdcli.cmd.VerifyCommand;
import io.github.ofdcli.cmd.VersionCommand;
import io.github.ofdcli.util.FilesUtil;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
        name = "ofd",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        description = "Command-line tool for OFD (Open Fixed-layout Document) processing.",
        footer = {
                "Run 'ofd <command> --help' for command-specific help.",
                "",
                "Two distributions are shipped:",
                "  • ofd         (native binary, 8 subcommands — see list below)",
                "  • ofd-cli.jar (fat-jar, all 13 subcommands, requires JRE 11+)",
                "The sign/verify/validate/to-html/to-svg subcommands are only",
                "available in the fat-jar: sign/verify/validate need a BouncyCastle",
                "provider that GraalVM 25.0.4 CE cannot register (oracle/graal#13412);",
                "to-html/to-svg need AWT which the substrate VM cannot load.",
                "to-pdf is supported in both — see PDFExporterOpenPDF in the",
                "ofdrw 2.4.0-openpdf.1 fork (rightgenius/ofdrw)."})
public class Main implements Runnable {

    /**
     * All 13 subcommands — the fat-jar set.
     */
    private static final Class<?>[] FULL_SUBCOMMANDS = {
            VersionCommand.class,
            InfoCommand.class,
            ToPngCommand.class,
            ToPdfCommand.class,
            ToHtmlCommand.class,
            ToSvgCommand.class,
            ExtractCommand.class,
            MergeCommand.class,
            SignCommand.class,
            VerifyCommand.class,
            EncryptCommand.class,
            DecryptCommand.class,
            ValidateCommand.class,
    };

    /**
     * The subcommands that work in GraalVM native-image today.
     *
     * <p>Excluded from the native binary (kept in the fat-jar):</p>
     * <ul>
     *   <li>{@link SignCommand}, {@link VerifyCommand}, {@link ValidateCommand} —
     *       need a BouncyCastleProvider that the closed-world
     *       JceSecurity.getVerificationResult check in GraalVM 25.0.4 CE
     *       refuses to verify for runtime-registered providers
     *       (see oracle/graal#13412). These subcommands call
     *       {@code Signature.getInstance("SM3WithSM2", "BC")} via the JCE
     *       provider API, which goes through {@code JceSecurity.canUseProvider}
     *       and blows up at runtime.</li>
     *   <li>{@code encrypt} / {@code decrypt} <em>do</em> work in the native
     *       binary because ofdrw-crypto's {@code UserPasswordEncryptor} uses
     *       BC's <strong>lightweight crypto API</strong>
     *       ({@code org.bouncycastle.crypto.engines.SM4Engine} +
     *       {@code SM3.Digest}) directly — it never touches
     *       {@code java.security.Security}, so the closed-world provider
     *       check is irrelevant.</li>
     *   <li>{@link ToHtmlCommand}, {@link ToSvgCommand} — HtmlMaker / SVGExporter
     *       extend AWTMaker, which on macOS triggers
     *       {@code sun/font/CFontManager} JNI lookups that fail with SIGABRT
     *       in the substrate VM.</li>
     * </ul>
     *
     * <p>{@link ToPdfCommand} <strong>is</strong> registered on the native
     * binary when ofdrw-converter {@code 2.4.0-openpdf.1} (or any later
     * OpenPDF-backed variant) is on the classpath — OpenPDF is a pure-Java
     * fork of iText 4 and does not touch {@code java.awt.image.ColorModel}
     * at static init, so the {@code UnsatisfiedLinkError} that
     * PDFBox's PDDocument.&lt;clinit&gt; triggers does not occur.</p>
     */
    private static final Class<?>[] NATIVE_SUBCOMMANDS = {
            VersionCommand.class,
            InfoCommand.class,
            ToPngCommand.class,
            ToPdfCommand.class,
            ExtractCommand.class,
            MergeCommand.class,
            EncryptCommand.class,
            DecryptCommand.class,
    };

    @Option(names = {"--json"}, scope = ScopeType.INHERIT,
            description = "Output machine-readable JSON to stdout (default: human-readable).")
    boolean json;

    public static void main(String[] args) {
        // Force headless BEFORE any AWT subsystem initializes — native-image
        // can't open a graphics device and would otherwise try OpenGL/CGL.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        // Native-image: redirect AWT to the HeadlessToolkit so the macOS
        // CFontManager / CGraphicsEnvironment classes are never touched.
        // Without this, SVGMaker / HtmlMaker trigger JNI lookups for
        // sun/font/CFontManager and abort with SIGABRT.
        if (System.getProperty("awt.toolkit") == null) {
            System.setProperty("awt.toolkit", "java.awt.HeadlessToolkit");
        }
        // BouncyCastleProvider is registered at image startup via
        // ProviderBootstrap.<clinit> (loaded lazily by SignCommand etc. —
        // they call Class.forName before any JCA lookup). On native-image
        // this still fails JceSecurity's closed-world check, which is why
        // those subcommands are excluded below.
        // (The Class.forName call is also done here so that even subcommands
        //  that touch BC transitively — e.g. extract/merge on signed OFDs —
        //  don't crash on the first JCE lookup. Best-effort.)
        if (!FilesUtil.isNativeImage()) {
            try {
                Class.forName("io.github.ofdcli.security.ProviderBootstrap");
            } catch (Throwable t) {
                // Class not on classpath (BC is a transitive dep of ofdrw-gm; this
                // catch is just defensive for slimmed-down test classpaths).
            }
        }
        // Native-image: java.home is unset which breaks FontConfiguration lookup.
        // Point it to the JDK install we built from. Only set if missing.
        if (System.getProperty("java.home") == null) {
            // We don't actually need a real JRE; substrate VM just needs *some*
            // path that ends in /jdk-... or similar. Use /opt/homebrew/opt/openjdk@17
            // if present, otherwise fall back to JAVA_HOME env or "/".
            String home = System.getenv("JAVA_HOME");
            if (home == null || home.isEmpty()) {
                home = "/Library/Java/JavaVirtualMachines/temurin-11.jdk/Contents/Home";
            }
            System.setProperty("java.home", home);
        }
        // Native-image + macOS: /tmp is a symlink to /private/tmp which trips
        // Files.createDirectories' idempotency check in ofdrw-crypto. Point
        // java.io.tmpdir at the real directory before any ofdrw code runs.
        if (System.getProperty("java.io.tmpdir") == null) {
            String realTmp = "/private/tmp";
            if (java.nio.file.Files.isDirectory(java.nio.file.Paths.get(realTmp))) {
                System.setProperty("java.io.tmpdir", realTmp);
            }
        }
        // Build the picocli tree. Native binary exposes only the 10 subcommands
        // that work in closed-world land; fat-jar exposes all 13.
        Class<?>[] subcommands = FilesUtil.isNativeImage()
                ? NATIVE_SUBCOMMANDS
                : FULL_SUBCOMMANDS;
        CommandLine root = new CommandLine(new Main());
        for (Class<?> sub : subcommands) {
            root.addSubcommand(sub);
        }
        int exitCode = root
                .setExecutionExceptionHandler((ex, cmd, parseResult) -> {
                    // Print error to stderr; picocli default already does this, but keep deterministic.
                    System.err.println("Error: " + ex.getMessage());
                    if (Boolean.getBoolean("ofd.verbose")) {
                        ex.printStackTrace(System.err);
                    }
                    return ExitCode.INTERNAL_ERROR;
                })
                .setExecutionStrategy(new CommandLine.RunLast())
                .execute(args);
        System.exit(exitCode);
    }

    @Override
    public void run() {
        // No subcommand: print top-level help.
        new CommandLine(this).usage(System.out);
    }

    public boolean isJson() {
        return json;
    }
}
