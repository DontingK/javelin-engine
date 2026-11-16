package cn.donting.javelin.editor.importer.shader;

import cn.donting.javelin.core.render.material.LightingModel;
import cn.donting.javelin.core.render.material.MaterialPropertyType;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.ShaderPass;
import cn.donting.javelin.core.render.material.PassState;
import cn.donting.javelin.rhi.types.BlendFactor;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ShaderImporter 端到端单测：.shader 源码 → 解析 → #version 后注入 →
 * shaderc 编译 SPIR-V → 装配完整 Shader（需要 shaderc natives，走真实编译）。
 *
 * <p>验收点：v0.13 测试资源 default.shader 走通全链、属性/Pass/SPIR-V 齐全、
 * 透明 Pass Blend 态正确、SPIR-V magic number 合法。</p>
 */
public class ShaderImporterTest {

    private static String readTestShader() {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream("shader/default.shader")) {
            if (in == null) {
                throw new IllegalStateException("classpath 资源不存在: shader/default.shader");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试着色器失败", e);
        }
    }

    private static String readResource(String path) {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        try (InputStream in = cl.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("classpath 资源不存在: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("读取测试着色器失败: " + path, e);
        }
    }

    @Test
    public void buildShaderEndToEnd() {
        String source = readTestShader();
        Shader shader = ShaderImporter.buildShader(source, "default.shader");

        assertEquals("TestDefault", shader.getShaderName());
        assertEquals(5, shader.getProperties().size());
        assertEquals(MaterialPropertyType.COLOR, shader.getProperties().get(0).getType());
        assertEquals(MaterialPropertyType.RANGE, shader.getProperties().get(1).getType());
        assertEquals(MaterialPropertyType.TEXTURE_2D, shader.getProperties().get(2).getType());
        assertEquals(MaterialPropertyType.TEXTURE_2D, shader.getProperties().get(3).getType());
        assertEquals(MaterialPropertyType.FLOAT, shader.getProperties().get(4).getType());

        assertEquals(2, shader.getPasses().size());

        // 不透明 Pass：SPIR-V 非空 + magic number（0x07230203，小端 03 02 23 07）
        ShaderPass forward = shader.pass("Forward");
        assertValidSpirv(forward.getVertexSpirv());
        assertValidSpirv(forward.getFragmentSpirv());
        assertTrue(!forward.getState().isBlendEnabled());

        // 透明 Pass：Blend 态正确（引擎透明分桶 = Blend 态）
        ShaderPass transparent = shader.pass("Transparent");
        assertValidSpirv(transparent.getVertexSpirv());
        assertValidSpirv(transparent.getFragmentSpirv());
        PassState state = transparent.getState();
        assertTrue(state.isBlendEnabled());
        assertEquals(BlendFactor.SRC_ALPHA, state.getSrcColor());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, state.getDstColor());
        assertTrue(!state.isDepthWrite());

        // Pass 名即阶段归属（严格制，2026-10-17）：按名直查，无回退
        assertEquals("Forward", shader.pass("Forward").getName());
    }

    /**
     * Lit.shader 端到端（批次③ + 批次④阴影段 + 批次④-B G-Buffer 段）：注入
     * javelinLighting 三参函数（viewNormal/viewPos/worldPos）+ clusterGrid/clusterIndices
     * 声明 + binding4 shadowMap（sampler2DShadow）+ sunViewProj 扩块后全链编译通过；
     * vs 四插值（视空间位置/法线/UV + 世界位置）；GBuffer 段三张 MRT 输出编译通过。
     */
    @Test
    public void litShaderEndToEnd() {
        String source = readResource("shader/Lit.shader");
        Shader shader = ShaderImporter.buildShader(source, "Lit.shader");

        assertEquals("Lit", shader.getShaderName());
        assertEquals(2, shader.getProperties().size());

        ShaderPass forward = shader.pass("Forward");
        assertValidSpirv(forward.getVertexSpirv());
        assertValidSpirv(forward.getFragmentSpirv());

        // 批次④-B：HYBRID 档不透明几何通道（三张 MRT 输出，与 Forward 同 shader 双 Pass）
        ShaderPass gbuffer = shader.pass("GBuffer");
        assertNotNull(gbuffer, "Lit.shader 缺 Pass GBuffer（HYBRID 档不透明几何通道）");
        assertValidSpirv(gbuffer.getVertexSpirv());
        assertValidSpirv(gbuffer.getFragmentSpirv());
    }

