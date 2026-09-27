package com.javelin.rhi;

import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * DescriptorSetLayout 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanDescriptorSetLayout implements DescriptorSetLayout {

    private final VulkanDevice device;
    private final long layoutHandle;
    private volatile boolean disposed;

    VulkanDescriptorSetLayout(VulkanDevice device, DescriptorSetLayoutDesc desc) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(
                    desc.bindings().size(), stack);

            for (int i = 0; i < desc.bindings().size(); i++) {
                DescriptorSetLayoutDesc.Binding b = desc.bindings().get(i);
                VkDescriptorSetLayoutBinding binding = bindings.get(i);
                binding.binding(b.binding());
                binding.descriptorType(toVkDescriptorType(b.type()));
                binding.descriptorCount(b.count());
                binding.stageFlags(toVkShaderStageFlags(b.stageFlags()));
            }

            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.pBindings(bindings);

            LongBuffer layoutBuf = stack.mallocLong(1);
            int result = vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, layoutBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create descriptor set layout");
            }

            this.layoutHandle = layoutBuf.get(0);
        }

        CleanerRegistry.register(this, device, layoutHandle, CleanerRegistry.ResourceType.DESCRIPTOR_SET_LAYOUT);
        device.stats().onDescriptorSetLayoutCreated();
    }

    @Override
    public long handle() {
        return layoutHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        device.stats().onDescriptorSetLayoutDisposed();
        CleanerRegistry.markDisposed(layoutHandle);
        vkDestroyDescriptorSetLayout(device.vkDevice(), layoutHandle, null);
    }

    static int toVkDescriptorType(com.javelin.rhi.types.DescriptorType type) {
        return switch (type) {
            case SAMPLER -> VK_DESCRIPTOR_TYPE_SAMPLER;
            case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case UNIFORM_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
            case STORAGE_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case UNIFORM_BUFFER_DYNAMIC -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case STORAGE_BUFFER_DYNAMIC -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
            case INPUT_ATTACHMENT -> VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
        };
    }

    static int toVkShaderStageFlags(com.javelin.rhi.types.ShaderStage stage) {
        return switch (stage) {
            case VERTEX -> VK_SHADER_STAGE_VERTEX_BIT;
            case TESSELLATION_CONTROL -> VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT;
            case TESSELLATION_EVALUATION -> VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT;
            case GEOMETRY -> VK_SHADER_STAGE_GEOMETRY_BIT;
            case FRAGMENT -> VK_SHADER_STAGE_FRAGMENT_BIT;
            case COMPUTE -> VK_SHADER_STAGE_COMPUTE_BIT;
            case ALL_GRAPHICS -> VK_SHADER_STAGE_ALL_GRAPHICS;
            case ALL -> VK_SHADER_STAGE_ALL;
        };
    }
}
