package com.javelin.rhi.desc;

import com.javelin.rhi.types.ShaderStage;

/**
 * 着色器创建描述符。
 */
public record ShaderDesc(
    ShaderStage stage,
    byte[] spirvBytecode,
    String entryPoint
) {
    public ShaderDesc {
        if (spirvBytecode == null || spirvBytecode.length == 0) {
            throw new IllegalArgumentException("SPIR-V bytecode cannot be empty");
        }
        if (entryPoint == null || entryPoint.isEmpty()) {
            entryPoint = "main";
        }
    }

    public ShaderDesc(ShaderStage stage, byte[] spirvBytecode) {
        this(stage, spirvBytecode, "main");
    }
}
