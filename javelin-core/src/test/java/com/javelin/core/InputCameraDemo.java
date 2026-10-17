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
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.scene.component.MeshRenderer;
import com.javelin.core.scene.component.Transform;
import com.javelin.core.settings.AntialiasingMode;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.tool.shaderc.ShaderStage;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 输入子系统验收 Demo（roadmap 二期①）：输入驱动相机在场景里移动，
 * 全链走 InputSubsystem（GLFW 回调 → 双缓冲边沿 → Input 门面，bind/查询合流门面）。
 *
 * <p>验收点（用户运行）：</p>
 * <ul>
 *   <li>WASD/方向键移动（Input.bind 布尔动作 + 键组轴），滚轮调移动速度（scroll 连续源轴）；</li>
 *   <li>按住右键拖拽转视角——右键走混绑动作（MouseButton 入绑）、光标增量走
 *       mouseDeltaX/Y 连续源轴（rotationYXZ：拖右看右、拖下看下，pitch 限 ±89°）；</li>
 *   <li>SPACE 边沿动作开关中央四边形旋转（justPressed 恰一帧有效——连按不丢、按住不重复触发）；</li>
 *   <li>原始层直查验收：按住 Shift 疾行（Input.isDown(KeyCode) 不走动作表）、
 *       F 打印光标窗口坐标（Input.wasPressed 原始边沿 + mouseX/Y 位置通道）；</li>
 *   <li>窗口失焦后再聚焦，之前按住的键不"卡键"（onFocusChanged 清持续态）；</li>
 *   <li>ESC 不再关窗（便利逻辑已移除，关窗用系统关闭按钮）。</li>
 * </ul>
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.InputCameraDemo</p>
 */
public final class InputCameraDemo extends JavelinEngineTestBase {

    private static final Logger log = LoggerFactory.getLogger(InputCameraDemo.class);

    private static final float ROTATE_SENS = 0.003f;   // 弧度/像素
    private static final float PITCH_LIMIT = (float) Math.toRadians(89.0);

    public InputCameraDemo(JavelinSetting setting) {
        super(setting);
        perfLogEnabled = true;   // 每秒 [perf] 观测，逻辑下沉 JavelinEngineTestBase.update()
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("Javelin - InputCameraDemo");
        setting.setVsync(true);
        setting.setMaxFps(180);
        setting.setAntialiasing(AntialiasingMode.MSAA_8);
        new InputCameraDemo(setting).start();
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

        GameScene scene = new GameScene("InputCameraDemo");

        // 相机节点 + FlyCamera（输入驱动）
        Node cameraNode = new Node();
        cameraNode.setName("Main");
        cameraNode.getTransform().setLocalPosition(new Vector3f(0f, 1.5f, 6f));
        Camera camera = cameraNode.addComponent(Camera.class);
        camera.setMain(true);
        cameraNode.addComponent(FlyCamera.class);
        scene.add(cameraNode);

        // 参照物：中央自旋四边形（SPACE 开关）+ 远近两组不透明 + 透明遮挡片
        addQuad(scene, quad, opaqueMaterial, 0.0f, 0.0f, -2.0f, 0.8f);
        addQuad(scene, quad, opaqueMaterial, -2.5f, 0.0f, -6.0f, 0f);
        addQuad(scene, quad, opaqueMaterial, 2.5f, 0.0f, -9.0f, 0f);
        addQuad(scene, quad, transparentMaterial, 0.6f, 0.4f, -1.0f, 0f);

        setScene(scene);
    }

    /**
     * 飞行相机组件：onAwake 里 Input.bind 配置动作/轴，onUpdate 经 Input 查询。
     * 每帧被 scene.update 驱动（mainLoop：earlyUpdate 已完成本帧输入翻转）。
     */
    @Reflective
    public static class FlyCamera extends Component {

        private float yaw = (float) Math.PI;   // 相机惯例 +Z 前向：位置 +Z 侧朝 -Z 看即 yaw=π
        private float pitch;
        private float speed = 3f;

