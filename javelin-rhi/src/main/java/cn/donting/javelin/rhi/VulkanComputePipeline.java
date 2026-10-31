package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * ComputePipeline 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanComputePipeline implements ComputePipeline {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanComputePipeline.class);

    private final VulkanDevice device;
    private final long pipelineHandle;
    private final long pipelineLayoutHandle;
    /** Cleaner 注册句柄：pipeline + 内部 pipelineLayout */
    private CleanerRegistry.Registration registration;
    private CleanerRegistry.Registration layoutRegistration;
    /** 管线布局引用的描述符集布局（按 desc.descriptorSetLayouts 创建，随管线销毁） */
    private final List<VulkanDescriptorSetLayout> ownedSetLayouts = new ArrayList<>();
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
            LOGGER.info("compute pipeline layout created: sets={} pushConst={} handle=0x{}",
                    desc.descriptorSetLayouts().size(), desc.pushConstantSize(),
                    Long.toHexString(pipelineLayoutHandle));

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
        LOGGER.info("compute pipeline created: handle=0x{}", Long.toHexString(pipelineHandle));

        // 规范：管线创建完成后即可销毁着色器模块——
        // 必须在 vkCreateComputePipelines 成功之后 dispose，销毁早于创建会解引用悬空句柄直接崩溃
        module.dispose();

        registration = CleanerRegistry.register(this, device, pipelineHandle, CleanerRegistry.ResourceType.PIPELINE);
        // 内部布局同样注册（与 Graphics 版对齐；此前未注册，只能靠显式 dispose 销毁）
        layoutRegistration = CleanerRegistry.register(this, device, pipelineLayoutHandle, CleanerRegistry.ResourceType.PIPELINE_LAYOUT);
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
        registration.dispose();
        layoutRegistration.dispose();
        for (VulkanDescriptorSetLayout setLayout : ownedSetLayouts) {
            setLayout.dispose();
        }
        ownedSetLayouts.clear();
    }

    private long createPipelineLayout(ComputePipelineDesc desc) {
        // 按 desc.descriptorSetLayouts 逐个创建 VkDescriptorSetLayout 并挂入管线布局；
        // 上层用相同 DescriptorSetLayoutDesc 经 createDescriptorSetLayout 创建的布局与之
        // 「定义一致」（identically defined），可安全用于分配描述符集并绑定（15.7-2）
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();

            List<DescriptorSetLayoutDesc> setDescs = desc.descriptorSetLayouts();
            if (!setDescs.isEmpty()) {
                long[] setLayoutHandles = new long[setDescs.size()];
                for (int i = 0; i < setDescs.size(); i++) {
                    VulkanDescriptorSetLayout setLayout = new VulkanDescriptorSetLayout(device, setDescs.get(i));
                    ownedSetLayouts.add(setLayout);
                    setLayoutHandles[i] = setLayout.handle();
                }
                layoutInfo.pSetLayouts(stack.longs(setLayoutHandles));
            }

            if (desc.pushConstantSize() > 0) {
                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
                pushRange.get(0)
                        .stageFlags(VK_SHADER_STAGE_COMPUTE_BIT)
                        .offset(0)
                        .size(desc.pushConstantSize());
                layoutInfo.pPushConstantRanges(pushRange);
            }

            LongBuffer layoutBuf = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, layoutBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create pipeline layout");
            }
            return layoutBuf.get(0);
        }
    }
}
