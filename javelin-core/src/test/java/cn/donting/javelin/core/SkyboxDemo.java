package cn.donting.javelin.core;

import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.KeyCode;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.PassState;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.ShaderPass;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.render.mesh.Mesh;
import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.core.render.texture.TextureLoader;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.scene.component.Environment;
import cn.donting.javelin.core.scene.component.MeshRenderer;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.tool.shaderc.ShaderStage;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * 三期批次⑤步骤二+3b 验收 Demo：SkyboxPass 管线 3.5 位 + ClearFlags.SKYBOX 兑现
 * + 资产路径挂载（Engine_Skybox_Design §7；SceneRenderDemo 形态 + 内存构建
 * CubemapImage → 环境设置组件字段 → 桥 sync 懒上传——3b 后不再直灌 RHI 纹理，
 * 编辑器检查器编辑走同一条 Environment.skybox 链）。
 *
 * <p>验收点：
 * <ul>
 *   <li>相机绕 Y 旋转：背景六向颜色随方向变化（cubemap 六面渐变），旋转连续过渡；</li>
 *   <li>不透明板挡住天空盒（深度测试：被遮区域不透出背景）；</li>
 *   <li>透明半 tint 板画在天空盒之上（3.5 位在 Transparent 前）；</li>
 *   <li><b>F 键摘挂 skybox</b>：摘除时 SKYBOX 视口即时回退纯色清屏（背景色）不报错，
 *       重挂即时恢复——覆盖「无天空盒回退 SOLID_COLOR」拍板；</li>
 *   <li>ESC 退出不崩。</li>
 * </ul>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.SkyboxDemo
 */
public final class SkyboxDemo extends JavelinEngineTestBase {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(SkyboxDemo.class);

    /** 单面边长（渐变面，同 CubemapTest 口径） */
    private static final int FACE_SIZE = 64;

    /** 六面基色（Vulkan cubemap layer 序：+X/-X/+Y/-Y/+Z/-Z） */
    private static final float[][] FACE_COLORS = {
            {1.0f, 0.0f, 0.0f},   // +X 红
            {1.0f, 0.0f, 1.0f},   // -X 品红
            {0.0f, 1.0f, 0.0f},   // +Y 绿
            {0.0f, 1.0f, 1.0f},   // -Y 青
            {0.0f, 0.0f, 1.0f},   // +Z 蓝
            {1.0f, 1.0f, 0.0f},   // -Z 黄
    };

    private RenderingSystem rs;
    private GameScene scene;
    private CubemapImage cubemapAsset;
    /** 环境设置组件（挂在 Environment 节点上；F 键摘挂 skybox 用） */
    private Environment environment;

    public SkyboxDemo(JavelinSetting setting) {
        super(setting);
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("Javelin - SkyboxDemo");
        new SkyboxDemo(setting).start();
    }

    @Override
    protected RenderingSystem createRenderingSystem() {
        rs = new RenderingSystem(device(), frameLoop());
        return rs;
    }

    @Override
    protected void onInitialize() {
        byte[] vs = TestShaders.compileResource("shaders/builtin_quad.vert", ShaderStage.VERTEX);
        byte[] fs = TestShaders.compileResource("shaders/builtin_quad.frag", ShaderStage.FRAGMENT);

        Mesh quad = createQuad();
        Texture2D texture = new Texture2D(TextureLoader.loadImage(TextureLoader.resolveDefaultTexturePath()));

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
        transparentMaterial.setColor("_Tint", 1f, 1f, 1f, 0.4f);

        GameScene scene = new GameScene("SkyboxDemo");
        addCameraNode(scene, "Main", 0f, 0f, 0f);
        // 不透明板：深度测试挡住被遮天空盒；透明半 tint 板：画在天空盒之上（3.5 位之后）
        addQuad(scene, quad, opaqueMaterial, -0.8f, 0.0f, -3.0f);
        addQuad(scene, quad, transparentMaterial, 0.9f, 0.4f, -2.0f);
        setScene(scene);
        this.scene = scene;

        // 资产路径挂载（3b：内存构建 CubemapImage → 环境设置组件字段 → 桥 sync 懒上传，
        // 不再直灌 RHI 纹理；2026-11-08 载体由 Scene.skybox 字段改为 Environment 组件，
        // 挂在名为 Environment 的普通节点上——编辑器里就是层级树一个可选中节点）
        cubemapAsset = createGradientCubemap();
        Node environmentNode = new Node();
        environmentNode.setName("Environment");
        environment = environmentNode.addComponent(Environment.class);
        scene.add(environmentNode);
        environment.setSkybox(cubemapAsset);
        log.info("skybox 资产已挂入环境设置节点: faceSize={}", cubemapAsset.getFaceSize());
    }

