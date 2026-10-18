package com.javelin.editor.menu.top;

import com.javelin.core.scene.GameScene;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.SceneChangedEvent;
import com.javelin.editor.meta.AssetMeta;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.serializable.TextExporter;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 保存场景（file.save，Unity Save Scene 语义）。
 *
 * <p>把 {@link EditorScene} 的用户场景（gameScene，编辑器辅助对象不外溢）经
 * {@link TextExporter} 文本协议写回 {@link EditorScene#getGameSceneFile()}。
 * 保存走 {@link JavelinEditor#submitTask} 引擎主线程帧边界——与 SceneWatch /
 * 渲染 pull 同帧序，读树无撕裂。成功后 clearDirty 并发布 scene.changed
 *（dirty=false，前端重拉 /scene/tree 树根星号消失）；失败记 error 并 tinyfd
 * 弹窗（帧内异常不经 CommandService.execute 的 catch，自带隔离）。</p>
 *
 * <p>guid 口径（2026-10-01 拍板）：GameScene.write 内嵌 guid/lastModified 自述
 * （super.write）；真值源为伴生 .meta——加载侧（JavelinEditor.loadScene）同步
 * meta.guid 进 gameScene，此处防御性补读一次兜底旧文件（无内嵌 guid 字段）。</p>
 *
 * @author javelin
 * @create 2026/10/1
 */
public class SaveSceneMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(SaveSceneMenuCommand.class);

    @Override
    public String id() {
        return "file.save";
    }

    @Override
    public String label() {
        return "保存";
    }

    @Override
    public String shortcut() {
        return "Ctrl+S";
    }

    @Override
    public void execute() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过保存场景（file.save）");
            return;
        }
        editor.submitTask(() -> save(editor));
    }

    /**
     * 保存（引擎主线程帧边界执行）。
     *
     * @return 是否保存成功（场景未就绪/导出失败=false。双调用方：file.save 命令、
     *         场景打开的「保存并打开」分支——失败即中止打开）
     */
    public static boolean save(JavelinEditor editor) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        File file = editorScene.getGameSceneFile();
        if (gameScene == null || file == null) {
            LOGGER.warn("场景未就绪（gameScene/gameSceneFile 为空），跳过保存场景（file.save）");
            return false;
        }
        try {
            syncGuid(editor, gameScene, file);
            // lastModified 记保存时刻（write 内嵌落盘；下次导入按此判产物新鲜度）
            gameScene.setLastModified(System.currentTimeMillis());
            new TextExporter(file).export(gameScene);
            // 产物联动（2026-10-11 拍板）：保存成功即提交导入任务——管线线程池异步
            // 重导 Library 二进制产物，编辑器零卡顿；产物新鲜度供运行时/打包消费
            editor.getAssetImportPipeline().submitImport(file);
            editorScene.clearDirty();
            // 前端树根星号随重拉消失（边沿通知，状态以拉取为准）
            EditorEventBus.publish(new SceneChangedEvent(false));
            LOGGER.info("保存场景: {}（{} 个根节点）", file.getName(), gameScene.getRootNodes().size());
            return true;
        } catch (Exception ex) {
            // 帧内异常不走 CommandService.execute 的 catch——自带日志+弹窗隔离
            LOGGER.error("保存场景失败（file.save）: {}", file, ex);
            String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            TinyFileDialogs.tinyfd_messageBox("错误", "保存场景失败: " + message, "ok", "error", 1);
            return false;
        }
    }

    /** 防御性 guid 补读（正路在加载侧；兜底无内嵌字段的旧文件） */
    private static void syncGuid(JavelinEditor editor, GameScene gameScene, File file) {
        if (gameScene.getGuid() != null) {
            return;
        }
        AssetMeta meta = editor.getMetaManager().getMeta(file);
        if (meta != null && meta.getGuid() != null) {
            gameScene.setGuid(meta.getGuid());
        }
    }
}
