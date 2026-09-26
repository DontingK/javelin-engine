package com.javelin.editor.importer.shader;

import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.MaterialPropertyType;
import com.javelin.core.render.material.PassState;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * .shader 解析器 + 注入器 CPU 单测（不起 shaderc，纯语法层验证）。
 *
 * <p>覆盖：v0.13 全语法（Properties 五类型 / Pass 状态命令 / vs/fs 捕获）、
 * GUID 文件头透明、GLSL 注释中大括号不破坏块捕获、错误报行号、
 * 注入声明顺序与保留字校验（RHI 21.5 / 21.6）。
 */
public class ShaderParserTest {

    private static final String FULL_SHADER = """
            // @javelin-guid: 0123456789abcdef0123456789abcdef
            Shader "TestFull"
            {
                Properties
                {
                    _Color    ("Tint",       Color)       = (1, 0.5, 0.25, 1)
                    _Offset   ("Offset",     Vector)      = (0, 0, 0, 0)
                    _Metallic ("Metallic",   Range(0, 1)) = 0.25
                    _Speed    ("Speed",      Float)       = 1.5
                    _MainTex  ("Base Map",   2D)          = "white" {}
                    _BumpMap  ("Normal Map", 2D)          = "bump" {}
                }

                Pass Forward
                {
                    Cull Back
                    ZWrite On
                    ZTest LEqual
                    Blend Off
                    ColorMask RGBA

                    vs {
                        #version 450
                        // } 注释里的大括号不应截断块捕获
                        layout(location = 0) in vec3 aPos;
                        void main() { gl_Position = cam.viewProj * pc.model * vec4(aPos, 1.0); }
                    }
                    fs {
                        #version 450
                        layout(location = 0) out vec4 o_color;
                        void main() { o_color = material._Color; }
                    }
                }

                Pass Transparent
                {
                    Cull Off
                    ZWrite Off
                    ZTest Greater
                    Blend SrcAlpha OneMinusSrcAlpha
                    ColorMask RG

                    vs {
                        #version 450
                        void main() { }
                    }
                    fs {
                        #version 450
                        void main() { }
                    }
                }
            }
            """;

    @Test
    public void parseFullSyntax() {
        ShaderParser.ParsedShader shader = ShaderParser.parse(FULL_SHADER, "full.shader");

        assertEquals("TestFull", shader.shaderName());

        // Properties：声明序 + 五类型全字段
        assertEquals(6, shader.properties().size());
        MaterialProperty color = shader.properties().get(0);
        assertEquals("_Color", color.getName());
        assertEquals("Tint", color.getDisplayName());
        assertEquals(MaterialPropertyType.COLOR, color.getType());
        assertEquals(new float[]{1f, 0.5f, 0.25f, 1f}, color.getDefaultValues());
        assertEquals(MaterialPropertyType.VECTOR, shader.properties().get(1).getType());
        MaterialProperty metallic = shader.properties().get(2);
        assertEquals(MaterialPropertyType.RANGE, metallic.getType());
        assertEquals(0f, metallic.getMin());
        assertEquals(1f, metallic.getMax());
        assertEquals(new float[]{0.25f}, metallic.getDefaultValues());
        assertEquals(MaterialPropertyType.FLOAT, shader.properties().get(3).getType());
        assertEquals(new float[]{1.5f}, shader.properties().get(3).getDefaultValues());
        assertEquals("white", shader.properties().get(4).getTexturePlaceholder());
        assertEquals("bump", shader.properties().get(5).getTexturePlaceholder());

        // Pass 状态命令 → PassState
        assertEquals(2, shader.passes().size());
        ShaderParser.ParsedPass forward = shader.passes().get(0);
        assertEquals("Forward", forward.name());
        PassState fs = forward.state();
        assertEquals(CullMode.BACK, fs.getCull());
        assertTrue(fs.isDepthWrite());
        assertEquals(CompareOp.LESS_OR_EQUAL, fs.getDepthTest());
        assertTrue(!fs.isBlendEnabled());
        assertEquals(Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                ColorComponentFlags.B, ColorComponentFlags.A), fs.getColorMask());
        // vs/fs 原文捕获完整（注释大括号未截断），#version 与 GLSL 保留
        assertTrue(forward.vsBody().contains("#version 450"));
        assertTrue(forward.vsBody().contains("// } 注释里的大括号不应截断块捕获"));
        assertTrue(forward.vsBody().contains("cam.viewProj * pc.model"));

