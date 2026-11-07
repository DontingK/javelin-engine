package cn.donting.javelin.tool.debugtext;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * DebugText 点阵字库光栅化器。
 *
 * <p>把用户提供的 TTF/OTF 在 16px 处抗锯齿渲染后阈值二值化（alpha ≥ 128 → 1bpp），
 * 输出统一的 16x16 位图：半角 ASCII 画在左 8 列（半格 8x16），全角（汉字等）占满 16x16。
 * 整行基线一致（取字体自身 ascent 圆整），格子内水平按 advance 居中。</p>
 *
 * <p><b>bin 产物格式（大端序，DataOutputStream/DataInputStream 直读写）：</b></p>
 * <pre>
 * int  magic     = 0x4A445446        // "JDTF"
 * int  version   = 1
 * int  glyphCount
 * int[glyphCount] codepoints          // 升序（加载侧二分查找）
 * glyphCount × 32 字节  位图            // 每字形 16 行 × 2 字节（1bpp，MSB 在最左列）
 * </pre>
 * <p>字形宽度（半角 8 / 全角 16）不进 bin，由引擎侧 wcwidth 分类按 codepoint 区间推导。</p>
 *
 * <p><b>调用方</b>：编辑器 {@code BitmapFontImporter}（.chars 字符集文本 → 导出
 * {@code BitmapFont} 资产）与 core 测试侧 {@code TestAssetManager}（内置源现烘焙）；
 * 产物不再以文件形式提交仓库，旧的独立 CLI（DebugTextAssets）已于 2026-11-07 清退。</p>
 *
 * @since 1.0.0
 */
public final class DebugTextFontRasterizer {

    /** 格子宽（全角），像素。 */
    public static final int CELL_WIDTH = 16;
    /** 格子高=行高，像素。 */
    public static final int CELL_HEIGHT = 16;
    /** 半角宽，像素。 */
    public static final int HALF_WIDTH = 8;
    /** 每字形字节数：16 行 × 2 字节。 */
    public static final int BYTES_PER_GLYPH = CELL_HEIGHT * 2;

    /** bin 魔数 "JDTF"（Javelin Debug Text Font）。 */
    public static final int MAGIC = 0x4A445446;
    /** bin 格式版本。 */
    public static final int VERSION = 1;

    /** □ 缺字回退符（加载侧 DebugFont16.glyphIndex 缺字回退目标，字符集解析时无条件补入）。 */
    public static final int FALLBACK_CODEPOINT = 0x25A1;

    /** 打包字体（思源黑体 Bold，SIL OFL）在 tool resources 下的路径。 */
    public static final String BUNDLED_FONT_RESOURCE = "/front/SourceHanSansSC-Bold.otf";

    /** 打包字体解出的临时文件（同一 JVM 只解一次）。 */
    private static Path bundledFontCache;

    /** 二值化阈值：alpha ≥ 该值视为点亮。 */
    private static final int THRESHOLD = 128;

    private DebugTextFontRasterizer() {
    }

