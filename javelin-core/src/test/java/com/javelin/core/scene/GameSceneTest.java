package com.javelin.core.scene;

import com.javelin.core.render.RenderCommand;
import com.javelin.core.render.Renderable;
import com.javelin.core.scene.component.Camera;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Node / Component / GameScene 生命周期单元测试。
 */
class GameSceneTest {

    /** 记录生命周期调用顺序的测试组件 */
    static class RecordingComponent extends Component {
        final List<String> log = new ArrayList<>();
        int updateCount = 0;

        @Override
        protected void onAttach(Node node) {
            log.add("onAttach");
        }

        @Override
        protected void onEnable() {
            log.add("onEnable");
        }

        @Override
        protected void onUpdate(float deltaTime) {
            log.add("onUpdate");
            updateCount++;
        }

        @Override
        protected void onDisable() {
            log.add("onDisable");
        }

        @Override
        protected void onDetach() {
            log.add("onDetach");
        }
    }

    /** 实现 Renderable 的测试组件 */
    static class RenderableComponent extends Component implements Renderable {
        @Override
        public void collect(List<RenderCommand> outCommands) {
            outCommands.add(null); // 占位，测试只验证收集行为
        }
    }

    static class OtherComponent extends Component {
    }

    private GameScene newSceneWith(Node root) {
        GameScene scene = new GameScene("test");
        scene.addRoot(root);
        return scene;
    }

    @Test
    void addComponentTriggersAttachThenEnable() {
        Node node = new Node();
        newSceneWith(node);

        RecordingComponent c = node.addComponent(new RecordingComponent());

        assertEquals(List.of("onAttach", "onEnable"), c.log);
        assertTrue(c.isActivated());
    }