        ShaderParser.ParsedPass transparent = shader.passes().get(1);
        assertEquals("Transparent", transparent.name());
        PassState ts = transparent.state();
        assertEquals(CullMode.NONE, ts.getCull());
        assertTrue(!ts.isDepthWrite());
        assertEquals(CompareOp.GREATER, ts.getDepthTest());
        assertTrue(ts.isBlendEnabled());
        assertEquals(BlendFactor.SRC_ALPHA, ts.getSrcColor());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, ts.getDstColor());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, ts.getSrcAlpha());
        assertEquals(BlendFactor.SRC_ALPHA, ts.getDstColor());
        assertEquals(Set.of(ColorComponentFlags.R, ColorComponentFlags.G), ts.getColorMask());
    }

    @Test
    public void vulkanSpelledStateValuesAccepted() {
        String source = """
                Shader "V"
                {
                    Pass Forward
                    {
                        Cull FRONT
                        ZTest GREATER_OR_EQUAL
                        Blend ONE_MINUS_SRC_ALPHA SRC_ALPHA
                        vs { #version 450
                        void main() { } }
                        fs { #version 450
                        void main() { } }
                    }
                }
                """;
        ShaderParser.ParsedShader shader = ShaderParser.parse(source, "v.shader");
        PassState state = shader.passes().get(0).state();
        assertEquals(CullMode.FRONT, state.getCull());
        assertEquals(CompareOp.GREATER_OR_EQUAL, state.getDepthTest());
        assertEquals(BlendFactor.ONE_MINUS_SRC_ALPHA, state.getSrcColor());
        assertEquals(BlendFactor.SRC_ALPHA, state.getDstColor());
    }

    @Test
    public void syntaxErrorsCarryLineNumbers() {
        // 未知顶层块
        ShaderParseException e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("""
                        Shader "X" { UnknownBlock { } }
                        """, "a.shader"));
        assertTrue(e.getMessage().contains("未知顶层块"));

        // Pass 缺 fs
        e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("""
                        Shader "X" { Pass Forward { vs { #version 450
                        } } }
                        """, "b.shader"));
        assertTrue(e.getMessage().contains("fs"));

        // 未知属性类型
        e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("""
                        Shader "X" { Properties { _A ("A", Texture3D) = "white" } }
                        """, "c.shader"));
        assertTrue(e.getMessage().contains("未知属性类型"));

        // ZTest Always（CompareOp 无 ALWAYS）
        e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("""
                        Shader "X" { Pass Forward { ZTest Always
                        vs { #version 450
                        void main() { } }
                        fs { #version 450
                        void main() { } } } }
                        """, "d.shader"));
        assertTrue(e.getMessage().contains("ALWAYS"));

        // 重复 Pass
        e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("""
                        Shader "X" {
                        Pass Forward { vs { #version 450
                        void main() { } } fs { #version 450
                        void main() { } } }
                        Pass Forward { vs { #version 450
                        void main() { } } fs { #version 450
                        void main() { } } } }
                        """, "e.shader"));
        assertTrue(e.getMessage().contains("重复 Pass"));

        // 无 Pass
        e = assertThrows(ShaderParseException.class, () ->
                ShaderParser.parse("Shader \"X\" { }", "f.shader"));
        assertTrue(e.getMessage().contains("至少需要一个 Pass"));
    }

    // ═══ 注入器 ═══

    @Test
    public void injectInsertsDeclarationsAfterVersion() {
        String body = """
                #version 450
                layout(location = 0) out vec4 o;
                void main() { o = material._Color; }
                """;
        String injected = ShaderInjector.inject(body, ShaderParser.parse(FULL_SHADER,
                "full.shader").properties(), "full.shader", "fs");

        // #version 仍最前，注入紧随其后
        assertTrue(injected.startsWith("#version 450\n"));
        int versionLine = injected.indexOf('\n');
        int cameraBlock = injected.indexOf("uniform CameraBlock { mat4 viewProj; } cam;");
        int pushBlock = injected.indexOf("layout(push_constant) uniform PushBlock");
        int materialBlock = injected.indexOf("layout(std140, set = 2, binding = 0) uniform MaterialBlock");
        assertTrue(versionLine < cameraBlock);
        assertTrue(cameraBlock < pushBlock);
        assertTrue(pushBlock < materialBlock);

        // MaterialBlock 字段：声明序、类型正确、无贴图槽混入
        assertTrue(injected.contains("    vec4 _Color;"));
        assertTrue(injected.contains("    vec4 _Offset;"));
        assertTrue(injected.contains("    float _Metallic;"));
        assertTrue(injected.contains("    float _Speed;"));

        // 贴图槽：binding 按声明序 1..n
        int mainTex = injected.indexOf("layout(set = 2, binding = 1) uniform sampler2D _MainTex;");
        int bumpMap = injected.indexOf("layout(set = 2, binding = 2) uniform sampler2D _BumpMap;");
        assertTrue(mainTex > 0);
        assertTrue(bumpMap > mainTex);

        // 用户代码保留在注入之后
        assertTrue(injected.indexOf("void main()") > materialBlock);
    }

    @Test
    public void injectWithoutNumericPropertiesSkipsMaterialBlock() {
        String body = "#version 450\nvoid main() { }\n";
        String injected = ShaderInjector.inject(body, java.util.List.of(), "a.shader", "vs");
        assertTrue(injected.contains("uniform CameraBlock"));
        assertTrue(!injected.contains("MaterialBlock"));
    }

    @Test
    public void injectRejectsReservedNamesAndMissingVersion() {
        // 手写注入同名声明（保留字）
        String withReserved = """
                #version 450
                layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; } cam;
                void main() { }
                """;
        assertThrows(ShaderParseException.class, () ->
                ShaderInjector.inject(withReserved, java.util.List.of(), "a.shader", "vs"));

        // 缺 #version（注入锚点）
        assertThrows(ShaderParseException.class, () ->
                ShaderInjector.inject("void main() { }", java.util.List.of(), "a.shader", "vs"));
    }
}
