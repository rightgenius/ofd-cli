package io.github.ofdcli.awt;

import com.itextpdf.io.font.ItextFontUtil;
import org.ofdrw.converter.FontLoader;
import org.ofdrw.converter.font.TrueTypeFont;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Native-image bootstrap for OFD font loading.
 *
 * <p>The ofdrw {@link FontLoader} initializes via {@code Font.createFont()} from
 * {@code java.awt.Font}. In GraalVM native-image the AWT FontManager cannot
 * enumerate host system fonts (no display server, JNI font enumeration
 * unavailable), so {@code Font.createFont} silently throws and the loader ends
 * up with an empty font table, ultimately throwing "系统中无可用字体".
 *
 * <p>This bootstrap bypasses AWT by:
 * <ol>
 *   <li>Scanning configured font directories directly from the filesystem
 *       (GraalVM allows raw FS access by default).</li>
 *   <li>Validating each file by parsing it with ofdrw's {@code TrueTypeFont}
 *       (no AWT needed).</li>
 *   <li>Registering every valid font under a wide set of CJK name aliases.</li>
 *   <li>Reflectively replacing the {@code FontLoader} singleton with a
 *       pre-populated instance, bypassing its broken AWT-based {@code init()}.</li>
 * </ol>
 *
 * <p>On a regular JVM this class is effectively a no-op — harmless: the JVM
 * path uses {@link FontLoader#getInstance()} which behaves normally.
 */
public final class NativeImageFontBootstrap {
    private static final Set<String> COMMON_NAMES = new HashSet<>(Arrays.asList(
            "宋体", "SimSun", "楷体", "KaiTi", "KaiTi_GB2312", "黑体", "Heiti", "STHeiti",
            "STHeiti-Light", "STHeiti-Medium", "仿宋", "FangSong", "STSong",
            "STKaiti", "PingFang", "Hiragino Sans GB", "Songti SC", "STSongti-SC-Regular",
            "Kaiti SC", "STSongti SC", "Arial Unicode MS", "Times New Roman",
            "Arial", "Helvetica", "Courier", "Symbol", "MinionPro", "FangSong_GB2312",
            "STSong-Light", "FangSong", "华文宋体", "华文楷体", "华文仿宋", "华文黑体"
    ));

    /**
     * Fonts whose macOS-shipped TTF has an OS/2 v0 table that fontbox's
     * {@code TrueTypeFont.getOS2Windows()} cannot parse, causing
     * {@code OpenPdfMaker.getDefaultFont()} to silently return {@code null}
     * and fall through to PDF Helvetica. We avoid picking these as the
     * "first font" so the PDF has a real font embedded.
     */
    private static final Set<String> MAC_FONT_BLACKLIST = new HashSet<>(Arrays.asList(
            "symbol",                  // OS/2 v0, no Windows metrics
            "zapfdingbats",            // ditto
            "apple symbols",           // ditto
            "trattatello",             // decorative
            "papyrus",                 // decorative
            "snellroundhand",          // script
            "bradley hand",            // hand
            "marker felt",             // marker
            "noteworthy",              // handwritten
            "signpainter",             // script
            "savoye",                  // script
            "apple chancery",          // script
            "zapfino",                 // script
            "luminari",                // decorative
            "bigcaslon",               // display
            "cochin",                  // display
            "copperplate",             // display
            "futura",                  // geometric
            "kefa",                    // display
            "marion",                  // display
            "phosphate",               // decorative
            "rockwell",                // slab serif
            "skia",                    // handwriting
            "sukhumvit set",           // thai
            "geneva",                  // legacy, OS/2 v0
            "monaco",                  // legacy, OS/2 v0
            "helveticaneue",           // macOS ttc, OS/2 v0
            "helvetica",               // macOS ttc, OS/2 v0
            "avenir",                  // macOS ttc, OS/2 v0
            "avenir next",             // macOS ttc, OS/2 v0
            "courier",                 // macOS ttc, OS/2 v0
            "menlo",                   // macOS ttc, OS/2 v0
            "times",                   // macOS ttc, OS/2 v0
            "optima",                  // macOS ttc, OS/2 v0
            "palatino",                // macOS ttc, OS/2 v0
            "georgia",                 // macOS ttc, OS/2 v0
            "verdana",                 // macOS ttc, OS/2 v0
            "arialhb",                 // macOS ttc, OS/2 v0 (Arabic-only)
            "arialuni",                // could be CJK (Arial Unicode MS)
            "lastresort"               // fallback
    ));

    /**
     * First-font preference order — higher index = lower priority. When
     * selecting {@code firstFont} for the OpenPdfMaker default-font path we
     * match filenames against this list (case-insensitive, extension-stripped,
     * and a {@code .ttc} only counts if it's a well-behaved collection).
     * <p>Why this matters: {@code OpenPdfMaker.getDefaultFont()} embeds the
     * first font path returned by {@code FontLoader.getDefaultFontPath()}
     * via {@code BaseFont.createFont(path, "Identity-H", true)}. If that
     * font has a v0 OS/2 table, fontbox's {@code getOS2Windows()} returns
     * {@code null} and OpenPdfMaker falls through to a {@code null} cached
     * BaseFont, which OpenPDF then silently swaps to PDF Helvetica.  Almost
     * every stock macOS /System/Library/Fonts font is in this state, which
     * is why the /System/Library/Fonts folder is essentially unusable as
     * the "first font" source on macOS for OpenPDF.
     *
     * <p><b>macOS CJK caveat</b>: stock macOS only ships CJK glyphs in
     * TrueType Collections ({@code Hiragino Sans GB.ttc}, {@code STHeiti
     * Medium.ttc}). OpenPdfMaker.getTrueTypeFont() dispatches a {@code .ttc}
     * to {@code TrueTypeCollection.getFontByName(fontName)} and only the
     * exact sub-font name (e.g. {@code "HiraginoSansGB-W3"}) is found.
     * OFD files almost always reference a generic family name (e.g.
     * {@code "宋体"}) so the lookup misses and we fall through.  For
     * Chinese OFD rendering on macOS, users must install a standalone CJK
     * TTF (Source Han Sans, Noto Sans CJK SC, etc.) and pass it via
     * {@code --font-dir /path/to/fonts/}.
     */
    private static final List<String> FIRST_FONT_PREFERENCE = Arrays.asList(
            // CJK fonts first (macOS, then others)
            "PingFang SC",
            "Hiragino Sans GB",
            "STSong",
            "STSongti SC",
            "Songti SC",
            "STKaiti",
            "Kaiti SC",
            "STHeiti",
            "Heiti SC",
            "Arial Unicode MS",
            // Linux common CJK
            "Noto Sans CJK SC",
            "Noto Serif CJK SC",
            "WenQuanYi Zen Hei",
            "WenQuanYi Micro Hei",
            // Windows common CJK
            "SimSun",
            "Microsoft YaHei",
            "FangSong",
            "KaiTi",
            // Big Latin fonts that are usually in /System/Library/Fonts/Supplemental
            "Arial",
            "Arial Bold",
            "Arial Italic",
            "Arial Black",
            "Times New Roman",
            "Courier New",
            "Trebuchet MS"
    );

    private static volatile boolean done = false;

    private NativeImageFontBootstrap() {}

    /**
     * Pre-populate the FontLoader singleton. Idempotent. On regular JVM, no-op.
     *
     * @return true if a custom bootstrap ran; false if FontLoader's normal path was used.
     */
    public static synchronized boolean bootstrap() {
        if (done) {
            return false;
        }
        done = true;

        if (!isNativeImage()) {
            return false;
        }

        Path fontDir;
        try {
            fontDir = Files.createTempDirectory("ofd-cli-fonts-");
        } catch (IOException e) {
            return false;
        }

        Set<File> scanDirs = computeScanDirs();
        Map<String, String> mapping = new ConcurrentHashMap<>();
        // First-font candidate tracking. We defer the choice until we've
        // scanned everything so we can pick the highest-priority file.
        List<Path> standaloneTtfs = new ArrayList<>();
        List<Path> collections = new ArrayList<>();
        Path firstFont = null;

        for (File dir : scanDirs) {
            if (!dir.isDirectory()) continue;
            try (Stream<Path> walk = Files.walk(dir.toPath())) {
                Iterator<Path> it = walk.iterator();
                while (it.hasNext()) {
                    Path p = it.next();
                    if (!Files.isRegularFile(p)) continue;
                    String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                    if (!(name.endsWith(".ttf") || name.endsWith(".otf") || name.endsWith(".ttc"))) {
                        continue;
                    }
                    // Skip pathologically large files — no real font is > 50MB.
                    // /System/Library/Fonts has .package and .ttc files that
                    // are actually 200MB+ on some macOS releases.
                    long size;
                    try { size = Files.size(p); } catch (IOException e) { continue; }
                    if (size > 50L * 1024 * 1024) continue;
                    // Skip macOS decorative / script fonts whose OS/2 table
                    // is v0-without-Windows-metrics. These pass ofdrw's
                    // TrueTypeFont.parse() but fail fontbox's
                    // TrueTypeFont.getOS2Windows(), so OpenPdfMaker rejects
                    // them and falls through to PDF Helvetica. See the
                    // MAC_FONT_BLACKLIST javadoc for the list rationale.
                    String baseNoExt = stripExt(p.getFileName().toString()).toLowerCase(Locale.ROOT);
                    if (MAC_FONT_BLACKLIST.contains(baseNoExt)) {
                        continue;
                    }
                    // Skip TrueType Collections (.ttc) as firstFont candidate
                    // — they have multiple sub-fonts and fontbox needs the
                    // specific sub-font name to extract one. A standalone
                    // .ttf is much simpler and more reliable.
                    boolean isCollection = name.endsWith(".ttc");
                    byte[] buf;
                    TrueTypeFont ttf;
                    try {
                        buf = Files.readAllBytes(p);
                        ttf = new TrueTypeFont().parse(buf);
                        if (ttf == null) continue;
                    } catch (Exception e) {
                        continue;
                    }
                    // Copy file into our font dir so loadAsDefaultFont's
                    // Files.newInputStream can find it later.  CRITICAL: keep
                    // the .ttf/.otf/.ttc extension as the *last* suffix —
                    // OpenPdfMaker.getTrueTypeFont uses path.endsWith(".ttf")
                    // to dispatch to fontbox.TTFParser, and a hash like
                    // "Symbol.ttf_123456" would fail that check.
                    String origName = p.getFileName().toString();
                    int dot = origName.lastIndexOf('.');
                    String base, ext;
                    if (dot > 0) {
                        base = origName.substring(0, dot);
                        ext = origName.substring(dot);  // includes the leading "."
                    } else {
                        base = origName;
                        ext = "";
                    }
                    String uniqueName = base + "_" + Math.abs(p.toString().hashCode()) + ext;
                    Path dest = fontDir.resolve(uniqueName);
                    try {
                        Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        continue;
                    }
                    String pathStr = dest.toString();
                    // Remember candidates for firstFont selection below.
                    // We don't pick firstFont eagerly here because we want
                    // a priority-based pick after the full scan completes.
                    if (isCollection) {
                        collections.add(dest);
                    } else {
                        standaloneTtfs.add(dest);
                    }
                    // Register under every CJK alias — better to be redundant
                    // than to miss a font referenced by an OFD.
                    for (String alias : COMMON_NAMES) {
                        mapping.putIfAbsent(alias, pathStr);
                    }
                    // Also register the file basename (without extension)
                    String basename = p.getFileName().toString();
                    int bdot = basename.lastIndexOf('.');
                    if (bdot > 0) basename = basename.substring(0, bdot);
                    for (String v : basenameVariations(basename)) {
                        mapping.putIfAbsent(v, pathStr);
                    }
                }
            } catch (Exception ignored) {
                // skip unreadable dirs
            }
        }

        if (standaloneTtfs.isEmpty() && collections.isEmpty()) {
            return false;
        }

        // Now: pick firstFont by priority.  Walk FIRST_FONT_PREFERENCE in
        // order; the first match against any candidate's basename wins.
        // Standalone TTFs are preferred over TTCs (TTCs need a sub-font
        // name to extract, and OpenPdfMaker will probably pick the wrong
        // one without a fontname hint).
        Path preferredFirst = null;
        for (String preferred : FIRST_FONT_PREFERENCE) {
            String needle = preferred.toLowerCase(Locale.ROOT);
            for (Path p : standaloneTtfs) {
                if (p.getFileName().toString().toLowerCase(Locale.ROOT).contains(needle)) {
                    preferredFirst = p;
                    break;
                }
            }
            if (preferredFirst != null) break;
        }
        if (preferredFirst != null) {
            firstFont = preferredFirst;
        } else if (!standaloneTtfs.isEmpty()) {
            firstFont = standaloneTtfs.get(0);
        } else {
            firstFont = collections.get(0);
        }
        if (firstFont == null) {
            // No priority match. Fall back to first standalone TTF.
            if (!standaloneTtfs.isEmpty()) {
                firstFont = standaloneTtfs.get(0);
            } else if (!collections.isEmpty()) {
                firstFont = collections.get(0);
            }
        }

        // DEBUG
        try {
            byte[] fb = Files.readAllBytes(firstFont);
            TrueTypeFont fttf = new TrueTypeFont().parse(fb);
        } catch (Exception ignored) {}

        // Now: replace the FontLoader singleton instance with a pre-populated
        // one (bypassing its broken AWT-based init()).
        try {
            // Set DefaultFontPath & defaultFont & iTextDefaultFont so the static
            // loadAsDefaultFont() works regardless of init().
            byte[] firstBuf = Files.readAllBytes(firstFont);
            TrueTypeFont ttf = new TrueTypeFont().parse(firstBuf);
            setStaticField("DefaultFontPath", firstFont);
            setStaticField("defaultFont", ttf);
            setStaticField("iTextDefaultFont", ItextFontUtil.loadFont(firstBuf));

            // Create a new FontLoader instance (without running init()) and
            // inject our mapping.
            Constructor<?> ctor = FontLoader.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            FontLoader loader = (FontLoader) ctor.newInstance();

            Field f = FontLoader.class.getDeclaredField("fontNamePathMapping");
            f.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, String> target = (Map<String, String>) f.get(loader);
            target.clear();
            target.putAll(mapping);

            // Set our pre-populated instance as the singleton BEFORE anyone
            // calls FontLoader.getInstance(). When they do, instance != null
            // and init() is skipped.
            Field instanceField = FontLoader.class.getDeclaredField("instance");
            instanceField.setAccessible(true);
            instanceField.set(null, loader);

            // Verify by calling getInstance() — should return our pre-populated one.
            FontLoader.getInstance();
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static Set<File> computeScanDirs() {
        Set<File> dirs = new LinkedHashSet<>();
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac") || os.contains("darwin")) {
            dirs.add(new File("/System/Library/Fonts"));
            dirs.add(new File("/System/Library/Fonts/Supplemental"));
            String userHome = System.getProperty("user.home", "");
            if (!userHome.isEmpty()) {
                dirs.add(new File(userHome + "/Library/Fonts"));
            }
        } else if (os.contains("win")) {
            dirs.add(new File("C:/Windows/Fonts"));
        } else {
            dirs.add(new File("/usr/share/fonts"));
            dirs.add(new File("/usr/local/share/fonts"));
            String userHome = System.getProperty("user.home", "");
            if (!userHome.isEmpty()) {
                dirs.add(new File(userHome + "/.fonts"));
                dirs.add(new File(userHome + "/.local/share/fonts"));
            }
        }
        String extraProp = System.getProperty("ofd.fonts.extra");
        if (extraProp != null && !extraProp.isEmpty()) {
            for (String p : extraProp.split(File.pathSeparator)) {
                if (!p.isEmpty()) {
                    dirs.add(new File(p));
                }
            }
        }
        return dirs;
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static Set<String> basenameVariations(String base) {
        Set<String> out = new LinkedHashSet<>();
        out.add(base);
        out.add(base.toLowerCase(Locale.ROOT));
        String lower = base.toLowerCase(Locale.ROOT);
        if (lower.contains("songti") || lower.contains("song ti")) {
            out.add("宋体"); out.add("SimSun"); out.add("STSong");
        }
        if (lower.contains("kaiti") || lower.contains("kai ti")) {
            out.add("楷体"); out.add("KaiTi"); out.add("STKaiti");
        }
        if (lower.contains("stheitimedium") || lower.contains("stheitilight") || lower.contains("hei")) {
            out.add("黑体"); out.add("Heiti"); out.add("STHeiti");
        }
        if (lower.contains("hiragino") || lower.contains("gb")) {
            out.add("黑体"); out.add("Heiti"); out.add("PingFang");
        }
        if (lower.contains("pingfang")) {
            out.add("PingFang"); out.add("黑体");
        }
        return out;
    }

    private static boolean isNativeImage() {
        return System.getProperty("org.graalvm.nativeimage.imagecode") != null
                || "Substrate VM".equals(System.getProperty("java.vm.name"));
    }

    private static void setStaticField(String name, Object value) throws Exception {
        Field f = FontLoader.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(null, value);
    }
}
