package com.javelin.rhi.desc;

import com.javelin.rhi.api.PipelineLayout;
import com.javelin.rhi.types.ShaderStage;

/**
 * 计算管线创建描述符。
 */
public record ComputePipelineDesc(
    ShaderDesc computeShader,
    PipelineLayoutDesc layout
) {
    public ComputePipelineDesc {
        if (computeShader == null) throw new IllegalArgumentException("Compute shader is required");
    }
}
