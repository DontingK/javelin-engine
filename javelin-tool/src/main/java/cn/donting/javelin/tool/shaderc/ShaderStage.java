package cn.donting.javelin.tool.shaderc;

import org.lwjgl.util.shaderc.Shaderc;

/**
 * 着色器阶段枚举。
 *
 * <p>映射到 shaderc 的着色器类型常量，用于编译时指定着色器阶段。</p>
 *
 * @author Phillip Webb
 * @since 1.0.0
 */
public enum ShaderStage {

    /**
     * 顶点着色器。
     */
    VERTEX(Shaderc.shaderc_glsl_vertex_shader),

    /**
     * 片段着色器（像素着色器）。
     */
    FRAGMENT(Shaderc.shaderc_glsl_fragment_shader),

    /**
     * 几何着色器。
     */
    GEOMETRY(Shaderc.shaderc_glsl_geometry_shader),

    /**
     * 计算着色器。
     */
    COMPUTE(Shaderc.shaderc_glsl_compute_shader),

    /**
     * 细分控制着色器（Tessellation Control / Hull）。
     */
    TESS_CONTROL(Shaderc.shaderc_glsl_tess_control_shader),

    /**
     * 细分评估着色器（Tessellation Evaluation / Domain）。
     */
    TESS_EVALUATION(Shaderc.shaderc_glsl_tess_evaluation_shader);

    private final int shadercKind;

    /**
     * 构造枚举值。
     *
     * @param shadercKind 对应的 shaderc 类型常量
     */
    ShaderStage(int shadercKind) {
        this.shadercKind = shadercKind;
    }

    /**
     * 获取 shaderc 类型常量。
     *
     * @return shaderc 类型值
     */
    public int getShadercKind() {
        return shadercKind;
    }
}
