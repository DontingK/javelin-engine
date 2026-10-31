package cn.donting.javelin.tool.debugtext;

import cn.donting.javelin.tool.shaderc.ShaderCompiler;
import cn.donting.javelin.tool.shaderc.ShaderStage;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.SortedSet;
import java.util.TreeSet;

/**
 * DebugText 调试文本资产生成入口（CLI）。
 *
 * <p>一次生成 core 运行时所需的全部三件产物，写入输出目录（即
 * {@code javelin-core/src/main/resources/debug/}）：</p>
 * <ul>
 *   <li>{@code debug_font16.bin} —— 16x16 1bpp 点阵字库
 *       （字符集 = ASCII 32-126 + GB2312 符号区 01-09 + GB2312 一级 3755 + □ U+25A1）；</li>
 *   <li>{@code debugtext.vert.spv} / {@code debugtext.frag.spv} —— 本类同包
 *       resources（{@code /debugtext/}）里的 GLSL 源经 shaderc 离线编译。</li>
 * </ul>
 *
 * <p><b>用法（参数全部可选，零参即可跑）：</b></p>
 * <pre>
 * gradlew :javelin-tool:run                                     # 全默认
 * gradlew :javelin-tool:run --args="&lt;TTF/OTF 路径&gt;"        # 换字体
 * gradlew :javelin-tool:run --args="&lt;字体&gt; &lt;输出目录&gt;"  # 全指定
 * </pre>
 * <p>默认字体 = 打包在 tool resources 的思源黑体 Bold（SIL OFL，
 * {@code /front/SourceHanSansSC-Bold.otf}）；默认输出目录 = 自动上溯定位仓库根
 * （认 {@code settings.gradle} + {@code javelin-core/}），指向
 * {@code javelin-core/src/main/resources/debug}——不依赖调用时的 CWD。</p>
 *
 * @since 1.0.0
 */
public final class DebugTextAssets {

    /** bin 产物名。 */
    public static final String FONT_BIN = "debug_font16.bin";
    /** vs 产物名。 */
    public static final String VERT_SPV = "debugtext.vert.spv";
    /** fs 产物名。 */
    public static final String FRAG_SPV = "debugtext.frag.spv";

    /** 打包字体：思源黑体 Bold（SIL OFL，tool resources）。 */
    private static final String BUNDLED_FONT = "/front/SourceHanSansSC-Bold.otf";

    private static final String VERT_RESOURCE = "/debugtext/debugtext.vert";
    private static final String FRAG_RESOURCE = "/debugtext/debugtext.frag";

    private DebugTextAssets() {
    }

