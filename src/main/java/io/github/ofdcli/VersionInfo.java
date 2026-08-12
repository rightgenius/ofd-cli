package io.github.ofdcli;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public final class VersionInfo {
    public static final String VERSION;
    public static final String BUILD_TIME;
    public static final String GIT_COMMIT;

    static {
        Properties p = new Properties();
        try (InputStream in = VersionInfo.class.getResourceAsStream("/ofd-cli.properties")) {
            if (in != null) {
                p.load(in);
            }
        } catch (IOException ignored) {
            // Resource missing in unit tests is fine; fall back to defaults.
        }
        VERSION = resolve(p, "version", "0.1.0-SNAPSHOT");
        BUILD_TIME = resolve(p, "buildTime", "dev");
        GIT_COMMIT = resolve(p, "gitCommit", "no-commit");
    }

    /**
     * Properties loaded from a resource filtered by Maven may contain unresolved
     * placeholders like {@code ${git.commit.id.abbrev}} when the underlying build
     * extension (e.g. git-commit-id-plugin) could not determine the value. Detect
     * those and fall back to the default so users never see raw placeholders.
     */
    private static String resolve(Properties p, String key, String defaultValue) {
        String v = p.getProperty(key, defaultValue);
        if (v == null || v.isEmpty() || (v.startsWith("${") && v.endsWith("}"))) {
            return defaultValue;
        }
        return v;
    }

    private VersionInfo() {}
}
