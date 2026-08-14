package io.github.ofdcli.util;

import io.github.ofdcli.Main;
import io.github.ofdcli.awt.NativeImageFontBootstrap;
import org.ofdrw.converter.FontLoader;
import picocli.CommandLine;

import java.io.File;
import java.nio.file.Path;
import java.util.List;

/**
 * Font setup shared by every subcommand that renders OFD content.
 *
 * <p>This is the same code path that {@code ToPngCommand} used inline;
 * lifted so {@code to-pdf} / {@code to-html} / {@code to-svg} all get
 * identical Chinese-font coverage.
 */
public final class FontSetup {

    private FontSetup() {}

    public static void setup(List<Path> extraFontDirs, boolean noDefaultFonts) {
        // Lazy font bootstrap on native-image. FontLoader.getInstance() must
        // be called after NativeImageFontBootstrap.bootstrap() to pick up our
        // pre-populated singleton. NativeImageFontBootstrap is a no-op on
        // regular JVM, so this is harmless there.
        NativeImageFontBootstrap.bootstrap();
        FontLoader fl = FontLoader.getInstance();
        // CJK aliases that show up in OFD files from Chinese government,
        // ride-hailing and e-commerce platforms. Mirrors OFD2PNGTest.java.
        fl.addAliasMapping("KaiTi_GB2312", "楷体")
                .addAliasMapping("KaiTi", "楷体")
                .addSimilarFontReplaceRegexMapping(".*Kai.*", "楷体")
                .addSimilarFontReplaceRegexMapping(".*SimSun.*", "SimSun")
                .addSimilarFontReplaceRegexMapping(".*Song.*", "宋体")
                .addSimilarFontReplaceRegexMapping(".*Hei.*", "黑体")
                .addSimilarFontReplaceRegexMapping(".*MinionPro.*", "SimSun");
        FontLoader.setSimilarFontReplace(true);

        // On the JVM fat-jar path, scan system font dirs. On native-image,
        // NativeImageFontBootstrap has already pre-populated FontLoader
        // before Main.main() even constructed the CommandLine.
        if (!noDefaultFonts && !FilesUtil.isNativeImage()) {
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("mac") || os.contains("darwin")) {
                scan("/System/Library/Fonts");
                scan("/System/Library/Fonts/Supplemental");
            } else if (os.contains("win")) {
                scan("C:/Windows/Fonts");
            } else {
                scan("/usr/share/fonts");
                scan("/usr/local/share/fonts");
            }
        }
        if (extraFontDirs != null) {
            for (Path d : extraFontDirs) {
                scan(d.toString());
            }
        }
    }

    private static void scan(String dir) {
        File f = new File(dir);
        if (f.isDirectory()) {
            try {
                FontLoader.getInstance().scanFontDir(f);
            } catch (Exception e) {
                System.err.println("Warning: failed to scan font dir " + dir + ": " + e.getMessage());
            }
        }
    }

    public static boolean isJson(Main parent) {
        return parent != null && parent.isJson();
    }
}
