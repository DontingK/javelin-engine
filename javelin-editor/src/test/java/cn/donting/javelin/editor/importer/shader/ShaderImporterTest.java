package cn.donting.javelin.editor.importer.shader;

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

    private static void assertValidSpirv(byte[] spirv) {
        assertTrue(spirv != null && spirv.length > 100, "SPIR-V 产物为空");
        byte[] magic = {0x03, 0x02, 0x23, 0x07};
        assertArrayEquals(magic, new byte[]{spirv[0], spirv[1], spirv[2], spirv[3]},
                "SPIR-V magic number 不合法");
    }
}
