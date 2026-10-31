package cn.donting.javelin.editor.importer.shader;

import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.MaterialPropertyType;
import cn.donting.javelin.editor.plugin.EditorLang;

import java.util.ArrayList;
import java.util.List;

/**
 * 声明注入器（RHI 21.5 节，v0.13）：把引擎内置块与材质声明注入到 vs/fs 块的
 * {@code #version} 行之后（无标记，位置固定）。
 *
 * <p>注入内容（与运行时实际通道一一对应，Engine_Rendering_Design 第 6 节）：
 * <pre>
 * layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; } cam;   // UBO1 动态偏移选相机
 * layout(push_constant) uniform PushBlock { mat4 model; float tpf; } pc;     // 每物件 push
 * layout(std140, set = 2, binding = 0) uniform MaterialBlock { ... } material; // 数值属性（有才注入）
 * layout(set = 2, binding = N) uniform sampler2D _Name;                      // 贴图槽，声明序
 * </pre>
 * MaterialBlock 字段排布与 {@code MaterialBlock.pack} 同源（RHI 15.7 三方布局一致）：
 * 声明序、跳过 2D；float（FLOAT/RANGE）/ vec4（COLOR/VECTOR），std140 对齐由 GLSL 编译器与
 * CPU 排布器同规则保证；无数值属性不注入 MaterialBlock（空块 16B 占位只存在 CPU 侧，
 * 描述符布局超集 Vulkan 合法）。</p>
 *
 * <p>保留字校验（RHI 21.6-7）：用户不得手写 cam / pc / material 注入同名声明，
 * CameraBlock / PushBlock / MaterialBlock 任何出现即构建报错。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class ShaderInjector {

    private static final String ENGINE_BLOCKS = """
            layout(set = 0, binding = 0) uniform CameraBlock { mat4 viewProj; } cam;
            layout(push_constant) uniform PushBlock { mat4 model; float tpf; } pc;""";

    private ShaderInjector() {
    }

    /**
     * 注入一个 vs/fs 块：#version 行之后拼入引擎内置块 + 材质声明。
     *
     * @param body       GLSL 原文（{@code #version 450} 必填，RHI 21.6-6 注入锚点）
     * @param properties Properties 声明序（MaterialBlock 字段 + sampler 槽来源）
     * @param sourceName 源文件名（报错定位）
     * @param stageName  阶段标记（"vs"/"fs"，报错定位）
     */
    public static String inject(String body, List<MaterialProperty> properties,
                                String sourceName, String stageName) {
        validateReservedNames(body, sourceName, stageName);

        int versionEnd = findVersionLineEnd(body, sourceName, stageName);
        StringBuilder injected = new StringBuilder(body.length() + 512);
        injected.append(body, 0, versionEnd);
        injected.append("\n// ── engine injected declarations (auto-generated, do not write) ──\n");
        injected.append(ENGINE_BLOCKS).append('\n');
        appendMaterialDeclaration(injected, properties);
        injected.append("// ── injection end ──\n");
        injected.append(body, versionEnd, body.length());
        return injected.toString();
    }

    /**
     * 定位 #version 行结尾（其后插入）。要求 #version 450 出现在首个非空行。
     */
    private static int findVersionLineEnd(String body, String sourceName, String stageName) {
        int i = 0;
        int length = body.length();
        while (i < length && (body.charAt(i) == ' ' || body.charAt(i) == '\t'
                || body.charAt(i) == '\r' || body.charAt(i) == '\n')) {
            i++;
        }
        if (!body.startsWith("#version", i)) {
            throw new ShaderParseException(EditorLang.key("shader.inject.missingVersion", stageName),
                    sourceName, 0);
        }
        while (i < length && body.charAt(i) != '\n') {
            i++;
        }
        return Math.min(i + 1, length);
    }

    /**
     * 材质声明：MaterialBlock（数值属性 std140）+ 贴图 sampler 槽（binding 1..n 声明序）。
     * 无数值属性时不注入 MaterialBlock。
     */
    private static void appendMaterialDeclaration(StringBuilder out, List<MaterialProperty> properties) {
        StringBuilder fields = new StringBuilder();
        for (MaterialProperty property : properties) {
            if (property.getType() == MaterialPropertyType.COLOR
                    || property.getType() == MaterialPropertyType.VECTOR) {
                fields.append("    vec4 ").append(property.getName()).append(";\n");
            } else if (property.getType() == MaterialPropertyType.FLOAT
                    || property.getType() == MaterialPropertyType.RANGE) {
                fields.append("    float ").append(property.getName()).append(";\n");
            }
        }
        if (!fields.isEmpty()) {
            out.append("layout(std140, set = 2, binding = 0) uniform MaterialBlock {\n");
            out.append(fields);
            out.append("} material;\n");
        }

        int binding = 1;
        for (MaterialProperty property : properties) {
            if (property.getType() == MaterialPropertyType.TEXTURE_2D) {
                out.append("layout(set = 2, binding = ").append(binding++)
                        .append(") uniform sampler2D ").append(property.getName()).append(";\n");
            }
        }
    }

    /**
     * 保留字校验：注入同名声明冲突（RHI 21.6-7）。块名任何出现即报错
     * （注释里提及也会拦——保留字，写注释请换说法）。
     */
    private static void validateReservedNames(String body, String sourceName, String stageName) {
        ArrayList<String> found = new ArrayList<>();
        for (String reserved : new String[]{"CameraBlock", "PushBlock", "MaterialBlock"}) {
            if (body.contains(reserved)) {
                found.add(reserved);
            }
        }
        if (!found.isEmpty()) {
            throw new ShaderParseException(EditorLang.key("shader.inject.reservedName", stageName, found),
                    sourceName, 0);
        }
    }
}
