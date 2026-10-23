package com.javelin.tool.pick;

import com.javelin.tool.shaderc.ShaderCompiler;
import com.javelin.tool.shaderc.ShaderStage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * GPU ID-buffer 拾取着色器资产生成入口（CLI）。
 *
 * <p>本类同包 resources（{@code /pick/}）里的 GLSL 源经 shaderc 离线编译为
 * SPIR-V，写入 {@code javelin-core/src/main/resources/pick/}（覆盖制拾取
 * pass 专用，DebugTextAssets 同链路先例——core 主代码不出现 GLSL，拍板）。</p>
 *
 * <p><b>用法：</b></p>
 * <pre>
 * gradlew :javelin-tool:generatePickSpv              # 全默认（写 core resources/pick）
 * gradlew :javelin-tool:generatePickSpv --args="&lt;输出目录&gt;"  # 指定输出目录
 * </pre>
 *
 * @since 1.0.0
 */
public final class PickAssets {

    private static final String VERT_RESOURCE = "/pick/pick.vert";
    private static final String FRAG_RESOURCE = "/pick/pick.frag";
    private static final String VERT_SPV = "pick.vert.spv";
    private static final String FRAG_SPV = "pick.frag.spv";

    private PickAssets() {
    }

    /**
     * CLI 入口。
     *
     * @param args [0]=输出目录（可选，缺省写 core resources/pick）
     */
    static void main(String[] args) throws IOException {
        Path outDir = args.length > 0 ? Path.of(args[0]) : defaultOutDir();
        Files.createDirectories(outDir);
        System.out.println("[Pick] 输出: " + outDir.toAbsolutePath());

        try (ShaderCompiler compiler = new ShaderCompiler()) {
            writeSpv(compiler, VERT_RESOURCE, ShaderStage.VERTEX, outDir.resolve(VERT_SPV));
            writeSpv(compiler, FRAG_RESOURCE, ShaderStage.FRAGMENT, outDir.resolve(FRAG_SPV));
        }
        System.out.println("[Pick] 完成");
    }

    /** GLSL 源（classpath 资源）→ shaderc 编译 → 写 .spv 文件。 */
    private static void writeSpv(ShaderCompiler compiler, String resource, ShaderStage stage, Path target)
            throws IOException {
        String source = readResource(resource);
        byte[] spirv = compiler.compile(source, stage, resource);
        Files.write(target, spirv);
        System.out.println("[Pick] 编译 " + resource + " (" + stage + ") -> " + spirv.length + " 字节");
    }

    /**
     * 零参默认输出目录：从 CWD 逐级上溯定位仓库根（同时存在
     * {@code settings.gradle} 与 {@code javelin-core/} 目录者），返回
     * {@code <仓库根>/javelin-core/src/main/resources/pick}（DebugTextAssets 同法）。
     */
    private static Path defaultOutDir() {
        Path dir = Path.of("").toAbsolutePath();
        while (dir != null) {
            if (Files.isRegularFile(dir.resolve("settings.gradle"))
                    && Files.isDirectory(dir.resolve("javelin-core"))) {
                return dir.resolve("javelin-core").resolve("src/main/resources/pick");
            }
            dir = dir.getParent();
        }
        System.err.println("[Pick] 未找到仓库根，输出到 CWD 相对路径 javelin-core/src/main/resources/pick");
        return Path.of("javelin-core/src/main/resources/pick");
    }

    private static String readResource(String resource) throws IOException {
        try (InputStream in = PickAssets.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("classpath 资源不存在: " + resource);
            }
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            in.transferTo(buffer);
            return buffer.toString(StandardCharsets.UTF_8);
        }
    }
}
