package com.javelin.core.render.material;

import com.javelin.core.serialize.binary.BinaryExporter;
import com.javelin.core.serialize.binary.BinaryImporter;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CullMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Shader 二进制资产产物格式 round-trip 单测（Engine_Material_Design §3，
 * 2026-09-26 落地）：构建 → BinaryExporter → BinaryImporter → 字段级相等。
 *
 * <p>覆盖：MaterialProperty 五类型的默认值/区间/占位名（含 TEXTURE_2D 的 null 数组）、
 * ShaderPass 的 SPIR-V byte[]、PassState 全字段（含 colorMask 枚举集）、
 * guid/lastModified（AbsAsset）、passes 声明序。
 */
public class ShaderSerializationTest {

    @TempDir
    Path tempDir;

    private Shader buildSample() {
        return Shader.builder("PBR_Standard")
                .property(MaterialProperty.color("_BaseColor", "Base Color", 1f, 0.5f, 0.25f, 1f))
                .property(MaterialProperty.floatProp("_Metallic", "Metallic", 0.25f))
                .property(MaterialProperty.range("_Roughness", "Roughness", 0f, 1f, 0.5f))
                .property(MaterialProperty.vector("_Offset", "Offset", 1f, 2f, 3f, 4f))
                .property(MaterialProperty.texture2D("_AlbedoMap", "Albedo", "white"))
                .property(MaterialProperty.texture2D("_NormalMap", "Normal", "bump"))
                .pass(new ShaderPass("Forward",
                        new byte[]{1, 2, 3, 4}, new byte[]{5, 6, 7}, new PassState()))
                .pass(new ShaderPass("Transparent",
                        new byte[]{9, 8}, new byte[]{7},
                        new PassState()
                                .setCull(CullMode.FRONT)
                                .setDepthWrite(false)
                                .setBlend(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA)
                                .setColorMask(Set.of(ColorComponentFlags.R, ColorComponentFlags.G))))
                .build();
    }

    private Shader roundTrip(Shader source) throws Exception {
        File file = tempDir.resolve("shader.asset").toFile();
        new BinaryExporter(file).export(source);
        return new BinaryImporter(file).importData(Shader.class);
    }

    @Test
    public void roundTripPreservesAllFields() throws Exception {
        Shader source = buildSample();
        source.setGuid("a1d2830d83ec423d94eec58c947f94f1");
        source.setLastModified(1727347200000L);

        Shader restored = roundTrip(source);

        assertEquals("a1d2830d83ec423d94eec58c947f94f1", restored.getGuid());
        assertEquals(1727347200000L, restored.getLastModified());
        assertEquals("PBR_Standard", restored.getShaderName());

        // Properties：声明序 + 全类型
        assertEquals(6, restored.getProperties().size());
        MaterialProperty baseColor = restored.getProperties().get(0);
        assertEquals("_BaseColor", baseColor.getName());
        assertEquals("Base Color", baseColor.getDisplayName());
        assertEquals(MaterialPropertyType.COLOR, baseColor.getType());
        assertArrayEquals(new float[]{1f, 0.5f, 0.25f, 1f}, baseColor.getDefaultValues());

        MaterialProperty metallic = restored.getProperties().get(1);
        assertEquals(MaterialPropertyType.FLOAT, metallic.getType());
        assertArrayEquals(new float[]{0.25f}, metallic.getDefaultValues());

        MaterialProperty roughness = restored.getProperties().get(2);
        assertEquals(MaterialPropertyType.RANGE, roughness.getType());
        assertEquals(0f, roughness.getMin());
        assertEquals(1f, roughness.getMax());
        assertArrayEquals(new float[]{0.5f}, roughness.getDefaultValues());

        MaterialProperty offset = restored.getProperties().get(3);
        assertEquals(MaterialPropertyType.VECTOR, offset.getType());
        assertArrayEquals(new float[]{1f, 2f, 3f, 4f}, offset.getDefaultValues());

        // TEXTURE_2D：defaultValues 为 null、占位名保留
        MaterialProperty albedo = restored.getProperties().get(4);
        assertEquals(MaterialPropertyType.TEXTURE_2D, albedo.getType());
        assertNull(albedo.getDefaultValues());
        assertEquals("white", albedo.getTexturePlaceholder());
        assertEquals("bump", restored.getProperties().get(5).getTexturePlaceholder());

        // Passes：声明序 + SPIR-V 字节 + 状态
        assertEquals(2, restored.getPasses().size());
        ShaderPass forward = restored.getPasses().get(0);
        assertEquals("Forward", forward.getName());
        assertArrayEquals(new byte[]{1, 2, 3, 4}, forward.getVertexSpirv());
        assertArrayEquals(new byte[]{5, 6, 7}, forward.getFragmentSpirv());
        PassState fs = forward.getState();
        assertEquals(CullMode.BACK, fs.getCull());
        assertTrue(fs.isDepthWrite());
        assertTrue(!fs.isBlendEnabled());

        ShaderPass transparent = restored.getPasses().get(1);
        assertEquals("Transparent", transparent.getName());
        assertArrayEquals(new byte[]{9, 8}, transparent.getVertexSpirv());
        assertArrayEquals(new byte[]{7}, transparent.getFragmentSpirv());
        PassState ts = transparent.getState();
        assertEquals(CullMode.FRONT, ts.getCull());
        assertTrue(!ts.isDepthWrite());
        assertTrue(ts.isBlendEnabled());
        assertEquals(BlendFactor.SRC_ALPHA, ts.getSrcColor());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, ts.getDstColor());
        assertEquals(BlendFactor.SRC_ALPHA, ts.getSrcAlpha());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, ts.getDstAlpha());
        assertEquals(Set.of(ColorComponentFlags.R, ColorComponentFlags.G), ts.getColorMask());

        // 查找语义不丢
        assertNotNull(restored.pass("Transparent"));
        assertEquals("Forward", restored.forwardPass().getName());
    }

    @Test
    public void emptyPropertiesAndDefaultPassStateRoundTrip() throws Exception {
        Shader source = Shader.builder("Default")
                .pass(new ShaderPass("Forward", new byte[]{1}, new byte[]{2}, null))
                .build();

        Shader restored = roundTrip(source);

        assertEquals(0, restored.getProperties().size());
        assertEquals(16, MaterialBlock.uboByteSize(restored));
        PassState state = restored.pass("Forward").getState();
        assertNotNull(state);
        assertEquals(CullMode.BACK, state.getCull());
        assertTrue(state.isDepthWrite());
        assertEquals(Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                ColorComponentFlags.B, ColorComponentFlags.A), state.getColorMask());
    }
}