    /**
     * CLI 入口。
     *
     * @param args [0]=TTF/OTF 路径（可选，缺省用打包字体），[1]=输出目录（可选，缺省写 core resources/debug）
     */
    static void main(String[] args) throws IOException {
        System.setProperty("java.awt.headless", "true");
        Charset charset1 = System.out.charset();
        Path fontFile = args.length > 0 ? Path.of(args[0]) : bundledFont();
        Path outDir = args.length > 1 ? Path.of(args[1]) : defaultOutDir();
        if (!Files.isRegularFile(fontFile)) {
            System.err.println("字体文件不存在: " + fontFile.toAbsolutePath());
            System.exit(2);
        }
        Files.createDirectories(outDir);
        System.out.println("[DebugText] 字体: " + fontFile.toAbsolutePath());
        System.out.println("[DebugText] 输出: " + outDir.toAbsolutePath());

        // ── 1. 字库 bin ──────────────────────────────────────────────
        SortedSet<Integer> charset = charset();
        System.out.println("[DebugText] 字符集: " + charset.size() + " 字形（ASCII 95 + GB2312 符号区 + 一级 3755 + □）");

        Path binPath = outDir.resolve(FONT_BIN);
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(binPath)))) {
            DebugTextFontRasterizer.rasterize(fontFile, charset.stream().toList(), out);
        }
        printPreview(binPath, charset);

        // ── 2. SPIR-V ────────────────────────────────────────────────
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            writeSpv(compiler, VERT_RESOURCE, ShaderStage.VERTEX, outDir.resolve(VERT_SPV));
            writeSpv(compiler, FRAG_RESOURCE, ShaderStage.FRAGMENT, outDir.resolve(FRAG_SPV));
        }

        System.out.println("[DebugText] 完成: " + binPath.toAbsolutePath());
        System.out.println("[DebugText] 完成: " + outDir.resolve(VERT_SPV).toAbsolutePath());
        System.out.println("[DebugText] 完成: " + outDir.resolve(FRAG_SPV).toAbsolutePath());
    }

    /**
     * 字符集：ASCII 32-126（半角）+ GB2312 符号区 01-09（0xA1A1..0xA9FE，
     * 全角标点/制表符/假名等，用户 2026-09-27 补拍板）+ GB2312 一级 3755
     * （0xB0A1..0xD7F9）+ □ 回退符，TreeSet 升序去重。
     */
    static SortedSet<Integer> charset() {
        SortedSet<Integer> set = new TreeSet<>();
        for (int c = 0x20; c <= 0x7E; c++) {
            set.add(c);
        }
        Charset gb2312 = Charset.forName("GB2312");
        for (int hi = 0xA1; hi <= 0xA9; hi++) {
            for (int lo = 0xA1; lo <= 0xFE; lo++) {
                addGb2312(set, gb2312, hi, lo);            // 符号区：未定义码位解码得 U+FFFD 自然跳过
            }
        }
        for (int hi = 0xB0; hi <= 0xD7; hi++) {
            int loEnd = (hi == 0xD7) ? 0xF9 : 0xFE;   // 一级末位 0xD7F9，其后为二级
            for (int lo = 0xA1; lo <= loEnd; lo++) {
                addGb2312(set, gb2312, hi, lo);
            }
        }
        set.add(0x25A1);                               // □ 缺字回退符
        return set;
    }

    /** GB2312 双字节码位解码入集：解码失败（U+FFFD）静默跳过。 */
    private static void addGb2312(SortedSet<Integer> set, Charset gb2312, int hi, int lo) {
        String s = new String(new byte[]{(byte) hi, (byte) lo}, gb2312);
        if (s.codePointAt(0) != 0xFFFD) {
            set.add(s.codePointAt(0));
        }
    }

    /** GLSL 源（classpath 资源）→ shaderc 编译 → 写 .spv 文件。 */
    private static void writeSpv(ShaderCompiler compiler, String resource, ShaderStage stage, Path target)
            throws IOException {
        String source = readResource(resource);
        byte[] spirv = compiler.compile(source, stage, resource);
        Files.write(target, spirv);
        System.out.println("[DebugText] 编译 " + resource + " (" + stage + ") -> " + spirv.length + " 字节");
    }

    /**
     * 零参默认字体：把打包在 resources 里的思源黑体 Bold 解到临时文件
     * （{@code Font.createFont} 只收 {@link java.io.File}），JVM 退出时自动清理。
     */
    private static Path bundledFont() throws IOException {
        try (InputStream in = DebugTextAssets.class.getResourceAsStream(BUNDLED_FONT)) {
            if (in == null) {
                throw new IOException("内置字体资源不存在: " + BUNDLED_FONT);
            }
            Path temp = Files.createTempFile("debugtext-font", ".otf");
            Files.copy(in, temp, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            temp.toFile().deleteOnExit();
            return temp;
        }
    }

    /**
     * 零参默认输出目录：从 CWD 逐级上溯定位仓库根（同时存在
     * {@code settings.gradle} 与 {@code javelin-core/} 目录者），返回
     * {@code <仓库根>/javelin-core/src/main/resources/debug}；
     * 定位失败（如从仓库外运行）退回相对路径交由调用方自担。
     */
    private static Path defaultOutDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    && Files.isDirectory(dir.resolve("javelin-core"))) {
                return dir.resolve("javelin-core").resolve("src/main/resources/debug");
            }
            dir = dir.getParent();
        }
        System.err.println("[DebugText] 未找到仓库根，输出到 CWD 相对路径 javelin-core/src/main/resources/debug");
        return Path.of("javelin-core/src/main/resources/debug");
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = DebugTextAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }

    /**
     * bin 回读预览：验证产物可解析（magic/版本/计数 + 抽样字形 ASCII 画）。
     * 字库可读性的第一道验收，不用起引擎。
     */
    private static void printPreview(Path binPath, SortedSet<Integer> charset) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(binPath)))) {
            if (in.readInt() != DebugTextFontRasterizer.MAGIC || in.readInt() != DebugTextFontRasterizer.VERSION) {
                throw new IOException("bin 头不合法: " + binPath);
            }
            int count = in.readInt();
            System.out.println("[DebugText] bin 回读校验: " + count + " 字形（期望 " + charset.size() + "）");
            if (count != charset.size()) {
                throw new IOException("bin 字形数不符");
            }
            int[] codepoints = new int[count];
            for (int i = 0; i < count; i++) {
                codepoints[i] = in.readInt();
            }
            // 位图数据整体读回（~123KB，全量校验顺带免除跳转游标）
            byte[] bitmaps = new byte[count * DebugTextFontRasterizer.BYTES_PER_GLYPH];
            in.readFully(bitmaps);
            for (int sample : new int[]{'A', '汉', '，', 0x25A1}) {
                int idx = java.util.Arrays.binarySearch(codepoints, sample);
                if (idx < 0) {
                    System.out.println("[DebugText] 预览字形不在字库: U+" + Integer.toHexString(sample));
                    continue;
                }
                System.out.println("[DebugText] 字形预览 U+" + Integer.toHexString(sample)
                        + " '" + new String(Character.toChars(sample)) + "':");
                int width = DebugTextFontRasterizer.isHalfwidth(sample)
                        ? DebugTextFontRasterizer.HALF_WIDTH : DebugTextFontRasterizer.CELL_WIDTH;
                for (int y = 0; y < DebugTextFontRasterizer.CELL_HEIGHT; y++) {
                    StringBuilder line = new StringBuilder();
                    for (int px = 0; px < width; px++) {
                        boolean on = (bitmaps[idx * DebugTextFontRasterizer.BYTES_PER_GLYPH
                                + y * 2 + (px >> 3)] & (0x80 >> (px & 7))) != 0;
                        line.append(on ? '#' : '.');
                    }
                    System.out.println("  " + line);
                }
            }
        }
    }
}
