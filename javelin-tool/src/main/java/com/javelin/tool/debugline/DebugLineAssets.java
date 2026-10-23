package com.javelin.tool.debugline;

import com.javelin.tool.shaderc.ShaderCompiler;
import com.javelin.tool.shaderc.ShaderStage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 编辑器调试线着色器资产生成入口（CLI，Editor_DebugLine_Design）。
 *
 * <p>本类同包 resources（{@code /debugline/}）里的 GLSL 源经 shaderc 离线编译为
 * SPIR-V，写入 {@code javelin-editor/src/main/resources/debugline/}（编辑器专属
 * DebugLinePass 专用，PickAssets 同链路先例——GLSL 源不进 editor 主代码）。</p>
 *
 * <p><b>用法：</b></p>
 * <pre>
 * gradlew :javelin-tool:generateDebugLineSpv              # 全默认（写 editor resources/debugline）
 * gradlew :javelin-tool:generateDebugLineSpv --args="<输出目录>"  # 指定输出目录
 * </pre>
 *
 * @since 1.0.0
 */
public final class DebugLineAssets {

    private static final String VERT_RESOURCE = "/debugline/debugline.vert";
    private static final String FRAG_RESOURCE = "/debugline/debugline.frag";
    private static final String VERT_SPV = "debugline.vert.spv";
    private static final String FRAG_SPV = "debugline.frag.spv";

    private DebugLineAssets() {
    }

    /**
     * CLI 入口。
     *
     * @param args [0]=输出目录（可选，缺省写 editor resources/debugline）
     */
    static void main(String[] args) throws IOException {
        Path outDir = args.length > 0 ? Path.of(args[0]) : defaultOutDir();
        Files.createDirectories(outDir);
        System.out.println("[DebugLine] 输出: " + outDir.toAbsolutePath());

        try (ShaderCompiler compiler = new ShaderCompiler()) {
            writeSpv(compiler, VERT_RESOURCE, ShaderStage.VERTEX, outDir.resolve(VERT_SPV));
            writeSpv(compiler, FRAG_RESOURCE, ShaderStage.FRAGMENT, outDir.resolve(FRAG_SPV));
        }
        System.out.println("[DebugLine] 完成");
    }

    /** GLSL 源（classpath 资源）→ shaderc 编译 → 写 .spv 文件。 */
    private static void writeSpv(ShaderCompiler compiler, String resource, ShaderStage stage, Path target)
            throws IOException {
        String source = readResource(resource);
        byte[] spirv = compiler.compile(source, stage, resource);
        Files.write(target, spirv);
        System.out.println("[DebugLine] 编译 " + resource + " (" + stage + ") -> " + spirv.length + " 字节");
    }

    /**
     * 零参默认输出目录：从 CWD 逐级上溯定位仓库根（同时存在
     * {@code settings.gradle} 与 {@code javelin-core/} 目录者），返回
     * {@code <仓库根>/javelin-editor/src/main/resources/debugline}（PickAssets 同法）。
     */
    private static Path defaultOutDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    && Files.isDirectory(dir.resolve("javelin-core"))) {
                return dir.resolve("javelin-editor").resolve("src/main/resources/debugline");
            }
            dir = dir.getParent();
        }
        System.err.println("[DebugLine] 未找到仓库根，输出到 CWD 相对路径 javelin-editor/src/main/resources/debugline");
        return Path.of("javelin-editor/src/main/resources/debugline");
    }

    /** 读 classpath 资源为字符串（UTF-8）。 */
    private static String readResource(String resource) throws IOException {
        try (InputStream in = DebugLineAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
