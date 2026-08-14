---
name: ofd
description: Process OFD (Open Fixed-layout Document, China 版式文档) files. Use when the user has an .ofd file or asks about OFD / 电子发票 / 差旅报销 / 签章 / 验签. Calls the `ofd` CLI (single-file binary, zero JRE).
---

# ofd-cli

CLI wrapper around the [rightgenius/ofdrw](https://github.com/rightgenius/ofdrw) library. Single-file native binary, zero JRE dependency, designed for AI agent subprocess calls.

## When to use me

Use this skill when the user:
- Has one or more `.ofd` files (especially **电子发票 / 电子凭证 / 差旅报销单 / 合同 / 证书**)
- Asks to **convert** (PNG / PDF / HTML / SVG), **extract text**, **sign** / **verify** (GB/T 35275 SM2/SM3), **encrypt** / **decrypt**, **validate** integrity (GM/T 0099), or **inspect** metadata
- Wants to batch-process a folder of OFDs
- Mentions "OFD" without an obvious alternative tool

Do **not** use me for: PDF processing (use a PDF tool), DOCX (use a DOCX tool), OFD-to-PDF that needs perfect formatting (the OpenPDF path is good for invoices but not for all layouts).

## Prerequisites

```bash
# Verify installed
ofd --version
# → ofd-cli 0.1.6
```

If not installed: <https://github.com/rightgenius/ofd-cli#install> (one-line curl).

## Subcommands (13 total, 8 in native binary)

| Subcommand | Purpose | native | fat-jar |
|---|---|:---:|:---:|
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

For ❌ subcommands, use `java -jar ofd-cli.jar <cmd>`.

## Invocation contract

- **stdout** = result / data
- **stderr** = progress logs / errors
- **`--json`** is supported by every subcommand → machine-readable output
- Exit codes: `0`=OK / `1`=partial failure / `2`=usage error / `3`=internal error / `4`=IO error

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
# Single file
ofd to-png invoice.ofd -o out/

# Batch folder
ofd to-png ./ofd_folder/ -o ./png/

# Higher resolution (10 px/mm ≈ 254 dpi)
ofd to-png invoice.ofd -o out/ --ppm 10

# Specific pages
ofd to-pdf invoice.ofd -o out.pdf --pages 1-3
```

### 3. Extract text

```bash
# Plain text to stdout
ofd extract invoice.ofd

# To file
ofd extract invoice.ofd -o out.txt

# Structured JSON
ofd extract invoice.ofd --json

# ⚠️ OFD that stores text as SVG paths (e.g. some 滴滴电子发票) returns empty
# — fall back to OCR on the to-png output if text is required.
```

### 4. Merge multiple OFDs

```bash
ofd merge a.ofd b.ofd c.ofd -o merged.ofd
ofd merge ./ofd_folder/ -o merged.ofd
```

### 5. Verify signature

```bash
ofd verify signed.ofd            # → VALID / UNSIGNED / INVALID
ofd verify signed.ofd --json
```

Exit code is always 0 for `VALID` / `UNSIGNED`; 1 for `INVALID`.

### 6. Sign (fat-jar only)

```bash
java -jar ofd-cli.jar sign input.ofd -p12 USER.p12 -P 777777 --alias private -o signed.ofd
```

### 7. Encrypt / decrypt

```bash
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret
ofd decrypt encrypted.ofd -o plain.ofd -u alice -P s3cret
```

## Python helper (recommended for agents)

```python
import subprocess, json

def ofd(*args):
    """Run ofd CLI. Returns parsed JSON if --json in args, else stdout string.
    Raises RuntimeError on non-zero exit."""
    r = subprocess.run(["ofd", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ofd {' '.join(args)} failed (exit {r.returncode}): {r.stderr}")
    if "--json" in args:
        return json.loads(r.stdout)
    return r.stdout

# Example: batch convert a folder
for f in Path("invoices/").glob("*.ofd"):
    ofd("to-png", str(f), "-o", "out/")
    info = ofd("info", str(f), "--json")
    print(f["name"], "→", info["pageCount"], "pages")
```

## Gotchas

- **macOS / Linux** binary needs `lib*.dylib` / `lib*.so` next to it. `install.sh` downloads them; manual install must keep them in the same directory.
- **CJK fonts**: `to-png` / `to-pdf` auto-scan `/System/Library/Fonts` etc. If CJK chars render as boxes, add `--font-dir /path/to/cjk/fonts` (TTC works; OpenPDF auto-appends `,0` for sub-font).
- **`to-html` / `to-svg` / `sign` / `verify` / `validate` not in native binary** — by design (GraalVM 25.x limitations). Use `ofd-cli.jar` for these.
- **Didi / 滴滴电子发票**: text is stored as SVG paths, so `extract` returns empty. Use `to-png` then OCR if text is required; the visual content renders correctly.

## Exit code cheatsheet

| Code | Meaning |
|---|---|
| 0 | OK (all succeeded) |
| 1 | Partial failure (batch — some files failed) |
| 2 | Usage error (bad args / missing input) |
| 3 | Internal error (unexpected exception) |
| 4 | IO error (file not found / unreadable) |