    @Test
    void sceneUpdateCallsOnUpdate() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());
        c.log.clear();

        scene.update(1 / 60f);

        assertEquals(1, c.updateCount);
        assertEquals(List.of("onUpdate"), c.log);
    }

    @Test
    void removeComponentTriggersDisableThenDetach() {
        Node node = new Node();
        newSceneWith(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());
        c.log.clear();

        node.removeComponent(c);

        assertEquals(List.of("onDisable", "onDetach"), c.log);
        assertFalse(c.isActivated());
        assertNull(c.node());
    }

    @Test
    void setActiveFalseTriggersDisableOnWholeSubtree() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        parent.addChild(child);
        newSceneWith(parent);
        RecordingComponent pc = parent.addComponent(new RecordingComponent());
        RecordingComponent cc = child.addComponent(new RecordingComponent());
        pc.log.clear();
        cc.log.clear();

        parent.setActive(false);

        assertEquals(List.of("onDisable"), pc.log);
        assertEquals(List.of("onDisable"), cc.log);
        assertFalse(parent.activeInHierarchy());
        assertFalse(child.activeInHierarchy());

        // 失活子树不再更新
        parent.scene().update(1 / 60f);
        assertEquals(0, pc.updateCount);
        assertEquals(0, cc.updateCount);
    }

    @Test
    void reactivateTriggersEnableAgain() {
        Node node = new Node();
        newSceneWith(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());

        node.setActive(false);
        node.setActive(true);

        assertEquals(List.of("onAttach", "onEnable", "onDisable", "onEnable"), c.log);
    }

    @Test
    void setEnabledOnComponentTogglesActivation() {
        Node node = new Node();
        newSceneWith(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());
        c.log.clear();

        c.setEnabled(false);
        assertEquals(List.of("onDisable"), c.log);

        c.setEnabled(true);
        assertEquals(List.of("onDisable", "onEnable"), c.log);
    }

    @Test
    void duplicateComponentTypeRejected() {
        Node node = new Node();
        node.addComponent(new RecordingComponent());
        assertThrows(IllegalStateException.class, () -> node.addComponent(new RecordingComponent()));
    }

    @Test
    void differentComponentTypesCoexist() {
        Node node = new Node();
        node.addComponent(new RecordingComponent());
        node.addComponent(new OtherComponent());
        // 组件列表含自动创建的 Transform
        assertEquals(3, node.getComponents().size());
    }

    @Test
    void transformIsAutoAttachedComponent() {
        Node node = new Node();
        assertSame(node.transform(), node.getComponent(Transform.class));
        assertTrue(node.hasComponent(Transform.class));
    }

    @Test
    void transformCannotBeAddedOrRemoved() {
        Node node = new Node();
        assertThrows(IllegalStateException.class, () -> node.addComponent(new Transform()));
        assertThrows(IllegalStateException.class, () -> node.removeComponent(Transform.class));
        assertThrows(IllegalStateException.class, () -> node.removeComponent(node.transform()));
        // 防护后 Transform 仍在
        assertSame(node.transform(), node.getComponent(Transform.class));
    }

    @Test
    void destroyIsDeferredToEndOfFrame() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());
        c.log.clear();

        node.destroy();
        // 帧末之前组件仍在，尚未触发 onDisable/onDetach
        assertTrue(node.isDestroyed());
        assertTrue(c.isActivated());
        assertEquals(List.of(), c.log);

        scene.update(1 / 60f);

        assertEquals(List.of("onDisable", "onDetach"), c.log);
        assertTrue(scene.getRootNodes().isEmpty());
    }

    @Test
    void destroyParentDestroysWholeSubtree() {
        Node parent = new Node("parent");
        Node child = new Node("child");
        parent.addChild(child);
        GameScene scene = newSceneWith(parent);
        RecordingComponent cc = child.addComponent(new RecordingComponent());
        cc.log.clear();

        parent.destroy();
        scene.update(1 / 60f);

        assertEquals(List.of("onDisable", "onDetach"), cc.log);
        assertTrue(parent.children().isEmpty());
    }

    @Test
    void destroyFromInsideOnUpdateIsSafe() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        node.addComponent(new Component() {
            @Override
            protected void onUpdate(float deltaTime) {
                node().destroy();
            }
        });

        // 不应抛出并发修改异常
        assertDoesNotThrow(() -> scene.update(1 / 60f));
        assertTrue(scene.getRootNodes().isEmpty());
    }

    @Test
    void reparentSyncsSceneReference() {
        Node rootA = new Node("a");
        Node rootB = new Node("b");
        Node child = new Node("child");
        GameScene sceneA = newSceneWith(rootA);
        GameScene sceneB = newSceneWith(rootB);
        rootA.addChild(child);

        assertSame(sceneA, child.scene());

        rootB.addChild(child);
        assertSame(sceneB, child.scene());
        assertSame(rootB, child.getParent());
    }

    @Test
    void collectRenderablesSkipsInactiveAndDisabled() {
        Node activeNode = new Node("active");
        Node inactiveNode = new Node("inactive");
        GameScene scene = newSceneWith(activeNode);
        scene.addRoot(inactiveNode);

        activeNode.addComponent(new RenderableComponent());
        inactiveNode.addComponent(new RenderableComponent());
        Node disabledNode = new Node("disabled");
        activeNode.addChild(disabledNode);
        RenderableComponent disabled = disabledNode.addComponent(new RenderableComponent());
        disabled.setEnabled(false);

        List<Renderable> out = new ArrayList<>();
        scene.collectRenderables(out);

        assertEquals(1, out.size());
        inactiveNode.setActive(true);
        disabled.setEnabled(true);

        out.clear();
        scene.collectRenderables(out);
        assertEquals(3, out.size());
    }

    @Test
    void cacheRegistersWhenNodeEntersScene() {
        Node node = new Node();
        Camera camera = node.addComponent(new Camera());
        RenderableComponent renderable = node.addComponent(new RenderableComponent());
        GameScene scene = new GameScene("test");
        List<Camera> cameras = new ArrayList<>();
        List<Renderable> renderables = new ArrayList<>();
        // 游离节点：不注册
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);
        assertTrue(cameras.isEmpty());
        assertTrue(renderables.isEmpty());

        scene.addRoot(node);
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);
        assertEquals(List.of(camera), cameras);
        assertEquals(List.of(renderable), renderables);
    }

    @Test
    void cacheUnregistersOnRemoveComponent() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        Camera camera = node.addComponent(new Camera());
        RenderableComponent renderable = node.addComponent(new RenderableComponent());
        List<Camera> cameras = new ArrayList<>();
        List<Renderable> renderables = new ArrayList<>();
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);
        assertEquals(1, cameras.size());
        assertEquals(1, renderables.size());

        node.removeComponent(camera);
        node.removeComponent(renderable);
        cameras.clear();
        renderables.clear();
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);
        assertTrue(cameras.isEmpty());
        assertTrue(renderables.isEmpty());
    }

    @Test
    void cacheUnregistersOnDestroyAtEndOfFrame() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        node.addComponent(new Camera());
        node.addComponent(new RenderableComponent());
        List<Camera> cameras = new ArrayList<>();
        scene.collectCameras(cameras);
        assertEquals(1, cameras.size());

        node.destroy();
        // 帧末拆除前组件仍在，缓存查询结果不变
        cameras.clear();
        scene.collectCameras(cameras);
        assertEquals(1, cameras.size());

        scene.update(1 / 60f);
        cameras.clear();
        List<Renderable> renderables = new ArrayList<>();
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);
        assertTrue(cameras.isEmpty());
        assertTrue(renderables.isEmpty());
    }

    @Test
    void cacheMovesWithSubtreeAcrossScenes() {
        Node rootA = new Node("a");
        Node rootB = new Node("b");
        Node child = new Node("child");
        GameScene sceneA = newSceneWith(rootA);
        GameScene sceneB = newSceneWith(rootB);
        rootA.addChild(child);
        Camera camera = child.addComponent(new Camera());

        List<Camera> cameras = new ArrayList<>();
        sceneA.collectCameras(cameras);
        assertEquals(List.of(camera), cameras);
        cameras.clear();
        sceneB.collectCameras(cameras);
        assertTrue(cameras.isEmpty());

        rootB.addChild(child);
        cameras.clear();
        sceneA.collectCameras(cameras);
        assertTrue(cameras.isEmpty());
        sceneB.collectCameras(cameras);
        assertEquals(List.of(camera), cameras);
    }

    @Test
    void activationChangeWithoutStructuralChange() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        node.addComponent(new Camera());
        List<Camera> out = new ArrayList<>();
        scene.collectCameras(out);
        assertEquals(1, out.size());

        // 无结构变化，仅激活状态翻转 → 快照过期并按需重建
        node.setActive(false);
        out.clear();
        scene.collectCameras(out);
        assertTrue(out.isEmpty());

        node.setActive(true);
        out.clear();
        scene.collectCameras(out);
        assertEquals(1, out.size());
    }

    @Test
    void setEnabledMarksSnapshotDirty() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        Camera camera = node.addComponent(new Camera());
        List<Camera> out = new ArrayList<>();
        scene.collectCameras(out);
        assertEquals(1, out.size());

        camera.setEnabled(false);
        out.clear();
        scene.collectCameras(out);
        assertTrue(out.isEmpty());

        camera.setEnabled(true);
        out.clear();
        scene.collectCameras(out);
        assertEquals(1, out.size());
    }

    @Test
    void snapshotStableAcrossRepeatedQueries() {
        Node node = new Node();
        GameScene scene = newSceneWith(node);
        node.addComponent(new Camera());
        node.addComponent(new RenderableComponent());

        List<Camera> cameras = new ArrayList<>();
        List<Renderable> renderables = new ArrayList<>();
        scene.collectCameras(cameras);
        scene.collectRenderables(renderables);

        // 连续两次查询（中间无变化）：结果内容一致，快照直接复用
        List<Camera> cameras2 = new ArrayList<>();
        List<Renderable> renderables2 = new ArrayList<>();
        scene.collectCameras(cameras2);
        scene.collectRenderables(renderables2);
        assertEquals(cameras, cameras2);
        assertEquals(renderables, renderables2);
        assertEquals(1, cameras.size());
        assertEquals(1, renderables.size());
    }

    @Test
    void findNodesByNameFindsInactiveToo() {
        Node root = new Node("root");
        Node target = new Node("target");
        root.addChild(target);
        Node inactive = new Node("target");
        root.addChild(inactive);
        GameScene scene = newSceneWith(root);
        inactive.setActive(false);

        List<Node> found = new ArrayList<>();
        scene.findNodesByName("target", found);

        assertEquals(2, found.size());
    }

    @Test
    void sceneManagerDelegatesToActiveScene() {
        SceneManager manager = new SceneManager();
        GameScene scene = manager.createScene("main");
        manager.setActiveScene(scene);

        Node node = new Node();
        scene.addRoot(node);
        RecordingComponent c = node.addComponent(new RecordingComponent());

        manager.update(1 / 60f);
        assertEquals(1, c.updateCount);

        List<Renderable> out = new ArrayList<>();
        manager.collectRenderables(out);
        assertTrue(out.isEmpty());

        manager.shutdown();
        assertNull(manager.getActiveScene());
        assertEquals(List.of("onAttach", "onEnable", "onUpdate", "onDisable", "onDetach"), c.log);
    }
}
