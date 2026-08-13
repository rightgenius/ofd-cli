# ofd-cli

Command-line tool for **OFD** (Open Fixed-layout Document) processing, built
on [ofdrw](https://github.com/ofdrw/ofdrw). Single static binary — no
JRE/JDK install required on the target machine.

```
$ ofd --help
Usage: ofd [-hV] [--json] [COMMAND]
Command-line tool for OFD (Open Fixed-layout Document) processing.
Commands:
  version   Print version information and exit.
  info      Show OFD document metadata.
  to-png    Convert OFD file(s) to PNG images.
  to-pdf    Convert OFD file(s) to PDF.
  to-html   Convert OFD file(s) to HTML.
  to-svg    Convert OFD file(s) to per-page SVG files.
  extract   Extract plain text from OFD file(s).
  merge     Merge multiple OFD files into a single OFD.
  sign      Sign an OFD file (GB/T 35275 SM2/SM3).  (fat-jar only)
  verify    Verify digital signatures on an OFD file.  (fat-jar only)
  encrypt   Encrypt an OFD file with a user password.
  decrypt   Decrypt an OFD file with a user password.
  validate  Apply or verify OFD integrity protection (GM/T 0099).  (fat-jar only)
```

## Features

- **13 subcommands** covering the full OFD lifecycle: inspect, render,
  extract, merge, sign, verify, encrypt, decrypt, integrity-protect.
- **Single static binary** via GraalVM native-image (~54 MB, no JRE).
- **AI-friendly**: standardized exit codes (0/1/2/3/4), `--json` global flag
  on every subcommand for machine-readable output, logs to stderr, results
  to stdout.
- **Cross-platform** (macOS / Linux / Windows) — auto-discovers CJK fonts
  from the host system.
- **Two run modes**:
  - **fat-jar** (`java -jar ofd-cli.jar`) — full 13 subcommands, requires JRE 11+
  - **native** (`./ofd`) — 10 subcommands, sub-second startup, no JRE

## Quick Start

```bash
# Native binary (no JRE required)
./ofd info invoice.ofd
./ofd to-png invoice.ofd -o out/
./ofd to-pdf invoice.ofd -o out/invoice.pdf
./ofd extract invoice.ofd -o out.txt

# Fat-jar fallback (when native lacks a feature you need)
java -jar target/ofd-cli.jar to-html invoice.ofd -o out/invoice.html

# JSON for AI / pipelines
./ofd info invoice.ofd --json
```

## Subcommand Reference

### `version`
Print version information (name, build time, git commit, Java/OS).

### `info <file>`
Show document metadata: page count, page dimensions, document info
(title, author, dates, keywords), signature presence, attachment list.

```bash
ofd info invoice.ofd                 # human-readable
ofd info invoice.ofd --json          # structured output
ofd info ./ofd_folder/               # inspect first OFD in a directory
```

### `to-png <file|dir> -o <out>`
Render each page to a PNG file. Default resolution 7.56 px/mm ≈ 192 dpi.

```bash
ofd to-png invoice.ofd -o out/
ofd to-png ./ofd_folder/ -o ./png/ --ppm 10     # higher resolution
ofd to-png invoice.ofd --font-dir /extra/fonts  # extra CJK fonts
ofd to-png invoice.ofd --no-default-fonts       # skip system font scan
```

### `to-pdf <file> -o <out.pdf>`
Convert to PDF (PDFBox 2.x, no AGPL entanglement).

```bash
ofd to-pdf invoice.ofd -o out/invoice.pdf
ofd to-pdf invoice.ofd -o out/ --pages 1-3       # specific pages
```

### `to-html <file> -o <out.html>`
Render to HTML. Sibling `<out>.html-ofd-svg/` directory holds the
per-page SVG assets referenced by the HTML.

```bash
ofd to-html invoice.ofd -o out/invoice.html
ofd to-html invoice.ofd -o out/ -w 1200          # custom viewport
```

### `to-svg <file> -o <out-dir>`
Per-page SVG files in `<out-dir>/<basename>/Page_1.svg` etc.

```bash
ofd to-svg invoice.ofd -o svg_out/
ofd to-svg invoice.ofd -o svg_out/ --ppm 10      # higher resolution
```

### `extract <file>`
Plain-text extraction. Uses `ContentExtractor` which walks the page
tree and collects `TextObject` content. Note: OFDs that render text
as vector paths (e.g. some Didi e-invoices) return empty text.

```bash
ofd extract invoice.ofd                  # → stdout
ofd extract invoice.ofd -o out.txt       # → file
ofd extract ./ofd_folder/ -o ./text/     # batch
ofd extract invoice.ofd --json           # structured per-page output
ofd extract invoice.ofd --separator '---'   # custom page separator
```

### `merge <file>... -o <out.ofd>`
Concatenate pages from multiple OFDs into a single OFD.

```bash
ofd merge a.ofd b.ofd c.ofd -o merged.ofd
ofd merge ./ofd_folder/ -o merged.ofd
```

### `sign <file> -o <signed.ofd> -p12 <p12> -P <password>`
Sign with GB/T 35275 SM2-with-SM3 via PKCS#12 keystore.

```bash
ofd sign input.ofd -p12 USER.p12 -P 777777 --alias private -o signed.ofd
```

### `verify <file>`
Verify digital signature (GB/T 35275). Returns:
- exit 0 + `VALID` if signature is valid
- exit 0 + `UNSIGNED` if file has no signatures
- exit 1 + `INVALID` if signature failed verification

```bash
ofd verify signed.ofd
ofd verify signed.ofd --json
```

### `encrypt <file> -o <out> -u <user> -P <password>`
Encrypt with a user password (OFD-encrypted container is a valid OFD).

```bash
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret -t 1   # owner type
```

### `decrypt <file> -o <out> -P <password>`
Decrypt a previously encrypted OFD.

```bash
ofd decrypt encrypted.ofd -o plain.ofd -u alice -P s3cret
ofd decrypt encrypted.ofd -o plain.ofd -P s3cret    # password only
```

### `validate <file>`
Apply or verify OFD integrity protection (GM/T 0099 7.4.6).

```bash
# Verify integrity
ofd validate signed.ofd
ofd validate signed.ofd --json

# Apply integrity protection (requires PKCS#12)
ofd validate input.ofd -o protected.ofd --apply \
  -p12 USER.p12 -P 777777 --alias private
```

## Exit Codes

| Code | Meaning           | Example                              |
|------|-------------------|--------------------------------------|
| 0    | OK                | All inputs processed successfully    |
| 1    | PARTIAL_FAILURE   | Batch run had some failures          |
| 2    | USAGE_ERROR       | Missing or invalid arguments         |
| 3    | INTERNAL_ERROR    | Unexpected exception                |
| 4    | IO_ERROR          | File not found / not readable        |

## Build from Source

Requires JDK 11+ and Maven 3.9+.

```bash
# fat-jar (always works, full feature set)
mvn -DskipTests package
java -jar target/ofd-cli.jar --version

# native binary (one-time setup of GraalVM is required)
brew install --cask graalvm/tap/graalvm-jdk           # or download manually
export JAVA_HOME=/path/to/graalvm
mvn -Pnative -DskipTests clean package
./target/ofd --version
```

### Native-image limitations

The native binary exposes only **10 of the 13** subcommands. The
`sign`, `verify`, and `validate` subcommands are intentionally not
registered on the native binary (see `Main.NATIVE_SUBCOMMANDS` in
`Main.java`); they require a BouncyCastleProvider whose closed-world
registration is not supported in GraalVM 25.0.4 CE
(see [oracle/graal#13412](https://github.com/oracle/graal/issues/13412)).

For a complete matrix of what works where:

| Subcommand                | Native  | Fat-jar | Notes                                    |
|---------------------------|---------|---------|------------------------------------------|
| `version`, `info`         | ✅      | ✅      |                                          |
| `to-png`, `extract`       | ✅      | ✅      |                                          |
| `merge`, `encrypt`, `decrypt` | ✅  | ✅      |                                          |
| `to-pdf`                  | ❌      | ✅      | AWT CFontManager / PDFBox reflection     |
| `to-html`, `to-svg`       | ❌      | ✅      | AWT CFontManager JNI lookup              |
| `sign`                    | ❌ (not registered) | ✅ | GraalVM 25.0.4 CE BC provider limit  |
| `verify`                  | ❌ (not registered) | ✅ | same                                     |
| `validate`                | ❌ (not registered) | ✅ | same                                     |

For the subcommands the native binary doesn't support, use the fat-jar:

```bash
java -jar target/ofd-cli.jar to-html invoice.ofd -o out.html
java -jar target/ofd-cli.jar sign input.ofd -p12 cert.p12 -P pwd -o signed.ofd
```

The CLI's own `--help` footer lists the distribution split so users
discover it without reading this README.

## Font Setup

`to-png` / `to-pdf` / `to-html` / `to-svg` need CJK fonts (Song, Kai,
Hei) which the standard OFD issuer references. On **macOS / Linux /
Windows** the CLI auto-scans the system font directories:

- macOS: `/System/Library/Fonts`, `/System/Library/Fonts/Supplemental`,
  `~/Library/Fonts`
- Linux: `/usr/share/fonts`, `/usr/local/share/fonts`, `~/.fonts`
- Windows: `C:/Windows/Fonts`

Override with `--font-dir /path/to/fonts` (repeatable) or
`--no-default-fonts` to skip the system scan.

On **native-image**, AWT cannot enumerate fonts so we pre-populate the
`FontLoader` singleton by scanning the filesystem directly and
registering each valid font under 30+ CJK aliases (宋体, SimSun,
黑体, KaiTi, …). See `io.github.ofdcli.awt.NativeImageFontBootstrap`.

## Testing

Two test layers:

```bash
# Unit tests (JUnit 5, fast)
mvn -o test

# Integration tests (39 cases per mode, 78 total)
./src/test/scripts/run-tests.sh -m jar
./src/test/scripts/run-tests.sh -m native
```

Test fixtures are copied from the ofdrw project's `target/test-classes/`
(see `src/test/resources/`). The PKCS#12 keystore `USER.p12` has
alias `private` and password `777777`.

## License

Apache 2.0. Bundled third-party components: see the per-jar NOTICE files
in `target/ofd-cli.jar`.
