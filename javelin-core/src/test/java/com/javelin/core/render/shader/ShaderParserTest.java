package com.javelin.core.render.shader;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ShaderParser 单元测试。
 */
class ShaderParserTest {

    private final ShaderParser parser = new ShaderParser();

    /** 从测试资源读取 shader 源码 */
    private static String loadResource(String name) {
        try (InputStream in = ShaderParserTest.class.getResourceAsStream("/shader/" + name)) {
            assertNotNull(in, "测试资源不存在: " + name);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test
    void testParseMinimalShader() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                    vs {
                        #version 450
                        void main() {
                            gl_Position = vec4(0.0);
                        }
                    }
                    fs {
                        #version 450
                        void main() {
                            o_color = vec4(1.0);
                        }
                    }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        assertNotNull(shader);
        assertEquals("Javelin/Test", shader.name);
        assertNull(shader.guid);
        assertEquals(1, shader.passes.size());

        ShaderAST.PassNode pass = shader.passes.get(0);
        assertEquals("Forward", pass.name);
        assertEquals(ShaderAST.RenderPath.Forward, pass.renderPath);
        assertEquals(ShaderAST.RenderLayer.Opaque, pass.renderLayer);
        assertTrue(pass.zWrite);
        assertEquals(ShaderAST.CompareFunction.LessEqual, pass.zTest);
        assertEquals(ShaderAST.CullMode.Back, pass.cullMode);
        assertFalse(pass.blend.enabled);
        assertFalse(pass.blendDeclared);
        assertNotNull(pass.vertexShader);
        assertEquals(ShaderAST.StageType.VERTEX, pass.vertexShader.type);
        assertNotNull(pass.fragmentShader);
        assertEquals(ShaderAST.StageType.FRAGMENT, pass.fragmentShader.type);
    }

    @Test
    void testParseAllPropertyTypes() {
        String source = """
            Shader "Javelin/Test" {
                Properties {
                    _FloatVal ("浮点", Float) = 1.5
                    _IntVal   ("整数", Int) = 3
                    _BoolVal  ("开关", Bool) = true
                    _Vec2     ("向量2", Vector2) = (0.5, 0.5)
                    _Vec3     ("向量3", Vector3) = (1.0, 2.0, 3.0)
                    _Vec4     ("向量4", Vector4) = (1.0, 2.0, 3.0, 4.0)
                    _Color    ("颜色", Color) = (1.0, 0.0, 0.0, 1.0)
                    _MainTex  ("主纹理", Texture2D) = "white"
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        assertNotNull(shader.properties);
        assertEquals(8, shader.properties.properties.size());

        assertEquals(1.5f, (Float) shader.properties.properties.get(0).defaultValue);
        assertEquals(3, (Integer) shader.properties.properties.get(1).defaultValue);
        assertEquals(Boolean.TRUE, shader.properties.properties.get(2).defaultValue);
        assertArrayEquals(new float[]{0.5f, 0.5f}, (float[]) shader.properties.properties.get(3).defaultValue);
        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, (float[]) shader.properties.properties.get(4).defaultValue);
        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f, 4.0f}, (float[]) shader.properties.properties.get(5).defaultValue);
        assertArrayEquals(new float[]{1.0f, 0.0f, 0.0f, 1.0f}, (float[]) shader.properties.properties.get(6).defaultValue);
        assertEquals("white", shader.properties.properties.get(7).defaultValue);

        // 非 Range 类型的上下限必须为 null
        for (ShaderAST.PropertyNode prop : shader.properties.properties) {
            assertNull(prop.rangeMin, prop.name + " 不应有 rangeMin");
            assertNull(prop.rangeMax, prop.name + " 不应有 rangeMax");
        }
    }

    @Test
    void testParseRangeKeepsMinMax() {
        String source = """
            Shader "Javelin/Test" {
                Properties {
                    _Metallic ("金属度", Range(0, 1)) = 0.5
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        ShaderAST.PropertyNode prop = shader.properties.properties.get(0);
        assertEquals("Range", prop.type);
        assertEquals(0.5f, (Float) prop.defaultValue);
        // Range 上下限必须保留（engine4 丢弃，编辑器滑动条需要）
        assertNotNull(prop.rangeMin);
        assertNotNull(prop.rangeMax);
        assertEquals(0.0f, prop.rangeMin);
        assertEquals(1.0f, prop.rangeMax);
    }

    @Test
    void testParseRenderStates() {
        String source = """
            Shader "Javelin/Test" {
                Pass Transparent {
                    renderPath Forward
                    renderLayer Transparent
                    zWrite Off
                    zTest Less
                    cullMode None
                    blend SrcAlpha OneMinusSrcAlpha
                    vs { #version 450 void main() { gl_Position = vec4(0.0); } }
                    fs { #version 450 void main() { o_color = vec4(1.0); } }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        ShaderAST.PassNode pass = shader.passes.get(0);

        assertEquals(ShaderAST.RenderPath.Forward, pass.renderPath);
        assertEquals(ShaderAST.RenderLayer.Transparent, pass.renderLayer);
        assertFalse(pass.zWrite);
        assertEquals(ShaderAST.CompareFunction.Less, pass.zTest);
        assertEquals(ShaderAST.CullMode.None, pass.cullMode);
        assertTrue(pass.blend.enabled);
        assertEquals(ShaderAST.BlendFactor.SrcAlpha, pass.blend.srcFactor);
        assertEquals(ShaderAST.BlendFactor.OneMinusSrcAlpha, pass.blend.dstFactor);
    }

    @Test
    void testParseBlendOff() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                    blend Off
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        ShaderAST.PassNode pass = parser.parse(source).passes.get(0);
        assertFalse(pass.blend.enabled);
        assertTrue(pass.blendDeclared);
        assertEquals(ShaderAST.BlendFactor.One, pass.blend.srcFactor);
        assertEquals(ShaderAST.BlendFactor.Zero, pass.blend.dstFactor);
    }

    @Test
    void testParseCodeBlockBraceBalancing() {
        // GLSL 内部允许任意大括号（函数体、if、for），配平提取不应截断
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                    vs {
                        #version 450
                        layout(location = 0) in vec3 a_pos;
                        void main() {
                            vec4 p = vec4(a_pos, 1.0);
                            for (int i = 0; i < 1; i++) {
                                if (i == 0) { p = p * 1.0; }
                            }
                            gl_Position = p;
                        }
                    }
                    fs {
                        #version 450
                        void main() {
                            vec4 c = vec4(1.0);
                            { c = c * 2.0; }
                            o_color = c;
                        }
                    }
                }
            }
            """;

        ShaderAST.PassNode pass = parser.parse(source).passes.get(0);
        assertTrue(pass.vertexShader.source.contains("for (int i = 0; i < 1; i++)"));
        assertTrue(pass.vertexShader.source.contains("gl_Position = p;"));
        assertTrue(pass.fragmentShader.source.contains("o_color = c;"));
        // 代码块不应吞掉闭合 Pass 的大括号
        assertEquals(1, parser.parse(source).passes.size());
    }

    @Test
    void testParseGuidFromHeaderComment() {
        ShaderAST.ShaderNode shader = parser.parse(loadResource("default.shader"));
        assertEquals("8f3a2c1e-9b47-4d21-a5f6-2c8e1d0b7a34", shader.guid);
        assertEquals("Default", shader.name);
    }

    @Test
    void testParseTestResourceShader() {
        // 完整示例（单 Forward Pass 版）：属性 + GlobalUniforms + 状态声明
        ShaderAST.ShaderNode shader = parser.parse(loadResource("default.shader"));

        assertEquals(4, shader.properties.properties.size());
        assertEquals(ShaderAST.RenderPath.Forward, shader.passes.get(0).renderPath);
        assertEquals(ShaderAST.RenderLayer.Opaque, shader.passes.get(0).renderLayer);
        assertEquals(ShaderAST.CompareFunction.LessEqual, shader.passes.get(0).zTest);
        assertEquals(ShaderAST.CullMode.Back, shader.passes.get(0).cullMode);

        // GlobalUniforms 声明覆盖默认 PerFrame 块
        assertNotNull(shader.globalUniforms);
        assertNotNull(shader.globalUniforms.perFrame);
        assertEquals(4, shader.globalUniforms.perFrame.fields.size());
        assertNull(shader.globalUniforms.perObject);

        // Range 上下限
        ShaderAST.PropertyNode metallic = ShaderAST.findProperty(shader, "_Metallic");
        assertNotNull(metallic);
        assertEquals(0.0f, metallic.rangeMin);
        assertEquals(1.0f, metallic.rangeMax);
    }

    @Test
    void testParseMultiplePasses() {
        String source = """
            Shader "Javelin/PBR" {
                Properties {
                    _Color ("颜色", Color) = (1.0, 1.0, 1.0, 1.0)
                }
                Pass GBuffer {
                    renderPath Deferred
                    vs { #version 450 void main() { gl_Position = vec4(0.0); } }
                    fs { #version 450 void main() { o_color = vec4(1.0); } }
                }
                Pass ShadowCaster {
                    renderPath Shadow
                    zWrite On
                    zTest Less
                    vs { #version 450 void main() { gl_Position = vec4(0.0); } }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        assertEquals(2, shader.passes.size());

        ShaderAST.PassNode gbuffer = ShaderAST.findPass(shader, "GBuffer");
        assertNotNull(gbuffer);
        assertEquals(ShaderAST.RenderPath.Deferred, gbuffer.renderPath);

        ShaderAST.PassNode shadow = ShaderAST.findPass(shader, "ShadowCaster");
        assertNotNull(shadow);
        assertEquals(ShaderAST.RenderPath.Shadow, shadow.renderPath);
        // 仅声明 vs 的 Pass 合法
        assertNull(shadow.fragmentShader);
    }

    @Test
    void testExtractTextureSlots() {
        String source = """
            Shader "Javelin/Test" {
                Properties {
                    _MainTex ("主纹理", Texture2D) = "white"
                    _NormalMap ("法线", Texture2D) = "bump"
                    _Color ("颜色", Color) = (1.0, 0.0, 0.0, 1.0)
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() { gl_Position = vec4(0.0); } }
                    fs { #version 450 void main() { o_color = vec4(1.0); } }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        var slots = ShaderAST.extractTextureSlots(shader);

        assertEquals(2, slots.size());
        assertTrue(slots.contains("_MainTex"));
        assertTrue(slots.contains("_NormalMap"));
    }

    @Test
    void testParseWithComments() {
        String source = """
            // 这是文件头注释
            /* 块注释 */
            Shader "Javelin/Test" {
                // 属性注释
                Properties {
                    _Color ("颜色", Color) = (1.0, 0.0, 0.0, 1.0) // 行尾注释
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() { gl_Position = vec4(0.0); } }
                    fs { #version 450 void main() { o_color = vec4(1.0); } }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        assertNotNull(shader);
        assertEquals("Javelin/Test", shader.name);
    }

    @Test
    void testParseGlobalUniforms() {
        String source = """
            Shader "Javelin/Test" {
                GlobalUniforms {
                    JavelinPerFrame {
                        mat4 u_view
                        mat4 u_proj
                        float u_time
                    }
                    JavelinPerObject {
                        mat4 u_model
                    }
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        ShaderAST.ShaderNode shader = parser.parse(source);
        assertNotNull(shader.globalUniforms);
        assertNotNull(shader.globalUniforms.perFrame);
        assertEquals("JavelinPerFrame", shader.globalUniforms.perFrame.name);
        assertEquals(3, shader.globalUniforms.perFrame.fields.size());

        assertNotNull(shader.globalUniforms.perObject);
        assertEquals("JavelinPerObject", shader.globalUniforms.perObject.name);
        assertEquals(1, shader.globalUniforms.perObject.fields.size());
    }

    @Test
    void testParseDuplicatePassNameThrows() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        assertThrows(ShaderParseException.class, () -> parser.parse(source));
    }

    @Test
    void testParseDuplicatePropertyNameThrows() {
        String source = """
            Shader "Javelin/Test" {
                Properties {
                    _Color ("颜色A", Color) = (1.0, 0.0, 0.0, 1.0)
                    _Color ("颜色B", Color) = (0.0, 1.0, 0.0, 1.0)
                }
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                }
            }
            """;

        assertThrows(ShaderParseException.class, () -> parser.parse(source));
    }

    @Test
    void testParseMissingBraceThrows() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                    vs { #version 450 void main() {} }
                    fs { #version 450 void main() {} }
                // 缺少 }
            """;

        assertThrows(ShaderParseException.class, () -> parser.parse(source));
    }

    @Test
    void testParsePassWithoutStageThrows() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Forward
                }
            }
            """;

        assertThrows(ShaderParseException.class, () -> parser.parse(source));
    }

    @Test
    void testErrorMessageContainsLineColumn() {
        String source = """
            Shader "Javelin/Test" {
                Pass Forward {
                    renderPath Bogus
                }
            }
            """;

        ShaderParseException ex = assertThrows(ShaderParseException.class, () -> parser.parse(source));
        assertTrue(ex.line > 0, "异常应携带行号: " + ex.getMessage());
        assertTrue(ex.column > 0, "异常应携带列号: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("[" + ex.line + ":" + ex.column + "]"),
                "消息应含 [行:列] 前缀: " + ex.getMessage());
    }
}
