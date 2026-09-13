package com.javelin.editor.importer.shader;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GLSL 预处理器 —— 在 SPIR-V 编译前对着色器代码进行预处理。
 *
 * <p>按序处理：</p>
 * <ol>
 *   <li>{@code #version} 保留（若存在必须在首个非空行，否则报错）</li>
 *   <li>注入全局 uniform：{@code JavelinPerFrame}（set=0, binding=0）、
 *       {@code JavelinPushConstants}（仅 u_mvp）、{@code JavelinPerObject}
 *       （set=0, binding=1，动态偏移）；DSL 声明了 GlobalUniforms 时覆盖前两者</li>
 *   <li>从 Properties 生成材质声明：Texture2D → sampler2D（set=1，binding 从 0 顺序排），
 *       其余属性 → MaterialParams std140 UBO（set=1, binding=纹理数）</li>
 *   <li>展开 {@code #include}（由 {@link IncludeResolver} 提供文件内容，
 *       解析失败抛出 {@link ShaderParseException}）</li>
 * </ol>
 */
public class ShaderPreprocessor {

    /** 默认的 JavelinPerFrame Uniform 块声明（每帧填充，framesInFlight 轮转） */
    public static final String DEFAULT_PER_FRAME = """
        layout(std140, set = 0, binding = 0) uniform JavelinPerFrame {
            mat4  u_view;
            mat4  u_proj;
            vec3  u_cameraPos;
            float u_time;
            vec2  u_resolution;
            float u_deltaTime;
            int   u_frame;
        };
        """;

    /** 默认的 Push Constants 声明（仅 MVP，64B，不受 GlobalUniforms 覆盖；无实例名，u_mvp 直接可见） */
    public static final String DEFAULT_PUSH_CONSTANTS = """
        layout(push_constant) uniform JavelinPushConstants {
            mat4 u_mvp;
        };
        """;

    /** 默认的 JavelinPerObject Uniform 块声明（动态偏移寻址） */
    public static final String DEFAULT_PER_OBJECT = """
        layout(std140, set = 0, binding = 1) uniform JavelinPerObject {
            mat4 u_model;
            mat4 u_normalMatrix;
        };
        """;

    /**
     * 预处理着色器代码。
     *
     * @param source          原始 GLSL 源码
     * @param properties      Shader 的 Properties 定义（用于生成材质声明），可为 null
     * @param globalUniforms  全局 Uniform 定义（null 使用默认块）
     * @param includeResolver include 文件解析器，可为 null（遇到 #include 时报错）
     * @return 预处理后的 GLSL 源码
     * @throws ShaderParseException #version 位置非法或 include 解析失败时抛出
     */
    public String preprocess(String source,
                             ShaderAST.PropertiesNode properties,
                             ShaderAST.GlobalUniformsNode globalUniforms,
                             IncludeResolver includeResolver) {
        StringBuilder result = new StringBuilder();

        // 1. 保留 #version（必须在首个非空行）
        String versionLine = extractVersionLine(source);
        if (versionLine != null) {
            result.append(versionLine).append("\n");
        }

        // 2. 注入全局 uniform（PerFrame → PushConstants → PerObject）
        result.append(injectGlobalUniforms(globalUniforms));

        // 3. 生成材质声明（sampler2D + MaterialParams UBO）
        String materialDecls = generateMaterialDecls(properties);
        if (materialDecls != null) {
            result.append(materialDecls);
        }

        // 4. 源码主体（去掉 #version 行，展开 #include）
        String body = removeVersionLine(source);
        body = processIncludes(body, includeResolver);
        result.append(body);

        return result.toString();
    }

    /**
     * 提取并校验 #version 行。
     *
     * <p>#version 若存在必须位于首个非空行，否则抛出 {@link ShaderParseException}。</p>
     *
     * @param source GLSL 源码
     * @return #version 行内容，不存在返回 null
     */
    private String extractVersionLine(String source) {
        String[] lines = source.split("\n");
        int firstNonEmpty = -1;
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (firstNonEmpty == -1) {
                firstNonEmpty = i;
            }
            if (trimmed.startsWith("#version")) {
                if (i != firstNonEmpty) {
                    throw new ShaderParseException("#version 必须是首个非空行", i + 1, 1);
                }
                return trimmed;
            }
        }
        return null;
    }

    /** 移除首行的 #version（已保留在结果顶部） */
    private String removeVersionLine(String source) {
        StringBuilder sb = new StringBuilder();
        for (String line : source.split("\n")) {
            if (!line.trim().startsWith("#version")) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }

    /**
     * 注入全局 Uniform 声明。
     *
     * <p>DSL 的 GlobalUniforms 覆盖 PerFrame / PerObject 默认块，
     * Push Constants 块固定为 {@code u_mvp}，不受覆盖。</p>
     */
    private String injectGlobalUniforms(ShaderAST.GlobalUniformsNode globalUniforms) {
        StringBuilder sb = new StringBuilder();

        if (globalUniforms != null && globalUniforms.perFrame != null) {
            sb.append(formatUniformBlock(globalUniforms.perFrame, 0, 0));
        } else {
            sb.append(DEFAULT_PER_FRAME);
        }
        sb.append("\n");

        sb.append(DEFAULT_PUSH_CONSTANTS).append("\n");

        if (globalUniforms != null && globalUniforms.perObject != null) {
            sb.append(formatUniformBlock(globalUniforms.perObject, 0, 1));
        } else {
            sb.append(DEFAULT_PER_OBJECT);
        }
        sb.append("\n");

        return sb.toString();
    }

    /** 格式化 Uniform 块为 std140 UBO 声明 */
    private String formatUniformBlock(ShaderAST.UniformBlockNode block, int set, int binding) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("layout(std140, set = %d, binding = %d) uniform %s {%n", set, binding, block.name));
        for (ShaderAST.UniformFieldNode field : block.fields) {
            sb.append("    ").append(field.type).append(" ").append(field.name).append(";\n");
        }
        sb.append("};\n");
        return sb.toString();
    }

    /**
     * 从 Properties 生成材质声明。
     *
     * <p>每个 Texture2D 生成一个 sampler2D（set=1，binding 从 0 按声明顺序），
     * 其余属性打包进 MaterialParams std140 UBO（set=1, binding=纹理数）。
     * 全部为纹理或没有属性时不会生成 UBO。</p>
     *
     * @param properties Properties 节点，可为 null
     * @return GLSL 声明文本，无可生成内容返回 null
     */
    private String generateMaterialDecls(ShaderAST.PropertiesNode properties) {
        if (properties == null || properties.properties.isEmpty()) {
            return null;
        }

        List<ShaderAST.PropertyNode> uboFields = new ArrayList<>();
        List<ShaderAST.PropertyNode> textureFields = new ArrayList<>();

        for (ShaderAST.PropertyNode prop : properties.properties) {
            if ("Texture2D".equals(prop.type)) {
                textureFields.add(prop);
            } else {
                uboFields.add(prop);
            }
        }

        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < textureFields.size(); i++) {
            ShaderAST.PropertyNode prop = textureFields.get(i);
            sb.append(String.format("layout(set = 1, binding = %d) uniform sampler2D %s;%n", i, prop.name));
        }

        if (!uboFields.isEmpty()) {
            int uboBinding = textureFields.size();
            sb.append(String.format("layout(std140, set = 1, binding = %d) uniform MaterialParams {%n", uboBinding));
            for (ShaderAST.PropertyNode prop : uboFields) {
                sb.append("    ").append(mapPropertyTypeToGlsl(prop.type)).append(" ").append(prop.name).append(";\n");
            }
            sb.append("};\n");
        }

        return sb.length() > 0 ? sb.toString() : null;
    }

    /** 属性类型到 GLSL 类型的映射（std140 下 Bool 按 int 布局，仍声明为 bool） */
    private String mapPropertyTypeToGlsl(String type) {
        return switch (type) {
            case "Float", "Range" -> "float";
            case "Int" -> "int";
            case "Bool" -> "bool";
            case "Vector2" -> "vec2";
            case "Vector3" -> "vec3";
            case "Vector4", "Color" -> "vec4";
            default -> "float"; // fallback
        };
    }

    /**
     * 展开 {@code #include} 指令。
     *
     * <p>同一文件多次包含只展开一次；解析不到路径或解析器为 null 时
     * 抛出 {@link ShaderParseException}（带行号）。</p>
     *
     * @param source   GLSL 源码
     * @param resolver include 解析器
     * @return 展开后的源码
     */
    private String processIncludes(String source, IncludeResolver resolver) {
        StringBuilder result = new StringBuilder();
        Set<String> included = new HashSet<>();
        String[] lines = source.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith("#include")) {
                String path = extractIncludePath(trimmed);
                int lineNo = i + 1;
                if (path == null) {
                    throw new ShaderParseException("#include 缺少路径: " + trimmed, lineNo, 1);
                }
                if (included.contains(path)) {
                    continue;
                }
                if (resolver == null) {
                    throw new ShaderParseException("未提供 IncludeResolver，无法解析 #include: " + path, lineNo, 1);
                }
                String content = resolver.resolve(path);
                if (content == null) {
                    throw new ShaderParseException("无法解析 #include: " + path, lineNo, 1);
                }
                included.add(path);
                result.append("// #include \"").append(path).append("\"\n");
                result.append(content).append("\n");
            } else {
                result.append(line).append("\n");
            }
        }

        return result.toString();
    }

    /** 提取 #include 双引号内的路径 */
    private String extractIncludePath(String line) {
        int start = line.indexOf('"');
        int end = line.lastIndexOf('"');
        if (start >= 0 && end > start) {
            return line.substring(start + 1, end);
        }
        return null;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  IncludeResolver 接口
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Include 文件解析器接口，由编辑器导入管线提供。
     */
    @FunctionalInterface
    public interface IncludeResolver {
        /**
         * 解析 include 路径为文件内容。
         *
         * @param path include 路径（如 "common/brdf.glsl"）
         * @return 文件内容，找不到返回 null
         */
        String resolve(String path);
    }
}
