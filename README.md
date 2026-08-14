# ofd-cli

> **OFD**（Open Fixed-layout Document，**版式文档**）命令行工具，基于 [ofdrw/ofdrw](https://github.com/ofdrw/ofdrw)（Apache 2.0 原版）封装，**PDF 渲染 + 国密签名/加密** 改用 [rightgenius/ofdrw](https://github.com/rightgenius/ofdrw) 的 fork（`ofdrw-converter` 切 OpenPDF，`ofdrw-gm` / `ofdrw-sign` / `ofdrw-crypto` 把 JCE 迁到 BC 轻量级 crypto API）以兼容 GraalVM native-image。
> 专为 **AI Agent** 与**自动化流水线**设计：单文件静态二进制、标准化退出码、JSON 输出。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![CI](https://img.shields.io/github/actions/workflow/status/rightgenius/ofd-cli/ci.yml?branch=main&label=CI)](https://github.com/rightgenius/ofd-cli/actions/workflows/ci.yml)
[![Release](https://img.shields.io/github/v/release/rightgenius/ofd-cli?label=release)](https://github.com/rightgenius/ofd-cli/releases/latest)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://adoptium.net/)
[![Native binary](https://img.shields.io/badge/native--image-53MB-success.svg)](https://www.graalvm.org/native-image/)
[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux-lightgrey.svg)](#)

---

## 这是什么

`ofd-cli` 把 Java 生态里最完善的 OFD 处理库 [`ofdrw/ofdrw`](https://github.com/ofdrw/ofdrw)（Apache 2.0）装进一个**单文件可执行二进制**里，零 JRE 依赖，可以直接被 shell、CI、AI agent 调用。

由于原版 `ofdrw/ofdrw` 的 PDF 渲染依赖 Apache PDFBox，PDFBox 在静态初始化时触发 `java.awt.image.ColorModel` → `Toolkit.<clinit>` → `System.loadLibrary("awt")`，在 GraalVM native-image 的 closed-world substrate VM 里没有 `libawt.dylib` 可加载，native binary 跑 `to-pdf` 直接 SIGABRT。

为支持 native-image，本项目改用 [`rightgenius/ofdrw`](https://github.com/rightgenius/ofdrw) —— `ofdrw/ofdrw` 的 fork。两类 GraalVM 兼容问题都在 fork 里修了：
- **PDF 渲染**：`ofdrw-converter` 把 PDFBox 切到 [OpenPDF 1.3.39](https://github.com/LibrePDF/OpenPDF)（LGPL/MPL，可商用），绕开 AWT `libawt.dylib` 加载
- **国密签名 / 加密**：`ofdrw-gm` / `ofdrw-sign` / `ofdrw-crypto` 把 JCE provider API（`Signature.getInstance("SM3WithSM2", "BC")` / `KeyStore.getInstance("PKCS12", "BC")`）迁到 BC 轻量级 crypto API（`SM2Signer` / `SM3Digest` / `PKCS12PfxPdu` + `BcPKCS12PBEInputDecryptorProviderBuilder`），让 `sign` / `verify` / `validate`（读 + 写）都能在 native binary 跑

详见 [上游](#上游) 段。

它解决三件事：

1. **AI Agent 友好**——标准化退出码、`--json` 全局 flag、日志走 stderr、结果走 stdout。
2. **零部署摩擦**——把 jar 编译成 native binary，用户机器不需要任何 Java 运行时。
3. **不重复造轮子**——所有 OFD 解析、渲染、签名、加密逻辑都委托给 ofdrw，本项目只做 CLI 包装和 native 编译。

适用场景：

- 🧾 发票 / 电子凭证批处理（PDF 转 PNG、文本提取、合规签名）
- 🤖 AI agent 操作 OFD 文件（差旅报销、合同审核）
- 🔁 CI / 流水线集成（每条命令都有 exit code 和 JSON 输出）

---

## 安装

### 一行安装（推荐）✨

**macOS / Linux**：

```bash
curl -fsSL https://raw.githubusercontent.com/rightgenius/ofd-cli/main/scripts/install.sh | sh
```

脚本会自动：
1. 识别你的平台（macOS arm64 / Linux x64 / Linux arm64）
2. 从最新 [GitHub Release](https://github.com/rightgenius/ofd-cli/releases/latest) 下载对应的 native binary
3. 校验 SHA-256 哈希
4. 同时下载 binary 所需的 `lib*.dylib` / `lib*.so`（macOS AWT 依赖，必须跟 binary 同目录）
5. 安装到 `/usr/local/bin/ofd`（有写权限）或 `~/.local/bin/ofd`（无 sudo 时）
6. 跑 `ofd --version` 验证

环境变量：
- `OFD_VERSION=v0.4.0` — 锁定特定版本（默认：latest）
- `OFD_INSTALL_DIR=/path` — 覆盖安装位置

### Homebrew（待发布）

```bash
brew install rightgenius/tap/ofd
```

### 手动下载

从 [Releases](https://github.com/rightgenius/ofd-cli/releases/latest) 页面下载对应平台的 binary：

| 平台 | 文件名 |
|---|---|
| macOS arm64 (Apple Silicon) | `ofd-darwin-arm64` |
| macOS x86_64 (Intel) | _暂未提供_ |
| Linux x86_64 | `ofd-linux-x64` |
| Linux arm64 | _暂未提供_ |
| Windows x86_64 | _暂未提供_（先用 fat-jar） |

```bash
# 示例：手动安装 macOS arm64 版本
curl -L -o ofd https://github.com/rightgenius/ofd-cli/releases/latest/download/ofd-darwin-arm64
chmod +x ofd
sudo mv ofd /usr/local/bin/
ofd --version
```

### Windows

PowerShell：

```powershell
Invoke-WebRequest -Uri "https://github.com/rightgenius/ofd-cli/releases/latest/download/ofd-windows-x64.exe" -OutFile "ofd.exe"
```

或先用 fat-jar（功能完整，需要 JRE 11+）：

```powershell
Invoke-WebRequest -Uri "https://github.com/rightgenius/ofd-cli/releases/latest/download/ofd-cli.jar" -OutFile "ofd-cli.jar"
java -jar ofd-cli.jar --version
```

### 校验下载

每个 release 附带 `SHA256SUMS` 文件：

```bash
# 下载后校验
curl -L -O https://github.com/rightgenius/ofd-cli/releases/latest/download/SHA256SUMS
sha256sum -c --ignore-missing SHA256SUMS
```

---

## 快速开始

```bash
$ ofd --version
ofd-cli 0.3.0
  commit: 82273b0
  java:   25.0.4 (GraalVM Community)
  os:     Mac OS X aarch64

$ ofd info invoice.ofd
File:    invoice.ofd
Pages:   1
Size:    248.5 × 139.7 mm
DocInfo: 发票 (issued 2026-07-15)
Signatures: 1
Attachments: 0

$ ofd to-png invoice.ofd -o out/
Rendered 1 page(s) to out/

$ ofd extract invoice.ofd
发票号码: 050001700111
开票日期: 2026年07月15日
...

# 喂给 AI agent 的 JSON
$ ofd info invoice.ofd --json | jq '.'
{
  "pageCount": 1,
  "pageSize": { "widthMm": 248.5, "heightMm": 139.7 },
  "docInfo": { "title": "发票", ... },
  "signatures": [{ "id": "Signature_1", "provider": "BC" }],
  "attachments": []
}
```

---

## 两种发行版

| 维度 | `ofd`（native binary） | `ofd-cli.jar`（fat-jar） |
|---|---|---|
| 启动时间 | < 50 ms | ~500 ms |
| 大小 | 53 MB | 34 MB |
| JRE 依赖 | 无 | 需要 JRE 11+ |
| 子命令数 | **12** | **13** |

native binary 是首选。fat-jar 是兜底——当 native 缺某个子命令时用它。

---

## 子命令速查

| 子命令 | 用途 | native | fat-jar |
|---|---|:---:|:---:|
| `version` | 版本信息 | ✅ | ✅ |
| `info` | 文档元数据（页数、签名、附件） | ✅ | ✅ |
| `to-png` | 渲染为 PNG（默认 192 dpi） | ✅ | ✅ |
| `to-pdf` | 渲染为 PDF | ✅ | ✅ |
| `to-html` | 渲染为 HTML（含 SVG 资源） | ❌ | ✅ |
| `to-svg` | 渲染为 SVG（每页一个文件） | ❌ | ✅ |
| `extract` | 提取纯文本 | ✅ | ✅ |
| `merge` | 合并多个 OFD | ✅ | ✅ |
| `sign` | 数字签名（GB/T 35275 SM2/SM3） | ✅ | ✅ |
| `verify` | 验签 | ✅ | ✅ |
| `encrypt` | 密码加密 | ✅ | ✅ |
| `decrypt` | 密码解密 | ✅ | ✅ |
| `validate` | 完整性校验（GM/T 0099） | ✅ | ✅ |

> ❌ 标在 native 上的子命令是被**故意隐藏**的（不是有 bug）——见 [Native 二进制限制](#native-二进制限制) 一节。

---

## 子命令详细参考

### `info <file>`

展示文档元数据：页数、页面尺寸、文档信息（标题、作者、日期、关键词）、签名状态、附件列表。

```bash
ofd info invoice.ofd                 # 人类可读
ofd info invoice.ofd --json          # 结构化输出
ofd info ./ofd_folder/               # 目录下第一个 OFD
```

### `to-png <file|dir> -o <out>`

把每页渲染成 PNG。默认 7.56 px/mm ≈ 192 dpi。

```bash
ofd to-png invoice.ofd -o out/
ofd to-png ./ofd_folder/ -o ./png/
ofd to-png invoice.ofd --ppm 10         # 高分辨率
ofd to-png invoice.ofd --font-dir /extra/fonts       # 追加字体目录
ofd to-png invoice.ofd --no-default-fonts            # 跳过系统字体扫描
```

### `to-pdf <file> -o <out.pdf>`

渲染为 PDF。PDF 渲染基于 [`rightgenius/ofdrw`](https://github.com/rightgenius/ofdrw) 的 [OpenPDF 1.3.39](https://github.com/LibrePDF/OpenPDF) fork（**LGPL/MPL**，可商用），native-image 下不再触发 AWT FontManager JNI 失败，**macOS 中文 / 拉丁混排正常渲染**（TTC 字体自动加 `,0` sub-font 语法）。`sign` / `verify` / `validate` 也走这个 fork 的 `ofdrw-gm` / `ofdrw-sign` / `ofdrw-crypto` 三个子模块（BC 轻量级实现）。OFD 解析 / 排版等其他部分仍用原版 [ofdrw/ofdrw](https://github.com/ofdrw/ofdrw)。

```bash
ofd to-pdf invoice.ofd -o out/invoice.pdf
ofd to-pdf invoice.ofd -o out/ --pages 1-3          # 指定页码
```

### `to-html <file> -o <out.html>`

渲染为 HTML。同级目录 `<out>.html-ofd-svg/` 存放每页的 SVG 资源。

```bash
ofd to-html invoice.ofd -o out/invoice.html
ofd to-html invoice.ofd -o out/ -w 1200              # 自定义视口宽度
```

### `to-svg <file> -o <out-dir>`

每页一个 SVG，输出到 `<out-dir>/<basename>/Page_1.svg` 等。

```bash
ofd to-svg invoice.ofd -o svg_out/
ofd to-svg invoice.ofd -o svg_out/ --ppm 10          # 高分辨率
```

### `extract <file>`

提取纯文本。基于 ofdrw 的 `ContentExtractor`，按页遍历收集 `TextObject` 内容。

> ⚠️ 注意：部分 OFD 平台生成的电子发票把文字以 SVG 路径形式存储，`extract` 会返回空文本——需要 OCR 或 `to-png` 后再处理。

```bash
ofd extract invoice.ofd                  # → stdout
ofd extract invoice.ofd -o out.txt       # → 文件
ofd extract ./ofd_folder/ -o ./text/     # 批量
ofd extract invoice.ofd --json           # 分页结构化输出
ofd extract invoice.ofd --separator '---'   # 自定义分页分隔符
```

### `merge <file>... -o <out.ofd>`

把多个 OFD 的页面拼成单个 OFD。

```bash
ofd merge a.ofd b.ofd c.ofd -o merged.ofd
ofd merge ./ofd_folder/ -o merged.ofd
```

### `sign <file> -o <signed.ofd> -p12 <p12> -P <password>`

用 PKCS#12 证书签 OFD，遵循 GB/T 35275（SM2-with-SM3）。

```bash
ofd sign input.ofd -p12 USER.p12 -P 777777 --alias private -o signed.ofd
```

### `verify <file>`

验签。

- 退出 0 + `VALID` —— 签名有效
- 退出 0 + `UNSIGNED` —— 文件未签名
- 退出 1 + `INVALID` —— 签名验证失败

```bash
ofd verify signed.ofd
ofd verify signed.ofd --json
```

### `encrypt <file> -o <out> -u <user> -P <password>`

用用户密码加密 OFD（OFD 加密容器本身仍是合法 OFD 文件）。

```bash
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret
ofd encrypt input.ofd -o encrypted.ofd -u alice -P s3cret -t 1   # 自定义 owner type
```

### `decrypt <file> -o <out> -P <password>`

解密 OFD。

```bash
ofd decrypt encrypted.ofd -o plain.ofd -u alice -P s3cret
ofd decrypt encrypted.ofd -o plain.ofd -P s3cret    # 只需密码
```

### `validate <file>`

应用或校验 OFD 完整性保护（GM/T 0099 7.4.6）。

```bash
# 校验完整性
ofd validate signed.ofd
ofd validate signed.ofd --json

# 应用完整性保护（需要 PKCS#12）
ofd validate input.ofd -o protected.ofd --apply \
  -p12 USER.p12 -P 777777 --alias private
```

---

## 退出码（Agent 编程约定）

所有子命令都遵循统一的退出码协议：

| Code | 名称 | 含义 |
|:---:|---|---|
| `0` | `OK` | 处理成功 |
| `1` | `PARTIAL_FAILURE` | 批处理中部分失败 |
| `2` | `USAGE_ERROR` | 参数缺失或非法 |
| `3` | `INTERNAL_ERROR` | 未预期异常 |
| `4` | `IO_ERROR` | 文件找不到 / 不可读 |

**输出流约定**：
- 结果 / 数据 → **stdout**
- 进度日志 / 错误信息 → **stderr**
- 结构化输出：所有子命令都支持 `--json`

Agent 调用模板（伪代码）：

```python
result = subprocess.run(
    ["ofd", "verify", path, "--json"],
    capture_output=True, text=True
)
if result.returncode == 0:
    data = json.loads(result.stdout)
    handle(data)
elif result.returncode == 2:
    # 参数错
    ...
else:
    # 其他错误：日志在 stderr
    log(result.stderr)
```

---

## Native 二进制限制

v0.4.0 之前，native binary 出于 GraalVM closed-world 的 JCE provider 限制，**故意不注册** 5 个子命令（`sign` / `verify` / `validate` / `to-html` / `to-svg`）。v0.4.0 起，**`sign` / `verify` / `validate`（读 + 写）全部迁到 BouncyCastle 轻量级 crypto API，native binary 也支持了**——只剩 `to-html` / `to-svg` 因为 AWT 限制仍 fat-jar only。

**为什么不注册而不是 fail gracefully**（适用 `to-html` / `to-svg`）：CLI 不会因为「子命令注册了但运行报错」比「子命令不存在」更友好。报 `Unknown command` 明确告诉用户「这个 binary 没这个能力，去看文档」；报 UnsatisfiedLinkError 只会让用户怀疑这是 bug。

**`to-html` / `to-svg` 跑不起来的根因**：

| 子命令 | 根因 |
|---|---|
| `to-html` / `to-svg` | AWTMaker 父类触发 `sun/font/CFontManager` JNI — macOS 字体管理器在 native-image 下无法解析 → SIGABRT |

**`sign` / `verify` / `validate` 怎么搞定的**（v0.3.0 + v0.4.0 的核心改动）：原本这几个走 JCE provider API（`Signature.getInstance("SM3WithSM2", "BC")`），GraalVM 25.x CE 的 closed-world `JceSecurity.getVerificationResult` 校验不允许 build time 之后注册 provider（[oracle/graal#13412](https://github.com/oracle/graal/issues/13412)）。v0.3.0 迁 verify / validate (读)，v0.4.0 迁 sign + validate --apply：

| 原 JCE 调用 | 轻量级 BC 替代 | 引入版本 |
|---|---|---|
| `KeyStore.getInstance("PKCS12", "BC")` + `ks.getKey/getCert` | `PKCS12PfxPdu` + `PKCS12SafeBagFactory` + `BcPKCS12PBEInputDecryptorProviderBuilder` | v0.3.0 |
| `Signature.getInstance("SM3withSM2", "BC")` + `initVerify/Sign` + `update/verify/sign` | `SM2Signer` + `ECPublicKeyParameters` / `ECPrivateKeyParameters` + `update/generateSignature/verifySignature` | v0.3.0（验） / v0.4.0（签） |
| `MessageDigest.getInstance("SM3", "BC")`（Reference 文件 SM3） | `SM3Digest`（同输出，更轻量） | v0.3.0 |
| `MessageDigest.getInstance("SHA-256", "BC")` | `MessageDigest.getInstance("SHA-256")`（JDK 自带） | v0.3.0 |
| `JcaX509CertificateConverter().setProvider("BC")` | `X509CertificateHolder`（BC ASN.1 结构） | v0.3.0 |
| `JcePKCSPBEInputDecryptorProviderBuilder` | `BcPKCS12PBEInputDecryptorProviderBuilder` | v0.4.0（`validate --apply`） |
| `GMProtectSigner`（`Signature.getInstance(..., "BC")`） | `GMProtectSignerLight`（`SM2Signer` 走轻量级） | v0.4.0（`validate --apply`） |

完整支持矩阵：

| 子命令 | native | fat-jar |
|---|:---:|:---:|
| `version`, `info` | ✅ | ✅ |
| `to-png`, `to-pdf`, `extract`, `merge` | ✅ | ✅ |
| `encrypt`, `decrypt` | ✅ | ✅ |
| `sign`, `verify` | ✅ | ✅ |
| `validate`（读 + `--apply`） | ✅ | ✅ |
| `to-html`, `to-svg` | ❌ 不注册 | ✅ |

需要 `to-html` / `to-svg` 时直接用 fat-jar：

```bash
java -jar ofd-cli.jar to-html invoice.ofd -o out/invoice.html
java -jar ofd-cli.jar to-svg invoice.ofd -o out/
```

CLI 自身的 `--help` 页脚会列出两种发行版的差异，无需读 README 也能看到。

---

## 字体处理

`to-png` / `to-pdf` / `to-html` / `to-svg` 需要 CJK 字体（宋体、楷体、黑体等），这是 OFD 标准里规定的引用字。CLI 会**自动扫描系统字体目录**：

| 平台 | 扫描路径 |
|---|---|
| macOS | `/System/Library/Fonts`、`/System/Library/Fonts/Supplemental`、`~/Library/Fonts` |
| Linux | `/usr/share/fonts`、`/usr/local/share/fonts`、`~/.fonts` |
| Windows | `C:\Windows\Fonts` |

覆盖方式：

- `--font-dir <path>`（可多次）
- `--no-default-fonts`（跳过系统扫描）

native binary 因为 AWT 无法枚举字体，会通过反射直接读文件系统预填 `FontLoader` 单例（见 `io.github.ofdcli.awt.NativeImageFontBootstrap`），注册的字体涵盖 30+ 个 CJK 别名（宋体 / SimSun / 黑体 / KaiTi / ...）。

---

## 源码构建

需要 JDK 11+、Maven 3.9+。

```bash
git clone https://github.com/rightgenius/ofd-cli.git
cd ofd-cli

# fat-jar（任何 JDK 11+）
mvn -DskipTests package
java -jar target/ofd-cli.jar --version

# native binary（需要 GraalVM JDK 25+）
brew install --cask graalvm/tap/graalvm-jdk           # 或手动下载
export JAVA_HOME=/path/to/graalvm
mvn -Pnative -DskipTests clean package
./target/ofd --version
```

> 💡 编译 native binary 不需要把 `JAVA_HOME` 设成 GraalVM 也能用——Maven 会自动用本机 javac 编 Java 11 字节码，native-image 步骤单独调 GraalVM。

---

## 测试

两层测试：

```bash
# 单元测试（JUnit 5，几秒钟）
mvn -o test

# 集成测试（fat-jar 39 用例，native 39 用例 — native 已覆盖 to-pdf + validate --apply）
./src/test/scripts/run-tests.sh -m jar     # fat-jar
./src/test/scripts/run-tests.sh -m native   # native binary
```

测试资源从上游 ofdrw 项目的 `target/test-classes/` 复制到 `src/test/resources/`。  
测试用 PKCS#12 证书：`src/test/resources/USER.p12`，alias = `private`，密码 = `777777`。

### 性能（native vs fat-jar 实测，macOS M-series，1.5 MB OFD）

| 操作 | fat-jar | native | 倍数 |
|---|---:|---:|---:|
| 启动 (`--version`) | ~500 ms | ~14 ms | **~36x** |
| `sign` | ~1100 ms | ~410 ms | ~2.7x |
| `verify` | ~780 ms | ~148 ms | ~5.3x |

native 二进制大（56 MB vs 34 MB）但启动快 36x，CI 流水线、agent loop、shell 脚本里反复调 `ofd` 的时候差距明显。

---

## 变更日志

### v0.4.0（最新）

**`validate --apply` 在 native binary 上也跑通了**——`sign` / `verify` / `validate`（读 + 写）三个子命令在 native 都支持。`to-html` / `to-svg` 仍 fat-jar only。

依赖 `rightgenius/ofdrw` 升到 2.4.0-openpdf.7，把 ofdrw-crypto 里 `GMProtectSigner` 走的 JCE provider API 迁到 BC 轻量级 crypto API：

- `ofdrw-crypto` 新增 `GMProtectSignerLight`（`SM2Signer` + `ECPrivateKeyParameters` 走 `GmVerifyHelper.sm3WithSm2Sign`，证书用 `X509CertificateHolder`）替代 `GMProtectSigner`（JCE `Signature.getInstance("SM3WithSM2", "BC")`）
- `ofd-cli` `ValidateCommand` 切到 `PKCS12ToolsLight` + `GMProtectSignerLight`
- `OFDIntegrity.protect(ProtectSigner)` 接口不变，新实现 plug-in；老的 `GMProtectSigner`（JCE）保留给老调用方

native binary 子命令数：**11 → 12**（`validate --apply` 也支持了）。native binary 体积从 58 MB 缩到 53 MB（reflection 闭合变紧）。

> BC SM2 sign 是 non-deterministic（随机 k，类似 ECDSA），同一明文两次签名的 `r||s` 字节不一样，但都能被同一 `OFDIntegrityVerifier` + `GMProtectVerifier` 接受。fat-jar 和 native 签的输出互相能识别（4/4 cross-compat 组合测过）。

### v0.3.0

**`sign` / `verify` / `validate`（读）在 native binary 跑通**——`to-html` / `to-svg` 仍 fat-jar only。

依赖 `rightgenius/ofdrw` 升到 2.4.0-openpdf.6，把这三个子命令走的 JCE provider API 全迁到 BC 轻量级 crypto API：

- `ofdrw-gm` 新增 `PKCS12ToolsLight`（`PKCS12PfxPdu` + `PKCS12SafeBagFactory` + `BcPKCS12PBEInputDecryptorProviderBuilder`）替代 `KeyStore.getInstance("PKCS12", "BC")`
- `ofdrw-gm` `GmVerifyHelper` 新增 `sm3WithSm2Sign`（`SM2Signer` + `ECPrivateKeyParameters`）替代 `Signature.getInstance("SM3withSM2", "BC")`
- `ofdrw-sign` 新增 `GBT35275DSContainerLight`（走 `GmVerifyHelper.sm3WithSm2Sign`）替代 `GBT35275DSContainer`（JCE）
- `ofdrw-sign` `OFDValidator.checkFileIntegrity` 改用 `SM3Digest` 流式 SM3 替代 JCE `MessageDigest`，同时支持 SM3 字符串名和 OID `1.2.156.10197.1.401`

native binary 子命令数：**8 → 11**（加了 sign / verify / validate）。

`validate --apply`（生成 OFDEntries.xml 保护）仍 fat-jar only——需要 ofdrw-crypto 的 `GMProtectSigner` 也迁到轻量级 API，留后续 PR。

### v0.2.0 / v0.1.6

见 [Releases](https://github.com/rightgenius/ofd-cli/releases) 页面。

---

## 上游

`ofd-cli` 是 `ofdrw` 生态的**命令行包装层**。本项目**不**修改 `ofdrw` 本身的 OFD 解析 / 渲染 / 签名 / 加密逻辑 —— 那些能力全部来自上游。

### 真正的上游

- **原版上游**：[ofdrw/ofdrw](https://github.com/ofdrw/ofdrw) — Apache 2.0，Java 生态里最完善的 OFD 处理库
- **本项目**：[rightgenius/ofd-cli](https://github.com/rightgenius/ofd-cli) — CLI 封装 + native-image 编译 + 字体 bootstrap + 标准化退出码 / JSON 输出

### 为什么还有一个 `rightgenius/ofdrw`

`ofdrw/ofdrw` 的两个能力在 GraalVM native-image 下不可用（见上文）：一是 PDF 渲染（PDFBox 触发 AWT JNI），二是国密签名 / 加密（JCE `Signature.getInstance("SM3WithSM2", "BC")` 触发 `JceSecurity.canUseProvider` 校验失败）。所以**本项目额外维护了一个 fork**：

- **GraalVM 兼容 fork**：[rightgenius/ofdrw](https://github.com/rightgenius/ofdrw) — `feature/openpdf-replacement` 分支，Apache 2.0
- **用途**：本项目只依赖此 fork 的 4 个子模块 —— `ofdrw-converter`（PDF 渲染）+ `ofdrw-gm` / `ofdrw-sign` / `ofdrw-crypto`（国密签名加密的 BC 轻量级实现）；其他子模块（`ofdrw-core` / `ofdrw-pkg` / `ofdrw-layout` …）仍用原版 `ofdrw/ofdrw`

### `rightgenius/ofdrw` 相对原版改了哪些

相对于 `ofdrw/ofdrw` 的 commit `7df66b68` 之后的所有改动，按重要性排序：

| 改动 | 类别 | 解决的问题 |
|---|---|---|
| **PDFBox → OpenPDF 1.3.39** | 主要 | PDFBox `PDDocument.<clinit>` 在 native-image 触发 AWT `UnsatisfiedLinkError`；OpenPDF 是纯 Java fork，无 native 依赖 |
| **国密签名 / 加密从 JCE 迁到 BC 轻量级 crypto API** | 主要 | JCE `Signature.getInstance("SM3WithSM2", "BC")` / `KeyStore.getInstance("PKCS12", "BC")` 触发 GraalVM closed-world `JceSecurity.canUseProvider` 校验失败（[oracle/graal#13412](https://github.com/oracle/graal/issues/13412)）；fork 改成 `SM2Signer` + `ECPublicKeyParameters` / `ECPrivateKeyParameters` 走 BC 轻量级 crypto 路径，让 `sign` / `verify` / `validate` 在 native binary 都能跑 |
| **`OFDValidator` 接受 SM3 OID `1.2.156.10197.1.401`** | 重要 | 真实 OFD（数科电子发票、z 科 SDK 签的）`Signature.xml` 写的是 SM3 OID 不是字符串 `"SM3"`，老代码只匹配字符串会误报"不支持的杂凑算法" |
| **OpenPDF `Image` SMask BC 默认黑底** → 用 explicit `/Mask` 替换 | 主要 | 真实 OFD 平台生成的电子发票带 alpha 通道图，OpenPDF 1.3.39 把 alpha 透明区默认填成黑色；改用 PDF spec 推荐的 explicit `/Mask` 方案，印章 alpha 透出底层红字 |
| **OpenPDF `clip()` 不重置 current path** → `clip()` 后立刻 `newPath()` | 重要 | PDF spec 8.5.4 要求 W 算子消耗 path；OpenPDF 不重置，导致字符 path 跟 page rect 走 non-zero winding 抵消，字符被裁 |
| **TTC 字体路径加 `,0` sub-font 后缀** | 重要 | macOS 中文 CJK 字体都是 TTC 集合（PingFang.ttc / STSong.ttc …），OpenPDF `TrueTypeCollection` 需要 `path,0` 语法，否则静默失败 → 中文渲染为空 |
| **`org.ofdrw.gm.GmProviders`** 统一封装 BC provider 获取 | 次要 | 让应用层能用同一入口拿 provider，方便后续做 GraalVM 兼容层 |

历史 tag：`v2.4.0-openpdf.1` / `.2` / `.3` / `.4` / `.5` / `.6` / `.7`（`.5` 起进入国密签名 BC 轻量级迁移阶段，`.7` 完成 `validate --apply` 的 `GMProtectSignerLight`）。详见 [rightgenius/ofdrw release 页](https://github.com/rightgenius/ofdrw/tags)。

### 在哪里报 bug

- **OFD 解析 / 渲染 / 签名 / 加密的逻辑 bug** → 优先去上游 [`ofdrw/ofdrw`](https://github.com/ofdrw/ofdrw) issue；如果能稳定复现的输入只跟 fork 的 OpenPDF 路径相关，去 [`rightgenius/ofdrw`](https://github.com/rightgenius/ofdrw)
- **CLI 体验 / native-image 编译 / AI agent 集成 / 字体 bootstrap** → 在本仓库 [`rightgenius/ofd-cli`](https://github.com/rightgenius/ofd-cli) 开 issue

---

## 贡献

欢迎 PR 和 issue。建议优先方向：

- ✅ ~~修复 GraalVM BC provider 限制，让 native binary 也支持 sign/verify/validate~~ （v0.3.0 完成）
- ✅ ~~修复 `validate --apply` 在 native 上的完整 JCE 路径~~ （v0.4.0 完成：`GMProtectSignerLight` 走 BC 轻量级 API）
- 🎯 修复 `to-pdf` / `to-html` / `to-svg` 在 native 上的 AWT CFontManager 问题
- 📦 新平台/架构的 native binary（Linux ARM64、Alpine、RISC-V）
- 🐛 边界 OFD 文件的解析兼容性
- 📚 文档改进、翻译

跑测试 + 提 PR：

```bash
mvn -o test
./src/test/scripts/run-tests.sh -m jar
./src/test/scripts/run-tests.sh -m native
```

---

## AI Agent 集成

`ofd-cli` 的所有子命令都遵循 [退出码协议](#退出码agent-编程约定)，结果走 stdout、日志走 stderr、全部支持 `--json`——**专为 agent 调 subprocess 而设计**。

### 一次性装好 skill（推荐）

仓库根的 [`skills/ofd/SKILL.md`](skills/ofd/SKILL.md) 是给 AI agent 用的 skill 文件，覆盖所有子命令的调用模式 + JSON 输出 schema、Python / Node.js 调用模板、踩坑指南。把它装到你 agent 的 skills 目录，agent 就能自动知道怎么用 `ofd`：

| Agent | 安装路径 |
|---|---|
| Claude Code | `~/.claude/skills/ofd/SKILL.md` |
| Codex | `~/.codex/skills/ofd/SKILL.md` |
| Cursor | `~/.cursor/rules/ofd.md` |
| 其他通用 agent | 任何会被作为 system prompt / context 加载的位置都行 |

一行安装（自动检测 agent 类型）：

```bash
# Claude Code
mkdir -p ~/.claude/skills/ofd && \
  curl -fsSL https://raw.githubusercontent.com/rightgenius/ofd-cli/main/skills/ofd/SKILL.md \
    -o ~/.claude/skills/ofd/SKILL.md

# Codex
mkdir -p ~/.codex/skills/ofd && \
  curl -fsSL https://raw.githubusercontent.com/rightgenius/ofd-cli/main/skills/ofd/SKILL.md \
    -o ~/.codex/skills/ofd/SKILL.md

# Cursor (作为 rule)
mkdir -p ~/.cursor/rules && \
  curl -fsSL https://raw.githubusercontent.com/rightgenius/ofd-cli/main/skills/ofd/SKILL.md \
    -o ~/.cursor/rules/ofd.md
```

### agent 调用的标准模板

Python：

```python
import subprocess, json

def ofd(*args, check=True):
    """Run ofd CLI; returns parsed JSON if --json, else stdout string.
    Raises RuntimeError on non-zero exit."""
    r = subprocess.run(["ofd", *args], capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"ofd {args} failed (exit {r.returncode}): {r.stderr}")
    if "--json" in args:
        return json.loads(r.stdout)
    return r.stdout

# 示例：批量提取电子发票文本
result = ofd("info", "invoice.ofd", "--json")
print(result["pageCount"], result["docInfo"])
```

Node.js：

```js
const { execFileSync } = require("child_process");
const ofd = (...args) => execFileSync("ofd", args, { encoding: "utf8" });

const info = JSON.parse(ofd("info", "invoice.ofd", "--json"));
```

Shell：

```bash
# Agent 写脚本时直接调
ofd to-png invoice.ofd -o /tmp/invoice/ && ls /tmp/invoice/
```

> **PATH 兜底**：agent 运行环境里 `ofd` 不一定在 `PATH`。如果 `subprocess` 找不到，先查 `~/.local/bin/ofd`、`/usr/local/bin/ofd`、`/opt/homebrew/bin/ofd`，或在调用前显式 `export PATH="$HOME/.local/bin:$PATH"`。

### 给 agent 的"何时用我"提示

把这段加到你的 agent system prompt 或 CLAUDE.md / AGENTS.md：

> 当用户提供 `.ofd` 文件或询问 OFD（Open Fixed-layout Document）相关任务时，使用 `ofd` CLI。
> 先跑 `ofd info <file> --json` 了解文档结构，再选择对应子命令（`to-png` / `to-pdf` / `extract` / `sign` 等）。
> 退出码 0=成功 / 1=部分失败 / 2=参数错 / 3=内部错 / 4=IO 错。
> 拿到的 OFD 没有明确指令时，先 `ofd to-png` 渲染看内容——视觉上往往一看就明白用户想要什么。

---

## License

本项目基于 **Apache License 2.0** 开源。完整许可见 [LICENSE](LICENSE)。

包含的第三方组件各自的许可见 fat-jar 内 `META-INF/NOTICE.*` 文件：

- [ofdrw/ofdrw](https://github.com/ofdrw/ofdrw) — Apache 2.0（原版 OFD 解析 / 渲染 / 签名 / 加密）
- [rightgenius/ofdrw](https://github.com/rightgenius/ofdrw) — Apache 2.0（PDF 渲染 fork）
- [BouncyCastle](https://www.bouncycastle.org/) — MIT
- [OpenPDF](https://github.com/LibrePDF/OpenPDF) — LGPL/MPL
- [Picocli](https://picocli.info/) — Apache 2.0
- [Jackson](https://github.com/FasterXML/jackson) — Apache 2.0
- [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) — BSD-3-Clause
- [Apache XML Graphics (Batik)](https://xmlgraphics.apache.org/) — Apache 2.0

---

<p align="center">
  如果这个项目帮到你了，给个 ⭐ 让更多人看到。
</p>
