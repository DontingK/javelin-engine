package com.javelin.core.scene;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import org.joml.Vector3f;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 场景系统单元测试：挂入/摘除、组件注册表、update 帧驱动生命周期状态机
 *（2026-10-02 拍板：awake/enable/disable/start/update 下帧生效，destroy 摘出
 * 即时）、setParent 自动入/出场景、Transform 层级与防环。
 *
 * <p>测试组件经 {@code @Reflective} 走编译期注册（testAnnotationProcessor，
 * 落在 com.javelin.game.reflective.ReflectiveRegistration）。</p>
 */
public class GameSceneTest {

    /** 生命周期事件记录（awake/enable/start/update/disable/destroy × 组件名） */
    private static final List<String> EVENTS = new ArrayList<>();

    @Reflective
    public static class ProbeComponent extends Component {
        public String probeName;
        int awakeCount;
        int enableCount;
        int startCount;
        int updateCount;
        int disableCount;
        int destroyCount;

        @Override
        protected void onAwake() {
            awakeCount++;
            EVENTS.add("awake:" + probeName);
        }

        @Override
        protected void onEnable() {
            enableCount++;
            EVENTS.add("enable:" + probeName);
        }

        @Override
        protected void onStart() {
            startCount++;
            EVENTS.add("start:" + probeName);
        }

        @Override
        protected void onUpdate(float dt) {
            updateCount++;
        }

        @Override
        protected void onDisable() {
            disableCount++;
            EVENTS.add("disable:" + probeName);
        }

        @Override
        protected void onDestroy() {
            destroyCount++;
            EVENTS.add("destroy:" + probeName);
        }
    }

    private GameScene scene;

    @BeforeEach
    void setUp() {
        EVENTS.clear();
        scene = new GameScene("test");
    }

    private Node nodeWithProbe(String name) {
        Node node = new Node();
        node.setName(name);
        ProbeComponent probe = node.addComponent(ProbeComponent.class);
        probe.probeName = name;
        return node;
    }

    private ProbeComponent probeOf(Node node) {
        return node.getComponent(ProbeComponent.class);
    }

    // ═══ 挂入（add / attachSubtree） ═══

