package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Buffer;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.MemoryUsage;
import com.javelin.rhi.vulkan.util.VulkanChecks;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import org.lwjgl.PointerBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan buffer implementation. */
public class VulkanBuffer extends Buffer {

    private final VkDevice device;
    private final long buffer;
    private final long memory;
    private final long size;
    private final BufferUsage usage;
    private final MemoryUsage memoryUsage;
    private ByteBuffer mappedData;

    public VulkanBuffer(VulkanDevice device, BufferDesc desc) {
        this.device = device.vkDevice();
        this.size = desc.size();
        this.usage = desc.usage();
        this.memoryUsage = desc.memoryUsage();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Create buffer
            VkBufferCreateInfo createInfo = VkBufferCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO)
                .size(size)
                .usage(usage.value())
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer pBuffer = stack.mallocLong(1);
            VulkanChecks.check(vkCreateBuffer(this.device, createInfo, null, pBuffer), "vkCreateBuffer");
            this.buffer = pBuffer.get(0);

            // Query memory requirements
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(this.device, buffer, memReqs);

            // Allocate memory
            int memoryTypeIndex = findMemoryType(device, memReqs.memoryTypeBits(), desc.memoryUsage());

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memoryTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            VulkanChecks.check(vkAllocateMemory(this.device, allocInfo, null, pMemory), "vkAllocateMemory");
            this.memory = pMemory.get(0);

            // Bind memory
            VulkanChecks.check(vkBindBufferMemory(this.device, buffer, memory, 0), "vkBindBufferMemory");

            // Map at creation if requested
            if (desc.mappedAtCreation()) {
                map();
            }
        }
    }

    private int findMemoryType(VulkanDevice device, int typeFilter, MemoryUsage usage) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(((VulkanPhysicalDevice) device.physicalDevice()).vkPhysicalDevice(), memProps);

        int requiredFlags;
        int preferredFlags = 0;

        switch (usage) {
            case GPU_ONLY -> requiredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            case CPU_TO_GPU -> {
                requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
                preferredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
            }
            case GPU_TO_CPU -> requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
            case CPU_ONLY -> requiredFlags = VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
            default -> requiredFlags = VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
        }

        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0) {
                int flags = memProps.memoryTypes(i).propertyFlags();
                if ((flags & requiredFlags) == requiredFlags) {
                    if (preferredFlags == 0 || (flags & preferredFlags) == preferredFlags) {
                        memProps.free();
                        return i;
                    }
                }
            }
        }

        // Fallback: match required flags only
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0) {
                int flags = memProps.memoryTypes(i).propertyFlags();
                if ((flags & requiredFlags) == requiredFlags) {
                    memProps.free();
                    return i;
                }
            }
        }

        memProps.free();
        throw new RuntimeException("Failed to find suitable memory type for usage: " + usage);
    }

    @Override
    public long handle() {
        return buffer;
    }

    @Override
    protected void doDispose() {
        if (mappedData != null) {
            unmap();
        }
        vkDestroyBuffer(device, buffer, null);
        vkFreeMemory(device, memory, null);
    }

    @Override
    public long size() {
        return size;
    }

    @Override
    public BufferUsage usage() {
        return usage;
    }

    @Override
    public MemoryUsage memoryUsage() {
        return memoryUsage;
    }

    @Override
    public ByteBuffer map() {
        if (mappedData != null) return mappedData;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            VulkanChecks.check(vkMapMemory(device, memory, 0, size, 0, pData), "vkMapMemory");
            mappedData = MemoryUtil.memByteBuffer(pData.get(0), (int) size);
            return mappedData;
        }
    }

    @Override
    public ByteBuffer map(long offset, long size) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pData = stack.mallocPointer(1);
            VulkanChecks.check(vkMapMemory(device, memory, offset, size, 0, pData), "vkMapMemory");
            return MemoryUtil.memByteBuffer(pData.get(0), (int) size);
        }
    }

    @Override
    public void unmap() {
        if (mappedData != null) {
            vkUnmapMemory(device, memory);
            mappedData = null;
        }
    }

    @Override
    public boolean isMapped() {
        return mappedData != null;
    }

    @Override
    public void upload(byte[] data) {
        ByteBuffer mapped = map();
        mapped.put(data);
        unmap();
    }

    @Override
    public void upload(ByteBuffer data) {
        ByteBuffer mapped = map();
        mapped.put(data);
        unmap();
    }

    @Override
    public void upload(long offset, ByteBuffer data) {
        ByteBuffer mapped = map(offset, data.remaining());
        mapped.put(data);
        unmap();
    }

    long memory() {
        return memory;
    }
}
