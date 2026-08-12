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
import java.util.Arrays;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
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
                    // Files.newInputStream can find it later.
                    String uniqueName = p.getFileName().toString() + "_" + Math.abs(p.toString().hashCode());
                    Path dest = fontDir.resolve(uniqueName);
                    try {
                        Files.copy(p, dest, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    } catch (IOException e) {
                        continue;
                    }
                    String pathStr = dest.toString();
                    if (firstFont == null) firstFont = dest;
                    // Register under every CJK alias — better to be redundant
                    // than to miss a font referenced by an OFD.
                    for (String alias : COMMON_NAMES) {
                        mapping.putIfAbsent(alias, pathStr);
                    }
                    // Also register the file basename (without extension)
                    String base = p.getFileName().toString();
                    int dot = base.lastIndexOf('.');
                    if (dot > 0) base = base.substring(0, dot);
                    for (String v : basenameVariations(base)) {
                        mapping.putIfAbsent(v, pathStr);
                    }
                }
            } catch (Exception ignored) {
                // skip unreadable dirs
            }
        }

        if (firstFont == null) {
            return false;
        }

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