        @Override
        protected void onAwake() {
            // 动作绑定（Input 唯一使用方式，全局单表）
            Input.bind("MoveForward", KeyCode.W, KeyCode.UP);
            Input.bind("MoveBack", KeyCode.S, KeyCode.DOWN);
            Input.bind("MoveUp", KeyCode.E);
            Input.bind("MoveDown", KeyCode.Q);
            Input.bindAxis("MoveX")
                    .positive(KeyCode.D, KeyCode.RIGHT)
                    .negative(KeyCode.A, KeyCode.LEFT);
            Input.bind("LookAround", MouseButton.RIGHT);   // 鼠标键绑定源（混绑验收）
            Input.bindAxis("LookX").mouseDeltaX();          // 连续源：光标增量
            Input.bindAxis("LookY").mouseDeltaY();
            Input.bindAxis("Zoom").scroll();                // 连续源：滚轮增量
        }

        @Override
        protected void onDestroy() {
            // 全局单表谁绑谁解：本组件离开场景时清掉自己的动作名
            Input.unbind("MoveForward");
            Input.unbind("MoveBack");
            Input.unbind("MoveUp");
            Input.unbind("MoveDown");
            Input.unbind("MoveX");
            Input.unbind("LookAround");
            Input.unbind("LookX");
            Input.unbind("LookY");
            Input.unbind("Zoom");
        }

        @Override
        protected void onUpdate(float dt) {
            // 右键按住拖拽转视角：门控走混绑动作，增量走连续源轴
            // （rotationYXZ = Ry·Rx·Rz：拖右看右、拖下看下）
            if (Input.isDown("LookAround")) {
                yaw -= Input.axis("LookX") * ROTATE_SENS;
                pitch += Input.axis("LookY") * ROTATE_SENS;
                if (pitch > PITCH_LIMIT) {
                    pitch = PITCH_LIMIT;
                } else if (pitch < -PITCH_LIMIT) {
                    pitch = -PITCH_LIMIT;
                }
            }
            Quaternionf rotation = new Quaternionf().rotationYXZ(yaw, pitch, 0f);
            Transform t = getNode().getTransform();
            t.setLocalRotation(rotation);

            // 滚轮调移动速度（scroll 连续源轴验收）
            float zoom = Input.axis("Zoom");
            if (zoom != 0f) {
                speed = Math.max(0.5f, speed + zoom * 0.5f);
            }

            // 原始层验收①：F 打印光标窗口坐标（原始边沿 + 位置通道，不过动作表）
            if (Input.wasPressed(KeyCode.F)) {
                log.info("[raw-input] cursor ({}, {})", Input.mouseX(), Input.mouseY());
            }

            // 原始层验收②：Shift 疾行（isDown 直查物理键）
            float sprint = Input.isDown(KeyCode.LEFT_SHIFT) ? 2f : 1f;

            // WASD 沿视线系移动，QE 沿世界 Y 升降
            Vector3f forward = new Vector3f(0f, 0f, 1f).rotate(rotation);
            Vector3f right = new Vector3f(1f, 0f, 0f).rotate(rotation);
            Vector3f move = new Vector3f();
            if (Input.isDown("MoveForward")) {
                move.add(forward);
            }
            if (Input.isDown("MoveBack")) {
                move.sub(forward);
            }
            float x = Input.axis("MoveX");
            if (x != 0f) {
                move.add(right.x * x, right.y * x, right.z * x);
            }
            if (Input.isDown("MoveUp")) {
                move.y += 1f;
            }
            if (Input.isDown("MoveDown")) {
                move.y -= 1f;
            }
            if (move.lengthSquared() > 1e-6f) {
                Vector3f position = t.getLocalPosition();
                t.setLocalPosition(move.normalize().mul(speed * sprint * dt).add(position));
            }
        }
    }

    /** 自旋组件：SPACE 边沿翻转旋转方向（wasPressed 恰一帧有效，按住不放不重复触发） */
    @Reflective
    public static class Spinner extends Component {
        public float speed;
        private final Quaternionf rotation = new Quaternionf();

        @Override
        protected void onUpdate(float dt) {
            if (Input.wasPressed(KeyCode.SPACE)) {
                // 边沿验收：这里必须每按一次只触发一帧（按住不放不重复触发）
                toggleSpin();
            }
            rotation.rotateY(speed * dt);
            getNode().getTransform().setLocalRotation(rotation);
        }

        private void toggleSpin() {
            // 简化验收：翻转自身旋转方向即可观察
            speed = -speed;
        }
    }

    private Spinner addQuad(GameScene scene, Mesh quad, Material material,
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
        return spinner;
    }

    @Override
    protected Scene loadScene() {
        return null;
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
