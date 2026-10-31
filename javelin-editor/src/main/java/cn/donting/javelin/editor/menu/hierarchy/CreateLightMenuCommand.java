package cn.donting.javelin.editor.menu.hierarchy;

import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Light;
import cn.donting.javelin.core.scene.component.Transform;
import cn.donting.javelin.editor.JavelinEditor;
import cn.donting.javelin.editor.scene.EditorScene;
import cn.donting.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 创建灯（hierarchy.create.light.directional / point / spot，Unity Light 对齐）。
 *
 * <p>挂入位置与选中语义同 {@link CreateCameraMenuCommand}：有选中 → 选中节点下，
 * 无选中 → 场景根级；创建后自动选中新节点；重名去重 BaseName (n)。节点位姿
 * Unity 新建默认（原点、无旋转——方向光朝局部 +Z）；灯参数取组件默认
 * （intensity 1 / range 10 / 内 30° 外 45°）。主光无标志——引擎自动裁决最强
 * 激活方向光（对齐 Unity，2026-10-31 用户拍板，Scene.getSun 单点）。</p>
 *
 * <p>灯组件为子类族（同日拍板，Collider 族同构——每型参数集不相交不摆死字段、
 * **不支持切换类型**）：一类构造注入组件类（id/baseName/创建按型分流）。
 * Spot 收集侧已支持（进 lightList），着色分支归批次③——菜单项先行解锁，
 * 画面验收随批次③。</p>
 *
 * <p>动场景结构经 {@link JavelinEditor#submitTask} 提交引擎主线程帧边界执行；
 * 只写不发通知——变更由 SceneWatchAppState 快照 diff 自动发现。</p>
 *
 * @author javelin
 * @create 2026/10/31
 */
public class CreateLightMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateLightMenuCommand.class);

    /** Unity 菜单新建灯的节点名口径（GameObject → Light） */
    public static final String DIRECTIONAL_NAME = "Directional Light";
    public static final String POINT_NAME = "Point Light";
    public static final String SPOT_NAME = "Spot Light";

    private final Class<? extends Light> lightClass;
    private final String id;
    private final String baseName;
    private final String icon;

    /** @param lightClass 灯子类（DirectionalLight / PointLight / SpotLight） */
    public CreateLightMenuCommand(String id, String baseName, String icon, Class<? extends Light> lightClass) {
        this.id = id;
        this.baseName = baseName;
        this.icon = icon;
        this.lightClass = lightClass;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String icon() {
        return icon;
    }

    @Override
    public void execute() {
        Node parent = getSelectedNode();
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建节点（{}）", id);
            return;
        }
        editor.submitTask(() -> create(editor, parent));
    }

    /** 创建节点（引擎主线程帧边界执行） */
    private void create(JavelinEditor editor, Node parent) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建节点（{}）", id);
            return;
        }
        Node node = new Node();
        node.setName(uniqueName(gameScene));
        node.addComponent(lightClass);
        if (parent != null && parent.getScene() == gameScene) {
            node.getTransform().setParent(parent.getTransform());
        } else {
            gameScene.add(node);
        }
        EditorSelection.setSelectedNode(node);
        LOGGER.info("创建节点: {} (id={})", node.getName(), node.getId());
    }

    /** 全树重名去重：BaseName / BaseName (1) / ...（与 CreateCameraMenuCommand 同式） */
    private String uniqueName(GameScene gameScene) {
        Set<String> names = new HashSet<>();
        for (Node root : gameScene.getRootNodes()) {
            collectNames(root, names);
        }
        if (!names.contains(baseName)) {
            return baseName;
        }
        int n = 1;
        while (names.contains(baseName + " (" + n + ")")) {
            n++;
        }
        return baseName + " (" + n + ")";
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