    /** F 键摘挂 skybox（经环境组件字段——桥下帧推送，同 3b 检查器编辑链路） */
    @Override
    protected void update() {
        super.update();
        if (Input.wasPressed(KeyCode.F)) {
            boolean attached = rs.skybox() != null;
            environment.setSkybox(attached ? null : cubemapAsset);
            log.info("F 键切换：skybox {}", attached ? "摘除（应回退纯色清屏）" : "重挂");
        }
    }

    /** 六面渐变 cubemap 资产（同 CubemapTest 口径：base 色沿对角线渐变） */
    private CubemapImage createGradientCubemap() {
        byte[] pixels = new byte[FACE_SIZE * FACE_SIZE * 6 * 4];
        int i = 0;
        for (int face = 0; face < 6; face++) {
            float[] base = FACE_COLORS[face];
            for (int y = 0; y < FACE_SIZE; y++) {
                for (int x = 0; x < FACE_SIZE; x++) {
                    float t = (x + y) / (float) (2 * (FACE_SIZE - 1));
                    pixels[i++] = (byte) (clamp01(base[0] + t) * 255);
                    pixels[i++] = (byte) (clamp01(base[1] + t) * 255);
                    pixels[i++] = (byte) (clamp01(base[2] + t) * 255);
                    pixels[i++] = (byte) 0xFF;
                }
            }
        }
        return new CubemapImage(FACE_SIZE, Format.R8G8B8A8_UNORM, pixels);
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }

    /** 相机节点：SKYBOX 清屏 + 自旋组件驱动绕 Y 旋转（六向背景验收） */
    private static void addCameraNode(GameScene scene, String name, float x, float y, float z) {
        Node node = new Node();
        node.setName(name);
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        Camera camera = node.addComponent(Camera.class);
        camera.setMain(true);
        camera.setClearFlags(ClearFlags.SKYBOX);
        // 回退可见：醒目背景色（摘 skybox 时 SKYBOX 视口按此色清屏）
        camera.setBackgroundColor(new Color(0.4f, 0.2f, 0.2f, 1f));
        CameraOrbiter orbiter = node.addComponent(CameraOrbiter.class);
        orbiter.speed = 0.5f;
        scene.add(node);
    }

    private static void addQuad(GameScene scene, Mesh quad, Material material,
                                float x, float y, float z) {
        Node node = new Node();
        node.setName("Quad");
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        renderer.setMesh(quad);
        renderer.setMaterial(material);
        scene.add(node);
    }

    /** 相机自旋组件：onUpdate 里累积绕 Y 旋转（Spinner 同构） */
    @Reflective
    public static class CameraOrbiter extends Component {
        public float speed;
        private final Quaternionf rotation = new Quaternionf();

        @Override
        protected void onUpdate(float dt) {
            rotation.rotateY(speed * dt);
            getNode().getTransform().setLocalRotation(rotation);
        }
    }

    /** 单位四边形（SceneRenderDemo 同款：POSITION + UV0，CCW 绕序） */
    private static Mesh createQuad() {
        Mesh mesh = new Mesh();
        mesh.setPositions(new float[]{
                -0.8f, 0.8f, 0.0f,
                -0.8f, -0.8f, 0.0f,
                0.8f, -0.8f, 0.0f,
                0.8f, 0.8f, 0.0f,
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
