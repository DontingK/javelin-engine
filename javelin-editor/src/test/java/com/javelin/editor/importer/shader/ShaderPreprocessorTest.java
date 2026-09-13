package com.javelin.editor.importer.shader;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * ShaderPreprocessor 单元测试。
 */
class ShaderPreprocessorTest {

    private final ShaderPreprocessor preprocessor = new ShaderPreprocessor();

    /** 最简 vs 源码（合法的首行 #version） */
    private static final String SIMPLE_VS = """
        #version 450
        void main() { gl_Position = vec4(0.0); }
        """;

    @Test
    void testVersionMustBeFirstLine() {
        String source = """
            // 注释在 #version 之前
            #version 450
            void main() { gl_Position = vec4(0.0); }
            """;

        ShaderParseException ex = assertThrows(ShaderParseException.class,
                () -> preprocessor.preprocess(source, null, null, null));
        assertTrue(ex.getMessage().contains("#version"), ex.getMessage());
        assertTrue(ex.line > 0, "异常应携带行号: " + ex.getMessage());
    }

    @Test
    void testDefaultUniformBlocksInjected() {
        String result = preprocessor.preprocess(SIMPLE_VS, null, null, null);

        // PerFrame：set=0 binding=0，含全部默认字段
        assertTrue(result.contains("layout(std140, set = 0, binding = 0) uniform JavelinPerFrame {"), result);
        assertTrue(result.contains("u_view;"), result);
        assertTrue(result.contains("u_proj;"), result);
        assertTrue(result.contains("u_cameraPos;"), result);
        assertTrue(result.contains("u_time;"), result);
        assertTrue(result.contains("u_resolution;"), result);
        assertTrue(result.contains("u_deltaTime;"), result);
        assertTrue(result.contains("u_frame;"), result);

        // Push Constants：仅 u_mvp
        assertTrue(result.contains("layout(push_constant) uniform JavelinPushConstants {"), result);
        assertTrue(result.contains("mat4 u_mvp;"), result);

        // PerObject：set=0 binding=1，动态偏移 UBO
        assertTrue(result.contains("layout(std140, set = 0, binding = 1) uniform JavelinPerObject {"), result);
        assertTrue(result.contains("u_model;"), result);
        assertTrue(result.contains("u_normalMatrix;"), result);
    }

    @Test
    void testVersionPreservedAtTop() {
        String result = preprocessor.preprocess(SIMPLE_VS, null, null, null);
        assertTrue(result.startsWith("#version 450"), result);
        // 主体中不应残留第二处 #version
        assertEquals(1, countOccurrences(result, "#version"), result);
    }

    @Test
    void testTexturePropertiesBecomeSamplers() {
        ShaderAST.PropertiesNode props = new ShaderAST.PropertiesNode();
        props.properties.add(textureProp("_MainTex"));
        props.properties.add(textureProp("_NormalMap"));

        String result = preprocessor.preprocess(SIMPLE_VS, props, null, null);

        // 纹理按声明顺序从 binding = 0 排列，位于 set = 1
        assertTrue(result.contains("layout(set = 1, binding = 0) uniform sampler2D _MainTex;"), result);
        assertTrue(result.contains("layout(set = 1, binding = 1) uniform sampler2D _NormalMap;"), result);
        // 没有非纹理属性，不应生成 MaterialParams
        assertFalse(result.contains("MaterialParams"), result);
    }

    @Test
    void testNonTexturePropertiesBecomeMaterialParams() {
        ShaderAST.PropertiesNode props = new ShaderAST.PropertiesNode();
        props.properties.add(textureProp("_MainTex"));
        ShaderAST.PropertyNode color = new ShaderAST.PropertyNode();
        color.name = "_Color";
        color.type = "Color";
        props.properties.add(color);
        ShaderAST.PropertyNode metallic = new ShaderAST.PropertyNode();
        metallic.name = "_Metallic";
        metallic.type = "Range";
        props.properties.add(metallic);

        String result = preprocessor.preprocess(SIMPLE_VS, props, null, null);

        // 非纹理属性打包进 MaterialParams，binding = 纹理数
        assertTrue(result.contains("layout(std140, set = 1, binding = 1) uniform MaterialParams {"), result);
    }

