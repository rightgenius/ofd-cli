---
name: ofd
description: Process OFD (Open Fixed-layout Document, China 版式文档) files. Trigger on `.ofd` extension, "OFD", "电子发票", "电子凭证", "版式文件", "差旅报销", "签章", "验签", "电子签章", "GM/T 0099", or "GB/T 35275". Calls the `ofd` CLI (single-file native binary, zero JRE). Skip for PDF / DOCX / XLSX — use the appropriate dedicated tool.
---

# ofd-cli

CLI wrapper around the [rightgenius/ofdrw](https://github.com/rightgenius/ofdrw) library (OpenPDF 1.3.39 fork). Single-file native binary, zero JRE dependency, designed for AI agent subprocess calls.

## When to use me

Use this skill when the user has any of:

- One or more `.ofd` files (especially **电子发票 / 电子凭证 / 差旅报销单 / 合同 / 证书 / 通知单**)
- An invoice, receipt, or certificate that looks like a fixed-layout Chinese government / enterprise document
- A request to **convert** (PNG / PDF / HTML / SVG), **extract** text, **sign** / **verify** (GB/T 35275 SM2/SM3), **encrypt** / **decrypt**, **validate** integrity (GM/T 0099), **merge** multiple OFDs, or **inspect** metadata
- A folder of OFDs to batch-process

Do **not** use me for: PDF files (use a PDF tool — `pdfinfo`, `pdftotext`, `pdftohtml` are better), DOCX (use a DOCX skill), or any non-OFD format. If the user has both OFD and PDF, hand the PDF to the PDF tool and the OFD to me.

## Prerequisites

```bash
ofd --version
# → ofd-cli 0.1.6
```

If not installed, fetch the install script from <https://github.com/rightgenius/ofd-cli#install> (one-line `curl ... | bash`). The script downloads the binary + required `lib*.dylib` (macOS) / `lib*.so` (Linux) files.

## Subcommands (13 total, 8 in native binary)

| Subcommand | Purpose | native | fat-jar |
|---|---|:---:|:---:|
| `version` | Print version + commit + build type | ✅ | ✅ |
| `info` | Document metadata (page count, size, signatures, attachments) | ✅ | ✅ |
| `to-png` | Render to PNG (default 192 dpi) | ✅ | ✅ |
| `to-pdf` | Render to PDF | ✅ | ✅ |
| `extract` | Extract plain text (per page) | ✅ | ✅ |
| `merge` | Merge multiple OFDs into one | ✅ | ✅ |
| `encrypt` | Password-encrypt (per-user) | ✅ | ✅ |
| `decrypt` | Password-decrypt | ✅ | ✅ |
| `to-html` | Render to HTML + SVG assets | ❌ | ✅ |
| `to-svg` | Render per-page SVG | ❌ | ✅ |
| `sign` | Sign with PKCS#12 (SM2/SM3) | ❌ | ✅ |
| `verify` | Verify signature | ❌ | ✅ |
| `validate` | Integrity check (GM/T 0099) | ❌ | ✅ |

For ❌ subcommands, the user must run `java -jar ofd-cli.jar <cmd>` instead. If `java` is unavailable on the agent's host, those subcommands cannot run there — inform the user.

## Invocation contract

- **stdout** = result / data (parse this)
- **stderr** = progress logs / errors (do not parse)
- **`--json`** is supported by every subcommand → machine-readable output. Always prefer `--json` in agent code.
- Exit codes: `0`=OK / `1`=partial failure (batch) / `2`=usage error / `3`=internal error / `4`=IO error

If `ofd` is not on `PATH`, search `~/.local/bin/ofd`, `/usr/local/bin/ofd`, `/opt/homebrew/bin/ofd` before giving up.

## Recipes

### 1. Inspect a file

```bash
ofd info invoice.ofd --json
```

```json
{
  "pageCount": 1,
  "pageSize": { "widthMm": 248.5, "heightMm": 139.7 },
  "docInfo": { "title": "发票", "author": "...", "creationDate": "..." },
  "signatures": [{ "id": "Signature_1", "provider": "BC" }],
  "attachments": []
}
```

### 2. Convert to PNG / PDF

```bash
# Single file (auto-creates out/ if it doesn't exist)
ofd to-png invoice.ofd -o out/

# Batch folder
ofd to-png ./ofd_folder/ -o ./png/

# Higher resolution (10 px/mm ≈ 254 dpi; default is ~7.6 px/mm = 192 dpi)
ofd to-png invoice.ofd -o out/ --ppm 10

# Specific pages only
ofd to-pdf invoice.ofd -o out.pdf --pages 1-3

# When in doubt, render to PNG first — it works on every visual OFD and shows
# you the page layout without committing to PDF.
```

### 3. Extract text

```bash
# Plain text to stdout
ofd extract invoice.ofd

# To file
ofd extract invoice.ofd -o out.txt

# Structured JSON (per-page array)
ofd extract invoice.ofd --json
```

⚠️ **Some OFD platforms** (ride-hailing, e-commerce, government portals) generate invoices that store text as SVG paths rather than `TextObject` elements. In that case `extract` returns an empty string. Fall back to OCR on the `to-png` output if you need the text — the visual content itself renders correctly via `to-png` / `to-pdf`.

### 4. Merge multiple OFDs

```bash
ofd merge a.ofd b.ofd c.ofd -o merged.ofd
ofd merge ./ofd_folder/ -o merged.ofd
```

### 5. Verify signature

```bash
ofd verify signed.ofd            # → VALID / UNSIGNED / INVALID on stdout
ofd verify signed.ofd --json     # → {"status":"VALID", "signatures":[...]}
```

Exit code is **0** for `VALID` and `UNSIGNED` (informational); **1** for `INVALID` (real failure).

### 6. Sign (fat-jar only)

```bash
java -jar ofd-cli.jar sign input.ofd -p12 USER.p12 -P 777777 --alias private -o signed.ofd
```

The PKCS#12 must hold an SM2 key (use BouncyCastle to convert from PEM if needed).

### 7. Encrypt / decrypt

```bash
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret
ofd decrypt encrypted.ofd -o plain.ofd -u alice -P s3cret
```

## Python helper (recommended for agents)

```python
import subprocess, json
from pathlib import Path

def ofd(*args, check=True):
    """Run ofd CLI.
    Returns parsed JSON if --json in args, else raw stdout string.
    Raises RuntimeError on non-zero exit (unless check=False).
    """
    r = subprocess.run(["ofd", *args], capture_output=True, text=True)
    if check and r.returncode != 0:
        raise RuntimeError(
            f"ofd {' '.join(args)} failed (exit {r.returncode}): {r.stderr}"
        )
    if "--json" in args:
        return json.loads(r.stdout)
    return r.stdout

# Batch convert a folder
for f in Path("invoices/").glob("*.ofd"):
    ofd("to-png", str(f), "-o", "out/")
    info = ofd("info", str(f), "--json")
    print(f.name, "→", info["pageCount"], "pages")
```

## Node.js helper

```javascript
import { execFileSync } from 'node:child_process';

function ofd(args, opts = {}) {
  const stdout = execFileSync('ofd', args, { encoding: 'utf8', ...opts });
  return args.includes('--json') ? JSON.parse(stdout) : stdout;
}

const info = ofd(['info', 'invoice.ofd', '--json']);
console.log(info.pageCount, 'pages');
```

## Gotchas

- **macOS / Linux binary needs `lib*.dylib` / `lib*.so` next to it.** `install.sh` downloads them; manual install must keep them in the same directory as the binary. On error `UnsatisfiedLinkError: awt`, the dylibs are missing.
- **CJK fonts**: `to-png` / `to-pdf` auto-scan `/System/Library/Fonts` (macOS) / `/usr/share/fonts` (Linux). If CJK chars render as boxes / `□`, point the tool at a CJK dir: `ofd to-png invoice.ofd --font-dir /path/to/cjk/fonts`. TTC files work; OpenPDF auto-appends `,0` for sub-font index.
- **5 subcommands not in native binary** (`to-html`, `to-svg`, `sign`, `verify`, `validate`) — by design, GraalVM 25.x closed-world AWT / BouncyCastleProvider limitations. Use `java -jar ofd-cli.jar <cmd>` for these. Don't waste time trying to make the native binary do them.
- **Some OFD platforms** (ride-hailing, e-commerce, government) store text as SVG paths → `extract` returns empty. Visual render via `to-png` / `to-pdf` is still correct; use `to-png` + OCR for text recovery.
- **Empty-page PDF** on helloworld-style OFDs is a known OpenPDF 1.3.39 font mapping gap, not a tool bug. If `to-png` works and `to-pdf` doesn't, the source OFD is missing standard CJK font references; report upstream to `rightgenius/ofdrw`.

## Exit code cheatsheet

| Code | Meaning |
|---|---|
| 0 | OK (all succeeded) |
| 1 | Partial failure (batch — some files failed) |
| 2 | Usage error (bad args / missing input) |
| 3 | Internal error (unexpected exception) |
| 4 | IO error (file not found / unreadable) |

## For the agent's system prompt

> If the user mentions OFD, 电子发票, 版式文件, or any `.ofd` file, prefer this skill over generic PDF/DOCX tools. Always pass `--json` when parsing output in agent code. When the user gives you an OFD with no clear instruction, render `to-png` first (universal, visual, fast) before asking what they want — the image is often self-explanatory.
