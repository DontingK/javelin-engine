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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
     * Standard.shader 端到端（原 {@code Lit.shader}，2026-11-26 改名；批次③ + 批次④阴影段 +
     * 批次④-B G-Buffer 段 + REND-03 PBR 段）：注入 javelinLighting 三参函数（viewNormal/viewPos/
     * worldPos）+ clusterGrid/clusterIndices 声明 + binding4 shadowMap（sampler2DShadow）+
     * sunViewProj 扩块后全链编译通过；vs 四插值（视空间位置/法线/UV + 世界位置）；
     * GBuffer 段四张 MRT 输出（含 REND-03 的 material）编译通过。
     */
    @Test
    public void standardShaderEndToEnd() {
        String source = readResource("shader/Standard.shader");
        Shader shader = ShaderImporter.buildShader(source, "Standard.shader");

        assertEquals("Standard", shader.getShaderName());
        assertEquals(2, shader.getProperties().size());

        ShaderPass forward = shader.pass("Forward");
        assertValidSpirv(forward.getVertexSpirv());
        assertValidSpirv(forward.getFragmentSpirv());

        // 批次④-B：HYBRID 档不透明几何通道（多张 MRT 输出，与 Forward 同 shader 双 Pass）
        ShaderPass gbuffer = shader.pass("GBuffer");
        assertNotNull(gbuffer, "Standard.shader 缺 Pass GBuffer（HYBRID 档不透明几何通道）");
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
        // P4-06 B6 起主光阴影是三态混合（CSM / RT 可见性图 / 按 rtParams.x 混），
        // 强度档作用在最终 shadowFactor 上而非直接乘 pcfShadow
        assertTrue(injected.contains("shadow = mix(1.0, shadowFactor, lighting.shadowParams.z);"),
                "阴影强度未生效");
    }

    /**
     * REND-03：PBR 值档位注入（2026-11-26）。守卫三件事——
     * ① `PBR` 确实带出 GGX/Fresnel/Smith 公式块与 `javelinLightingPbr`；
     * ② rough/metal 是**参数**（不能引 `material`，否则全屏 Pass 与「声明在后」两种情况都会炸）；
     * ③ `LAMBERT` 不带 PBR 段（档位互不串，旧材质零变化）。
     */
    @Test
    public void lightingStageInjectsPbrStageOnlyForPbrModel() {
        String pbr = ShaderInjector.inject("#version 450\nvoid main() {}\n",
                java.util.List.of(), LightingModel.PBR, "pbr.frag", "fs");
        assertTrue(pbr.contains("float javelinD_GGX(float NoH, float rough)"), "缺 GGX 分布项");
        assertTrue(pbr.contains("float javelinG_Smith(float NoL, float NoV, float rough)"), "缺 Smith 遮蔽项");
        assertTrue(pbr.contains("vec3 javelinF_Schlick(vec3 f0, float VoH)"), "缺 Schlick 菲涅尔");
        assertTrue(pbr.contains("vec3 javelinLightingPbr(vec3 viewNormal, vec3 viewPos, vec3 worldPos,"),
                "缺 PBR 光照入口");
        assertTrue(pbr.contains("vec3 kd = (vec3(1.0) - F) * (1.0 - metal);"), "缺能量守恒拆分");
        assertTrue(pbr.contains("vec3 f0 = mix(vec3(0.04), albedo, metal);"), "缺金属度混合 F0");
        // 材质 fs 走 push 变体：PBR 段与 Lambert 段共用同一套 CSM/RT 阴影三态
        assertTrue(pbr.contains("shadow = mix(1.0, shadowFactor, lighting.shadowParams.z);"),
                "PBR 段未复用阴影三态混合");

        String lambert = ShaderInjector.inject("#version 450\nvoid main() {}\n",
                java.util.List.of(), LightingModel.LAMBERT, "lambert.frag", "fs");
        assertFalse(lambert.contains("javelinLightingPbr"), "LAMBERT 不应带 PBR 公式块");
        assertFalse(lambert.contains("javelinD_GGX"), "LAMBERT 不应带 GGX");
    }

    /**
     * P4-06 B9：反射是 PBR 的环境镜面来源。守卫四件事——UBO 新字段、binding15 声明、
     * Fresnel 环境份额（fEnv）与开关门控；`reflectParams.x = 0` 时整段不进（不采样未装配的图）。
     */
    @Test
    public void pbrStageConsumesRadianceReflection() {
        String pbr = ShaderInjector.inject("#version 450\nvoid main() {}\n",
                java.util.List.of(), LightingModel.PBR, "pbr.frag", "fs");
        assertTrue(pbr.contains("vec4 reflectParams;"), "LightingParamsBlock 缺 reflectParams 通道");
        assertTrue(pbr.contains("layout(set = 1, binding = 15) uniform sampler2D rtReflectMap;"),
                "缺 binding15 反射图声明");
        assertTrue(pbr.contains("if (lighting.reflectParams.x > 0.001) {"), "反射段缺开关门控");
        assertTrue(pbr.contains("vec3 fEnv = f0 + (max(vec3(1.0 - rough), f0) - f0)"),
                "缺粗糙度修正的 Fresnel 环境份额");
        assertTrue(pbr.contains("result += refl * fEnv"), "反射未计入结果（环境镜面缺失）");
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
