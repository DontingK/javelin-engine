package com.javelin.rhi.desc;

import com.javelin.rhi.types.ShaderStage;

/**
 * 管线布局创建描述符。
 */
public record PipelineLayoutDesc(
    DescriptorSetLayoutDesc[] setLayouts,
    PushConstantRange[] pushConstantRanges
) {
    public PipelineLayoutDesc {
        if (setLayouts == null) setLayouts = new DescriptorSetLayoutDesc[0];
        if (pushConstantRanges == null) pushConstantRanges = new PushConstantRange[0];
    }

    public PipelineLayoutDesc(DescriptorSetLayoutDesc[] setLayouts) {
        this(setLayouts, new PushConstantRange[0]);
    }

    // ========== 内部类型 ==========

    public record PushConstantRange(ShaderStage stages, int offset, int size) {}
}
