package cn.donting.javelin.core.scene;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.core.scene.component.annotation.UniqueComponent;
import cn.donting.javelin.core.serialize.binary.BinaryExporter;
import cn.donting.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code @UniqueComponent} 唯一性作用域与 {@link Environment} 组件测试（2026-11-08）。
 *
 * <p>覆盖：scene=true 全场景唯一（跨节点判，含子树挂入的预检与批内互比）、默认
 * 每节点唯一；拒绝均不留半挂态；环境组件按普通节点组件往返（节点检查器可见形态），
 * 生命周期随节点驱动。</p>
 *
 * <p>测试组件经 {@code @Reflective} 走编译期注册（testAnnotationProcessor，落在
 * game 分支——SceneSerializationTest 同款先例），故注解名与属性值同样由生成的注册类
 * 携带，与生产环境同一条 AOT 安全通道。</p>
 */
public class SceneComponentTest {

    @TempDir
    File tempDir;

    /** 场景唯一探针（生命周期计数） */
    @Reflective
    @UniqueComponent(scene = true)
    public static class ProbeSceneUniqueComponent extends Component {

        int awakeCount;
        int enableCount;
        int disableCount;
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
        protected void onDisable() {
            disableCount++;
        }

        @Override
        protected void onStart() {
            startCount++;
        }

        @Override
        protected void onUpdate(float dt) {
            updateCount++;
        }
    }

    /** 节点唯一探针（默认作用域） */
    @Reflective
    @UniqueComponent
    public static class ProbeNodeUniqueComponent extends Component {
    }

    // ── 唯一性作用域 ─────────────────────────────────────────

    @Test
    void sceneUniqueRejectsSecondInstanceAcrossNodes() {
        GameScene scene = new GameScene("Scene Unique");
        Node first = new Node();
        first.setName("first");
        scene.add(first);
        first.addComponent(ProbeSceneUniqueComponent.class);

        // 另一节点再加同型：落的表在场景注册表里已有一个，落表前拒绝
        Node second = new Node();
        second.setName("second");
        scene.add(second);
        assertThrows(IllegalStateException.class,
                () -> second.addComponent(ProbeSceneUniqueComponent.class));
        assertNull(second.getComponent(ProbeSceneUniqueComponent.class));
    }

    @Test
    void sceneUniqueRejectsSubtreeAttachWithoutHalfAttach() {
        GameScene scene = new GameScene("Subtree");
        Node resident = new Node();
        resident.setName("resident");
        scene.add(resident);
        resident.addComponent(Environment.class);

        // 裸节点可挂（无场景可判），挂入场景时预检拒绝
        Node incoming = new Node();
        incoming.setName("incoming");
        incoming.addComponent(Environment.class);
        assertThrows(IllegalStateException.class, () -> scene.add(incoming));

        // 预检在改归属之前：整棵拒绝，不留「已设归属、未注册」的半挂态
        assertNull(incoming.getScene());
        assertFalse(scene.getRootNodes().contains(incoming));
        assertEquals(1, scene.getRootNodes().size());
    }

    @Test
    void sceneUniqueRejectsDuplicateWithinOneSubtree() {
        GameScene scene = new GameScene("Subtree Duplicate");
        Node a = new Node();
        a.setName("a");
        a.addComponent(Environment.class);
        Node b = new Node();
        b.setName("b");
        b.getTransform().setParent(a.getTransform());
        b.addComponent(Environment.class);

        // 子树内两处同型：批内互比同样拒绝，且不留半挂态
        assertThrows(IllegalStateException.class, () -> scene.add(a));
        assertNull(a.getScene());
        assertNull(b.getScene());
    }

    @Test
    void nodeUniqueRejectsSecondComponentOnSameNode() {
        Node node = new Node();
        node.addComponent(ProbeNodeUniqueComponent.class);
        assertThrows(IllegalStateException.class,
                () -> node.addComponent(ProbeNodeUniqueComponent.class));
        assertEquals(1, node.getComponents(ProbeNodeUniqueComponent.class).size());
    }

    @Test
    void envSceneUniqueButNodeUniqueComponentCoexistAcrossNodes() {
        // 节点唯一型在**不同节点**上可各挂一个（作用域=节点，非场景）
        GameScene scene = new GameScene("Node Scope");
        Node a = new Node();
        a.setName("a");
        scene.add(a);
        Node b = new Node();
        b.setName("b");
        scene.add(b);
        a.addComponent(ProbeNodeUniqueComponent.class);
        b.addComponent(ProbeNodeUniqueComponent.class);
        assertEquals(1, a.getComponents(ProbeNodeUniqueComponent.class).size());
        assertEquals(1, b.getComponents(ProbeNodeUniqueComponent.class).size());
    }

    // ── Environment = 普通节点组件 ────────────────────────────

    @Test
    void environmentIsOrdinaryNodeComponentWithLifecycle() {
        GameScene scene = new GameScene("Environment");
        Node node = new Node();
        node.setName("probe-node");
        scene.add(node);
        ProbeSceneUniqueComponent probe = node.addComponent(ProbeSceneUniqueComponent.class);
        assertEquals(1, node.getComponents(ProbeSceneUniqueComponent.class).size());

        // 普通节点组件：生命周期随节点激活驱动
        scene.update(1.0f / 60.0f);
        assertEquals(1, probe.awakeCount);
        assertEquals(1, probe.enableCount);
        assertEquals(1, probe.startCount);
        assertEquals(1, probe.updateCount);

        // 节点失活即停更（层级激活语义不变）
        node.setActive(false);
        scene.update(1.0f / 60.0f);
        assertEquals(1, probe.disableCount);
        assertEquals(1, probe.updateCount);
    }

    @Test
    void getEnvironmentFindsNodeAttachedComponent() {
        GameScene scene = new GameScene("Lookup");
        assertNull(scene.getEnvironment());

        Node node = new Node();
        node.setName("Environment");
        scene.add(node);
        Environment environment = node.addComponent(Environment.class);

        assertSame(environment, scene.getEnvironment());
        assertSame(node, environment.getNode());
    }

    @Test
    void environmentRoundTripRestoresNodeAndSkyboxSlot() throws Exception {
        GameScene scene = new GameScene("Env Scene");
        scene.setGuid("scene-guid-env");
        Node node = new Node();
        node.setName("Environment");
        scene.add(node);
        node.addComponent(Environment.class);

        File file = new File(tempDir, "environment.bin");
        new BinaryExporter(file).export(scene);
        GameScene loaded = new BinaryImporter(file).importData(GameScene.class);

        assertEquals("scene-guid-env", loaded.getGuid());
        Environment environment = loaded.getEnvironment();
        assertNotNull(environment);
        assertNotNull(environment.getNode());
        assertEquals("Environment", environment.getNode().getName());
        assertNull(environment.getSkybox());
        // update 首帧状态机自然驱动（read 零回调）
        loaded.update(1.0f / 60.0f);
        assertTrue(environment.isActiveAndEnabled());
    }
}
