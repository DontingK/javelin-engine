package com.javelin.rhi;

import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.desc.PipelineLayoutDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * PipelineLayout 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanPipelineLayout implements PipelineLayout {

    private final VulkanDevice device;
    private final long layoutHandle;
    private volatile boolean disposed;

    VulkanPipelineLayout(VulkanDevice device, PipelineLayoutDesc desc) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // set layouts
            LongBuffer setLayouts = null;
            if (!desc.descriptorSetLayouts().isEmpty()) {
                setLayouts = stack.mallocLong(desc.descriptorSetLayouts().size());
                for (DescriptorSetLayoutDesc layoutDesc : desc.descriptorSetLayouts()) {
                    // 注意：这里需要实际的 DescriptorSetLayout handle
                    // 暂时跳过，Stage 4 完善
                }
            }

            // push constant ranges
            VkPushConstantRange.Buffer pushRanges = null;
            if (desc.pushConstantSize() > 0) {
                pushRanges = VkPushConstantRange.calloc(1, stack);
                VkPushConstantRange range = pushRanges.get(0);
                range.stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS);
                range.offset(0);
                range.size(desc.pushConstantSize());
            }

            VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            createInfo.sType$Default();
            if (setLayouts != null) {
                createInfo.pSetLayouts(setLayouts);
            }
            if (pushRanges != null) {
                createInfo.pPushConstantRanges(pushRanges);
            }

            LongBuffer layoutBuf = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device.vkDevice(), createInfo, null, layoutBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create pipeline layout");
            }

            this.layoutHandle = layoutBuf.get(0);
        }

        CleanerRegistry.register(this, device, layoutHandle, CleanerRegistry.ResourceType.PIPELINE_LAYOUT);
    }

    @Override
    public long handle() {
        return layoutHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        CleanerRegistry.markDisposed(layoutHandle);
        vkDestroyPipelineLayout(device.vkDevice(), layoutHandle, null);
    }
}
