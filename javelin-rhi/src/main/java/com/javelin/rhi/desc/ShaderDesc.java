package com.javelin.rhi.desc;

import com.javelin.rhi.types.ShaderStage;

/**
 * 着色器描述。
 * 对照 VkShaderModuleCreateInfo + VkPipelineShaderStageCreateInfo。
 */
public final class ShaderDesc {

    private final ShaderStage stage;
    private final byte[] spirv;

    public ShaderDesc(ShaderStage stage, byte[] spirv) {
        if (stage == null) {
            throw new IllegalArgumentException("ShaderStage must not be null");
        }
        if (spirv == null || spirv.length == 0) {
            throw new IllegalArgumentException("SPIR-V bytecode must not be empty");
        }
        this.stage = stage;
        this.spirv = spirv.clone();
    }

    public ShaderStage stage() { return stage; }

    /** 返回 SPIR-V 字节码的防御性拷贝 */
    public byte[] spirv() { return spirv.clone(); }

    /** SPIR-V 字节码长度 */
    public int spirvSize() { return spirv.length; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ShaderDesc that)) return false;
        if (stage != that.stage) return false;
        if (spirv.length != that.spirv.length) return false;
        for (int i = 0; i < spirv.length; i++) {
            if (spirv[i] != that.spirv[i]) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int result = stage.hashCode();
        for (byte b : spirv) {
            result = 31 * result + b;
        }
        return result;
    }

    @Override
    public String toString() {
        return "ShaderDesc{stage=" + stage + ", spirvSize=" + spirv.length + "}";
    }
}
