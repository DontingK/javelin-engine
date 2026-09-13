package com.javelin.core;

import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.settings.JavelinSetting;
import org.joml.Vector3f;

/**
 * JavelinEngine 测试类 —— 创建两个相机进行分屏清屏测试。
 *
 * <p>左半屏使用红色清屏，右半屏使用蓝色清屏，验证多相机渲染和视口系统。</p>
 */
public class JavelinEngineTest extends JavelinEngine {

    public JavelinEngineTest(JavelinSetting setting) {
        super(setting);
    }

    @Override
    protected void initialize() {
        super.initialize();
        GameScene scene = sceneManager.createScene("scene");
        sceneManager.setActiveScene(scene);

        // 创建两个相机节点，分别占据左半屏和右半屏
        createLeftCamera(scene);
        createRightCamera(scene);
    }

    /**
     * 创建左上相机（红色清屏）。
     */
    private void createLeftCamera(GameScene scene) {
        Node node = new Node("LeftCamera");
        node.transform().setLocalPosition(new Vector3f(0, 0, 5));

        Camera camera = new Camera();
        // 左上：viewportLeft=0, viewportTop=0, viewportRight=0.5, viewportBottom=0.5
        camera.setViewport(0.0f, 0.0f, 0.5f, 0.5f);
        camera.setClearColor(1.0f, 0.0f, 0.0f, 1.0f); // 红色清屏
        node.addComponent(camera);

        scene.addRoot(node);
    }

    /**
     * 创建右下相机（蓝色清屏）。
     */
    private void createRightCamera(GameScene scene) {
        Node node = new Node("RightCamera");
        node.transform().setLocalPosition(new Vector3f(0, 0, 5));

        Camera camera = new Camera();
        // 右下：viewportLeft=0.5, viewportTop=0.5, viewportRight=1, viewportBottom=1
        camera.setViewport(0.5f, 0.5f, 1.0f, 1.0f);
        camera.setClearColor(0.0f, 0.0f, 1.0f, 1.0f); // 蓝色清屏
        node.addComponent(camera);

        scene.addRoot(node);
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        JavelinEngineTest javelinEngineTest = new JavelinEngineTest(setting);
        javelinEngineTest.start();
    }
}
