package com.javelin.core.render.material;

import com.javelin.core.render.shader.ShaderAST;
import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Shader / ShaderPass / ShaderProperty 资产序列化单元测试。
 */
class ShaderSerializeTest {

    @TempDir
    Path tempDir;

    @SuppressWarnings("unchecked")
    private <T extends com.javelin.core.serialize.Serializable> T writeAndRead(T obj, String fileName) throws Exception {
        File file = tempDir.resolve(fileName).toFile();
        new BinaryExporter(file).export(obj);
        return (T) new BinaryImporter(file).importData((Class<T>) obj.getClass());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ShaderProperty
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void propertyAllTypesRoundTrip() throws Exception {
        Shader shader = new Shader();
        shader.addProperty(ShaderProperty.builder()
            .name("_FloatVal").displayName("浮点").type(ShaderPropertyType.FLOAT)
            .defaultValue(1.5f).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_IntVal").displayName("整数").type(ShaderPropertyType.INT)
            .defaultValue(3).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_BoolVal").displayName("开关").type(ShaderPropertyType.BOOL)
            .defaultValue(Boolean.TRUE).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_Vec2").displayName("向量2").type(ShaderPropertyType.VECTOR2)
            .defaultValue(new float[]{0.5f, 0.5f}).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_Vec3").displayName("向量3").type(ShaderPropertyType.VECTOR3)
            .defaultValue(new float[]{1f, 2f, 3f}).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_Vec4").displayName("向量4").type(ShaderPropertyType.VECTOR4)
            .defaultValue(new float[]{1f, 2f, 3f, 4f}).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_Color").displayName("颜色").type(ShaderPropertyType.COLOR)
            .defaultValue(new float[]{1f, 0f, 0f, 1f}).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_MainTex").displayName("主纹理").type(ShaderPropertyType.TEXTURE)
            .defaultValue("white").build());

        Shader r = writeAndRead(shader, "props.dat");

        assertEquals(8, r.getProperties().size());
        assertEquals(1.5f, (Float) r.getProperty("_FloatVal").getDefaultValue());
        assertEquals(3, (Integer) r.getProperty("_IntVal").getDefaultValue());
        assertEquals(Boolean.TRUE, r.getProperty("_BoolVal").getDefaultValue());
        assertArrayEquals(new float[]{0.5f, 0.5f}, (float[]) r.getProperty("_Vec2").getDefaultValue());
        assertArrayEquals(new float[]{1f, 2f, 3f}, (float[]) r.getProperty("_Vec3").getDefaultValue());
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, (float[]) r.getProperty("_Vec4").getDefaultValue());
        assertArrayEquals(new float[]{1f, 0f, 0f, 1f}, (float[]) r.getProperty("_Color").getDefaultValue());
        assertEquals("white", r.getProperty("_MainTex").getDefaultValue());
        assertEquals("颜色", r.getProperty("_Color").getDisplayName());
    }

    @Test
    void propertyRangeKeepsBounds() throws Exception {
        Shader shader = new Shader();
        shader.addProperty(ShaderProperty.builder()
            .name("_Metallic").displayName("金属度").type(ShaderPropertyType.RANGE)
            .defaultValue(0.5f).rangeMin(0.0f).rangeMax(1.0f).build());

        Shader r = writeAndRead(shader, "range.dat");

        ShaderProperty p = r.getProperty("_Metallic");
        assertEquals(ShaderPropertyType.RANGE, p.getType());
        assertEquals(0.5f, (Float) p.getDefaultValue());
        assertNotNull(p.getRangeMin());
        assertNotNull(p.getRangeMax());
        assertEquals(0.0f, p.getRangeMin());
        assertEquals(1.0f, p.getRangeMax());
    }

