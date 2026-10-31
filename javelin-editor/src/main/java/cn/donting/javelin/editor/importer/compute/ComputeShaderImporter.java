package cn.donting.javelin.editor.importer.compute;

import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.editor.importer.AssetImporter;
import cn.donting.javelin.editor.importer.ImportSettings;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.tool.shaderc.ShaderCompileException;
import cn.donting.javelin.tool.shaderc.ShaderCompiler;
import cn.donting.javelin.tool.shaderc.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * .compute 计算着色器导入器（.compute 源码 → Library 二进制资产）。
 *
 * <p>对齐 ShaderImporter 全套口径：读源 → shaderc 编译 SPIR-V（COMPUTE stage，
 * 入口恒 main）→ 装配 {@link ComputeShader} → BinaryExporter 写
 * {@code Library/{guid}.asset}。compute 不注入任何引擎声明（无 cam/pc/MaterialBlock
 * 通道）——源码须手写全部 layout 声明，构组侧描述符布局由消费方另行组织
 * （灯光档 §3.4 口径）。编译失败 → 落 stub 产物（空 ComputeShader + guid，
 * IsValid=false）→ 消费侧显性报错。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ComputeShaderImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(ComputeShaderImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".compute");

    @Override
    public String getName() {
        return "ComputeShaderImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new ComputeShaderImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();
        String sourceName = sourceFile.getName();

        try {
            String source = Files.readString(sourceFile.toPath(), StandardCharsets.UTF_8);
            ComputeShader computeShader = buildComputeShader(source, sourceName);

            String guid = task.getMeta().getGuid();
            computeShader.setGuid(guid);
            computeShader.setLastModified(sourceFile.lastModified());

            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(computeShader);

            LOGGER.info("计算着色器导入成功: {} -> {}",
                    sourceName, outputFile.getName());
            return true;

        } catch (Exception e) {
            LOGGER.error("计算着色器导入失败: {}", sourceName, e);
            task.setErrorMessage(EditorLang.text("import.compute.importFailed", e.getMessage()));
            return exportStub(sourceFile, task, project, e.getMessage());
        }
    }

    /**
     * 编译失败 stub 产物（对齐 ShaderImporter 口径，拍板 2026-10-27）：
     * 空字段 ComputeShader + guid 替换旧产物；消费侧按 isValid() 判坏显性报错。
     * 返回 true = 产物已落，false = meta 缺 guid 无处落 stub。
     */
    private boolean exportStub(File sourceFile, ImportTask task, Project project, String reason) {
        AssetMeta meta = task.getMeta();
        String guid = meta != null ? meta.getGuid() : null;
        if (guid == null || guid.isEmpty()) {
            return false;
        }
        try {
            ComputeShader stub = new ComputeShader();
            stub.setGuid(guid);
            File outputFile = new File(project.getLibrary(), guid + EXTENSION);
            new BinaryExporter(outputFile).export(stub);
            LOGGER.error("计算着色器编译失败，落 stub 产物: {} [{}] -> {}",
                    sourceFile.getName(), reason, outputFile.getName());
            return true;
        } catch (Exception exportError) {
            LOGGER.error("stub 产物落盘失败: {}", sourceFile.getName(), exportError);
            return false;
        }
    }

    /**
     * .compute 源码 → ComputeShader 实例（编译 → 装配，无注入步骤）。
     * 资产名 = 文件名去扩展名。不含 guid 回写与产物导出——管线与单测共用。
     *
     * @throws ShaderCompileException GLSL 编译失败
     */
    public static ComputeShader buildComputeShader(String source, String sourceName) {
        String assetName = sourceName.contains(".")
                ? sourceName.substring(0, sourceName.lastIndexOf('.'))
                : sourceName;
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            byte[] spirv = compiler.compile(source, ShaderStage.COMPUTE,
                    sourceName + "/main.comp");
            return new ComputeShader(assetName, spirv);
        }
    }
}
