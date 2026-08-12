package io.github.ofdcli;

/**
 * Exit codes for ofd-cli. AI agents and shell scripts can branch on these.
 *
 * <ul>
 *   <li>0 OK               - all operations succeeded</li>
 *   <li>1 PARTIAL_FAILURE  - batch operation had at least one failure but others succeeded</li>
 *   <li>2 USAGE_ERROR      - invalid arguments or input</li>
 *   <li>3 INTERNAL_ERROR   - unexpected exception</li>
 *   <li>4 IO_ERROR         - file not found, permission denied, disk full, etc.</li>
 * </ul>
 */
public final class ExitCode {
    public static final int OK = 0;
    public static final int PARTIAL_FAILURE = 1;
    public static final int USAGE_ERROR = 2;
    public static final int INTERNAL_ERROR = 3;
    public static final int IO_ERROR = 4;

    private ExitCode() {}
}
