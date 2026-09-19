package com.javelin.rhi.desc;

import java.util.List;

/**
 * 管线布局描述。
 * 对照 VkPipelineLayoutCreateInfo。
 */
public final class PipelineLayoutDesc {

    private final List<DescriptorSetLayoutDesc> descriptorSetLayouts;
    private final int pushConstantSize;

    public PipelineLayoutDesc(List<DescriptorSetLayoutDesc> descriptorSetLayouts, int pushConstantSize) {
        if (descriptorSetLayouts == null) {
            descriptorSetLayouts = List.of();
        }
        if (pushConstantSize < 0) {
            throw new IllegalArgumentException("pushConstantSize must be >= 0, got: " + pushConstantSize);
        }
        if (pushConstantSize > 128) {
            throw new IllegalArgumentException(
                    "pushConstantSize must be <= 128 bytes (vendor limit), got: " + pushConstantSize);
        }
        this.descriptorSetLayouts = List.copyOf(descriptorSetLayouts);
        this.pushConstantSize = pushConstantSize;
    }

    public List<DescriptorSetLayoutDesc> descriptorSetLayouts() {
        return descriptorSetLayouts;
    }

    public int pushConstantSize() {
        return pushConstantSize;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PipelineLayoutDesc that)) return false;
        return pushConstantSize == that.pushConstantSize
                && descriptorSetLayouts.equals(that.descriptorSetLayouts);
    }

    @Override
    public int hashCode() {
        return 31 * descriptorSetLayouts.hashCode() + pushConstantSize;
    }

    @Override
    public String toString() {
        return "PipelineLayoutDesc{sets=" + descriptorSetLayouts.size()
                + ", pushConstantSize=" + pushConstantSize + "}";
    }
}
