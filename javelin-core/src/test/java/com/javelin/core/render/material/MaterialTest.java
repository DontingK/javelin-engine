package com.javelin.core.render.material;

import com.javelin.core.render.texture.Image;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.Format;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 材质包数据模型单元测试：属性元数据、Pass 编译结构、Material 参数读写与校验。
 */
public class MaterialTest {

    private static Shader sampleShader() {
        return Shader.builder("PBR_Standard")
                .property(MaterialProperty.color("_BaseColor", "Base Color", 1, 1, 1, 1))
                .property(MaterialProperty.range("_Metallic", "Metallic", 0, 1, 0.5f))
                .property(MaterialProperty.floatProp("_UseNormalMap", "Use Normal Map", 0))
                .property(MaterialProperty.texture2D("_AlbedoMap", "Albedo", "white"))
                .pass(new ShaderPass("GBuffer", new byte[]{1}, new byte[]{2}, new PassState()))
                .pass(new ShaderPass("Transparent", new byte[]{3}, new byte[]{4},
                        new PassState()
                                .setBlend(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)
                                .setDepthWrite(false)))
                .build();
    }

    @Test
    void materialSeedsDefaultsFromShaderProperties() {
        Material material = new Material(sampleShader());

        assertArrayEquals(new float[]{1, 1, 1, 1}, material.getColor("_BaseColor"));
        assertEquals(0.5f, material.getFloat("_Metallic"));
        assertEquals(0.0f, material.getFloat("_UseNormalMap"));
        assertNull(material.getTexture("_AlbedoMap"));   // 未赋值 → 占位贴图由渲染侧解析
    }

    @Test
    void setShaderPreservesSameNameSameTypeValues() {
        Material material = new Material(sampleShader());
        material.setFloat("_Metallic", 0.9f);
        material.setColor("_BaseColor", 0.1f, 0.2f, 0.3f, 0.4f);

        // 换绑另一实例：同名同类型保留原值、新属性取新默认、旧有新无丢弃
        //（Unity 保参口径，2026-10-30 拍板——同 guid 换代（热重载）与换绑一视同仁）
        Shader next = Shader.builder("PBR_Next")
                .property(MaterialProperty.range("_Metallic", "Metallic", 0, 1, 0.1f))
                .property(MaterialProperty.color("_BaseColor", "Base Color", 0, 0, 0, 1))
                .property(MaterialProperty.floatProp("_Roughness", "Roughness", 0.2f))
                .pass(new ShaderPass("Forward", new byte[]{1}, new byte[]{2}, new PassState()))
                .build();
        material.setShader(next);

        assertEquals(0.9f, material.getFloat("_Metallic"));
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, material.getColor("_BaseColor"));
        assertEquals(0.2f, material.getFloat("_Roughness"));
        // 旧有新无（_UseNormalMap）丢弃：按新 shader 未声明属性报错
        assertThrows(IllegalArgumentException.class, () -> material.getFloat("_UseNormalMap"));

