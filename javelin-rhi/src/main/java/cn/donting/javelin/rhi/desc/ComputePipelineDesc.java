package cn.donting.javelin.rhi.desc;

import java.util.List;

/**
 * 计算管线描述。
 * 对照 VkComputePipelineCreateInfo。
 */
public final class ComputePipelineDesc {

    private final ShaderDesc shader;
    private final List<DescriptorSetLayoutDesc> descriptorSetLayouts;
    private final int pushConstantSize;

    public ComputePipelineDesc(ShaderDesc shader,
                               List<DescriptorSetLayoutDesc> descriptorSetLayouts,
                               int pushConstantSize) {
        if (shader == null) {
            throw new IllegalArgumentException("Compute shader must not be null");
        }
        if (descriptorSetLayouts == null) {
            descriptorSetLayouts = List.of();
        }
        if (pushConstantSize < 0) {
            throw new IllegalArgumentException("pushConstantSize must be >= 0, got: " + pushConstantSize);
        }
        this.shader = shader;
        this.descriptorSetLayouts = List.copyOf(descriptorSetLayouts);
        this.pushConstantSize = pushConstantSize;
    }

    public ShaderDesc shader() { return shader; }
    public List<DescriptorSetLayoutDesc> descriptorSetLayouts() { return descriptorSetLayouts; }
    public int pushConstantSize() { return pushConstantSize; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ComputePipelineDesc that)) return false;
        return shader.equals(that.shader)
                && descriptorSetLayouts.equals(that.descriptorSetLayouts)
                && pushConstantSize == that.pushConstantSize;
    }

    @Override
    public int hashCode() {
        int result = shader.hashCode();
        result = 31 * result + descriptorSetLayouts.hashCode();
        result = 31 * result + pushConstantSize;
        return result;
    }

    @Override
    public String toString() {
        return "ComputePipelineDesc{shader=" + shader
                + ", sets=" + descriptorSetLayouts.size()
                + ", pushConst=" + pushConstantSize + "}";
    }
}
