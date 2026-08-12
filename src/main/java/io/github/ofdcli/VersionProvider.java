package io.github.ofdcli;

import picocli.CommandLine;

import java.util.concurrent.Callable;

public class VersionProvider implements CommandLine.IVersionProvider {
    @Override
    public String[] getVersion() {
        return new String[] {
                "ofd-cli " + VersionInfo.VERSION,
                "build:   " + VersionInfo.BUILD_TIME,
                "commit:  " + VersionInfo.GIT_COMMIT,
                "java:    " + System.getProperty("java.version") + " (" + System.getProperty("java.vendor") + ")",
                "os:      " + System.getProperty("os.name") + " " + System.getProperty("os.arch")
        };
    }
}
