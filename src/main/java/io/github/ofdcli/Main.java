package io.github.ofdcli;

import io.github.ofdcli.awt.NativeImageFontBootstrap;
import io.github.ofdcli.cmd.ToPngCommand;
import io.github.ofdcli.cmd.VersionCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(
        name = "ofd",
        mixinStandardHelpOptions = true,
        versionProvider = VersionProvider.class,
        subcommands = {
                VersionCommand.class,
                ToPngCommand.class,
                // InfoCommand.class,        // v0.2
                // ToPdfCommand.class,       // v0.2
                // ExtractCommand.class,     // v0.2
                // ToHtmlCommand.class,      // v0.3
                // ToSvgCommand.class,       // v0.3
                // MergeCommand.class,       // v0.3
                // SignCommand.class,        // v0.4
                // VerifyCommand.class,      // v0.4
                // EncryptCommand.class,     // v0.4
                // DecryptCommand.class,     // v0.4
                // ValidateCommand.class,    // v0.4
        },
        description = "Command-line tool for OFD (Open Fixed-layout Document) processing.",
        footer = "Run 'ofd <command> --help' for command-specific help.")
public class Main implements Runnable {

    @Option(names = {"--json"},
            description = "Output machine-readable JSON to stdout (default: human-readable).")
    boolean json;

    public static void main(String[] args) {
        // Force headless BEFORE any AWT subsystem initializes — native-image
        // can't open a graphics device and would otherwise try OpenGL/CGL.
        if (System.getProperty("java.awt.headless") == null) {
            System.setProperty("java.awt.headless", "true");
        }
        // Native-image: AWT FontManager cannot enumerate host system fonts, so
        // FontLoader.init() ends up with an empty font table and throws
        // "系统中无可用字体". Pre-populate it via reflection (no AWT needed).
        NativeImageFontBootstrap.bootstrap();
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
