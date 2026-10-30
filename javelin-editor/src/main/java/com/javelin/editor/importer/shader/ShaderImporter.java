package com.javelin.editor.importer.shader;

import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.ShaderPass;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.project.Project;
import com.javelin.tool.shaderc.ShaderCompiler;
import com.javelin.tool.shaderc.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * .shader 着色器导入器（RHI 21 节 v0.13 语法 → Library 二进制资产）。
 *
 * <p>导入流程（Engine_Material_Design §3）：
 * 读源 → {@link ShaderParser} 解析（Properties/Pass/状态命令/vs/fs）→
 * {@link ShaderInjector} 在 #version 后注入声明（cam/pc/MaterialBlock/sampler）→
 * shaderc 编译 SPIR-V → 装配 {@link Shader} →
 * {@link BinaryExporter} 写 {@code Library/{guid}.asset}（Capsule 二进制协议）。</p>
 *
 * <p>本导入器是管线中首个真正写出二进制产物的实现；加载侧经
 * {@code AssetLibManager.loadGuid(guid, Shader.class)} 取回完整 Shader。
 * 解析/编译失败 → 落 stub 产物（空字段 Shader + guid，拍板 2026-10-27：原「导入失败
 * 不覆盖产物保持旧版」口径作废）→ 渲染侧按 Shader.isValid() 判坏回退默认 shader。</p>
 *
 * <p>guid 真值=伴生 {@code .meta}（2026-10-29 拍板——guid 头只剩 src 下 .java
 * 专属，内置资源 .meta 随包一起释放）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ShaderImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ShaderImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".shader");

    @Override
    public String getName() {
        return "ShaderImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new ShaderImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        try {
            String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);

            // ①~④ 解析 → 注入 → 编译 → 装配
            Shader shader = buildShader(source, sourceName);

            // ⑤ guid 回写 + 二进制产物：guid 真值=伴生 .meta（2026-10-29 拍板）。
            // 产物路径用 meta guid 拼装——保证产物文件名、shader 内 guid、index
            // 三者同源（不用 getOutputFile：那边固定取 meta.guid）
            String guid = task.getMeta().getGuid();
            shader.setGuid(guid);
            shader.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(shader);

            LOGGER.info("着色器导入成功: {} [{}] -> {}（{} 个 Pass，{} 个属性）",
                    sourceName, shader.getShaderName(), outputFile.getName(),
                    shader.getPasses().size(), shader.getProperties().size());
            return true;

        } catch (ShaderParseException e) {
            LOGGER.error("着色器解析失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.shader.parseFailed", e.getMessage()));
            return exportStub(sourceFile, task, project, e.getMessage());
        } catch (Exception e) {
            LOGGER.error("着色器导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.shader.importFailed", e.getMessage()));
            return exportStub(sourceFile, task, project, e.getMessage());
        }
    }

    /**
     * 编译/解析失败 stub 产物（拍板 2026-10-27）：落空字段 Shader（字段全空、仅 guid，
     * 替换旧产物）——loadGuid 有实例返回，渲染侧按 {@link Shader#isValid()} 判坏回退
     * 默认 shader（紫色）；修复后 reimport 真产物经热重载换代（stub 带 guid 可匹配）。
     * 返回 true = 产物已落（走导入完成链：meta/index/onImportComplete），
     * false = meta 缺 guid 无处落 stub（维持导入失败口径）。
     */
    private boolean exportStub(File sourceFile, ImportTask task, Project project, String reason) {
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isEmpty()) {
            return false;
        }
        try {
            Shader stub = new Shader();
            stub.setGuid(guid);
            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(stub);
            LOGGER.error("着色器编译失败，落 stub 产物（渲染回退默认 shader）: {} [{}] -> {}",
                    sourceFile.getName(), reason, outputFile.getName());
            return true;
        } catch (Exception exportError) {
            LOGGER.error("stub 产物落盘失败: {}", sourceFile.getName(), exportError);
            return false;
        }
    }

    /**
     * .shader 源码 → 完整 Shader 实例（解析 → #version 后注入 → shaderc 编译 → 装配）。
     * 不含 guid 回写与产物导出——管线与单测共用。
     *
     * @throws ShaderParseException     语法/结构错误（带行号）
     * @throws com.javelin.tool.shaderc.ShaderCompileException GLSL 编译失败（注入后的完整源名随异常给出）
     */
    public static Shader buildShader(String source, String sourceName) {
        ShaderParser.ParsedShader parsed = ShaderParser.parse(source, sourceName);

        Shader.Builder builder = Shader.builder(parsed.shaderName());
        for (MaterialProperty property : parsed.properties()) {
            builder.property(property);
        }
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            for (ShaderParser.ParsedPass parsedPass : parsed.passes()) {
                String vsName = sourceName + "/" + parsedPass.name() + ".vert";
                String fsName = sourceName + "/" + parsedPass.name() + ".frag";
                String vsSource = ShaderInjector.inject(
                        parsedPass.vsBody(), parsed.properties(), vsName, "vs");
                String fsSource = ShaderInjector.inject(
                        parsedPass.fsBody(), parsed.properties(), fsName, "fs");
                builder.pass(new ShaderPass(parsedPass.name(),
                        compiler.compile(vsSource, ShaderStage.VERTEX, vsName),
                        compiler.compile(fsSource, ShaderStage.FRAGMENT, fsName),
                        parsedPass.state()));
            }
        }
        return builder.build();
    }
}
