package com.javelin.editor.menu.hierarchy;

import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.obj.ObjectCache;
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
 * 创建 Plane 网格物体（hierarchy.create.plane，Unity 3D Object → Plane 对齐）。
 *
 * <p>挂入位置与选中语义同 {@link CreateEmptyMenuCommand}：有选中 → 选中节点下
 * （Transform.setParent 自动入场景）；无选中（含右键场景根）→ 场景根级
 * （GameScene.add）；创建后自动选中新节点（Unity 语义）；重名去重
 * Plane / Plane (n)。网格取内置资产 plane.smesh（10×10 XZ 平面，索引
 * 0,2,1/0,3,2 朝 +Y——2026-10-04 终裁：管线 GL 等效（CCW frontFace + 投影
 * Y 翻转 + 正高度视口，两次镜像抵消），可见面 = 几何法向侧，地面朝上即
 * +Y 绕序；FrontFace.java 误注已同步更正），经
 * AssetManager 共享加载（同一 Mesh 对象多 renderer 复用，场景设计 5.1 节）；
 * 材质为内置 default.shader 的运行期实例（固定紫，每 renderer 独立）。资产
 * 加载与节点创建同在引擎主线程任务内执行（对齐 EditGridAppState 的
 * loadAsset 线径）。</p>
 *
 * <p>序列化语义：mesh/material 均落盘可恢复——Material write/read 已落地
 * （2026-10-04：shader guid 引用回解 + 参数条目直通），保存重载后完整渲染
 * （落地前保存的旧场景残材质走 missing-material 不渲染，删节点重建即可）；
 * .mat 资产格式（材质 guid 化共享、Inspector 编辑载体）仍待。内置资产未就绪
 * （Library 缺产物/索引未建）时记 error 不建节点，避免留下不可见残骸。动场景
 * 结构经 {@link JavelinEditor#submitTask} 提交引擎主线程帧边界执行；只写不
 * 发通知——变更由 SceneWatchAppState 快照 diff 自动发现并推送前端。</p>
 *
 * @author javelin
 * @create 2026/10/4
 */
public class CreatePlaneMenuCommand extends HierarchyMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(CreatePlaneMenuCommand.class);

    private static final String BASE_NAME = "Plane";

    /** 内置 plane 网格 loadAsset 路径（builtin-asset-core 释放到 .javelin/assets 后的相对路径） */
    private static final String PLANE_MESH_PATH = "core/mesh/plane.smesh";

    /** 内置默认着色器 loadAsset 路径（材质模板，运行期 new Material 实例） */
    private static final String DEFAULT_SHADER_PATH = "core/shader/default.shader";

    @Override
    public String id() {
        return "hierarchy.create.plane";
    }

    @Override
    public String label() {
        return "平面";
    }

    @Override
    public void execute() {
        // 提交时快照父节点（Unity 语义：右键对象下创建；快照防执行帧选中漂移）
        Node parent = getSelectedNode();
        Integer parentId = parent != null ? parent.getId() : null;
        JavelinEditor editor = JavelinEditor.getInstance();
        if (editor == null) {
            LOGGER.warn("编辑器实例未创建，跳过创建节点（hierarchy.create.plane）");
            return;
        }
        editor.submitTask(() -> create(editor, parentId));
    }

    /** 创建节点（引擎主线程帧边界执行，资产加载同线程） */
    private static void create(JavelinEditor editor, Integer parentId) {
        EditorScene editorScene = editor.getEditorScene();
        GameScene gameScene = editorScene.getGameScene();
        if (gameScene == null) {
            LOGGER.warn("场景未就绪，跳过创建节点（hierarchy.create.plane）");
            return;
        }
        Mesh mesh = editor.getAssetManager().loadAsset(PLANE_MESH_PATH, Mesh.class);
        if (mesh == null) {
            LOGGER.error("plane 网格加载失败 path={}（Library 缺产物或索引未建），跳过创建", PLANE_MESH_PATH);
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
        Node parent = parentId != null ? ObjectCache.get(parentId) : null;
        if (parent != null && parent.getScene() == gameScene) {
            node.getTransform().setParent(parent.getTransform());
        } else {
            gameScene.add(node);
        }
        EditorSelection.setSelectedNodeId(node.getId());
        LOGGER.info("创建节点: {} (id={})", node.getName(), node.getId());
    }

    /** 全树重名去重：Plane / Plane (1) / ...（Unity 编号口径；与 CreateEmptyMenuCommand
     *  同式各持一份——命令按一动作一类自包含，网格族扩容时再议抽取） */
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
