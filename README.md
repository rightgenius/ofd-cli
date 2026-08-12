# ofd-cli

Command-line tool for **OFD** (Open Fixed-layout Document, 国标 GB/T 33190-2016) processing.

Built on top of [ofdrw](https://github.com/ofdrw/ofdrw) (Apache 2.0). Single binary, zero JRE dependency, AI-friendly.

## Status

🚧 **0.1.0-SNAPSHOT — scaffolding only**

This is the very first commit. Only `ofd version` works so far; subcommands land in v0.2+.

## Roadmap

| Version | Subcommands | Notes |
|---------|-------------|-------|
| 0.1.0 | `version` | GraalVM native-image pipeline validated |
| 0.2.0 | `info` `to-png` `to-pdf` `extract` | core read + render |
| 0.3.0 | `to-html` `to-svg` `merge` | more formats + composite |
| 0.4.0 | `sign` `verify` `encrypt` `decrypt` `validate` | crypto + OFD-A |

## Features (planned)

- `ofd info` — show OFD metadata (pages, dimensions, signatures, attachments)
- `ofd to-png` — render OFD to PNG (single page or batch)
- `ofd to-pdf` — export OFD to PDF
- `ofd to-html` — export OFD to HTML
- `ofd to-svg` — export OFD to SVG
- `ofd extract` — extract text content
- `ofd merge` — merge multiple OFD files
- `ofd sign` — sign with digital signature
- `ofd verify` — verify signature
- `ofd encrypt` / `ofd decrypt` — password-protect OFD
- `ofd validate` — check OFD-A compliance

## Installation (planned)

```bash
brew install ofdcli/tap/ofd            # macOS / Linux
scoop install ofd                       # Windows
```

Or download a single binary from [Releases](https://github.com/ofdcli/ofd-cli/releases).

## Usage (planned)

```bash
ofd --version
ofd info invoice.ofd
ofd to-png invoice.ofd -o invoice.png
ofd to-png invoices/ -o rendered/         # batch: directory in, directory out
ofd extract invoice.ofd
ofd to-pdf invoice.ofd -o invoice.pdf
```

### JSON output (for AI agents)

```bash
ofd info invoice.ofd --json
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
- All log lines are prefixed so they can be filtered with `2>logs.txt`

## Building from source

Prerequisites:
- JDK 11+ (Temurin, Zulu, GraalVM, or any distribution)
- Maven 3.8+

```bash
mvn package                              # build fat-jar: target/ofd-cli.jar
java -jar target/ofd-cli.jar version     # run via fat-jar

mvn -Pnative package                     # build native image (needs GraalVM JDK)
./target/ofd version                     # run native binary
```

To get a GraalVM JDK:
```bash
brew install --cask graalvm/tap/graalvm-ce-java17
export JAVA_HOME=/opt/homebrew/opt/graalvm-ce-java17
```

## License

Apache 2.0