    /**
     * 注入段静态断言（四期阴影质量分级 §3.3/§3.4）：光照段声明含 shadowParams 通道、
     * 含 PCF 单 tap 辅助函数与两档核（4/9 tap），且硬影/软影分支共用同一入口。
     * 真实编译覆盖归 {@link #litShaderEndToEnd}（注入后的 fs 走 shaderc）。
     */
    @Test
    public void lightingStageInjectsPcfShadowStage() {
        String injected = ShaderInjector.inject("#version 450\nvoid main() {}\n",
                java.util.List.of(), LightingModel.LAMBERT, "pcf.frag", "fs");
        assertTrue(injected.contains("vec4 shadowParams;"),
                "光照段声明缺 shadowParams（阴影品质档/强度/半径/参考深度偏移通道）");
        // 四期 CSM：多级联矩阵 + 切分 + atlas 参数 + 逐级联反深度
        assertTrue(injected.contains("mat4 sunViewProj[4];"), "缺级联矩阵数组 sunViewProj[4]");
        assertTrue(injected.contains("vec4 cascadeSplits;"), "缺级联切分通道");
        assertTrue(injected.contains("vec4 shadowParams2;"), "缺 atlas 参数通道 shadowParams2");
        assertTrue(injected.contains("vec4 cascadeInvZ;"), "缺逐级联反深度通道 cascadeInvZ");
        assertTrue(injected.contains("float javelinShadowTapC("),
                "缺单 tap 深度比较辅助函数（象限夹取）");
        assertTrue(injected.contains("float javelinCascadeShadow("),
                "缺单级联采样函数");
        assertTrue(injected.contains("lighting.shadowParams.x"),
                "阴影采样未消费品质档编码");
        assertTrue(injected.contains("return s / 16.0;"),
                "缺 4 tap 复现 3×3 核（软影低档）");
        assertTrue(injected.contains("return s / 144.0;"),
                "缺 9 tap 复现 5×5 核（软影高档）");
        assertTrue(injected.contains("shadow = mix(1.0, pcfShadow, lighting.shadowParams.z);"),
                "阴影强度未生效");
    }

    /**
     * 注入段静态断言（四期阴影质量分级 §3.6）：per-object 接收阴影标志按变体承载——
     * 材质 fs 走 push（{@code pc.receiveShadow}，声明含该字段）、全屏走 G-Buffer
     * {@code albedo.a}（{@code texelFetch} 取回）；{@code javelinLighting} 采样分支门控
     * 该标志。真实编译覆盖归 {@link #litShaderEndToEnd}（注入后 fs 走 shaderc）。
     */
    @Test
    public void lightingStageInjectsReceiveShadowFlag() {
        // 材质 fs（FORWARD 档）：push 变体——声明 PushBlock 含 receiveShadow + 门控生效
        String material = ShaderInjector.inject("#version 450\nvoid main() {}\n",
                java.util.List.of(), LightingModel.LAMBERT, "recv.frag", "fs");
        assertTrue(material.contains("float receiveShadow;"),
                "PushBlock 未扩 receiveShadow 字段（FORWARD 档承载）");
        assertTrue(material.contains("return pc.receiveShadow;"),
                "材质 fs 未注入 push 变体 javelinReceiveShadow");
        assertTrue(material.contains("&& javelinReceiveShadow() > 0.5)"),
                "阴影采样分支未门控 per-object 接收标志");
        // 全屏光照（HYBRID 档）：G-Buffer 变体
        String fullscreen = ShaderInjector.injectLightingStage(
                "#version 450\nvoid main() {}\n", "lighting.frag");
        assertTrue(fullscreen.contains("texelFetch(gbufferAlbedo"),
                "全屏未注入 G-Buffer 取回变体 javelinReceiveShadow（HYBRID 档承载）");
    }

    private static void assertValidSpirv(byte[] spirv) {
        assertTrue(spirv != null && spirv.length > 100, "SPIR-V 产物为空");
        byte[] magic = {0x03, 0x02, 0x23, 0x07};
        assertArrayEquals(magic, new byte[]{spirv[0], spirv[1], spirv[2], spirv[3]},
                "SPIR-V magic number 不合法");
    }
}
