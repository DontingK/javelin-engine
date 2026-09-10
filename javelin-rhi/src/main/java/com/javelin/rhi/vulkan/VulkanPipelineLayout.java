package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.DescriptorSetLayout;
import com.javelin.rhi.api.PipelineLayout;
import com.javelin.rhi.desc.PipelineLayoutDesc;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPushConstantRange;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 管线布局实现
 */
public class VulkanPipelineLayout extends PipelineLayout {

    private final VkDevice device;
    private final long handle;
    private final DescriptorSetLayout[] setLayouts;
    private final PushConstantRange[] pushConstantRanges;

    public VulkanPipelineLayout(VulkanDevice device, PipelineLayoutDesc desc) {
        this.device = device.vkDevice();

        // 创建描述符集布局
        if (desc.setLayouts() != null && desc.setLayouts().length > 0) {
            this.setLayouts = new DescriptorSetLayout[desc.setLayouts().length];
            for (int i = 0; i < desc.setLayouts().length; i++) {
                this.setLayouts[i] = device.createDescriptorSetLayout(desc.setLayouts()[i]);
            }
        } else {
            this.setLayouts = new DescriptorSetLayout[0];
        }

        // 推送常量范围
        if (desc.pushConstantRanges() != null && desc.pushConstantRanges().length > 0) {
            this.pushConstantRanges = new PushConstantRange[desc.pushConstantRanges().length];
            for (int i = 0; i < desc.pushConstantRanges().length; i++) {
                PipelineLayoutDesc.PushConstantRange range = desc.pushConstantRanges()[i];
                this.pushConstantRanges[i] = new PushConstantRange(range.stages(), range.offset(), range.size());
            }
        } else {
            this.pushConstantRanges = new PushConstantRange[0];
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 描述符集布局句柄
            LongBuffer pSetLayouts = null;
            if (setLayouts.length > 0) {
                pSetLayouts = stack.mallocLong(setLayouts.length);
                for (DescriptorSetLayout layout : setLayouts) {
                    pSetLayouts.put(layout.handle());
                }
                pSetLayouts.flip();
            }

            // 推送常量范围
            VkPushConstantRange.Buffer pPushConstantRanges = null;
            if (pushConstantRanges.length > 0) {
                pPushConstantRanges = VkPushConstantRange.calloc(pushConstantRanges.length, stack);
                for (int i = 0; i < pushConstantRanges.length; i++) {
                    PushConstantRange range = pushConstantRanges[i];
                    pPushConstantRanges.get(i)
                            .stageFlags(range.stages().value())
                            .offset(range.offset())
                            .size(range.size());
                }
            }

            VkPipelineLayoutCreateInfo createInfo = VkPipelineLayoutCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO)
                    .pSetLayouts(pSetLayouts)
                    .pPushConstantRanges(pPushConstantRanges);

            LongBuffer pLayout = stack.mallocLong(1);
            VulkanChecks.check(vkCreatePipelineLayout(device.vkDevice(), createInfo, null, pLayout), "vkCreatePipelineLayout");
            this.handle = pLayout.get(0);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyPipelineLayout(device, handle, null);
        for (DescriptorSetLayout layout : setLayouts) {
            layout.dispose();
        }
    }

    @Override
    public DescriptorSetLayout[] setLayouts() {
        return setLayouts;
    }

    @Override
    public PushConstantRange[] pushConstantRanges() {
        return pushConstantRanges;
    }
}
