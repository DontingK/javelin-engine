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

        VulkanShaderModule module;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 着色器阶段
            VkPipelineShaderStageCreateInfo stageInfo = VkPipelineShaderStageCreateInfo.calloc(stack);
            stageInfo.sType$Default();
            stageInfo.stage(VK_SHADER_STAGE_COMPUTE_BIT);
            module = new VulkanShaderModule(device, desc.shader().spirv());
            stageInfo.module(module.handle());
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

        // 规范：管线创建完成后即可销毁着色器模块——
        // 必须在 vkCreateComputePipelines 成功之后 dispose，销毁早于创建会解引用悬空句柄直接崩溃
        module.dispose();

        CleanerRegistry.register(this, device, pipelineHandle, CleanerRegistry.ResourceType.PIPELINE);
        device.stats().onComputePipelineCreated();
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
        device.stats().onComputePipelineDisposed();
        CleanerRegistry.markDisposed(pipelineHandle);
        // 内部布局显式销毁（与 Graphics 版成对；此前仅靠 Cleaner 兜底）
        CleanerRegistry.markDisposed(pipelineLayoutHandle);
        vkDestroyPipelineLayout(device.vkDevice(), pipelineLayoutHandle, null);
        vkDestroyPipeline(device.vkDevice(), pipelineHandle, null);
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
