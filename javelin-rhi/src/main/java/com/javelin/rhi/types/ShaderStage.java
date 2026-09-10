package com.javelin.rhi.types;

/**
 * 着色器阶段。
 */
public enum ShaderStage {
    VERTEX(0x00000001),
    TESSELLATION_CONTROL(0x00000002),
    TESSELLATION_EVALUATION(0x00000004),
    GEOMETRY(0x00000008),
    FRAGMENT(0x00000010),
    COMPUTE(0x00000020),
    ALL_GRAPHICS(0x0000001F),
    ALL(0x7FFFFFFF);

    private final int value;

    ShaderStage(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(ShaderStage... stages) {
        int result = 0;
        for (ShaderStage stage : stages) {
            result |= stage.value;
        }
        return result;
    }
}
