package cn.donting.javelin.editor.menu.hierarchy;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 创建相机（hierarchy.create.camera，Unity Camera 对齐）。
 *
 * <p>挂入位置与选中语义同 {@link CreateEmptyMenuCommand}：有选中 → 选中节点下
 * （Transform.setParent 自动入场景）；无选中（含右键场景根）→ 场景根级
 * （GameScene.add）；创建后自动选中新节点（Unity 语义）；重名去重
 * Main Camera / Main Camera (n)（Unity 菜单创建的命名口径）。节点位姿取 Unity
 * 新建默认（原点、无旋转——相机朝局部 +Z），相机参数全取组件默认（fov 60° /
 * near 0.1 / far 1000 / depth 0 / SOLID_COLOR / viewportRect null=全屏），
 * 编辑通道留 Inspector 组件面板后续接入。main 标志不预设——引擎侧 main 消费
 * 未落地（多相机取舍语义未定），不写入半成品语义。</p>
 *
 * <p>渲染与序列化语义：新相机入用户 game scene（GameScene.register 自动登记
 * cameras 注册表，SceneRenderBridge pull 每帧发现）；编辑器视口不受影响——
 * EditorScene SCENE 模式只渲染编辑子场景相机（编辑器相机），用户相机仅在
 * GAME 模式渲染（Unity Scene/Game 视图分离的现有实现）。Camera write/read
 * 已落地，保存重载完整恢复。动场景结构经 {@link JavelinEditor#submitTask}
 * 提交引擎主线程帧边界执行；只写不发通知——变更由 SceneWatchAppState
 * 快照 diff 自动发现并推送前端。</p>
 *
 * @author javelin
 * @create 2026/10/11
 */
public class CreateCameraMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateCameraMenuCommand.class);

    /** Unity 菜单新建相机的节点名口径（GameObject → Camera） */
    private static final String BASE_NAME = "Main Camera";

    @Override
    public String id() {
        return "hierarchy.create.camera";
    }

    @Override
    public String icon() {
        return "camera";
    }

    @Override
    public void execute() {
        // 提交时快照父节点引用（Unity 语义：右键对象下创建；快照防执行帧选中漂移）
        Node parent = getSelectedNode();
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建节点（hierarchy.create.camera）");
            return;
        }
        editor.submitTask(() -> create(editor, parent));
    }

    /** 创建节点（引擎主线程帧边界执行） */
    private static void create(JavelinEditor editor, Node parent) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建节点（hierarchy.create.camera）");
            return;
        }
        Node node = new Node();
        node.setName(uniqueName(gameScene));
        node.addComponent(Camera.class);
        if (parent != null && parent.getScene() == gameScene) {
            node.getTransform().setParent(parent.getTransform());
        } else {
            gameScene.add(node);
        }
        EditorSelection.setSelectedNode(node);
        LOGGER.info("创建节点: {} (id={})", node.getName(), node.getId());
    }

    /** 全树重名去重：Main Camera / Main Camera (1) / ...（Unity 编号口径；与
     *  CreateEmptyMenuCommand 同式各持一份——命令按一动作一类自包含，组件族扩容时再议抽取） */
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
