# ofd-cli

> **OFD**（Open Fixed-layout Document，**版式文档**）命令行工具，基于 [ofdrw](https://github.com/ofdrw/ofdrw) 封装。  
> 专为 **AI Agent** 与**自动化流水线**设计：单文件静态二进制、标准化退出码、JSON 输出。

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)
[![Java 11+](https://img.shields.io/badge/Java-11%2B-orange.svg)](https://adoptium.net/)
[![Native binary](https://img.shields.io/badge/native--image-54MB-success.svg)](https://www.graalvm.org/native-image/)
[![Maven Central](https://img.shields.io/badge/ofdrw-2.4.0-informational.svg)](https://central.sonatype.com/artifact/org.ofdrw/ofdrw-full)
[![Platform](https://img.shields.io/badge/platform-macOS%20%7C%20Linux%20%7C%20Windows-lightgrey.svg)](#)

---

## 这是什么

`ofd-cli` 把 Java 生态里最完善的 OFD 处理库 [ofdrw](https://github.com/ofdrw/ofdrw) 装进一个**单文件可执行二进制**里，零 JRE 依赖，可以直接被 shell、CI、AI agent 调用。

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

### macOS / Linux（推荐）

```bash
# 方式 A：Homebrew（待发布）
brew install ofdcli/tap/ofd

# 方式 B：GitHub Releases 下载
#   https://github.com/ofdcli/ofd-cli/releases/latest
curl -L https://github.com/ofdcli/ofd-cli/releases/latest/download/ofd-darwin-arm64 -o ofd
chmod +x ofd && sudo mv ofd /usr/local/bin/

# 验证
ofd --version
```

### Windows

从 [Releases](https://github.com/ofdcli/ofd-cli/releases/latest) 下载 `ofd-windows-amd64.exe`。

### JRE 不可用的环境 / 完整功能

```bash
# fat-jar（需 JRE 11+）
curl -L https://github.com/ofdcli/ofd-cli/releases/latest/download/ofd-cli.jar -o ofd-cli.jar
java -jar ofd-cli.jar --version
```

---

## 快速开始

```bash
$ ofd --version
ofd-cli 0.1.0
  commit: 6498739
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
| 启动时间 | < 100 ms | ~500 ms |
| 大小 | 54 MB | 33 MB |
| JRE 依赖 | 无 | 需要 JRE 11+ |
| 子命令数 | **10** | **13** |

native binary 是首选。fat-jar 是兜底——当 native 缺某个子命令时用它。

---

## 子命令速查

| 子命令 | 用途 | native | fat-jar |
|---|---|:---:|:---:|
| `version` | 版本信息 | ✅ | ✅ |
| `info` | 文档元数据（页数、签名、附件） | ✅ | ✅ |
| `to-png` | 渲染为 PNG（默认 192 dpi） | ✅ | ✅ |
| `to-pdf` | 渲染为 PDF（PDFBox 2.x） | ❌ | ✅ |
| `to-html` | 渲染为 HTML（含 SVG 资源） | ❌ | ✅ |
| `to-svg` | 渲染为 SVG（每页一个文件） | ❌ | ✅ |
| `extract` | 提取纯文本 | ✅ | ✅ |
| `merge` | 合并多个 OFD | ✅ | ✅ |
| `sign` | 数字签名（GB/T 35275 SM2/SM3） | ❌ | ✅ |
| `verify` | 验签 | ❌ | ✅ |
| `encrypt` | 密码加密 | ✅ | ✅ |
| `decrypt` | 密码解密 | ✅ | ✅ |
| `validate` | 完整性校验（GM/T 0099） | ❌ | ✅ |

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
ofd to-png ./ofd_folder/ -o ./png/ --ppm 10         # 高分辨率
ofd to-png invoice.ofd --font-dir /extra/fonts       # 追加字体目录
ofd to-png invoice.ofd --no-default-fonts            # 跳过系统字体扫描
```

### `to-pdf <file> -o <out.pdf>`

转换为 PDF（PDFBox 2.x，**非 AGPL**，可商用）。

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

> ⚠️ 注意：把文字渲染为矢量路径的 OFD（如部分滴滴电子发票）会提取出空文本。

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

native binary 出于以下原因**故意不注册** `sign` / `verify` / `validate` 三个子命令：

> GraalVM 25.0.4 CE 的 [closed-world JCE 验证](https://github.com/oracle/graal/issues/13412) 要求 `BouncyCastleProvider` 在 build time 就注册到 `Security`，但当前工具链在 `feature/bouncycastle-substitutions` 之类的解决方案落地前无法做到。

其它 ❌ 项（`to-pdf` / `to-html` / `to-svg`）是 **AWT CFontManager JNI lookup** 失败导致的渲染子命令不可用。

完整支持矩阵：

| 子命令 | native | fat-jar | 原因 |
|---|:---:|:---:|---|
| `version`, `info` | ✅ | ✅ | |
| `to-png`, `extract` | ✅ | ✅ | |
| `merge`, `encrypt`, `decrypt` | ✅ | ✅ | |
| `to-pdf` | ❌ | ✅ | PDFBox 反射 + AWT |
| `to-html`, `to-svg` | ❌ | ✅ | AWT CFontManager JNI |
| `sign` | ❌ 不注册 | ✅ | GraalVM BC provider 限制 |
| `verify` | ❌ 不注册 | ✅ | 同上 |
| `validate` | ❌ 不注册 | ✅ | 同上 |

需要这些子命令时直接用 fat-jar：

```bash
java -jar ofd-cli.jar sign input.ofd -p12 cert.p12 -P pwd -o signed.ofd
java -jar ofd-cli.jar to-html invoice.ofd -o out/invoice.html
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
git clone https://github.com/ofdcli/ofd-cli.git
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

# 集成测试（每种模式 39 用例，共 78）
./src/test/scripts/run-tests.sh -m jar     # fat-jar
./src/test/scripts/run-tests.sh -m native   # native binary
```

测试资源从上游 ofdrw 项目的 `target/test-classes/` 复制到 `src/test/resources/`。  
测试用 PKCS#12 证书：`src/test/resources/USER.p12`，alias = `private`，密码 = `777777`。

---

## 上游致谢

`ofd-cli` 是 [ofdrw](https://github.com/ofdrw/ofdrw) 项目的**命令行包装层**，所有 OFD 解析、渲染、签名、加密能力都来自 ofdrw 库。

- **上游项目**：[ofdrw/ofdrw](https://github.com/ofdrw/ofdrw) — Apache 2.0
- **本项目**：仅做 CLI 封装 + native-image 编译 + 字体 bootstrap + 标准化退出码 / JSON 输出
- **当前 ofdrw 版本**：`2.4.0`（来自 Maven Central）

如果你发现 OFD 处理逻辑层面的 bug，先去上游反馈；如果是 CLI 体验、native 编译、Agent 集成相关的问题，在本仓库开 issue。

### 关联 PR

针对 GraalVM native-image 下 BouncyCastle 注册困难的问题，本项目维护者在 [rightgenius/ofdrw#526148de](https://github.com/ofdrw/ofdrw/pull/new/feature/graal-bc-provider-v2) 提了 `GmProviders` 统一 Provider 获取入口的 PR，作为后续 native 全功能化的前置重构。

---

## 贡献

欢迎 PR 和 issue。建议优先方向：

- 🎯 修复 GraalVM BC provider 限制，让 native binary 也支持 sign/verify/validate
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

## 路线图

- [ ] 解决 GraalVM 25.x BC provider 限制，native 全功能化
- [ ] GitHub Actions CI（多平台 native 编译 + 集成测试）
- [ ] Homebrew tap（`brew install ofdcli/tap/ofd`）
- [ ] Linux ARM64 / Windows ARM64 native binary
- [ ] Shell 补全脚本（bash / zsh / fish）
- [ ] 流式输入支持（stdin → stdout）

---

## License

本项目基于 **Apache License 2.0** 开源。完整许可见 [LICENSE](LICENSE)。

包含的第三方组件各自的许可见 fat-jar 内 `META-INF/NOTICE.*` 文件：

- [ofdrw](https://github.com/ofdrw/ofdrw) — Apache 2.0
- [BouncyCastle](https://www.bouncycastle.org/) — MIT
- [PDFBox](https://pdfbox.apache.org/) — Apache 2.0
- [Picocli](https://picocli.info/) — Apache 2.0
- [Jackson](https://github.com/FasterXML/jackson) — Apache 2.0
- [TwelveMonkeys ImageIO](https://github.com/haraldk/TwelveMonkeys) — BSD-3-Clause
- [Apache XML Graphics (Batik)](https://xmlgraphics.apache.org/) — Apache 2.0

---

<p align="center">
  如果这个项目帮到你了，给个 ⭐ 让更多人看到。
</p>
