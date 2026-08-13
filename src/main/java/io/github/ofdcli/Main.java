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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Option;
import picocli.CommandLine.ScopeType;

@Command(
        name = "ofd",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
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
        },
        description = "Command-line tool for OFD (Open Fixed-layout Document) processing.",
        footer = "Run 'ofd <command> --help' for command-specific help.")
public class Main implements Runnable {

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
        // Native-image: BouncyCastleProvider must be available for sign/verify/validate.
        // The class's <clinit> calls Security.addProvider(...); loading it here
        // triggers the static initializer at image startup. See ProviderBootstrap
        // javadoc for why a runtime <clinit> is the right pattern.
        try {
            Class.forName("io.github.ofdcli.security.ProviderBootstrap");
        } catch (Throwable t) {
            // Class not on classpath (BC is a transitive dep of ofdrw-gm; this
            // catch is just defensive for slimmed-down test classpaths).
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
        // Native-image: AWT FontManager cannot enumerate host system fonts, so
        // FontLoader.init() ends up with an empty font table and throws
        // "系统中无可用字体". Pre-populate it via reflection (no AWT needed).
        // Done lazily by FontSetup.setup() — only when a rendering subcommand
        // is invoked. Doing it eagerly on every invocation slows down non-rendering
        // commands like `version`, `info`, `extract`.
        int exitCode = new CommandLine(new Main())
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
