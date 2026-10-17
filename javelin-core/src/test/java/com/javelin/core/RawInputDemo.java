package com.javelin.core;

import com.javelin.ast.annotation.Reflective;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.core.input.MouseButton;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.PassState;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.SurfaceType;
import com.javelin.core.render.material.ShaderPass;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.Component;
import com.javelin.core.scene.GameScene;
import com.javelin.core.scene.Node;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.tool.shaderc.ShaderStage;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 原始层输入验收 Demo（与 {@link InputCameraDemo} 成对照样例）：
 * <b>全程零 bind / 零动作名查询</b>——移动/视角/滚轮/边沿/光标位置全部
 * 经 {@link Input} 原始层直查（isDown/isMouseDown/wasPressed/mouseDelta/scrollDelta/mouseXY）。
 *
 * <p>与 InputCameraDemo（动作层）同场景同逻辑，两个 Demo 的 [perf]
 * 可做动作表解析开销的 A/B 对照（预期差异不可见：每次动作查询仅多
 * 一次 HashMap 解析 + 布尔数组探针）。</p>
 *
 * <p>验收点（用户运行）：</p>
 * <ul>
 *   <li>WASD/方向键移动、QE 升降——Input.isDown(KeyCode) 直查；</li>
 *   <li>Shift 疾行（isDown 组合直查）、滚轮调速度（scrollDeltaY 连续通道）；</li>
 *   <li>按住右键拖拽转视角——isMouseDown(MouseButton) + mouseDeltaX/Y 增量通道
 *       （rotationYXZ：拖右看右、拖下看下，pitch 限 ±89°）；</li>
 *   <li>F 打印光标窗口坐标（wasPressed 原始边沿 + mouseX/Y 位置通道）；</li>
 *   <li>SPACE 边沿翻转移动方向（wasPressed 恰一帧有效——按住不放不重复触发）。</li>
 * </ul>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.RawInputDemo</p>
 */
