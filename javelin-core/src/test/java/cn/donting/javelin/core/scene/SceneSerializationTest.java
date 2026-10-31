package cn.donting.javelin.core.scene;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.assets.Asset;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.scene.*;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import org.joml.Vector3f;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * .scene 序列化单元测试（二进制通道 round-trip，2026-10-01 落地）。
 *
 * <p>覆盖：树结构与 Transform 回链（parent/node 双向）、guid/lastModified 内嵌、
 * layer/tags 投影恢复、场景归属 read 直恢复（scene 落盘根 id 引用，2026-10-02
 * 拍板——无收尾修复）、注册表 read 重建（components 直传 + instanceof 分流）、
 * 生命周期 update 首帧自然驱动（帧驱动状态机与 read 全量恢复衔接）、Camera
 * 全字段、MeshRenderer 资产引用经 AssetManager 按 guid 解回（共享保持）。文本
 * 通道（TextExporter 在 editor 模块）经编辑器实测验收。</p>
 *
 * <p>测试组件经 {@code @Reflective} 走编译期注册（testAnnotationProcessor，
 * 落在 cn.donting.javelin.game.reflective.ReflectiveRegistration——GameSceneTest 同款先例）。</p>
 */
public class SceneSerializationTest {

    @TempDir
    File tempDir;

    /** 生命周期探针（用户组件继承序列化模式的样板：super + 自有字段直通） */
    @Reflective
    public static class ProbeComponent extends Component {
        public String probeName;
        int awakeCount;
        int enableCount;
        int startCount;
        int updateCount;

        @Override
        protected void onAwake() {
            awakeCount++;
        }

        @Override
        protected void onEnable() {
            enableCount++;
        }

        @Override
        protected void onStart() {
            startCount++;
        }

        @Override
        protected void onUpdate(float dt) {
            updateCount++;
        }

        @Override
        public void write(OutputCapsule output) {
            super.write(output);
            output.write("probeName", probeName);
        }

        @Override
        public void read(InputCapsule input) {
            super.read(input);
            probeName = input.readString("probeName");
        }
    }

    /** 资产引用通道 stub：guid → 固定实例（验证引用共享恢复，不落盘资产本体） */
    private static final class StubAssetManager implements AssetManager {
        private final Map<String, Asset> assets = new HashMap<>();

