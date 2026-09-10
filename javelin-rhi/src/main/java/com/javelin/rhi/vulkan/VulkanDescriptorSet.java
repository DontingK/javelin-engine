package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.types.TextureLayout;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 描述符集实现。
 */
public class VulkanDescriptorSet extends DescriptorSet {

    private final VkDevice device;
    private final long handle;
    private final DescriptorSetLayout layout;
    private final VulkanDescriptorPool pool;

    public VulkanDescriptorSet(VkDevice device, long handle, DescriptorSetLayout layout, VulkanDescriptorPool pool) {
        this.device = device;
        this.handle = handle;
        this.layout = layout;
        this.pool = pool;
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        // 由 pool 管理释放
    }

    @Override
    public DescriptorSetLayout layout() {
        return layout;
    }

    @Override
    public void writeBuffer(int binding, int arrayElement, Buffer buffer, long offset, long range) {
        writeBufferInternal(binding, arrayElement, buffer, offset, range, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);
    }

    @Override
    public void writeBuffer(int binding, Buffer buffer, long offset, long range) {
        writeBuffer(binding, 0, buffer, offset, range);
    }

    @Override
    public void writeTexture(int binding, int arrayElement, TextureView view, TextureLayout layout, Sampler sampler) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorImageInfo.Buffer imageInfo = org.lwjgl.vulkan.VkDescriptorImageInfo.calloc(1, stack)
                .imageLayout(VulkanFormats.toVkImageLayout(layout))
                .imageView(view.handle())
                .sampler(sampler != null ? sampler.handle() : VK_NULL_HANDLE);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(handle)
                .dstBinding(binding)
                .dstArrayElement(arrayElement)
                .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                .descriptorCount(1)
                .pImageInfo(imageInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }

    @Override
    public void writeTexture(int binding, TextureView view, TextureLayout layout, Sampler sampler) {
        writeTexture(binding, 0, view, layout, sampler);
    }

    @Override
    public void writeDynamicBuffer(int binding, int arrayElement, Buffer buffer, long offset, long range) {
        writeBufferInternal(binding, arrayElement, buffer, offset, range, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC);
    }

    @Override
    public void writeDynamicBuffer(int binding, Buffer buffer, long offset, long range) {
        writeDynamicBuffer(binding, 0, buffer, offset, range);
    }

    private void writeBufferInternal(int binding, int arrayElement, Buffer buffer, long offset, long range, int descriptorType) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorBufferInfo.Buffer bufferInfo = org.lwjgl.vulkan.VkDescriptorBufferInfo.calloc(1, stack)
                .buffer(buffer.handle())
                .offset(offset)
                .range(range);

            VkWriteDescriptorSet.Buffer write = VkWriteDescriptorSet.calloc(1, stack);
            write.get(0)
                .sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET)
                .dstSet(handle)
                .dstBinding(binding)
                .dstArrayElement(arrayElement)
                .descriptorType(descriptorType)
                .descriptorCount(1)
                .pBufferInfo(bufferInfo);

            vkUpdateDescriptorSets(device, write, null);
        }
    }
}
