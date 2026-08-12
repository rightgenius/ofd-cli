# ofd-cli

Command-line tool for **OFD** (Open Fixed-layout Document, 国标 GB/T 33190-2016) processing.

Built on top of [ofdrw](https://github.com/ofdrw/ofdrw) (Apache 2.0). Single binary, zero JRE dependency, AI-friendly.

## Status

🚧 **0.2.0-SNAPSHOT — `version` + `to-png` working, native-image PoC validated**

Both the fat-jar (JVM) and the GraalVM native-image build render Chinese OFD
invoices (滴滴电子发票 etc.) to PNG correctly. Native binary is ~38MB,
zero JRE dependency, cold-starts in ~25ms.

## Subcommands

| Command | Status | Description |
|---------|--------|-------------|
| `ofd version` | ✅ | Print tool / JVM / OS info (human or `--json`) |
| `ofd to-png` | ✅ | Render OFD page(s) to PNG, batch directory supported |
| `ofd info` | 🔜 v0.2 | Show OFD metadata (pages, dimensions, signatures, attachments) |
| `ofd to-pdf` | 🔜 v0.2 | Export OFD to PDF |
| `ofd extract` | 🔜 v0.2 | Extract text content |
| `ofd to-html` / `to-svg` / `merge` | 🔜 v0.3 | more formats + composite |
| `ofd sign` / `verify` / `encrypt` / `decrypt` / `validate` | 🔜 v0.4 | crypto + OFD-A |

## Features

- `ofd to-png` — render OFD to PNG (single page or batch directory)
- `ofd info` — show OFD metadata (pages, dimensions, signatures, attachments)
- `ofd to-pdf` — export OFD to PDF
- `ofd to-html` — export OFD to HTML
- `ofd to-svg` — export OFD to SVG
- `ofd extract` — extract text content
- `ofd merge` — merge multiple OFD files
- `ofd sign` — sign with digital signature
- `ofd verify` — verify signature
- `ofd encrypt` / `ofd decrypt` — password-protect OFD
- `ofd validate` — check OFD-A compliance

## Installation

Coming soon — for now build from source (see below).

```bash
brew install ofdcli/tap/ofd            # macOS / Linux (planned)
scoop install ofd                       # Windows (planned)
```

Or download a single binary from [Releases](https://github.com/ofdcli/ofd-cli/releases).

## Usage

```bash
ofd --version
ofd to-png invoice.ofd -o invoice.png
ofd to-png invoices/ -o rendered/         # batch: directory in, directory out
ofd to-png invoice.ofd -o out/ --ppm 10   # higher resolution
ofd to-png invoice.ofd -o out/ --font-dir /opt/fonts
ofd info invoice.ofd                      # (planned)
ofd to-pdf invoice.ofd -o invoice.pdf     # (planned)
```

### JSON output (for AI agents)

```bash
ofd --json to-png invoices/ -o out/        # full machine-readable report
```

Sample output:
```json
{"total":1,"ok":1,"failed":0,"elapsedMs":304,
 "outputDir":"/tmp/out",
 "results":[{"file":"invoice.ofd","status":"ok","pages":1,
   "outputs":["invoice_p1.png"],"elapsedMs":300}]}
```

### Exit codes

| Code | Meaning |
|------|---------|
| 0 | Success |
| 1 | Partial failure (one or more files failed in batch) |
| 2 | Usage / argument error |
| 3 | Internal error |
| 4 | I/O error |

### stdout vs stderr

- **stdout** — result data (pipe-friendly)
- **stderr** — progress logs and human-readable errors

## Building from source

Prerequisites:
- JDK 11+ (Temurin, Zulu, GraalVM, or any distribution)
- Maven 3.8+
- For native-image: GraalVM JDK (any 21+)

```bash
# Fat-jar (any JDK 11+)
mvn package
java -jar target/ofd-cli.jar to-png invoice.ofd -o out/

# Native-image (GraalVM JDK)
mvn -Pnative package
./target/ofd to-png invoice.ofd -o out/
```

To get a GraalVM JDK:
```bash
brew install --cask graalvm/tap/graalvm-ce-java17
export JAVA_HOME=/opt/homebrew/opt/graalvm-ce-java17
```

### How fonts work in native-image

The native binary has no AWT display server, so it can't enumerate system
fonts via the JDK's `Font.createFont` path. Instead, on startup
[`NativeImageFontBootstrap`](src/main/java/io/github/ofdcli/awt/NativeImageFontBootstrap.java):

1. Scans `/System/Library/Fonts` (macOS) / `C:/Windows/Fonts` (Windows) /
   `/usr/share/fonts` (Linux) directly from the filesystem.
2. Validates each file with ofdrw's `TrueTypeFont` parser (no AWT).
3. Registers every valid font under a wide set of CJK name aliases (宋体/黑体/楷体/SimSun/...).
4. Reflectively replaces the `FontLoader` singleton with a pre-populated
   instance, bypassing its broken AWT-based `init()`.

This works on all three platforms without bundling fonts in the binary.
On a regular JVM the bootstrap is a no-op.

## License

Apache 2.0
