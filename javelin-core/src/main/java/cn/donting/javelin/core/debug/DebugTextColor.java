package cn.donting.javelin.core.debug;

/**
 * DebugText 调色板与 attr 组合（doc/Engine_DebugText_Design.md §1 拍板：attr 一字节，
 * 低 4 位前景 + 高 4 位背景，共用同一张 16 色 EGA 风格调色板）。
 *
 * <pre>
 * DebugText.print(0, 0, DebugTextColor.LIME, "FPS: " + Time.getFps());
 * DebugText.print(0, 1, DebugTextColor.attr(DebugTextColor.YELLOW, DebugTextColor.BLUE), "标题");
 * </pre>
 *
 * <p>索引序对齐经典 VGA/EGA 16 色（bgfx 调试文本同源观感）：</p>
 * <ol start="0">
 *   <li>黑 <li>蓝 <li>绿 <li>青 <li>红 <li>品红 <li>棕 <li>浅灰
 *   <li>暗灰 <li>亮蓝 <li>亮绿 <li>亮青 <li>粉 <li>亮品红 <li>黄 <li>白
 * </ol>
 */
public final class DebugTextColor {

    // ---- 16 色索引（低 4 位前景 / 高 4 位背景共用） ----
    public static final int BLACK = 0;
    public static final int BLUE = 1;
    public static final int GREEN = 2;
    public static final int CYAN = 3;
    public static final int RED = 4;
    public static final int MAGENTA = 5;
    public static final int BROWN = 6;
    public static final int GRAY = 7;
    public static final int DARK_GRAY = 8;
    public static final int LIGHT_BLUE = 9;
    public static final int LIGHT_GREEN = 10;
    public static final int LIGHT_CYAN = 11;
    public static final int PINK = 12;
    public static final int LIGHT_MAGENTA = 13;
    public static final int YELLOW = 14;
    public static final int WHITE = 15;

    /** RGBA8888 调色板真值（VGA 经典取值），下标 = 颜色索引，[r,g,b,a] */
    static final byte[][] PALETTE = {
            {(byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0xFF},   // 0 黑
            {(byte) 0x00, (byte) 0x00, (byte) 0xAA, (byte) 0xFF},   // 1 蓝
            {(byte) 0x00, (byte) 0xAA, (byte) 0x00, (byte) 0xFF},   // 2 绿
            {(byte) 0x00, (byte) 0xAA, (byte) 0xAA, (byte) 0xFF},   // 3 青
            {(byte) 0xAA, (byte) 0x00, (byte) 0x00, (byte) 0xFF},   // 4 红
            {(byte) 0xAA, (byte) 0x00, (byte) 0xAA, (byte) 0xFF},   // 5 品红
            {(byte) 0xAA, (byte) 0x55, (byte) 0x00, (byte) 0xFF},   // 6 棕
            {(byte) 0xAA, (byte) 0xAA, (byte) 0xAA, (byte) 0xFF},   // 7 浅灰
            {(byte) 0x55, (byte) 0x55, (byte) 0x55, (byte) 0xFF},   // 8 暗灰
            {(byte) 0x55, (byte) 0x55, (byte) 0xFF, (byte) 0xFF},   // 9 亮蓝
            {(byte) 0x55, (byte) 0xFF, (byte) 0x55, (byte) 0xFF},   // 10 亮绿
            {(byte) 0x55, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},   // 11 亮青
            {(byte) 0xFF, (byte) 0x55, (byte) 0x55, (byte) 0xFF},   // 12 粉
            {(byte) 0xFF, (byte) 0x55, (byte) 0xFF, (byte) 0xFF},   // 13 亮品红
            {(byte) 0xFF, (byte) 0xFF, (byte) 0x55, (byte) 0xFF},   // 14 黄
            {(byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF},   // 15 白
    };

    private DebugTextColor() {
    }

    /** 组合 attr 字节：{@code attr(fg, bg)}，取值均须 0~15 */
    public static int attr(int fg, int bg) {
        return (fg & 0xF) | ((bg & 0xF) << 4);
    }
}
