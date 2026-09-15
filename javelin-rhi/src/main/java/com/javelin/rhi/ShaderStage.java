package com.javelin.rhi;

/**
 * 着色器阶段常量定义，用于按位掩码指定管线绑定的着色器阶段。
 */
public final class ShaderStage {

    private ShaderStage() {
    }

    /** 顶点着色器阶段。 */
    public static final int VERTEX = 1;
    /** 片段着色器阶段。 */
    public static final int FRAGMENT = 2;
    /** 计算着色器阶段。 */
    public static final int COMPUTE = 4;
    /** 几何着色器阶段。 */
    public static final int GEOMETRY = 8;
    /** 曲面细分控制着色器阶段。 */
    public static final int TESSELLATION_CONTROL = 16;
    /** 曲面细分评估着色器阶段。 */
    public static final int TESSELLATION_EVALUATION = 32;
}
