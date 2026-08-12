package io.github.ofdcli.cmd;

import io.github.ofdcli.Main;
import io.github.ofdcli.VersionInfo;
import picocli.CommandLine.Command;

@Command(
        name = "version",
        description = "Print version information and exit.")
public class VersionCommand implements Runnable {

    @picocli.CommandLine.ParentCommand
    Main parent;

    @Override
    public void run() {
        if (parent != null && parent.isJson()) {
            String json = "{" +
                    "\"tool\":\"ofd-cli\"," +
                    "\"version\":\"" + VersionInfo.VERSION + "\"," +
                    "\"buildTime\":\"" + VersionInfo.BUILD_TIME + "\"," +
                    "\"gitCommit\":\"" + VersionInfo.GIT_COMMIT + "\"," +
                    "\"java\":\"" + escape(System.getProperty("java.version")) + "\"," +
                    "\"os\":\"" + escape(System.getProperty("os.name") + " " + System.getProperty("os.arch")) + "\"" +
                    "}";
            System.out.println(json);
        } else {
            System.out.println("ofd-cli " + VersionInfo.VERSION);
            System.out.println("  build:  " + VersionInfo.BUILD_TIME);
            System.out.println("  commit: " + VersionInfo.GIT_COMMIT);
            System.out.println("  java:   " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")");
            System.out.println("  os:     " + System.getProperty("os.name") + " " + System.getProperty("os.arch"));
        }
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
