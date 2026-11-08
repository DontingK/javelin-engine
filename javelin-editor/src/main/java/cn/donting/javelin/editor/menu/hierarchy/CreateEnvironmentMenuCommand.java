package cn.donting.javelin.editor.menu.hierarchy;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import cn.donting.javelin.editor.window.MainFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.util.HashSet;
import java.util.Set;

/**
 * 创建环境设置（hierarchy.create.environment，2026-11-08）。
 *
 * <p>建一个名为 Environment 的节点并挂 {@link Environment} 组件——就是普通节点组件
 * （层级树可见可选中可删除，节点检查器直接出组件节，与相机/灯完全同构）；组件带
 * {@code @UniqueComponent(scene = true)}，全场景至多一个。</p>
 *
 * <p><b>菜单项恒可用</b>（2026-11-08 用户拍板）：环境已存在/场景未就绪时不置灰，而是
 * 点击后弹警告说明原因——置灰会让用户不知道「为什么不让创建」。判定在引擎线程做
 * （避免与场景编辑竞争），弹框 hop 回 EDT。</p>
 *
 * <p>挂到<b>场景根级</b>（不随选中节点下走，区别于相机/灯）：环境是场景级设置，
 * 挂在任意物体下既无意义，父节点被删还会连带消失。创建后自动选中新节点
 * （Unity 语义），检查器随即出环境节。</p>
 *
 * <p>只写不发通知——变更由 SceneWatchAppState 快照 diff 自动发现并推送
 * （CreateCameraMenuCommand 同口径）；动场景结构经
 * {@link JavelinEditor#submitTask} 提交引擎主线程帧边界执行。</p>
 *
 * @author javelin
 * @create 2026/11/08
 */
public class CreateEnvironmentMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateEnvironmentMenuCommand.class);

    /** 节点名（对齐相机/灯族的英文默认名口径） */
    private static final String BASE_NAME = "Environment";

    @Override
    public String id() {
        return "hierarchy.create.environment";
    }

    @Override
    public String icon() {
        return "environment";
    }

    @Override
    public void execute() {
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建环境设置（hierarchy.create.environment）");
            return;
        }
        editor.submitTask(() -> create(editor));
    }

    /** 创建节点（引擎主线程帧边界执行）；不满足条件即弹警告，不静默返回 */
    private static void create(JavelinEditor editor) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene != null ? editorScene.getGameScene() : null;
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建环境设置（hierarchy.create.environment）");
            warn("hierarchy.create.environment.noScene");
            return;
        }
        if (gameScene.getEnvironment() != null) {
            // 场景唯一组件已存在——显性告知原因（不静默、不靠置灰）
            LOGGER.warn("环境设置已存在（全场景唯一组件），跳过创建");
            warn("hierarchy.create.environment.exists");
            return;
        }
        Node node = new Node();
        node.setName(uniqueName(gameScene));
        node.addComponent(Environment.class);
        gameScene.add(node);
        EditorSelection.setSelectedNode(node);
        LOGGER.info("创建环境设置节点: {} (id={})", node.getName(), node.getId());
    }

    /** 弹警告框（引擎线程 → EDT；标题复用命令显示名） */
    private static void warn(String messageKey) {
        SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(
                MainFrame.getInstance(),
                EditorLang.text(messageKey),
                EditorLang.text("hierarchy.create.environment"),
                JOptionPane.WARNING_MESSAGE));
    }

    /** 全树重名去重：Environment / Environment (1) / ...（与 CreateCameraMenuCommand 同式各持一份） */
    private static String uniqueName(GameScene gameScene) {
        Set<String> names = new HashSet<>();
        for (Node root : gameScene.getRootNodes()) {
            collectNames(root, names);
        }
        if (!names.contains(BASE_NAME)) {
            return BASE_NAME;
        }
        int n = 1;
        while (names.contains(BASE_NAME + " (" + n + ")")) {
            n++;
        }
        return BASE_NAME + " (" + n + ")";
    }

    private static void collectNames(Node node, Set<String> out) {
        if (node.getName() != null) {
            out.add(node.getName());
        }
        for (Transform child : node.getTransform()) {
            collectNames(child.getNode(), out);
        }
    }
}