    @Test
    void updateDrivesAwakeBeforeEnableInHierarchyOrder() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());

        scene.add(root);
        // 挂入即时：归属 + 注册（生命周期不触发——帧驱动拍板）
        assertEquals(0, probeOf(root).awakeCount);
        assertSame(scene, root.getScene());
        assertSame(scene, child.getScene());
        assertTrue(scene.getRootNodes().contains(root));

        scene.update(0.016f);
        // 三遍序（Unity 阶段序）：全部 awake → 全部 enable → start
        assertEquals(List.of("awake:root", "awake:child", "enable:root", "enable:child"), EVENTS);
        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).enableCount);
    }

    @Test
    void addRegistersCameraAndRenderableComponents() {
        Node node = new Node();
        Camera camera = node.addComponent(Camera.class);
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        node.addComponent(ProbeComponent.class);

        scene.add(node);

        assertTrue(scene.getCameras().contains(camera));
        assertTrue(scene.getRenderables().contains(renderer));
    }

    @Test
    void addIsIdempotentForAlreadyAttachedNodes() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());

        scene.add(root);
        scene.add(root);   // 重复 add 不重复注册（awaked 状态字段防重 fire）
        scene.update(0.016f);

        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).enableCount);
        assertEquals(1, probeOf(child).awakeCount);
    }

    @Test
    void addToSecondSceneThrows() {
        Node root = nodeWithProbe("root");
        scene.add(root);

        GameScene other = new GameScene("other");
        assertThrows(IllegalStateException.class, () -> other.add(root));
    }

    @Test
    void inactiveNodeAwakesButDoesNotEnable() {
        Node root = nodeWithProbe("root");
        root.setActive(false);

        scene.add(root);
        scene.update(0.016f);

        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(0, probeOf(root).enableCount);
        assertEquals(0, probeOf(root).updateCount);

        root.setActive(true);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).enableCount);
        assertEquals(1, probeOf(root).updateCount);
    }

    // ═══ update 驱动 ═══

    @Test
    void updateFiresStartOnceThenEveryFrame() {
        Node root = nodeWithProbe("root");
        scene.add(root);

        scene.update(0.016f);
        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).enableCount);
        assertEquals(1, probeOf(root).startCount);
        assertEquals(1, probeOf(root).updateCount);

        scene.update(0.016f);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).startCount);
        assertEquals(3, probeOf(root).updateCount);
    }

    @Test
    void disabledOrInactiveComponentsAreNotUpdated() {
        Node disabled = nodeWithProbe("disabled");
        disabled.getComponent(ProbeComponent.class).setEnabled(false);
        Node inactive = nodeWithProbe("inactive");
        inactive.setActive(false);
        Node normal = nodeWithProbe("normal");
        scene.add(disabled);
        scene.add(inactive);
        scene.add(normal);

        scene.update(0.016f);

        // awake 不判激活（Unity: inactive 对象也执行 Awake；enabled=false 同理）
        assertEquals(1, probeOf(disabled).awakeCount);
        assertEquals(1, probeOf(inactive).awakeCount);
        assertEquals(0, probeOf(disabled).updateCount);
        assertEquals(0, probeOf(inactive).updateCount);
        assertEquals(1, probeOf(normal).updateCount);
    }

    // ═══ setActive/setEnabled 翻转（下帧状态机联动） ═══

    @Test
    void setActiveTogglesSubtreeLifecycleNextFrame() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());
        scene.add(root);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).enableCount);
        assertEquals(1, probeOf(child).enableCount);

        root.setActive(false);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).disableCount);
        assertEquals(1, probeOf(child).disableCount);

        root.setActive(true);
        scene.update(0.016f);
        assertEquals(2, probeOf(root).enableCount);
        assertEquals(2, probeOf(child).enableCount);

        // 子节点自身失活（父激活：其 disable 为自身状态翻转）
        child.setActive(false);
        scene.update(0.016f);
        assertEquals(2, probeOf(child).disableCount);

        // 子节点自身失活后，父节点失活不再对它重复 disable（通知态已是关）
        root.setActive(false);
        scene.update(0.016f);
        assertEquals(2, probeOf(root).disableCount);
        assertEquals(2, probeOf(child).disableCount);

        root.setActive(true);
        scene.update(0.016f);
        assertEquals(3, probeOf(root).enableCount);
        assertEquals(2, probeOf(child).enableCount);
    }

    @Test
    void setEnabledTogglesComponentLifecycleNextFrame() {
        Node root = nodeWithProbe("root");
        scene.add(root);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).enableCount);
        assertEquals(1, probeOf(root).updateCount);

        // setEnabled 回调联动（原即时语义的缺口，帧驱动状态机统一修复）
        probeOf(root).setEnabled(false);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).disableCount);
        assertEquals(1, probeOf(root).updateCount);

        probeOf(root).setEnabled(true);
        scene.update(0.016f);
        assertEquals(2, probeOf(root).enableCount);
        assertEquals(2, probeOf(root).updateCount);
        // awake/start 每轮挂入只一次（disable/enable 往复不重触发）
        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).startCount);
    }

    // ═══ setParent 自动入/出场景 ═══

    @Test
    void setParentIntoSceneAttachesBareSubtree() {
        Node root = nodeWithProbe("root");
        scene.add(root);

        Node leaf = nodeWithProbe("leaf");
        Node grandChild = nodeWithProbe("grandChild");
        grandChild.getTransform().setParent(leaf.getTransform());
        leaf.getTransform().setParent(root.getTransform());

        // 整棵裸子树自动入场景（归属即时；生命周期下帧驱动）
        assertSame(scene, leaf.getScene());
        assertSame(scene, grandChild.getScene());

        scene.update(0.016f);
        assertEquals(1, probeOf(leaf).awakeCount);
        assertEquals(1, probeOf(leaf).enableCount);
        assertEquals(1, probeOf(grandChild).awakeCount);
        assertEquals(1, probeOf(leaf).updateCount);
        assertEquals(1, probeOf(grandChild).updateCount);
    }

    @Test
    void detachFromSceneRemovesWholeSubtree() {
        Node root = nodeWithProbe("root");
        Node leaf = nodeWithProbe("leaf");
        Node grandChild = nodeWithProbe("grandChild");
        grandChild.getTransform().setParent(leaf.getTransform());
        leaf.getTransform().setParent(root.getTransform());
        scene.add(root);
        scene.update(0.016f);

        leaf.getTransform().setParent(null);

        // 摘除即出场景：整棵子树（含孙节点）注销并即时触发 onDisable（按通知态）
        // /onDestroy（帧驱动生命周期唯一即时触发点）
        assertNull(leaf.getScene());
        assertNull(grandChild.getScene());
        assertEquals(1, probeOf(leaf).disableCount);
        assertEquals(1, probeOf(leaf).destroyCount);
        assertEquals(1, probeOf(grandChild).disableCount);
        assertEquals(1, probeOf(grandChild).destroyCount);

        scene.update(0.016f);
        assertEquals(0, probeOf(leaf).updateCount);
        assertEquals(1, probeOf(root).updateCount);
    }

    @Test
    void crossSceneSetParentThrows() {
        Node rootA = new Node();
        scene.add(rootA);
        Node rootB = new Node();
        GameScene sceneB = new GameScene("sceneB");
        sceneB.add(rootB);

        Node moving = new Node();
        scene.add(moving);

        assertThrows(IllegalStateException.class,
                () -> moving.getTransform().setParent(rootB.getTransform()));
        assertSame(scene, moving.getScene());
    }

    // ═══ remove ═══

    @Test
    void removeDestroysWholeSubtreeAndClearsOwnership() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());
        scene.add(root);

        scene.remove(root);

        assertNull(root.getScene());
        assertNull(child.getScene());
        assertEquals(1, probeOf(root).destroyCount);
        assertEquals(1, probeOf(child).destroyCount);
        assertFalse(scene.getRootNodes().contains(root));
        assertNull(root.getTransform().getParent());

        assertThrows(IllegalStateException.class, () -> scene.remove(root));
    }

    @Test
    void reattachRerunsFullLifecycle() {
        Node root = nodeWithProbe("root");
        scene.add(root);
        scene.update(0.016f);
        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).enableCount);

        scene.remove(root);
        assertEquals(1, probeOf(root).destroyCount);

        // 摘出重置生命周期状态：重挂后整轮重跑（awake/enable 重新计）
        scene.add(root);
        scene.update(0.016f);
        assertEquals(2, probeOf(root).awakeCount);
        assertEquals(2, probeOf(root).enableCount);
        assertEquals(2, probeOf(root).updateCount);
        assertEquals(1, probeOf(root).destroyCount);
    }

    // ═══ Node/Component 基础 ═══

    @Test
    void nodeAutoHoldsTransformAndRejectsDuplicate() {
        Node node = new Node();

        assertNotNull(node.getTransform());
        assertSame(node.getTransform(), node.getComponent(Transform.class));
        assertThrows(IllegalStateException.class, () -> node.addComponent(Transform.class));
    }

    @Test
    void getComponentsReturnsMatchingInstances() {
        Node node = new Node();
        Camera camera = node.addComponent(Camera.class);
        ProbeComponent probe = node.addComponent(ProbeComponent.class);

        assertSame(camera, node.getComponent(Camera.class));
        assertSame(probe, node.getComponent(ProbeComponent.class));
        assertNull(node.getComponent(MeshRenderer.class));
        assertEquals(1, node.getComponents(Camera.class).size());
    }

    // ═══ Transform ═══

    @Test
    void transformAccumulatesWorldPositionThroughParentChain() {
        Node parent = new Node();
        Node child = new Node();
        child.getTransform().setParent(parent.getTransform());
        parent.getTransform().setLocalPosition(new Vector3f(1, 0, 0));
        child.getTransform().setLocalPosition(new Vector3f(0, 2, 0));

        Vector3f world = child.getTransform().getWorldPosition(new Vector3f());
        assertEquals(1.0f, world.x, 1e-6f);
        assertEquals(2.0f, world.y, 1e-6f);
        assertEquals(0.0f, world.z, 1e-6f);
    }

    @Test
    void transformRejectsCyclicParenting() {
        Node a = new Node();
        Node b = new Node();
        b.getTransform().setParent(a.getTransform());

        assertThrows(IllegalArgumentException.class, () -> a.getTransform().setParent(a.getTransform()));
        assertThrows(IllegalArgumentException.class, () -> a.getTransform().setParent(b.getTransform()));
    }

    // ═══ 主相机（Unity Camera.main 口径） ═══

    @Test
    void mainCameraQueryFollowsUnitySemantics() {
        GameScene scene = new GameScene("main-camera-test");

        // 无相机 → null
        assertNull(scene.getMainCamera());

        // 有相机但未标 main → null（不按「首个相机」兜底）
        Node nodePlain = new Node();
        nodePlain.addComponent(Camera.class);
        scene.add(nodePlain);
        assertNull(scene.getMainCamera());

        // 标 main → 命中
        Node nodeMain = new Node();
        Camera main = nodeMain.addComponent(Camera.class);
        main.setMain(true);
        scene.add(nodeMain);
        assertSame(main, scene.getMainCamera());

        // 非激活节点上的 main 相机不命中（inactive 不算）
        nodeMain.setActive(false);
        assertNull(scene.getMainCamera());
        nodeMain.setActive(true);

        // 组件禁用同样不命中
        main.setEnabled(false);
        assertNull(scene.getMainCamera());
        main.setEnabled(true);
        assertSame(main, scene.getMainCamera());

        // 多个 main：取挂入序首个
        Node nodeSecond = new Node();
        Camera second = nodeSecond.addComponent(Camera.class);
        second.setMain(true);
        scene.add(nodeSecond);
        assertSame(main, scene.getMainCamera());

        // 摘出场景注销（主相机退场）
        scene.remove(nodeMain);
        assertSame(second, scene.getMainCamera());
    }
}
