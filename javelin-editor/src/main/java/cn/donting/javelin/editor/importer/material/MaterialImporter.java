package cn.donting.javelin.editor.importer.material;

import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.editor.importer.AssetImporter;
import cn.donting.javelin.editor.importer.ImportSettings;
import cn.donting.javelin.editor.importer.ImportTask;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.project.Project;
import cn.donting.javelin.editor.serializable.TextImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * 材质导入器：.mat 文本协议 → Library 二进制 Material 产物。
 *
 * <p>.mat 源即 {@link Material} 的文本序列化（javelin-text-serialization，与 .scene
 * 源同款——「.mat 就是 Material 的序列化」2026-10-19 拍板）：Material write/read
 * 已随 .scene 内嵌闭环（2026-10-04），本导入器零改 core。消费侧
 * {@code loadGuid(guid, Material.class)} 直接可用；.scene 里
 * {@code material=@guid:{guid}} 引用经 TextInputCapsule 声明类型回解。</p>
 *
 * <p>guid 口径（与 SceneImporter 同源）：伴生 .meta 为真值源、非空才定产物路径
 * ——.mat 首行是文本协议头，不能走文件头自包含。产物自描述 guid（meta 真值源
 * 覆盖内嵌自述，SimpleMeshImporter 同款）。</p>
 *
 * <p>残档拒绝：shader 引用缺失（文本里 shader=@null 或 guid 解不回）视为文件损坏
 * 口径，导入失败不落残产物——与 RenderObject 对 null shader 显式报错同语义，
 * 不静默降级。</p>
 *
 * <p>已知边界：texture 条目引用 Texture2D 资产，槽可空（null = 占位贴图）；shader
 * 引用解经 task 携带的壳管理器（导入期不加载 Shader 产物，见
 * {@link cn.donting.javelin.editor.importer.ShellAssetManager}），guid 未登记时
 * 解为 null（残档拒绝口径不变）。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/19
 */
public class MaterialImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(MaterialImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".mat");

    @Override
    public String getName() {
        return "MaterialImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new MaterialImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();

        // guid 真值源 = 伴生 .meta（SceneImporter 同口径）；缺失视为 meta 失常，拒绝导入
        String guid = task.getMeta().getGuid();
        if (guid == null || guid.isEmpty()) {
            task.setErrorMessage(EditorLang.text("import.metaMissingGuid"));
            return false;
        }

        Material material;
        try {
            // 解析即验证（文本协议，shader=@guid 引用解靠 task 携带的 assetManager）
            material = new TextImporter(sourceFile, task.getAssetManager()).importData(Material.class);
        } catch (Exception e) {
            LOGGER.error("解析材质文件失败: {}", sourceFile.getName(), e);
            task.setErrorMessage(EditorLang.text("import.material.parseFailed", e.getMessage()));
            return false;
        }

        // 残档拒绝：shader 引用缺失不落残产物（文件损坏口径）
        if (material.getShader() == null) {
            task.setErrorMessage(EditorLang.text("import.material.shaderMissing"));
            return false;
        }

        // 产物自述 guid（meta 真值源覆盖内嵌自述，SimpleMeshImporter 同款）
        material.setGuid(guid);
        File libraryDir = project.getLibrary();
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }
        File outputFile = new File(libraryDir, guid + EXTENSION);
        new BinaryExporter(outputFile).export(material);

        LOGGER.info("材质导入成功: {} -> {} (shader={})",
                sourceFile.getName(), outputFile.getName(),
                material.getShader().getGuid());
        return true;
    }
}
