package cn.donting.javelin.tool.debugtext;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
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
