package com.javelin.editor.importer.texture;

import com.javelin.core.render.texture.Texture2D;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.editor.importer.AssetImporter;
import com.javelin.editor.importer.ImportSettings;
import com.javelin.editor.importer.ImportTask;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.project.Project;
import com.javelin.editor.serializable.TextImporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

/**
 * 纹理导入器：.texture 文本协议 → Library 二进制 Texture2D 产物。
 *
 * <p>.texture 源即 {@link Texture2D} 的文本序列化（javelin-text-serialization，
 * 与 .mat 源同款——「.texture 就是 Texture2D 的序列化」）：image 字段 Asset 引用
 * 走 guid 分流（{@code image=@guid:{guid}} → 导入的 Image 像素资产；@null=未选图
 * 纹理壳合法——渲染侧按未赋槽占位解析）。消费侧
 * {@code loadGuid(guid, Texture2D.class)}、.mat 贴图槽 {@code texture=@guid:}
 * 引用经 TextInputCapsule 声明类型回解。</p>
 *
 * <p>guid 口径（与 MaterialImporter 同源）：伴生 .meta 为真值源；image 引用未
 * 导入（首启多 worker 竞态）时解析失败，下次启动经 !isImportedInLibrary 重试
 * （Scene/Material 同语义）。</p>
 *
 * @author javelin
 * @create 2026/10/25
 */
public class TextureImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(TextureImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".texture");

    @Override
    public String getName() {
        return "TextureImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new TextureImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();

        // guid 真值源 = 伴生 .meta（MaterialImporter 同口径）；缺失视为 meta 失常
        String guid = task.getMeta().getGuid();
        if (guid == null || guid.isEmpty()) {
            task.setErrorMessage(EditorLang.text("import.metaMissingGuid"));
            return false;
        }

        Texture2D texture;
        try {
            // 解析即验证（文本协议，image=@guid 引用解靠 task 携带的 assetManager；
            // image=@null 合法=未选图纹理壳）
            texture = new TextImporter(sourceFile, task.getAssetManager()).importData(Texture2D.class);
        } catch (Exception e) {
            LOGGER.error("解析纹理文件失败: {}", sourceFile.getName(), e);
            task.setErrorMessage(EditorLang.text("import.texture.parseFailed", e.getMessage()));
            return false;
        }

        // 产物自述 guid（meta 真值源覆盖内嵌自述，SimpleMeshImporter 同款）
        texture.setGuid(guid);
        File libraryDir = project.getLibrary();
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }
        File outputFile = new File(libraryDir, guid + EXTENSION);
        new BinaryExporter(outputFile).export(texture);

        LOGGER.info("纹理导入成功: {} -> {} (image={})",
                sourceFile.getName(), outputFile.getName(),
                texture.getImage() != null ? texture.getImage().getGuid()
                        : EditorLang.text("import.texture.noImage"));
        return true;
    }
}
