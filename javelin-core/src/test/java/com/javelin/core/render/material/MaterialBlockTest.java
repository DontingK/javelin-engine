package com.javelin.core.render.material;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * MaterialBlock std140 排布 + Shader.forwardPass 解析的 CPU 单测。
 *
 * <p>排布规则（RHI 21.4/21.5，GLSL/RHI/CPU 三方同源）：
 * 按属性声明序跳过 TEXTURE_2D；FLOAT/RANGE → float（4B 对齐），
 * COLOR/VECTOR → vec4（16B 对齐）；总长 16B 倍数，空块 16B 占位。
 */
public class MaterialBlockTest {

    @Test
    public void scalarThenVecPacksWithVecAlignment() {
        Shader shader = Shader.builder("T")
                .property(MaterialProperty.floatProp("_Metallic", "Metallic", 0.25f))
                .property(MaterialProperty.color("_BaseColor", "Base Color", 1f, 0.5f, 0.25f, 1f))
                .property(MaterialProperty.texture2D("_MainTex", "Main Tex", "white"))
                .build();
        Material material = new Material(shader);

        // float@0..4 → vec4 对齐到 16..32；贴图槽不占 UBO
        assertEquals(32, MaterialBlock.uboByteSize(shader));
        float[] block = MaterialBlock.pack(material);
        assertEquals(8, block.length);
        assertEquals(0.25f, block[0]);
        assertEquals(1f, block[4]);
        assertEquals(0.5f, block[5]);
        assertEquals(0.25f, block[6]);
        assertEquals(1f, block[7]);
    }

    @Test
    public void vecThenScalarPacksScalarAfterVec4() {
        Shader shader = Shader.builder("T")
                .property(MaterialProperty.color("_Tint", "Tint", 1f, 1f, 1f, 1f))
                .property(MaterialProperty.range("_Alpha", "Alpha", 0f, 1f, 0.75f))
                .build();
        Material material = new Material(shader);

        // vec4@0..16，float 4B 对齐 @16..20 → 总长 32
        assertEquals(32, MaterialBlock.uboByteSize(shader));
        float[] block = MaterialBlock.pack(material);
        assertEquals(0.75f, block[4]);
    }

    @Test
    public void textureOnlyShaderHas16BytePlaceholderBlock() {
        Shader shader = Shader.builder("T")
                .property(MaterialProperty.texture2D("_MainTex", "Main Tex", "white"))
                .build();
        Material material = new Material(shader);

        assertEquals(16, MaterialBlock.uboByteSize(shader));
        assertEquals(4, MaterialBlock.pack(material).length);
    }

    @Test
    public void runtimeValueChangesReflectedInPack() {
        Shader shader = Shader.builder("T")
                .property(MaterialProperty.floatProp("_Metallic", "Metallic", 0f))
                .build();
        Material material = new Material(shader);
        assertEquals(0f, MaterialBlock.pack(material)[0]);
        material.setFloat("_Metallic", 0.9f);
        assertEquals(0.9f, MaterialBlock.pack(material)[0]);
    }

    @Test
    public void forwardPassResolvesByNameThenBlendState() {
        byte[] spirv = new byte[]{1, 2, 3};
        // 名 "Forward" 命中
        Shader named = Shader.builder("Named")
                .pass(new ShaderPass("Transparent", spirv, spirv,
                        new PassState().setBlend(true, com.javelin.rhi.types.BlendFactor.SRC_ALPHA,
                                com.javelin.rhi.types.BlendFactor.ONE_MINUS_SRC_ALPHA)))
                .pass(new ShaderPass("Forward", spirv, spirv, new PassState()))
                .build();
        assertEquals("Forward", named.forwardPass().getName());

        // 无名命中：第一个非混合 pass
        Shader opaqueFirst = Shader.builder("OpaqueFirst")
                .pass(new ShaderPass("A", spirv, spirv, new PassState()))
                .pass(new ShaderPass("B", spirv, spirv,
                        new PassState().setBlend(true, com.javelin.rhi.types.BlendFactor.SRC_ALPHA,
                                com.javelin.rhi.types.BlendFactor.ONE_MINUS_SRC_ALPHA)))
                .build();
        assertEquals("A", opaqueFirst.forwardPass().getName());

        // 全混合：fallback 第一个 pass（对象进透明桶）
        Shader allBlend = Shader.builder("AllBlend")
                .pass(new ShaderPass("Transparent", spirv, spirv,
                        new PassState().setBlend(true, com.javelin.rhi.types.BlendFactor.SRC_ALPHA,
                                com.javelin.rhi.types.BlendFactor.ONE_MINUS_SRC_ALPHA)))
                .build();
        assertEquals("Transparent", allBlend.forwardPass().getName());

        // 无 pass：抛异常
        Shader empty = Shader.builder("Empty").build();
        assertThrows(IllegalStateException.class, empty::forwardPass);
    }

    @Test
    public void packRejectsUnboundMaterial() {
        assertThrows(IllegalArgumentException.class, () -> MaterialBlock.pack(new Material()));
        assertThrows(IllegalArgumentException.class, () -> MaterialBlock.uboByteSize(null));
        assertNotNull(MaterialPropertyType.TEXTURE_2D.glslType());
    }
}
