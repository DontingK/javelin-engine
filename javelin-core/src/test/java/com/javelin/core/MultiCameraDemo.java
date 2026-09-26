package com.javelin.core;

import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.render.RenderCamera;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.PassState;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.ShaderPass;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.core.render.texture.TextureLoader;
import com.javelin.core.scene.Scene;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.tool.shaderc.ShaderStage;

/**
 * 多相机多物体 Demo（RHI 19 节验收载体 = 引擎实现计划阶段③终点）：
 * 左右分屏两个相机各自渲染旋转四边形（含一个透明混合物体）。
 *
 * <p>验收点：
 * <ul>
 *   <li>左半屏 Main 相机、右半屏 Side 相机，各自视口区域清屏（矩形外互不覆盖）；</li>
 *   <li>不透明四边形各自绕 Y 轴旋转（速度不同），透明四边形在前方叠加混合、由远及近排序正确；</li>
 *   <li>物体不颠倒（Y 翻转入投影 + 正高度视口 + CCW 绕序配对）；</li>
 *   <li>resize 不崩溃、深度纹理按新尺寸换绑；ESC 退出。</li>
 * </ul>
 *
 * <p>直注册模式（无场景）：材质走材质桥路径（set 2 MaterialBlock + 贴图槽，
 * 透明桶由材质 Blend 态得出）。运行：gradlew :javelin-core:runTest
 * -DmainClass=com.javelin.core.MultiCameraDemo
 */
public final class MultiCameraDemo extends JavelinEngine {

    /** 旋转物体：RenderObject + 摆放位置 + 角速度（弧度每秒）+ 累积角 */
    private static final class Spinner {
        final RenderObject object;
        final float x;
        final float y;
        final float z;
        final float speed;
        float angle;

        Spinner(RenderObject object, float x, float y, float z, float speed) {
            this.object = object;
            this.x = x;
            this.y = y;
            this.z = z;
            this.speed = speed;
        }
    }

    private final Spinner[] spinners;

    private RenderCamera mainCam;
    private RenderCamera sideCam;

    public MultiCameraDemo(JavelinSetting setting) {
        super(setting);
        spinners = new Spinner[3];
    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setTitle("Javelin - MultiCameraDemo");
        new MultiCameraDemo(setting).start();
    }

    @Override
    protected RenderingSystem createRenderingSystem() {
        return new RenderingSystem(device(), frameLoop());
    }

    @Override
    protected Scene loadScene() {
        return null;
    }

    @Override
    protected void onInitialize() {
        // 着色程序：test resources 的 GLSL → shaderc → SPIR-V → ShaderPass（.shader 编译产物替身，
        // （core 主代码不含着色器源码，用户拍板 2026-09-25）
        byte[] vs = TestShaders.compileResource("shaders/builtin_quad.vert", ShaderStage.VERTEX);
        byte[] fs = TestShaders.compileResource("shaders/builtin_quad.frag", ShaderStage.FRAGMENT);

        Mesh quad = createQuad();
        Texture2D texture = TextureLoader.loadImage(TextureLoader.resolveDefaultTexturePath());

        // 不透明材质：Forward Pass，Blend Off（ZWrite On 默认）
        Shader opaqueShader = Shader.builder("BuiltinQuad")
                .property(MaterialProperty.color("_Tint", "Tint", 1f, 1f, 1f, 1f))
                .property(MaterialProperty.texture2D("_MainTex", "Main Texture", "white"))
                .pass(new ShaderPass("Forward", vs, fs, new PassState()))
                .build();
        Material opaqueMaterial = new Material(opaqueShader);
        opaqueMaterial.setTexture("_MainTex", texture);

        // 透明材质：独立 Shader（PSO 键 = shader+layout），Blend On + ZWrite Off
        Shader transparentShader = Shader.builder("BuiltinQuadTransparent")
                .property(MaterialProperty.color("_Tint", "Tint", 1f, 1f, 1f, 1f))
                .property(MaterialProperty.texture2D("_MainTex", "Main Texture", "white"))
                .pass(new ShaderPass("Forward", vs, fs, new PassState()
                        .setDepthWrite(false)
                        .setBlend(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)))
                .build();
        Material transparentMaterial = new Material(transparentShader);
        transparentMaterial.setTexture("_MainTex", texture);
        transparentMaterial.setColor("_Tint", 1f, 1f, 1f, 0.5f);

        mainCam = new RenderCamera("Main");
        mainCam.position().set(0.0f, 1.0f, 6.0f);
        sideCam = new RenderCamera("Side");
        sideCam.position().set(5.0f, 2.5f, 6.0f);
        sideCam.target().set(0.0f, 0.0f, -1.0f);
        renderingSystem().addCamera(mainCam);
        renderingSystem().addCamera(sideCam);

        // 两个不透明 + 一个透明（前方，验证混合与由远及近排序）
        spinners[0] = register(new RenderObject(quad, opaqueMaterial, true), -1.2f, 0.0f, -2.0f, 1.0f);
        spinners[1] = register(new RenderObject(quad, opaqueMaterial, true), 1.2f, 0.0f, -3.5f, 1.6f);
        spinners[2] = register(new RenderObject(quad, transparentMaterial, false), 0.0f, 0.2f, -1.2f, -0.7f);

        layoutCameras();
    }

    @Override
    protected void update() {
        float dt = Time.getDeltaTime();
        for (Spinner s : spinners) {
            if (s == null) {
                continue;
            }
            s.angle += s.speed * dt;
            // T * R_y：原地绕自身 Y 轴旋转，再平移到摆放位
            s.object.world().identity().translate(s.x, s.y, s.z).rotateY(s.angle);
        }
        layoutCameras();
    }

    private Spinner register(RenderObject object, float x, float y, float z, float speed) {
        renderingSystem().addRenderer(object);
        return new Spinner(object, x, y, z, speed);
    }

    /** 分屏视口跟随窗口尺寸（resize 时每帧重算） */
    private void layoutCameras() {
        int w = window().width();
        int h = window().height();
        int half = w / 2;
        mainCam.viewport(0, 0, half, h);
        sideCam.viewport(half, 0, w - half, h);
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