        // 同实例再绑 = no-op（不重置、不扰动）
        material.setShader(next);
        assertEquals(0.9f, material.getFloat("_Metallic"));
    }

    @Test
    void surfaceTypeDefaultsAndSurvivesShaderRebind() {
        // 默认 OPAQUE（URP 口径，拍板 2026-10-17 方案 B）
        Material material = new Material(sampleShader());
        assertEquals(SurfaceType.OPAQUE, material.getSurfaceType());

        material.setSurfaceType(SurfaceType.TRANSPARENT);
        assertEquals(SurfaceType.TRANSPARENT, material.getSurfaceType());

        // 面型随材质资产走、不随模板重置（区别于 values/textures）
        material.setShader(sampleShader());
        assertEquals(SurfaceType.TRANSPARENT, material.getSurfaceType());

        assertThrows(IllegalArgumentException.class,
                () -> material.setSurfaceType(null));
    }

    @Test
    void floatAndColorRoundTrip() {
        Material material = new Material(sampleShader());

        material.setFloat("_Metallic", 0.25f);
        material.setColor("_BaseColor", 0.1f, 0.2f, 0.3f, 0.4f);

        assertEquals(0.25f, material.getFloat("_Metallic"));
        assertArrayEquals(new float[]{0.1f, 0.2f, 0.3f, 0.4f}, material.getColor("_BaseColor"));
    }

    @Test
    void unknownPropertyRejected() {
        Material material = new Material(sampleShader());

        assertThrows(IllegalArgumentException.class, () -> material.setFloat("_Nope", 1));
        assertThrows(IllegalArgumentException.class, () -> material.getColor("_Nope"));
        assertThrows(IllegalArgumentException.class, () -> material.setTexture("_Nope", null));
    }

    @Test
    void typeMismatchRejected() {
        Material material = new Material(sampleShader());

        // _BaseColor 是 Color，不能按 Float 写
        assertThrows(IllegalArgumentException.class, () -> material.setFloat("_BaseColor", 1));
        // _AlbedoMap 是贴图槽，不能按 Color 读
        assertThrows(IllegalArgumentException.class, () -> material.getColor("_AlbedoMap"));
    }

    @Test
    void operationWithoutShaderRejected() {
        Material material = new Material();

        assertThrows(IllegalStateException.class, () -> material.setFloat("_X", 1));
    }

    @Test
    void passLookupByRenderStageName() {
        Shader shader = sampleShader();

        ShaderPass gbuffer = shader.pass("GBuffer");
        assertNotNull(gbuffer);
        assertArrayEquals(new byte[]{1}, gbuffer.getVertexSpirv());
        assertArrayEquals(new byte[]{2}, gbuffer.getFragmentSpirv());
        assertEquals("GBuffer", gbuffer.getName());
        assertNull(shader.pass("ShadowCaster"));

        // 声明顺序保持
        assertEquals("GBuffer", shader.getPasses().get(0).getName());
        assertEquals("Transparent", shader.getPasses().get(1).getName());
    }

    @Test
    void duplicatePassNameRejected() {
        Shader.Builder builder = Shader.builder("dup")
                .pass(new ShaderPass("GBuffer", null, null, null));

        assertThrows(IllegalArgumentException.class,
                () -> builder.pass(new ShaderPass("GBuffer", null, null, null)));
    }

    @Test
    void passStateDefaultsAndOverrides() {
        PassState opaque = new PassState();
        assertEquals(CullMode.BACK, opaque.getCull());
        assertTrue(opaque.isDepthWrite());
        assertEquals(CompareOp.LESS_OR_EQUAL, opaque.getDepthTest());
        assertFalse(opaque.isBlendEnabled());

        ShaderPass transparent = sampleShader().pass("Transparent");
        assertTrue(transparent.getState().isBlendEnabled());
        assertEquals(BlendFactor.SRC_ALPHA, transparent.getState().getSrcColor());
        assertFalse(transparent.getState().isDepthWrite());
    }

    @Test
    void rangePropertyValidatesDefaultValue() {
        assertThrows(IllegalArgumentException.class,
                () -> MaterialProperty.range("_X", "X", 0, 1, 1.5f));

        MaterialProperty p = MaterialProperty.range("_X", "X", 0, 1, 0.5f);
        assertEquals(0, p.getMin());
        assertEquals(1, p.getMax());
        assertEquals(MaterialPropertyType.RANGE, p.getType());
        assertEquals("float", p.getType().glslType());
    }

    @Test
    void textureSlotStoresEngineTextureAsset() {
        Material material = new Material(sampleShader());
        Texture2D albedo = new Texture2D(new Image(4, 4, Format.R8G8B8A8_UNORM, new byte[64]));

        material.setTexture("_AlbedoMap", albedo);

        assertSame(albedo, material.getTexture("_AlbedoMap"));
        assertEquals(4, albedo.getWidth());
        assertEquals(Format.R8G8B8A8_UNORM, albedo.getFormat());
    }

    @Test
    void builtInPlaceholderTexturesAreDeviceFree() {
        // Unity whiteTexture / blackTexture / normalTexture 语义；CPU 数据就绪，GPU 上传推迟渲染侧
        assertEquals(1, Texture2D.whiteTexture().getWidth());
        assertNotNull(Texture2D.whiteTexture().getPixels());
        assertEquals(0xFF, Texture2D.normalTexture().getPixels()[2] & 0xFF);  // bump 蓝通道 0xFF
        assertEquals(Texture2D.FilterMode.BILINEAR, Texture2D.whiteTexture().getFilterMode());
        assertEquals(Texture2D.WrapMode.REPEAT, Texture2D.whiteTexture().getWrapMode());

        Texture2D tex = new Texture2D(new Image(4, 4, Format.R8G8B8A8_UNORM, new byte[64]));
        tex.setFilterMode(Texture2D.FilterMode.POINT);
        tex.setWrapMode(Texture2D.WrapMode.CLAMP);
        assertEquals(Texture2D.FilterMode.POINT, tex.getFilterMode());
        assertEquals(Texture2D.WrapMode.CLAMP, tex.getWrapMode());
    }

    @Test
    void shaderPassRequiresName() {
        assertThrows(IllegalArgumentException.class,
                () -> new ShaderPass(" ", new byte[]{1}, new byte[]{2}, null));
    }
}
