package cn.donting.javelin.editor.importer.spv;

import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.editor.importer.AssetImporter;
import cn.donting.javelin.editor.importer.ImportSettings;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.importer.shader.ShaderInjector;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.tool.shaderc.ShaderCompiler;
import cn.donting.javelin.tool.shaderc.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

/**
 * 原生 GLSL 着色器导入器（.vert/.frag 源码 → Library 二进制 SpvShader）。
 *
 * <p>引擎覆盖制 Pass 的着色器装载链（2026-11-01 拍板：这类资源走导入不走
 * tool 内嵌 spv——源码进 builtin-asset-core，产物经 AssetManager 加载）。
 * 扩展名即 stage（.vert → VERTEX / .frag → FRAGMENT），单文件单 stage；
 * 不注入任何引擎声明（无 cam/pc/材质通道——引擎 Pass 手写全声明，
 * pick.spv 同族口径）。编译失败 → 落 stub 产物（空 SpvShader + guid，
 * IsValid=false）→ 消费侧显性报错。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class SpvShaderImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SpvShaderImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".vert", ".frag");

    @Override
    public String getName() {
        return "SpvShaderImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new SpvShaderImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        try {
            String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
            SpvShader spvShader = buildSpvShader(source, sourceName);

            String guid = task.getMeta().getGuid();
            spvShader.setGuid(guid);
            spvShader.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(spvShader);

            LOGGER.info("SPIR-V 着色器导入成功: {} -> {}",
                    sourceName, outputFile.getName());
            return true;

        } catch (Exception e) {
            LOGGER.error("SPIR-V 着色器导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.spv.importFailed", e.getMessage()));
            return exportStub(sourceFile, task, project, e.getMessage());
        }
    }

    /**
     * 编译失败 stub 产物（对齐 ShaderImporter/ComputeShaderImporter 口径）：
     * 空字段 SpvShader + guid 替换旧产物；消费侧按 isValid() 判坏显性报错。
     * 返回 true = 产物已落，false = meta 缺 guid 无处落 stub。
     */
    private boolean exportStub(File sourceFile, ImportTask task, Project project, String reason) {
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isEmpty()) {
            return false;
        }
        try {
            SpvShader stub = new SpvShader();
            stub.setGuid(guid);
            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(stub);
            LOGGER.error("SPIR-V 着色器编译失败，落 stub 产物: {} [{}] -> {}",
                    sourceFile.getName(), reason, outputFile.getName());
            return true;
        } catch (Exception exportError) {
            LOGGER.error("stub 产物落盘失败: {}", sourceFile.getName(), exportError);
            return false;
        }
    }

    /**
     * .vert/.frag 源 → SpvShader 实例（扩展名定 stage → 编译 → 装配，无注入）。
     * 资产名 = 文件名去扩展名。不含 guid 回写与产物导出——管线与单测共用。
     *
     * @throws cn.donting.javelin.tool.shaderc.ShaderCompileException GLSL 编译失败
     * @throws IllegalArgumentException 扩展名不识别
     */
    public static SpvShader buildSpvShader(String source, String sourceName) {
        String lower = sourceName.toLowerCase(Locale.ROOT);
        ShaderStage stage;
        if (lower.endsWith(".vert")) {
            stage = ShaderStage.VERTEX;
        } else if (lower.endsWith(".frag")) {
            stage = ShaderStage.FRAGMENT;
        } else {
            throw new IllegalArgumentException("不支持的着色器扩展名: " + sourceName);
        }
        // 全屏光照 Pass 可选注入（批次④-B）：带标记的文件注入相机块 + 光照段函数 +
        // G-Buffer 三采样（光照公式单源，Engine_RenderPipeline_Design §4.3 实装）
        if (source.contains(ShaderInjector.LIGHTING_STAGE_MARKER)) {
            source = ShaderInjector.injectLightingStage(
                    source.replace(ShaderInjector.LIGHTING_STAGE_MARKER, ""), sourceName);
        }
        String assetName = sourceName.contains(".")
                ? sourceName.substring(0, sourceName.lastIndexOf('.'))
                : sourceName;
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            byte[] spirv = compiler.compile(source, stage, sourceName);
            return new SpvShader(assetName, spirv);
        }
    }
}
