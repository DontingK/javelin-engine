package com.javelin.editor.menu.hierarchy;

import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.scene.EditorScene;
import com.javelin.editor.selection.EditorSelection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * 创建 Cylinder 网格物体（hierarchy.create.cylinder，Unity 3D Object → Cylinder 对齐）。
 *
 * <p>网格取内置资产 cylinder.smesh（Unity 口径高 2 × 直径 1，含端盖；端盖与
 * 侧面硬边靠重复顶点分离法线），旋转体算法离线生成（绕序叉积自检，生成脚本
 * 一次性工具不入库）；材质为内置 default.shader 运行期实例。挂入/选中/重名去重/
 * 线程与序列化语义全同 {@link CreatePlaneMenuCommand}，设计论证见 Editor_Menu_Design。</p>
 *
 * @author javelin
 * @create 2026/10/25
 */
public class CreateCylinderMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreateCylinderMenuCommand.class);

    private static final String BASE_NAME = "Cylinder";

    /** 内置 cylinder 网格 loadAsset 路径（builtin-asset-core 释放到 .javelin/assets 后的相对路径） */
    private static final String CYLINDER_MESH_PATH = "core/mesh/cylinder.smesh";

    /** 内置默认着色器 loadAsset 路径（材质模板，运行期 new Material 实例） */
    private static final String DEFAULT_SHADER_PATH = "core/shader/default.shader";

    @Override
    public String id() {
        return "hierarchy.create.cylinder";
    }

    @Override
    public void execute() {
        // 提交时快照父节点引用（Unity 语义：右键对象下创建；快照防执行帧选中漂移）
        Node parent = getSelectedNode();
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建节点（hierarchy.create.cylinder）");
            return;
        }
        editor.submitTask(() -> create(editor, parent));
    }

    /** 创建节点（引擎主线程帧边界执行，资产加载同线程） */
    private static void create(JavelinEditor editor, Node parent) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建节点（hierarchy.create.cylinder）");
            return;
        }
        Mesh mesh = editor.getAssetManager().loadAsset(CYLINDER_MESH_PATH, Mesh.class);
        if (mesh == null) {
            LOGGER.error("cylinder 网格加载失败 path={}（Library 缺产物或索引未建），跳过创建", CYLINDER_MESH_PATH);
            return;
        }
        Shader shader = editor.getAssetManager().loadAsset(DEFAULT_SHADER_PATH, Shader.class);
        if (shader == null) {
            LOGGER.error("默认着色器加载失败 path={}（Library 缺产物或索引未建），跳过创建", DEFAULT_SHADER_PATH);
            return;
        }
        Node node = new Node();
        node.setName(uniqueName(gameScene));
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        renderer.setMesh(mesh);
        renderer.setMaterial(new Material(shader));
        if (parent != null && parent.getScene() == gameScene) {
            node.getTransform().setParent(parent.getTransform());
        } else {
            gameScene.add(node);
        }
        EditorSelection.setSelectedNode(node);
        LOGGER.info("创建节点: {} (id={})", node.getName(), node.getId());
    }

    /** 全树重名去重：Cylinder / Cylinder (1) / ...（Unity 编号口径；与 CreateBoxMenuCommand
     *  同式各持一份——命令按一动作一类自包含，抽取再议） */
    private static String uniqueName(GameScene scene) {
        Set<String> names = new HashSet<>();
        for (Node root : scene.getRootNodes()) {
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