public final class RawInputDemo extends JavelinEngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(RawInputDemo.class);

    private static final float ROTATE_SENS = 0.003f;   // 弧度/像素
    private static final float PITCH_LIMIT = (float) Math.toRadians(89.0);

    public RawInputDemo(JavelinSetting setting) {
        super(setting);
        perfLogEnabled = true;   // 与 InputCameraDemo 对照 [perf]
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("Javelin - RawInputDemo");
        setting.setVsync(false);
        new RawInputDemo(setting).start();
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
        // 同一 Shader 双面型（拍板 2026-10-17 方案 B）：透明性由材质 surfaceType 裁决，
        // Blend/ZWrite 由渲染侧按面型覆盖，不再各建一份带混合态的 Shader
        Shader quadShader = Shader.builder("BuiltinQuad")
                .property(MaterialProperty.color("_Tint", "Tint", 1f, 1f, 1f, 1f))
                .property(MaterialProperty.texture2D("_MainTex", "Main Texture", "white"))
                .pass(new ShaderPass("Forward", vs, fs, new PassState()))
                .build();
        Material opaqueMaterial = new Material(quadShader);
        Material transparentMaterial = new Material(quadShader);
        transparentMaterial.setSurfaceType(SurfaceType.TRANSPARENT);
        transparentMaterial.setColor("_Tint", 1f, 0.6f, 0.2f, 0.5f);

        GameScene scene = new GameScene("RawInputDemo");

        // 相机节点 + RawFlyCamera（移动全程原始层直查）
        Node cameraNode = new Node();
        cameraNode.setName("Main");
        cameraNode.getTransform().setLocalPosition(new Vector3f(0f, 1.5f, 6f));
        Camera camera = cameraNode.addComponent(Camera.class);
        camera.setMain(true);
        cameraNode.addComponent(RawFlyCamera.class);
        scene.add(cameraNode);

        // 参照物：远近两组不透明 + 透明遮挡片（静态，位移感知用）
        addQuad(scene, quad, opaqueMaterial, 0.0f, 0.0f, -2.0f);
        addQuad(scene, quad, opaqueMaterial, -2.5f, 0.0f, -6.0f);
        addQuad(scene, quad, opaqueMaterial, 2.5f, 0.0f, -9.0f);
        addQuad(scene, quad, transparentMaterial, 0.6f, 0.4f, -1.0f);

        setScene(scene);
    }

    /**
     * 原始层飞行相机：全部查询走 Input 原始通道，无任何 bind/动作名——
     * 对照 {@code InputCameraDemo.FlyCamera}（动作层版本），行为应完全一致。
     */
    @Reflective
    public static class RawFlyCamera extends Component {

        private float yaw = (float) Math.PI;   // 相机惯例 +Z 前向：位置 +Z 侧朝 -Z 看即 yaw=π
        private float pitch;
        private float speed = 3f;

        @Override
        protected void onUpdate(float dt) {
            // 右键按住拖拽转视角（rotationYXZ = Ry·Rx·Rz：拖右看右、拖下看下）
            if (Input.isMouseDown(MouseButton.RIGHT)) {
                yaw -= Input.mouseDeltaX() * ROTATE_SENS;
                pitch += Input.mouseDeltaY() * ROTATE_SENS;
                if (pitch > PITCH_LIMIT) {
                    pitch = PITCH_LIMIT;
                } else if (pitch < -PITCH_LIMIT) {
                    pitch = -PITCH_LIMIT;
                }
            }
            Quaternionf rotation = new Quaternionf().rotationYXZ(yaw, pitch, 0f);
            Transform t = getNode().getTransform();
            t.setLocalRotation(rotation);

            // 滚轮调移动速度（连续通道直查）
            float scroll = Input.scrollDeltaY();
            if (scroll != 0f) {
                speed = Math.max(0.5f, speed + scroll * 0.5f);
            }

            // Shift 疾行（持续态直查）
            float sprint = Input.isDown(KeyCode.LEFT_SHIFT) ? 2f : 1f;

            // F 打印光标窗口坐标（原始边沿 + 位置通道）
            if (Input.wasPressed(KeyCode.F)) {
                log.info("[raw-input] cursor ({}, {})", Input.mouseX(), Input.mouseY());
            }

            // SPACE 边沿翻转移动方向（恰一帧有效，按住不放不重复触发）
            if (Input.wasPressed(KeyCode.SPACE)) {
                speed = -speed;
            }

            // WASD/方向键沿视线系移动，QE 沿世界 Y 升降——全部 isDown 直查
            Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
            Vector3f right = new Vector3f(1f, 0f, 0f).rotate(rotation);
            Vector3f move = new Vector3f();
            if (Input.isDown(KeyCode.W) || Input.isDown(KeyCode.UP)) {
                move.add(forward);
            }
            if (Input.isDown(KeyCode.S) || Input.isDown(KeyCode.DOWN)) {
                move.sub(forward);
            }
            if (Input.isDown(KeyCode.D) || Input.isDown(KeyCode.RIGHT)) {
                move.add(right);
            }
            if (Input.isDown(KeyCode.A) || Input.isDown(KeyCode.LEFT)) {
                move.sub(right);
            }
            if (Input.isDown(KeyCode.E)) {
                move.y += 1f;
            }
            if (Input.isDown(KeyCode.Q)) {
                move.y -= 1f;
            }
            if (move.lengthSquared() > 1e-6f) {
                Vector3f position = t.getLocalPosition();
                t.setLocalPosition(move.normalize().mul(speed * sprint * dt).add(position));
            }
        }
    }

    private void addQuad(GameScene scene, Mesh quad, Material material,
                         float x, float y, float z) {
        Node node = new Node();
        node.setName("Quad");
        node.getTransform().setLocalPosition(new Vector3f(x, y, z));
        MeshRenderer renderer = node.addComponent(MeshRenderer.class);
        renderer.setMesh(quad);
        renderer.setMaterial(material);
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
