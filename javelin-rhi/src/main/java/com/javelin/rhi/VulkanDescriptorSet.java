package com.javelin.rhi;

import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * DescriptorSet 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanDescriptorSet implements DescriptorSet {

    private final VulkanDevice device;
    private final long setHandle;
    private volatile boolean disposed;

    VulkanDescriptorSet(VulkanDevice device, long setHandle) {
        this.device = device;
        this.setHandle = setHandle;
    }

    @Override
    public void update(DescriptorUpdate... updates) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writeSets = VkWriteDescriptorSet.calloc(updates.length, stack);

            for (int i = 0; i < updates.length; i++) {
                DescriptorUpdate update = updates[i];
                VkWriteDescriptorSet write = writeSets.get(i);
                write.sType$Default();
                write.dstSet(setHandle);
                write.dstBinding(update.binding());
                write.descriptorType(toVkDescriptorType(update.type()));

                switch (update.type()) {
                    case UNIFORM_BUFFER, DYNAMIC_UNIFORM_BUFFER, STORAGE_BUFFER -> {
                        VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
                        bufInfo.get(0)
                                .buffer(update.buffer().handle())
                                .offset(update.offset())
                                .range(update.range());
                        write.pBufferInfo(bufInfo);
                    }
                    case SAMPLED_TEXTURE, STORAGE_TEXTURE -> {
                        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
                        imgInfo.get(0)
                                .sampler(update.sampler() != null ? update.sampler().handle() : VK_NULL_HANDLE)
                                .imageView(update.texture().handle())
                                .imageLayout(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        write.pImageInfo(imgInfo);
                    }
                    case SAMPLER -> {
                        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
                        imgInfo.get(0)
                                .sampler(update.sampler().handle())
                                .imageView(VK_NULL_HANDLE)
                                .imageLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                        write.pImageInfo(imgInfo);
                    }
                }
            }

            vkUpdateDescriptorSets(device.vkDevice(), writeSets, null);
        }
    }

    @Override
    public long handle() {
        return setHandle;
    }

    @Override
    public void dispose() {
        // DescriptorSet 由池管理，不单独销毁
        disposed = true;
    }

    private static int toVkDescriptorType(DescriptorUpdate.Type type) {
        return switch (type) {
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case DYNAMIC_UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case SAMPLED_TEXTURE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case STORAGE_TEXTURE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case SAMPLER -> VK_DESCRIPTOR_TYPE_SAMPLER;
        };
    }
}
