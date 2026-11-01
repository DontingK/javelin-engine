package cn.donting.javelin.editor.file;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.event.EditorEventBus;
import cn.donting.javelin.editor.event.SceneChangedEvent;
import cn.donting.javelin.editor.menu.top.SaveSceneMenuCommand;
import cn.donting.javelin.editor.meta.AssetMeta;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import cn.donting.javelin.editor.serializable.TextImporter;
import cn.donting.javelin.editor.window.MainFrame;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import java.io.File;
import java.util.List;

/**
 * .scene 场景文件类型：条目图标 scene.svg + 打开 = 运行时切换用户场景
 * （Unity Open Scene 口径）。
 *
 * <p>脏场景三键确认（用户拍板 2026-10-18，Unity 口径）：保存并打开 /
 * 不保存 / 取消——保存分支复用 {@link SaveSceneMenuCommand#save}。场景切换
 * 整体走 {@code JavelinEditor.submitTask} 帧边界单任务（save → load → swap →
 * 事件），与渲染 pull 同帧序无撕裂。</p>
 *
 * <p>切换收尾：clearDirty（新场景即净）+ {@code EditorSelection.setSelectedNode(null)}
 * 收口发布 SceneSelectEvent（Gizmo/TransformWatch/Inspector 经选中链自动复位）
 * + {@code SceneChangedEvent(false)} 驱动层级树/检查器重拉——收尾口径同
 * file.save。</p>
 *
 * @author javelin
 * @create 2026/10/18
 */
public class SceneFileType implements FileType {

    /** confirmDirty 返回值：取消（关闭弹窗视同取消） */
    private static final int CHOICE_CANCEL = 2;

    private static final Logger LOGGER = LoggerFactory.getLogger(SceneFileType.class);

    @Override
    public List<String> extensions() {
        return List.of(".scene");
    }

    @Override
    public String icon() {
        return "scene";
    }

    @Override
    public boolean canOpen() {
        return true;
    }

    @Override
    public void open(File file) {
        openScene(file);
    }

    /**
     * 打开场景整链入口（项目面板双击 / CreateSceneMenuCommand 创建即打开共用）：
     * 脏确认 → submitTask 帧边界切换（save → load → swap → 事件）。
     */
    public static void openScene(File file) {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过打开场景: {}", file);
            return;
        }
        EditorScene editorScene = editor.getEditorScene();
        if (editorScene.getGameScene() == null) {
            LOGGER.warn("用户场景未就绪，跳过打开场景: {}", file);
            return;
        }
        int choice = confirmDirty(editorScene);
        if (choice == CHOICE_CANCEL) {
            return;
        }
        boolean saveFirst = choice == 0;
        editor.submitTask(() -> swapScene(editor, file, saveFirst));
    }

    /**
     * 脏场景确认（净场景放行不弹）。创建场景等「必然离开当前场景」的命令用：
     * 返回 false = 用户取消，命令应中止。
     */
    public static boolean confirmDirtyOrCancel() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            return false;
        }
        EditorScene editorScene = editor.getEditorScene();
        if (editorScene == null || editorScene.getGameScene() == null) {
            return true;   // 无当前场景无从弄脏，直接放行
        }
        return confirmDirty(editorScene) != CHOICE_CANCEL;
    }

    /**
     * 脏场景确认（EDT 弹窗，父窗 MainFrame）。净场景直接放行不弹。
     *
     * @return 0 = 保存并打开；1 = 不保存；{@value #CHOICE_CANCEL} = 取消
     */
    private static int confirmDirty(EditorScene editorScene) {
        if (!editorScene.isDirty()) {
            return 1;
        }
        String name = editorScene.getGameScene().getName();
        int choice = JOptionPane.showOptionDialog(MainFrame.getInstance(),
                EditorLang.text("scene.open.dirtyConfirm", name),
                EditorLang.text("scene.open.title"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                new Object[]{EditorLang.text("scene.open.saveAndOpen"),
                        EditorLang.text("common.no"), EditorLang.text("common.cancel")},
                EditorLang.text("scene.open.saveAndOpen"));
        return choice < 0 ? CHOICE_CANCEL : choice;
    }

    /**
     * 记录最后打开场景到项目设置（project.settings 的 defaultOpenScene，Assets 相对
     * 路径）并落盘——下次打开项目自动恢复该场景（loadScene 消费）。双击打开与
     * 创建场景（CreateSceneMenuCommand 经 openScene）统一在本收尾回写。场景在
     * Assets 外时跳过（相对路径语义不成立，编辑器运行域文件不进启动恢复链）。
     */
    private static void rememberLastOpenScene(JavelinEditor editor, File file) {
        File assetsDir = editor.getProject().getAssetsDir();
        if (!file.toPath().normalize().startsWith(assetsDir.toPath().normalize())) {
            LOGGER.warn("场景不在 Assets 目录内，跳过启动恢复记录: {}", file);
            return;
        }
        String relative = cn.donting.javelin.editor.util.FileUtil.relativePath(assetsDir, file);
        editor.getSettingsManager().getProjectSettings().setDefaultOpenScene(relative);
        editor.getSettingsManager().saveProjectSettings();
    }

    /**
     * 场景切换（引擎主线程帧边界执行）：save（可选）→ 文本导入新场景 → 换引用 →
     * 清选中/清脏/发事件。失败记 error 并 tinyfd 弹窗（同 file.save 隔离口径）。
     */
    private static void swapScene(JavelinEditor editor, File file, boolean saveFirst) {
        EditorScene editorScene = editor.getEditorScene();
        if (saveFirst && !SaveSceneMenuCommand.save(editor)) {
            return; // 保存失败中止打开（错误信息已弹）
        }
        try {
            GameScene gameScene = new TextImporter(file, editor.getAssetManager()).importData(GameScene.class);
            // guid 同步（口径同 JavelinEditor.loadScene）：.meta 为管线真值源，
            // 同步进 gameScene 供保存时内嵌自述；旧文件无内嵌字段 read 恢复 null
            AssetMeta meta = editor.getMetaManager().getMeta(file);
            if (meta != null && meta.getGuid() != null) {
                gameScene.setGuid(meta.getGuid());
            }
            editorScene.setGameScene(gameScene, file);
            // 选中收口清空：Gizmo/TransformWatch/Inspector 旧场景引用经选中链复位
            EditorSelection.setSelectedNode(null);
            // 新场景即净 + 树/检查器重拉（树根星标随重拉消失）
            editorScene.clearDirty();
            EditorEventBus.publish(new SceneChangedEvent(false));
            rememberLastOpenScene(editor, file);
            LOGGER.info("打开场景: {}（{} 个根节点）", file.getName(), gameScene.getRootNodes().size());
        } catch (Exception ex) {
            LOGGER.error("打开场景失败: {}", file, ex);
            String message = ex.getMessage() != null ? ex.getMessage() : ex.toString();
            TinyFileDialogs.tinyfd_messageBox(EditorLang.text("common.error"),
                    EditorLang.text("scene.open.failed", message), "ok", "error", 1);
        }
    }
}