    @Test
    void propertyWithoutDefaultOrRangeRoundTrip() throws Exception {
        Shader shader = new Shader();
        shader.addProperty(ShaderProperty.builder()
            .name("_Color").displayName("颜色").type(ShaderPropertyType.COLOR).build());

        Shader r = writeAndRead(shader, "nodefault.dat");

        ShaderProperty p = r.getProperty("_Color");
        assertNull(p.getDefaultValue());
        assertNull(p.getRangeMin());
        assertNull(p.getRangeMax());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  ShaderPass
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void shaderPassFullRoundTrip() throws Exception {
        ShaderPass pass = new ShaderPass();
        pass.setName("Forward");
        pass.setVertexBytecode(new byte[]{0x03, 0x02, 0x23, 0x07, (byte) 0xFF});
        pass.setFragmentBytecode(new byte[]{0x03, 0x02, 0x23, 0x07, 0x11, 0x22});
        pass.setZWrite(false);
        pass.setZTest(ShaderAST.CompareFunction.Less);
        pass.setCullMode(ShaderAST.CullMode.None);
        pass.setRenderPath(ShaderAST.RenderPath.Forward);
        pass.setRenderLayer(ShaderAST.RenderLayer.Transparent);
        pass.setBlend(new BlendDesc(true, ShaderAST.BlendFactor.SrcAlpha, ShaderAST.BlendFactor.OneMinusSrcAlpha));
        pass.addTextureSlot("_MainTex");
        pass.addTextureSlot("_NormalMap");
        pass.addTextureSlot("_MainTex"); // 重复追加应被忽略

        Shader shader = new Shader();
        shader.addPass(pass);

        Shader r = writeAndRead(shader, "pass.dat");

        ShaderPass p = r.getPasses().get(0);
        assertEquals("Forward", p.getName());
        assertArrayEquals(new byte[]{0x03, 0x02, 0x23, 0x07, (byte) 0xFF}, p.getVertexBytecode());
        assertArrayEquals(new byte[]{0x03, 0x02, 0x23, 0x07, 0x11, 0x22}, p.getFragmentBytecode());
        assertFalse(p.isZWrite());
        assertEquals(ShaderAST.CompareFunction.Less, p.getZTest());
        assertEquals(ShaderAST.CullMode.None, p.getCullMode());
        assertEquals(ShaderAST.RenderPath.Forward, p.getRenderPath());
        assertEquals(ShaderAST.RenderLayer.Transparent, p.getRenderLayer());
        assertNotNull(p.getBlend());
        assertTrue(p.getBlend().isEnabled());
        assertEquals(ShaderAST.BlendFactor.SrcAlpha, p.getBlend().getSrcFactor());
        assertEquals(ShaderAST.BlendFactor.OneMinusSrcAlpha, p.getBlend().getDstFactor());
        assertEquals(java.util.List.of("_MainTex", "_NormalMap"), p.getTextureSlots());
    }

    @Test
    void shaderPassDefaultsAndNullBlendRoundTrip() throws Exception {
        ShaderPass pass = new ShaderPass();
        pass.setName("GBuffer");

        Shader shader = new Shader();
        shader.addPass(pass);

        Shader r = writeAndRead(shader, "pass-default.dat");

        ShaderPass p = r.getPasses().get(0);
        // 未显式声明时保持 DSL 默认值
        assertTrue(p.isZWrite());
        assertEquals(ShaderAST.CompareFunction.LessEqual, p.getZTest());
        assertEquals(ShaderAST.CullMode.Back, p.getCullMode());
        assertEquals(ShaderAST.RenderPath.Deferred, p.getRenderPath());
        assertEquals(ShaderAST.RenderLayer.Opaque, p.getRenderLayer());
        assertNull(p.getBlend());
        assertTrue(p.getTextureSlots().isEmpty());
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Shader 资产整体
    // ═══════════════════════════════════════════════════════════════════════

    @Test
    void shaderAssetRoundTrip() throws Exception {
        Shader shader = new Shader();
        shader.setGuid("guid-shader-default");
        shader.setLastModified(1_752_000_000_000L);

        shader.addProperty(ShaderProperty.builder()
            .name("_Color").displayName("Tint").type(ShaderPropertyType.COLOR)
            .defaultValue(new float[]{1f, 1f, 1f, 1f}).build());
        shader.addProperty(ShaderProperty.builder()
            .name("_Metallic").displayName("Metallic").type(ShaderPropertyType.RANGE)
            .defaultValue(0.0f).rangeMin(0.0f).rangeMax(1.0f).build());

        ShaderPass forward = new ShaderPass();
        forward.setName("Forward");
        forward.setRenderPath(ShaderAST.RenderPath.Forward);
        forward.setRenderLayer(ShaderAST.RenderLayer.Opaque);
        forward.setVertexBytecode(new byte[]{1, 2, 3});
        forward.setFragmentBytecode(new byte[]{4, 5, 6});
        forward.setBlend(BlendDesc.off());
        forward.addTextureSlot("_MainTex");
        shader.addPass(forward);

        ShaderPass shadow = new ShaderPass();
        shadow.setName("ShadowCaster");
        shadow.setRenderPath(ShaderAST.RenderPath.Shadow);
        shadow.setZTest(ShaderAST.CompareFunction.Less);
        shader.addPass(shadow);

        Shader r = writeAndRead(shader, "shader.dat");

        assertEquals("guid-shader-default", r.getGuid());
        assertEquals(1_752_000_000_000L, r.getLastModified());
        assertEquals(2, r.getProperties().size());
        assertEquals(2, r.getPasses().size());
        assertEquals("Forward", r.getPasses().get(0).getName());
        assertEquals("ShadowCaster", r.getPasses().get(1).getName());
        assertEquals(ShaderAST.RenderPath.Shadow, r.getPasses().get(1).getRenderPath());
        assertEquals(0.0f, r.getProperty("_Metallic").getRangeMin());
        assertEquals(1.0f, r.getProperty("_Metallic").getRangeMax());
        assertNotNull(r.getPasses().get(0).getBlend());
        assertFalse(r.getPasses().get(0).getBlend().isEnabled());
        assertNull(r.getPasses().get(1).getBlend());
    }
}