    /**
     * 光栅化字符集并写出 bin。
     *
     * <p>codepoints 须升序去重（建议 {@link TreeSet}）；缺字（字体不含该字形）
     * 输出全零位图并打印警告到 stderr，不中断。</p>
     *
     * @param fontFile  TTF/OTF 字体文件
     * @param codepoints 升序去重的字符集
     * @param out       bin 输出流（调用方负责关闭）
     * @throws IOException 字体读取或写出失败
     */
    public static void rasterize(Path fontFile, List<Integer> codepoints, DataOutputStream out) throws IOException {
        Font font;
        try {
            font = Font.createFont(Font.TRUETYPE_FONT, fontFile.toFile()).deriveFont(Font.PLAIN, 16f);
        } catch (java.awt.FontFormatException e) {
            throw new IOException("字体文件解析失败: " + fontFile, e);
        }

        BufferedImage image = new BufferedImage(CELL_WIDTH, CELL_HEIGHT, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            // 硬边渲染（ANTIALIAS OFF）：1bpp 阈值二值化的正确配法——抗锯齿会把劈在
            // 半像素边界上的 1px 细竖线分成两个 ~50% alpha 列，双双低于阈值整笔蒸发
            // （□ 只剩两横、│┆┊ 为空的实测坑，2026-09-27）；硬边+点阵风也正相配。
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);

            // 整行统一基线：实测参考串墨迹带，在 16px 行高内垂直居中。
            // 不能信字体自报 ascent——思源黑体这类大度量字体（hhea 抬得很高）会把
            // 句号/逗号/下划线等纯降部字形整块推出画布底（实测坑，2026-09-27）。
            FontRenderContext frc = g.getFontRenderContext();
            int baseline = computeBaseline(font);

            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(codepoints.size());
            for (int cp : codepoints) {
                out.writeInt(cp);
            }
            for (int cp : codepoints) {
                out.write(rasterGlyph(font, g, frc, baseline, image, cp));
            }
        } finally {
            g.dispose();
        }
    }

    /**
     * 字符集文本 → 升序去重 codepoint 集合。
     *
     * <p>排版字符（CR/LF/TAB）不算内容（换行/缩进是文件版式，不是要烘焙的字形）；
     * 其余 code point 全部计入。□ 回退符（{@link #FALLBACK_CODEPOINT}）无条件补入
     * ——加载侧 {@code DebugFont16.glyphIndex} 查不到字符时回退到它，缺席会把回退
     * 错误地指向首字形。</p>
     *
     * @param text 字符集文本（{@code .chars} 内容）
     * @return 升序去重 codepoint 集合
     */
    public static SortedSet<Integer> parseCharset(String text) {
        SortedSet<Integer> set = new TreeSet<>();
        text.codePoints().forEach(cp -> {
            if (cp == '\r' || cp == '\n' || cp == '\t') {
                return;
            }
            set.add(cp);
        });
        set.add(FALLBACK_CODEPOINT);
        return set;
    }

    /**
     * 光栅化字符集为 JDTF bin 字节（{@link #rasterize} 的内存态变体，导出资产用）。
     *
     * @param fontFile   TTF/OTF 字体文件
     * @param codepoints 字符集（升序去重由 {@link #parseCharset} 保证，非空）
     * @return JDTF bin 字节
     * @throws IOException 字体读取失败
     */
    public static byte[] rasterizeToBytes(Path fontFile, Collection<Integer> codepoints) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(8192);
        try (DataOutputStream out = new DataOutputStream(buffer)) {
            rasterize(fontFile, List.copyOf(codepoints), out);
        }
        return buffer.toByteArray();
    }

    /**
     * 解出打包字体到临时文件（{@link Font#createFont} 只收 {@link java.io.File}）。
     * 同一 JVM 内只解一次；临时文件 JVM 退出时清理。
     *
     * @return 打包字体临时文件路径
     * @throws IOException 资源缺失或写出失败
     */
    public static synchronized Path bundledFontPath() throws IOException {
        if (bundledFontCache != null && Files.isRegularFile(bundledFontCache)) {
            return bundledFontCache;
        }
        try (InputStream in = DebugTextFontRasterizer.class.getResourceAsStream(BUNDLED_FONT_RESOURCE)) {
            if (in == null) {
                throw new IOException("打包字体资源不存在: " + BUNDLED_FONT_RESOURCE);
            }
            Path temp = Files.createTempFile("debugtext-font", ".otf");
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            bundledFontCache = temp;
            return temp;
        }
    }

    /**
     * 光栅化单个字符：清画布 → 按 advance 居中画到基线 → 读 alpha 阈值打包 1bpp。
     *
     * @return 32 字节位图；缺字（notdef 或字形全空）返回全零位图由调用方计数
     */
    private static byte[] rasterGlyph(Font font, Graphics2D g, FontRenderContext frc, int baseline,
                                      BufferedImage image, int codePoint) {
        int cellWidth = isHalfwidth(codePoint) ? HALF_WIDTH : CELL_WIDTH;

        // 清画布为全透明（clearRect 受当前合成态影响，这里显式 Clear 合成）
        g.setComposite(AlphaComposite.Clear);
        g.fillRect(0, 0, CELL_WIDTH, CELL_HEIGHT);
        g.setComposite(AlphaComposite.SrcOver);

        GlyphVector gv = font.createGlyphVector(frc, new String(Character.toChars(codePoint)));
        boolean missing = gv.getNumGlyphs() == 0 || gv.getGlyphCode(0) == 0;
        float advance = gv.getGlyphMetrics(0).getAdvance();
        int x = Math.round((cellWidth - advance) * 0.5f);
        g.drawGlyphVector(gv, x, baseline);

        byte[] bitmap = new byte[BYTES_PER_GLYPH];
        boolean any = false;
        for (int y = 0; y < CELL_HEIGHT; y++) {
            for (int px = 0; px < cellWidth; px++) {
                if ((image.getRGB(px, y) >>> 24) >= THRESHOLD) {
                    bitmap[y * 2 + (px >> 3)] |= (byte) (0x80 >> (px & 7));
                    any = true;
                }
            }
        }
        if (missing) {
            System.err.println("[DebugTextFontRasterizer] 字体缺字形(回退空位图): U+"
                    + Integer.toHexString(codePoint).toUpperCase()
                    + " '" + new String(Character.toChars(codePoint)) + "'");
            return new byte[BYTES_PER_GLYPH];
        }
        if (!any && !isBlank(codePoint)) {
            System.err.println("[DebugTextFontRasterizer] 光栅化为空: U+"
                    + Integer.toHexString(codePoint).toUpperCase()
                    + " '" + new String(Character.toChars(codePoint)) + "'");
            return new byte[BYTES_PER_GLYPH];
        }
        return bitmap;
    }

    /**
     * 实测基线：把参考串（'Ag汉g.，'——覆盖 CJK 顶、拉丁帽高/降部、下缘标点/全角逗号）
     * 画到探针画布扫描墨迹带，令整带在 16px 行高内垂直居中，返回基线的格子内纸面 y。
     * 探针空（极端字体）兜底返回探针基线原值。
     */
    private static int computeBaseline(Font font) {
        int probeBaseline = 32;
        BufferedImage probe = new BufferedImage(96, 48, BufferedImage.TYPE_INT_ARGB);
        Graphics2D pg = probe.createGraphics();
        try {
            pg.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
            pg.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
            pg.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            pg.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            pg.drawGlyphVector(font.createGlyphVector(pg.getFontRenderContext(), "Ag汉g.，"),
                    4, probeBaseline);

            int top = Integer.MAX_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int y = 0; y < probe.getHeight(); y++) {
                for (int x = 0; x < probe.getWidth(); x++) {
                    if ((probe.getRGB(x, y) >>> 24) >= THRESHOLD) {
                        top = Math.min(top, y);
                        bottom = Math.max(bottom, y);
                    }
                }
            }
            if (top > bottom) {
                return probeBaseline;
            }
            int above = probeBaseline - top;               // 基线以上墨高
            int band = bottom - top + 1;                   // 全墨迹带高
            return above + Math.max(0, CELL_HEIGHT - band) / 2;   // 带高超行高则顶对齐
        } finally {
            pg.dispose();
        }
    }

    /** 空白类字符位图天然全空，不算缺字。 */
    private static boolean isBlank(int codePoint) {
        return codePoint == 0x20 || codePoint == 0x3000;   // ASCII 空格 / 全角空格
    }

    /**
     * 半角/全角分类（wcwidth 简化版，与引擎侧排版同一口径）：
     * ASCII 可打印区为半角 8px，其余（CJK/□ 等）一律全角 16px。
     */
    public static boolean isHalfwidth(int codePoint) {
        return codePoint >= 0x20 && codePoint <= 0x7E;
    }
}
