package com.javelin.core.debug;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * DebugText 16x16 点阵字库（运行时加载侧）。
 *
 * <p>加载 javelin-tool {@code DebugTextAssets} 生成的 {@code debug_font16.bin}
 * （classpath 资源 {@code /debug/debug_font16.bin}），格式契约见生成器
 * {@code com.javelin.tool.debugtext.DebugTextFontRasterizer} javadoc——大端：
 * "JDTF" magic / 版本 / 字形数 / 升序 codepoint 表 / 每字形 32 字节 1bpp 位图
 * （16 行 × 2 字节，MSB=最左列，半角画左 8 列）。</p>
 *
 * <p>图集布局：每行 32 字形（512px 宽）纵向排布；µv 由字形索引换算。
 * 缺字（不在字库的字符）回退 □（U+25A1，生成器保证收录）。</p>
 */
public final class DebugFont16 {

    /** 魔数 "JDTF"（与生成器 DebugTextFontRasterizer.MAGIC 约定一致，core 不依赖 tool） */
    static final int MAGIC = 0x4A445446;
    /** bin 格式版本 */
    static final int VERSION = 1;

    /** 字形宽（全角），像素 */
    public static final int GLYPH_WIDTH = 16;
    /** 字形高，像素 */
    public static final int GLYPH_HEIGHT = 16;
    /** 行间距：行与行之间的空像素（用户拍板 2026-09-27） */
    public static final int ROW_SPACING = 2;
    /** 行距：一行字形顶部到下一行字形顶部 = 字高 + 行间距（16+2=18px） */
    public static final int ROW_PITCH = GLYPH_HEIGHT + ROW_SPACING;
    /** 半角宽，像素 */
    public static final int HALF_WIDTH = 8;
    /** 图集每行字形数（图集宽固定 512，COLUMNS×GLYPH_HEIGHT 纵向增长，任何分辨率都在纹理上限内） */
    public static final int ATLAS_COLUMNS = 32;
    /** □ 缺字回退符 */
    public static final int FALLBACK_CODEPOINT = 0x25A1;

    private final int[] codepoints;
    private final byte[] bitmaps;
    private final int fallbackIndex;

    private DebugFont16(int[] codepoints, byte[] bitmaps, int fallbackIndex) {
        this.codepoints = codepoints;
        this.bitmaps = bitmaps;
        this.fallbackIndex = fallbackIndex;
    }

    /** 从 classpath 加载并解析字库 bin */
    public static DebugFont16 load(String resource) {
        try (InputStream stream = DebugFont16.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("字库资源不存在: " + resource + "（先跑 javelin-tool DebugTextAssets 生成）");
            }
            try (DataInputStream in = new DataInputStream(new BufferedInputStream(stream))) {
                int magic = in.readInt();
                int version = in.readInt();
                if (magic != MAGIC || version != VERSION) {
                    throw new IOException("字库文件头不合法: magic=0x" + Integer.toHexString(magic)
                            + " version=" + version + "（期望 JDTF/" + VERSION + "）");
                }
                int count = in.readInt();
                if (count <= 0) {
                    throw new IOException("字库字形数为 0");
                }
                int[] codepoints = new int[count];
                int fallback = -1;
                for (int i = 0; i < count; i++) {
                    int cp = in.readInt();
                    codepoints[i] = cp;
                    if (cp == FALLBACK_CODEPOINT) {
                        fallback = i;
                    }
                }
                byte[] bitmaps = new byte[count * GLYPH_HEIGHT * 2];
                in.readFully(bitmaps);
                return new DebugFont16(codepoints, bitmaps, Math.max(0, fallback));
            }
        } catch (IOException e) {
            throw new IllegalStateException("DebugText 字库加载失败: " + e.getMessage(), e);
        }
    }

    /** 字形总数 */
    public int glyphCount() {
        return codepoints.length;
    }

    /** codepoint → 字形索引；缺字回退 □（图集固定 32 列） */
    public int glyphIndex(int codePoint) {
        int low = 0;
        int high = codepoints.length - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midCp = codepoints[mid];
            if (midCp < codePoint) {
                low = mid + 1;
            } else if (midCp > codePoint) {
                high = mid - 1;
            } else {
                return mid;
            }
        }
        return fallbackIndex;
    }

    /**
     * 半角/全角分类（wcwidth 简化版）——与生成器
     * {@code com.javelin.tool.debugtext.DebugTextFontRasterizer#isHalfwidth} 同一口径：
     * ASCII 可打印区为半角 8px，其余一律全角 16px。
     */
    public static boolean isHalfwidth(int codePoint) {
        return codePoint >= 0x20 && codePoint <= 0x7E;
    }

    /** 图集宽，像素 */
    public int atlasWidth() {
        return ATLAS_COLUMNS * GLYPH_WIDTH;
    }

    /** 图集高，像素 */
    public int atlasHeight() {
        return ((codepoints.length + ATLAS_COLUMNS - 1) / ATLAS_COLUMNS) * GLYPH_HEIGHT;
    }

    /** 字形在图集中的像素 x */
    public int atlasGlyphX(int glyphIndex) {
        return (glyphIndex % ATLAS_COLUMNS) * GLYPH_WIDTH;
    }

    /** 字形在图集中的像素 y */
    public int atlasGlyphY(int glyphIndex) {
        return (glyphIndex / ATLAS_COLUMNS) * GLYPH_HEIGHT;
    }

    /** 位图行字节（2 字节/行 × 16 行）在 bitmaps 数组中的起始下标 */
    private int bitmapOffset(int glyphIndex, int row) {
        return glyphIndex * GLYPH_HEIGHT * 2 + row * 2;
    }

    /**
     * 全图集展开为 R8_UNORM 像素（1bpp → 每像素一字节 0/255），
     * 上传纹理用（先 put 后 flip）。
     */
    public byte[] atlasPixels() {
        byte[] pixels = new byte[atlasWidth() * atlasHeight()];
        int index = 0;
        while (index < codepoints.length) {
            int gx = atlasGlyphX(index);
            int gy = atlasGlyphY(index);
            for (int row = 0; row < GLYPH_HEIGHT; row++) {
                int b0 = bitmaps[bitmapOffset(index, row)] & 0xFF;
                int b1 = bitmaps[bitmapOffset(index, row) + 1] & 0xFF;
                int lineBase = (gy + row) * atlasWidth() + gx;
                for (int x = 0; x < GLYPH_WIDTH; x++) {
                    int bits = x < HALF_WIDTH ? b0 : b1;
                    int bit = x < HALF_WIDTH ? (0x80 >> x) : (0x80 >> (x - HALF_WIDTH));
                    pixels[lineBase + x] = (bits & bit) != 0 ? (byte) 0xFF : 0;
                }
            }
            index++;
        }
        return pixels;
    }
}
