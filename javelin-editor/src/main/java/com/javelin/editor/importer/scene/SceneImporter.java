package com.javelin.editor.importer.scene;

import com.javelin.core.scene.GameScene;
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
 * 场景导入器：.scene 文本协议 → Library 二进制 GameScene 产物。
 *
 * <p>与 shader/smesh 同构（文本源 → 二进制产物）：TextImporter 解析即验证——
 * 坏档导入失败不落残产物；产物供运行时/打包管线按 guid 消费（编辑器加载侧
 * loadScene 仍读 Assets/ 文本源，2026-10-11 拍板最小切片不动）。保存联动：
 * SaveSceneMenuCommand 保存成功后提交导入任务，产物即时新鲜。</p>
 *
 * <p>guid 口径（与 JavelinEditor.loadScene 同源）：伴生 .meta 为真值源、非空才
 * 定产物路径。.scene 不走文件头自包含——首行是文本协议头
 * （javelin-text-serialization），GuidHeaderManager 注入会破坏协议。</p>
 *
 * <p>已知边界：连续快速保存提交的同文件导入任务可能在多 worker 上交叠写同一
 * 产物（概率极低、下次保存自愈）；同文件任务去重归管线后续。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 */
public class SceneImporter implements AssetImporter {

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneImporter.class);

    private static final List<String> SUPPORTED_EXTENSIONS = List.of(".scene");

    @Override
    public String getName() {
        return "SceneImporter";
    }

    @Override
    public List<String> getSupportedExtensions() {
        return SUPPORTED_EXTENSIONS;
    }

    @Override
    public ImportSettings createDefaultSettings(File file) {
        return new SceneImportSettings();
    }

    @Override
    public boolean importAsset(ImportTask task, Project project) throws Exception {
        File sourceFile = task.getSourceFile();

        // guid 真值源 = 伴生 .meta（loadScene 同口径）；缺失视为 meta 失常，拒绝导入
        String guid = task.getMeta().getGuid();
        if (guid == null || guid.isEmpty()) {
            task.setErrorMessage(EditorLang.text("import.metaMissingGuid"));
            return false;
        }

        GameScene gameScene;
        try {
            // 解析即验证（文本协议，@guid 资产引用解靠 task 携带的 assetManager）
            gameScene = new TextImporter(sourceFile, task.getAssetManager()).importData(GameScene.class);
        } catch (Exception e) {
            LOGGER.error("解析场景文件失败: {}", sourceFile.getName(), e);
            task.setErrorMessage(EditorLang.text("import.scene.parseFailed", e.getMessage()));
            return false;
        }

        // 产物自述 guid（meta 真值源覆盖内嵌自述，SimpleMeshImporter 同款）
        gameScene.setGuid(guid);
        File libraryDir = project.getLibrary();
        if (!libraryDir.exists()) {
            libraryDir.mkdirs();
        }
        File outputFile = new File(libraryDir, guid + EXTENSION);
        new BinaryExporter(outputFile).export(gameScene);

        LOGGER.info("场景导入成功: {} -> {} (根节点数={})",
                sourceFile.getName(), outputFile.getName(), gameScene.getRootNodes().size());
        return true;
    }
}
