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
 * 场景系统单元测试：挂入/摘除生命周期、组件注册表、update 驱动、
 * setActive 级联、setParent 自动入/出场景、Transform 层级与防环。
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
    void addFiresAwakeBeforeEnableInHierarchyOrder() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());

        scene.add(root);

        assertEquals(List.of("awake:root", "awake:child", "enable:root", "enable:child"), EVENTS);
        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(1, probeOf(root).enableCount);
        assertSame(scene, root.getScene());
        assertSame(scene, child.getScene());
        assertTrue(scene.getRootNodes().contains(root));
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
        scene.add(root);   // 重复 add 不重复回调

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

        assertEquals(1, probeOf(root).awakeCount);
        assertEquals(0, probeOf(root).enableCount);

        root.setActive(true);
        assertEquals(1, probeOf(root).enableCount);
    }

    // ═══ update 驱动 ═══

    @Test
    void updateFiresStartOnceThenEveryFrame() {
        Node root = nodeWithProbe("root");
        scene.add(root);

        scene.update(0.016f);
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

        assertEquals(0, probeOf(disabled).updateCount);
        assertEquals(0, probeOf(inactive).updateCount);
        assertEquals(1, probeOf(normal).updateCount);
    }

    // ═══ setActive 级联 ═══

    @Test
    void setActiveCascadesEnableDisableThroughSubtree() {
        Node root = nodeWithProbe("root");
        Node child = nodeWithProbe("child");
        child.getTransform().setParent(root.getTransform());
        scene.add(root);

        root.setActive(false);
        assertEquals(1, probeOf(root).disableCount);
        assertEquals(1, probeOf(child).disableCount);

        root.setActive(true);
        assertEquals(2, probeOf(root).enableCount);
        assertEquals(2, probeOf(child).enableCount);

        // 子节点自身失活：此前已被父节点级联 disable 过一次，这是第 2 次
        child.setActive(false);
        assertEquals(2, probeOf(child).disableCount);

        // 子节点自身失活后，父节点翻转不影响它
        root.setActive(false);
        assertEquals(2, probeOf(root).disableCount);
        assertEquals(2, probeOf(child).disableCount);

        root.setActive(true);
        assertEquals(3, probeOf(root).enableCount);
        assertEquals(2, probeOf(child).enableCount);
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

        // 整棵裸子树自动入场景，含生命周期
        assertSame(scene, leaf.getScene());
        assertSame(scene, grandChild.getScene());
        assertEquals(1, probeOf(leaf).awakeCount);
        assertEquals(1, probeOf(leaf).enableCount);
        assertEquals(1, probeOf(grandChild).awakeCount);

        scene.update(0.016f);
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

        leaf.getTransform().setParent(null);

        // 摘除即出场景：整棵子树（含孙节点）注销并触发 onDisable/onDestroy
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
}
