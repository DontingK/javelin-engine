package com.javelin.rhi;

import com.javelin.rhi.desc.ComputePipelineDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * ComputePipeline 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanComputePipeline implements ComputePipeline {

    private final VulkanDevice device;
    private final long pipelineHandle;
    private final long pipelineLayoutHandle;
    private volatile boolean disposed;

    VulkanComputePipeline(VulkanDevice device, ComputePipelineDesc desc, long pipelineCache) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 着色器阶段
            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack);
            stageInfo.sType$Default();
            stageInfo.stage(VK_SHADER_STAGE_COMPUTE_BIT);
            stageInfo.module(createShaderModule(desc.shader().spirv()));
            stageInfo.pName(stack.UTF8("main"));

            // 管线布局
            this.pipelineLayoutHandle = createPipelineLayout(desc);

            // 管线创建信息
            VkComputePipelineCreateInfo.Buffer pipelineInfos = VkComputePipelineCreateInfo.calloc(1, stack);
            VkComputePipelineCreateInfo pipelineInfo = pipelineInfos.get(0);
            pipelineInfo.sType$Default();
            pipelineInfo.stage(stageInfo);
            pipelineInfo.layout(pipelineLayoutHandle);

            LongBuffer pipelineBuf = stack.mallocLong(1);
            int result = vkCreateComputePipelines(device.vkDevice(), pipelineCache,
                    pipelineInfos, null, pipelineBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create compute pipeline");
            }

            this.pipelineHandle = pipelineBuf.get(0);
        }

        CleanerRegistry.register(device, pipelineHandle, CleanerRegistry.ResourceType.PIPELINE);
    }

    @Override
    public long handle() {
        return pipelineHandle;
    }

    long layoutHandle() {
        return pipelineLayoutHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        vkDestroyPipeline(device.vkDevice(), pipelineHandle, null);
    }

    private long createShaderModule(byte[] spirv) {
        VulkanShaderModule module = new VulkanShaderModule(device, spirv);
        return module.handle();
    }

    private long createPipelineLayout(ComputePipelineDesc desc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();

            LongBuffer layoutBuf = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, layoutBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create pipeline layout");
            }
            return layoutBuf.get(0);
        }
    }
}
