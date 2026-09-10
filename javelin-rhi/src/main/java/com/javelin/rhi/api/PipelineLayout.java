package com.javelin.rhi.api;

import com.javelin.rhi.types.ShaderStage;

/**
 * 管线布局（描述符集布局 + 推送常量范围）。
 */
public abstract class PipelineLayout extends GPUResource {

    public abstract DescriptorSetLayout[] setLayouts();

    public abstract PushConstantRange[] pushConstantRanges();

    // ========== 内部类型 ==========

    public record PushConstantRange(ShaderStage stages, int offset, int size) {
    }
}