    @Test
    void testGlslTypeMapping() {
        ShaderAST.PropertiesNode props = new ShaderAST.PropertiesNode();
        props.properties.add(plainProp("_Color", "Color"));
        props.properties.add(plainProp("_Vec4", "Vector4"));
        props.properties.add(plainProp("_Vec3", "Vector3"));
        props.properties.add(plainProp("_Vec2", "Vector2"));
        props.properties.add(plainProp("_FloatVal", "Float"));
        props.properties.add(plainProp("_RangeVal", "Range"));
        props.properties.add(plainProp("_IntVal", "Int"));
        props.properties.add(plainProp("_BoolVal", "Bool"));

        String result = preprocessor.preprocess(SIMPLE_VS, props, null, null);

        assertTrue(result.contains("vec4 _Color;"), result);
        assertTrue(result.contains("vec4 _Vec4;"), result);
        assertTrue(result.contains("vec3 _Vec3;"), result);
        assertTrue(result.contains("vec2 _Vec2;"), result);
        assertTrue(result.contains("float _FloatVal;"), result);
        assertTrue(result.contains("float _RangeVal;"), result);
        assertTrue(result.contains("int _IntVal;"), result);
        assertTrue(result.contains("bool _BoolVal;"), result);
    }

    @Test
    void testIncludeExpansion() {
        String source = """
            #version 450
            #include "common/utils.glsl"
            void main() { gl_Position = transform(vec4(0.0)); }
            """;

        ShaderPreprocessor.IncludeResolver resolver = path ->
                "common/utils.glsl".equals(path) ? "vec4 transform(vec4 p) { return p; }" : null;

        String result = preprocessor.preprocess(source, null, null, resolver);

        assertTrue(result.contains("// #include \"common/utils.glsl\""), result);
        assertTrue(result.contains("vec4 transform(vec4 p) { return p; }"), result);
        // 原始 #include 行应被展开内容替换
        assertFalse(result.lines().anyMatch(l -> l.trim().startsWith("#include")), result);
    }

    @Test
    void testIncludeResolutionFailureThrows() {
        String source = """
            #version 450
            #include "missing.glsl"
            void main() { gl_Position = vec4(0.0); }
            """;

        // resolver 返回 null
        ShaderParseException ex = assertThrows(ShaderParseException.class,
                () -> preprocessor.preprocess(source, null, null, path -> null));
        assertTrue(ex.getMessage().contains("missing.glsl"), ex.getMessage());
        assertEquals(2, ex.line);

        // resolver 为 null
        assertThrows(ShaderParseException.class,
                () -> preprocessor.preprocess(source, null, null, null));
    }

    @Test
    void testGlobalUniformsOverrideDefaults() {
        ShaderAST.GlobalUniformsNode globalUniforms = new ShaderAST.GlobalUniformsNode();

        ShaderAST.UniformBlockNode perFrame = new ShaderAST.UniformBlockNode();
        perFrame.name = "JavelinPerFrame";
        ShaderAST.UniformFieldNode view = new ShaderAST.UniformFieldNode();
        view.type = "mat4";
        view.name = "u_view";
        perFrame.fields.add(view);
        globalUniforms.perFrame = perFrame;

        ShaderAST.UniformBlockNode perObject = new ShaderAST.UniformBlockNode();
        perObject.name = "JavelinPerObject";
        ShaderAST.UniformFieldNode model = new ShaderAST.UniformFieldNode();
        model.type = "mat4";
        model.name = "u_model";
        perObject.fields.add(model);
        globalUniforms.perObject = perObject;

        String result = preprocessor.preprocess(SIMPLE_VS, null, globalUniforms, null);

        // DSL 声明覆盖默认 PerFrame / PerObject 块
        assertTrue(result.contains("layout(std140, set = 0, binding = 0) uniform JavelinPerFrame {"), result);
        assertTrue(result.contains("mat4 u_view;"), result);
        assertFalse(result.contains("u_resolution;"), result);
        assertTrue(result.contains("layout(std140, set = 0, binding = 1) uniform JavelinPerObject {"), result);

        // Push Constants 不受 GlobalUniforms 覆盖，仍仅 u_mvp
        assertTrue(result.contains("layout(push_constant) uniform JavelinPushConstants {"), result);
        assertTrue(result.contains("mat4 u_mvp;"), result);
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════════════

    private static ShaderAST.PropertyNode textureProp(String name) {
        ShaderAST.PropertyNode prop = new ShaderAST.PropertyNode();
        prop.name = name;
        prop.type = "Texture2D";
        return prop;
    }

    private static ShaderAST.PropertyNode plainProp(String name, String type) {
        ShaderAST.PropertyNode prop = new ShaderAST.PropertyNode();
        prop.name = name;
        prop.type = type;
        return prop;
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0;
        int idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }
}
