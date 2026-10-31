package cn.donting.javelin.core;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.PassState;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.render.material.ShaderPass;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.core.render.texture.TextureLoader;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.tool.shaderc.ShaderStage;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 场景渲染接入验收 Demo：GameScene + Node/Transform/Camera/MeshRenderer + Spinner 组件，
 * 全程无手工注册相机/物体——每帧由 SceneRenderBridge 从场景 pull 翻译进
 * RenderingSystem（阶段②遗留接线 + 材质桥落地验收）。
 *
 * <p>验收点：
 * <ul>
 *   <li>双相机（Main/Side）横向均分分屏，视口随窗口 resize；</li>
 *   <li>不透明四边形经 Spinner.onUpdate 绕 Y 旋转（scene.update 驱动组件生命周期）；</li>
 *   <li>透明四边形按材质 Blend 态进透明桶、前方叠加混合、由远及近排序正确；</li>
 *   <li>材质路径：set 2 MaterialBlock（_Tint）+ 贴图槽上传 + 占位解析，白图兜底不崩。</li>
 * </ul>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.SceneRenderDemo
 */
public final class SceneRenderDemo extends JavelinEngineTestBase {

    /**
     * 旋转组件：onUpdate 里累积绕 Y 旋转（增量旋转，无需初始朝向）。
     * 组件生命周期（Awake/Enable/Start/Update）由 mainLoop → scene.update 驱动。
     */
    @Reflective
    public static class Spinner extends Component {
        public float speed;
        private final Quaternionf rotation = new Quaternionf();

        @Override
        protected void onUpdate(float dt) {
            rotation.rotateY(speed * dt);
            getNode().getTransform().setLocalRotation(rotation);
        }
    }

    public SceneRenderDemo(JavelinSetting setting) {
        super(setting);
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("Javelin - SceneRenderDemo");
        new SceneRenderDemo(setting).start();
    }

    @Override
    protected RenderingSystem createRenderingSystem() {
        return new RenderingSystem(device(), frameLoop());
    }

    @Override
    protected void onInitialize() {
        byte[] vs = TestShaders.compileResource("shaders/builtin_quad.vert", ShaderStage.VERTEX);
        byte[] fs = TestShaders.compileResource("shaders/builtin_quad.frag", ShaderStage.FRAGMENT);

        Mesh quad = createQuad();
        Texture2D texture = new Texture2D(TextureLoader.loadImage(TextureLoader.resolveDefaultTexturePath()));

        // 同一 Shader 双面型（拍板 2026-10-17 方案 B）：透明性由材质 surfaceType 裁决，
        // Blend/ZWrite 由渲染侧按面型覆盖，不再各建一份带混合态的 Shader
        Shader quadShader = Shader.builder("BuiltinQuad")
                .property(MaterialProperty.color("_Tint", "Tint", 1f, 1f, 1f, 1f))
                .property(MaterialProperty.texture2D("_MainTex", "Main Texture", "white"))
                .pass(new ShaderPass("Forward", vs, fs, new PassState()))
                .build();
        Material opaqueMaterial = new Material(quadShader);
        opaqueMaterial.setTexture("_MainTex", texture);
        Material transparentMaterial = new Material(quadShader);
        transparentMaterial.setSurfaceType(SurfaceType.TRANSPARENT);
        transparentMaterial.setTexture("_MainTex", texture);
        transparentMaterial.setColor("_Tint", 1f, 1f, 1f, 0.5f);

        GameScene scene = new GameScene("SceneRenderDemo");
        addCameraNode(scene, "Main", 0f, 1f, 6f, (float) Math.PI);
        addCameraNode(scene, "Side", 5f, 2.5f, 6f, (float) Math.atan2(-5, -7));
        addQuad(scene, quad, opaqueMaterial, -1.2f, 0.0f, -2.0f, 1.0f);
        addQuad(scene, quad, opaqueMaterial, 1.2f, 0.0f, -3.5f, 1.6f);
        addQuad(scene, quad, transparentMaterial, 0.0f, 0.2f, -1.2f, -0.7f);
        setScene(scene);
    }

    /** 相机节点：Unity 相机朝局部 +Z，yaw = atan2(dir.x, dir.z)（Main 朝 -Z → yaw=π） */
    private static void addCameraNode(GameScene scene, String name, float x, float y, float z, float yaw) {
        Node node = new Node();
        node.setName(name);
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        node.getTransform().setLocalRotation(new Quaternionf().rotateY(yaw));
        Camera camera = node.addComponent(Camera.class);
        camera.setMain("Main".equals(name));
        scene.add(node);
    }

    private static void addQuad(GameScene scene, Mesh quad, Material material,
                                float x, float y, float z, float speed) {
        Node node = new Node();
        node.setName("Quad");
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        renderer.setMesh(quad);
        renderer.setMaterial(material);
        Spinner spinner = node.addComponent(Spinner.class);
        spinner.speed = speed;
        scene.add(node);
    }

    /** 单位四边形（Y 向上，CCW 绕序）：POSITION(3f) + UV0(2f)，5 顶点属性规范序 stride 20 */
    private static Mesh createQuad() {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{
                -0.6f, 0.6f, 0.0f,
                -0.6f, -0.6f, 0.0f,
                0.6f, -0.6f, 0.0f,
                0.6f, 0.6f, 0.0f,
        });
        mesh.setUVs(new float[]{
                0.0f, 0.0f,
                0.0f, 1.0f,
                1.0f, 1.0f,
                1.0f, 0.0f,
        });
        mesh.setIndices(new int[]{0, 1, 2, 0, 2, 3});
        return mesh;
    }
}
