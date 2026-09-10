package com.javelin.rhi.desc;

import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.ShaderStage;

/**
 * 描述符集布局创建描述符。
 */
public record DescriptorSetLayoutDesc(
    Binding[] bindings
) {
    public DescriptorSetLayoutDesc {
        if (bindings == null) bindings = new Binding[0];
    }

    // ========== 内部类型 ==========

    public record Binding(
        int binding,
        DescriptorType descriptorType,
        int descriptorCount,
        ShaderStage stageFlags
    ) {
        public Binding(int binding, DescriptorType descriptorType, ShaderStage stageFlags) {
            this(binding, descriptorType, 1, stageFlags);
        }
    }
}
