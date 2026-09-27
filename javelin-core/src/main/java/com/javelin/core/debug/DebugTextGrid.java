package com.javelin.core.debug;

import java.util.Arrays;

/**
 * DebugText 文本网格（bgfx dbgText 同款 CUI 模型，2026-09-27 用户重定义 API：
 * <b>行级追加 + 自动叠行</b>，不做坐标式定位、不做 \n\r 转义）。
 *
 * <p>格子单位 = 半角 8px（col）× 行距 18px（row；字高 16 + 行间 2，2026-09-27 用户拍板）；
 * 全角（汉字等）占 2 列。
 * 左上原点，内容持续到 {@link #clear()}。窗口/表面尺寸变化由宿主
 * {@link #resize} 重建，内容与游标全部清空。</p>
 *
 * <p>游标模型：</p>
 * <ul>
 *   <li>{@code rowCursors[row]} —— 每行"追加位"（该行现有文字之后的第一空列），
 *       {@link #append} 固定行追加写在此处；</li>
 *   <li>{@code autoRow} —— 自动叠行指针，{@link #appendLine} 写该行后 +1；
 *       {@link #clear} 归 0。</li>
 * </ul>
 *
 * <p>存储：格子值 0 = 空；非 0 存 codepoint（空格照存——bg 上色要出画面）。
 * 全角只登记首格（扫描时画 2 列宽、跳过 colspan=2）。attr 低 4 前景 + 高 4 背景
 * （{@link DebugTextColor}）。</p>
 */
final class DebugTextGrid {

    private int cols;
    private int rows;
    private int[] cells;
    private byte[] attrs;
    /** 行追加游标：每行下一写入列 */
    private int[] rowCursors;
    /** 自动叠行指针（无参 printf 语义） */
    private int autoRow;
    private boolean hasContent;

    DebugTextGrid(int cols, int rows) {
        resize(cols, rows);
    }

    /** 列数（半角单位） */
    int cols() {
        return cols;
    }

    /** 行数 */
    int rows() {
        return rows;
    }

    /** 是否存在任何内容（overlay 空内容时回调零绘制） */
    boolean hasContent() {
        return hasContent;
    }

    /** 越界返回 0 */
    int codepointAt(int col, int row) {
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return 0;
        }
        return cells[row * cols + col];
    }

    /** 越界返回 0（黑底黑字） */
    int attrAt(int col, int row) {
        if (col < 0 || col >= cols || row < 0 || row >= rows) {
            return 0;
        }
        return attrs[row * cols + col] & 0xFF;
    }

    /** 表面尺寸变化重建：内容与游标全部清空 */
    void resize(int newCols, int newRows) {
        this.cols = Math.max(1, newCols);
        this.rows = Math.max(1, newRows);
        this.cells = new int[this.cols * this.rows];
        this.attrs = new byte[this.cols * this.rows];
        resetCursors();
    }

    /** 清空全部文本与游标（autoRow 归 0） */
    void clear() {
        Arrays.fill(cells, 0);
        Arrays.fill(attrs, (byte) 0);
        resetCursors();
    }

    /**
     * 清空单行：该行内容与追加游标归位；自动行指针不动（两个流各自独立——
     * "每帧 clear(row)+printf(row,...)" 即单行刷新，其余行持续语义不受扰）。
     */
    void clearRow(int row) {
        if (row < 0 || row >= rows) {
            return;
        }
        Arrays.fill(cells, row * cols, (row + 1) * cols, 0);
        Arrays.fill(attrs, row * cols, (row + 1) * cols, (byte) 0);
        rowCursors[row] = 0;
        recomputeHasContent();
    }

    /**
     * 固定行追加：写在 {@code rowCursors[row]}（该行现有文字之后），游标推进。
     * 越出右缘被裁剪，游标贴住 cols（后续追加空转，直到 clear）。
     */
    void append(int row, int attr, CharSequence txt) {
        if (txt == null || txt.isEmpty() || row < 0 || row >= rows) {
            return;
        }
        rowCursors[row] = write(rowCursors[row], row, attr, txt);
    }

    /** 自动叠行：写当前 autoRow（写完 +1 换下一行）；打满到 rows 即静默止步 */
    void appendLine(int attr, CharSequence txt) {
        if (txt == null || txt.isEmpty() || autoRow >= rows) {
            return;
        }
        rowCursors[autoRow] = write(rowCursors[autoRow], autoRow, attr, txt);
        autoRow++;
    }

    // ═════════════════ 内部 ═════════════════

    private void resetCursors() {
        rowCursors = new int[rows];
        autoRow = 0;
        hasContent = false;
    }

    /** 单行清后全网格重扫 hasContent（一次 int 数组扫描，可忽略） */
    private void recomputeHasContent() {
        for (int cell : cells) {
            if (cell != 0) {
                hasContent = true;
                return;
            }
        }
        hasContent = false;
    }

    /**
     * 从 col 起布字，返回写到的下一列（已按 cols 截住）。
     * 控制字符（&lt;0x20，含 \n\r\t——用户拍板不做转义换行）跳过不写不进位。
     * 半/全角分类与 {@link DebugFont16#isHalfwidth} 同口径。
     */
    private int write(int col, int row, int attr, CharSequence text) {
        String s = text.toString();
        int c = Math.max(0, col);
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            if (cp < 0x20) {
                continue;       // 控制字符不处理（\n\r\t 均在此列，不做转义换行）
            }
            int width = DebugFont16.isHalfwidth(cp) ? 1 : 2;
            if (c + width <= cols) {
                cells[row * cols + c] = cp;
                attrs[row * cols + c] = (byte) attr;
                hasContent = true;
            }
            c += width;         // 越界部分不写但列照走——返回游标贴住 cols
        }
        return Math.min(c, cols);
    }
}