        StubAssetManager(Asset... assets) {
            for (Asset asset : assets) {
                this.assets.put(asset.getGuid(), asset);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Asset> T loadGuid(String guid, Class<T> assetClass) {
            return (T) assets.get(guid);
        }

        @Override
        public String pathOfGuid(String guid) {
            return null;   // stub 无 Library 索引，反查恒未命中
        }

        @Override
        public String guidOfPath(String path) {
            return null;   // stub 无 Library 索引，路径反查恒未命中
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Asset> T loadGuidInstance(String guid, Class<T> assetClass) {
            return (T) assets.get(guid);
        }

        @Override
        public <T extends Asset> T loadAsset(String path, Class<T> assetClass) {
            throw new UnsupportedOperationException("本 stub 仅覆盖 guid 通道");
        }

        @Override
        public <T extends Asset> T loadAssetInstance(String path, Class<T> assetClass) {
            throw new UnsupportedOperationException("本 stub 仅覆盖 guid 通道");
        }

        @Override
        public java.util.List<String> listAssetPaths(String suffix) {
            throw new UnsupportedOperationException("本 stub 仅覆盖 guid 通道");
        }
    }

    @Test
    void roundTripPreservesTreeStructureAndFields() throws Exception {
        GameScene scene = new GameScene("Test Scene");
        scene.setGuid("scene-guid-0001");
        scene.setLastModified(123456789L);

        Node root = new Node();
        root.setName("root");
        root.getTransform().setLocalPosition(new Vector3f(1, 2, 3));
        scene.add(root);

        Node child = new Node();
        child.setName("child");
        // setParent 挂到已入场景父节点下：自动入场景（onParentChanged → attachSubtree）
        child.getTransform().setParent(root.getTransform());
        child.getTags().add(Tag.of("test-tag"));

        File file = new File(tempDir, "structure.bin");
        new BinaryExporter(file).export(scene);

        GameScene loaded = new BinaryImporter(file).importData(GameScene.class);

        // 场景本体：name + guid/lastModified 内嵌（AbsAsset super.write）
        assertEquals("Test Scene", loaded.getName());
        assertEquals("scene-guid-0001", loaded.getGuid());
        assertEquals(123456789L, loaded.getLastModified());

        // 树结构：根列表 + 名字 + local TRS
        assertEquals(1, loaded.getRootNodes().size());
        Node loadedRoot = loaded.getRootNodes().get(0);
        assertEquals("root", loadedRoot.getName());
        Vector3f position = loadedRoot.getTransform().getLocalPosition();
        assertEquals(1.0f, position.x, 1e-6f);
        assertEquals(2.0f, position.y, 1e-6f);
        assertEquals(3.0f, position.z, 1e-6f);

        // 子树：children 展开 + parent/node 双向回链 + 场景归属（scene 根 id 引用 read 直恢复）
        assertEquals(1, loadedRoot.getTransform().getChildCount());
        Node loadedChild = loadedRoot.getTransform().getChild(0).getNode();
        assertEquals("child", loadedChild.getName());
        assertSame(loadedRoot.getTransform(), loadedChild.getTransform().getParent());
        assertSame(loadedChild, loadedChild.getTransform().getNode());
        assertSame(loaded, loadedChild.getScene());

        // 投影恢复：tags 逐名驻留（Tag.of）、layer 索引查表（Layer.of）
        assertEquals(1, loadedChild.getTags().size());
        assertEquals("test-tag", loadedChild.getTags().get(0).getName());
        assertSame(Layer.DEFAULT, loadedChild.getLayer());
    }

    @Test
    void lifecycleDrivenOnFirstUpdateAfterRestore() throws Exception {
        GameScene scene = new GameScene("Lifecycle Scene");
        Node root = new Node();
        root.setName("probe-root");
        ProbeComponent probe = root.addComponent(ProbeComponent.class);
        probe.probeName = "probe-1";
        scene.add(root);

        File file = new File(tempDir, "lifecycle.bin");
        new BinaryExporter(file).export(scene);

        GameScene loaded = new BinaryImporter(file).importData(GameScene.class);

        // 场景归属由 read 直恢复（scene 根 id 引用）；read 零回调（生命周期不落盘不触发）
        Node loadedRoot = loaded.getRootNodes().get(0);
        assertSame(loaded, loadedRoot.getScene());
        ProbeComponent loadedProbe = loadedRoot.getComponent(ProbeComponent.class);
        assertNotNull(loadedProbe);
        assertEquals("probe-1", loadedProbe.probeName);
        assertEquals(0, loadedProbe.awakeCount);

        // update 首帧状态机自然驱动：awake → enable → start → update（注册表已重建才可达）
        loaded.update(1.0f / 60.0f);
        assertEquals(1, loadedProbe.awakeCount);
        assertEquals(1, loadedProbe.enableCount);
        assertEquals(1, loadedProbe.startCount);
        assertEquals(1, loadedProbe.updateCount);
    }

    @Test
    void roundTripCameraFieldsAndRegistry() throws Exception {
        GameScene scene = new GameScene("Camera Scene");
        Node cameraNode = new Node();
        cameraNode.setName("camera-node");
        Camera camera = cameraNode.addComponent(Camera.class);
        camera.setFov(75.0f);
        camera.setNear(0.5f);
        camera.setFar(500.0f);
        camera.setDepth(2.0f);
        camera.setMain(true);
        scene.add(cameraNode);

        File file = new File(tempDir, "camera.bin");
        new BinaryExporter(file).export(scene);

        GameScene loaded = new BinaryImporter(file).importData(GameScene.class);

        // 相机注册表经 read 分流重建（register 的 instanceof 口径）
        assertEquals(1, loaded.getCameras().size());
        Camera loadedCamera = loaded.getCameras().get(0);
        assertEquals(75.0f, loadedCamera.getFov(), 1e-6f);
        assertEquals(0.5f, loadedCamera.getNear(), 1e-6f);
        assertEquals(500.0f, loadedCamera.getFar(), 1e-6f);
        assertEquals(2.0f, loadedCamera.getDepth(), 1e-6f);
        assertTrue(loadedCamera.isMain());
        // 组件 node 回链（Component 基类双向引用恢复）
        assertSame(loaded.getRootNodes().get(0), loadedCamera.getNode());
    }

    @Test
    void assetReferenceResolvedByGuidKeepsSharedInstance() throws Exception {
        Mesh mesh = new Mesh();
        mesh.setGuid("mesh-guid-shared");
        mesh.setIndices(new int[]{0, 1, 2});

        GameScene scene = new GameScene("Asset Ref Scene");
        Node parent = new Node();
        parent.setName("parent");
        MeshRenderer parentRenderer = parent.addComponent(MeshRenderer.class);
        parentRenderer.setMesh(mesh);
        parentRenderer.setCastShadow(false);
        // 材质 guid 引用（二进制通道 Asset 分流）：经 AssetManager 解回同一实例
        Material material = new Material();
        material.setGuid("material-guid-1");
        parentRenderer.setMaterial(material);
        Node child = new Node();
        child.setName("child");
        MeshRenderer childRenderer = child.addComponent(MeshRenderer.class);
        childRenderer.setMesh(mesh);
        scene.add(parent);
        child.getTransform().setParent(parent.getTransform());

        File file = new File(tempDir, "asset-ref.bin");
        new BinaryExporter(file).export(scene);

        GameScene loaded = new BinaryImporter(file, new StubAssetManager(mesh, material))
                .importData(GameScene.class);

        Node loadedParent = loaded.getRootNodes().get(0);
        Node loadedChild = loadedParent.getTransform().getChild(0).getNode();
        MeshRenderer loadedParentRenderer = loadedParent.getComponent(MeshRenderer.class);
        MeshRenderer loadedChildRenderer = loadedChild.getComponent(MeshRenderer.class);

        // guid 引用经 AssetManager 解回同一实例：跨 renderer 共享保持
        assertSame(mesh, loadedParentRenderer.getMesh());
        assertSame(mesh, loadedChildRenderer.getMesh());
        // 材质 guid 引用解回同一实例；子 renderer 无材质 null（Unity missing 语义，不渲染）
        assertSame(material, loadedParentRenderer.getMaterial());
        assertNull(loadedChildRenderer.getMaterial());
        assertFalse(loadedParentRenderer.isCastShadow());
        assertTrue(loadedChildRenderer.isCastShadow());
        // 可渲染注册表（Renderable instanceof 分流）
        assertEquals(2, loaded.getRenderables().size());
    }

    @Test
    void materialDataRoundTripRestoresShaderRefAndValues() throws Exception {
        Shader shader = Shader.builder("TestShader")
                .property(MaterialProperty.color("_Color", "Color", 1.0f, 1.0f, 1.0f, 1.0f))
                .property(MaterialProperty.floatProp("_Float", "Float", 0.5f))
                .build();
        shader.setGuid("shader-guid-1");

        Material material = new Material(shader);
        material.setColor("_Color", 0.2f, 0.4f, 0.6f, 1.0f);
        material.setFloat("_Float", 0.75f);
        material.setSurfaceType(SurfaceType.TRANSPARENT);

        // 根级导出（根实例走 id 内嵌）：材质数据面 write/read 直接过堂；
        // shader 字段为非根 Asset 引用 → guid 分流 → AssetManager 解回
        File file = new File(tempDir, "material.bin");
        new BinaryExporter(file).export(material);
        Material loaded = new BinaryImporter(file, new StubAssetManager(shader))
                .importData(Material.class);

        assertSame(shader, loaded.getShader());
        float[] color = loaded.getColor("_Color");
        assertEquals(0.2f, color[0], 1e-6f);
        assertEquals(0.4f, color[1], 1e-6f);
        assertEquals(0.6f, color[2], 1e-6f);
        assertEquals(1.0f, color[3], 1e-6f);
        assertEquals(0.75f, loaded.getFloat("_Float"), 1e-6f);
        // 面型随材质数据面往返（透明性由材质裁决，拍板 2026-10-17 方案 B）
        assertEquals(SurfaceType.TRANSPARENT, loaded.getSurfaceType());
    }

    @Test
    void multiMaterialSubmeshRoundTrip() throws Exception {
        Mesh mesh = new Mesh();
        mesh.setGuid("mesh-guid-submesh");
        mesh.setPositions(new float[]{0, 0, 0, 1, 0, 0, 0, 1, 0, 1, 1, 0});
        mesh.setSubmeshes(List.of(new int[]{0, 1, 2}, new int[]{1, 3, 2}));
        Material materialA = new Material();
        materialA.setGuid("material-guid-a");
        Material materialB = new Material();
        materialB.setGuid("material-guid-b");

        GameScene scene = new GameScene("Submesh Scene");
        Node node = new Node();
        node.setName("submesh-node");
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        renderer.setMesh(mesh);
        renderer.setMaterials(List.of(materialA, materialB));
        scene.add(node);

        File file = new File(tempDir, "submesh.bin");
        new BinaryExporter(file).export(scene);
        GameScene loaded = new BinaryImporter(file, new StubAssetManager(mesh, materialA, materialB))
                .importData(GameScene.class);

        MeshRenderer loadedRenderer = loaded.getRootNodes().get(0).getComponent(MeshRenderer.class);
        // 多材质列表 guid 分流解回同实例（i 位对应子网格 i）；子网格区间随 mesh 引用保持
        assertEquals(2, loadedRenderer.getMesh().getSubmeshCount());
        assertEquals(2, loadedRenderer.getMaterials().size());
        assertSame(materialA, loadedRenderer.getMaterial(0));
        assertSame(materialB, loadedRenderer.getMaterial(1));
        assertSame(materialA, loadedRenderer.getMaterial());
    }
}
