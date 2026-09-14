package com.javelin.core;

import com.javelin.core.render.*;
import com.javelin.core.render.material.*;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.mesh.MeshBuilder;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.settings.JavelinSetting;
import org.joml.Vector3f;

/**
 * MeshRenderer 测试 —— 创建一个带 Mesh + Material 的渲染场景。
 *
 * <p>验证内容：</p>
 * <ul>
 *   <li>MeshBuilder 构建三角形 Mesh</li>
 *   <li>Shader / ShaderPass / Material 创建</li>
 *   <li>MeshRenderer 组件附加到 Node</li>
 *   <li>多 Pass 渲染（如果 Shader 有多个 Pass）</li>
 * </ul>
 */
public class MeshRendererTest extends JavelinEngine {

    public MeshRendererTest(JavelinSetting setting) {
        super(setting);
    }

    @Override
    protected void initialize() {
        super.initialize();
        GameScene scene = sceneManager.createScene("scene");
        sceneManager.setActiveScene(scene);

        // 创建相机
        createCamera(scene);

        // 创建三角形 Mesh + MeshRenderer
        createTriangle(scene);
    }

    /**
     * 创建主相机。
     */
    private void createCamera(GameScene scene) {
        Node node = new Node("MainCamera");
        node.transform().setLocalPosition(new Vector3f(0, 0, 5));

        Camera camera = new Camera();
        camera.setViewport(0.0f, 0.0f, 1.0f, 1.0f);
        camera.setClearColor(0.2f, 0.2f, 0.2f, 1.0f); // 深灰色清屏
        node.addComponent(camera);

        scene.addRoot(node);
    }

    /**
     * 创建三角形 MeshRenderer。
     */
    private void createTriangle(GameScene scene) {
        // 1. 构建 Mesh（位置 + 颜色）
        Mesh mesh = new MeshBuilder()
            .withPositions()
            .withColors()
            .vertex(-1.0f, -1.0f, 0.0f).color(1.0f, 0.0f, 0.0f, 1.0f)  // 左下 红
            .vertex( 1.0f, -1.0f, 0.0f).color(0.0f, 1.0f, 0.0f, 1.0f)  // 右下 绿
            .vertex( 0.0f,  1.0f, 0.0f).color(0.0f, 0.0f, 1.0f, 1.0f)  // 顶部 蓝
            .index(0, 1, 2)
            .build();

        // 2. 创建 Shader（简化版：无 SPIR-V 字节码）
        Shader shader = new Shader();

        // 3. 创建 ShaderPass
        ShaderPass pass = new ShaderPass();
        pass.setName("Forward");
        pass.setZWrite(true);
        pass.setZTest(CompareFunction.LessEqual);
        pass.setCullMode(CullMode.Back);
        pass.setRenderLayer(RenderLayer.Opaque);
        // 注意：vertexBytecode / fragmentBytecode 为空，实际运行需要有效的 SPIR-V
        shader.addPass(pass);

        // 4. 创建 Material
        Material material = new Material(shader);

        // 5. 上传 Mesh 数据到 GPU（需要在 RHI 初始化后）
        mesh.uploadToGpu(device());

        // 6. 创建 MeshRenderer
        Node node = new Node("Triangle");
        MeshRenderer meshRenderer = new MeshRenderer();
        meshRenderer.setMesh(mesh);
        meshRenderer.setMaterial(material);
        node.addComponent(meshRenderer);

        scene.addRoot(node);
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("MeshRenderer Test");
        setting.setWidth(1280);
        setting.setHeight(720);

        MeshRendererTest test = new MeshRendererTest(setting);
        test.start();
    }
}
