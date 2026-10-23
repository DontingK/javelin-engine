package com.javelin.rhi;

import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.types.BufferUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Buffer 实现。
 * 对照设计文档第 26.2 节。
 */
final class VulkanBuffer implements Buffer {

    private final VulkanDevice device;
    private final BufferDesc desc;
    private final long bufferHandle;
    private final long memoryHandle;
    private final long size;
    private volatile boolean disposed;

    VulkanBuffer(VulkanDevice device, BufferDesc desc) {
        this.device = device;
        this.desc = desc;
        this.size = desc.size();

        VkDevice vkDevice = device.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 创建缓冲
            VkBufferCreateInfo bufferInfo = VkBufferCreateInfo.calloc(stack);
            bufferInfo.sType$Default();
            bufferInfo.size(desc.size());
            bufferInfo.usage(toVkBufferUsage(desc.usage()));
            bufferInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);

            LongBuffer buf = stack.mallocLong(1);
            int result = vkCreateBuffer(vkDevice, bufferInfo, null, buf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create buffer");
            }
            this.bufferHandle = buf.get(0);

            // 内存需求
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetBufferMemoryRequirements(vkDevice, bufferHandle, memReqs);

            // 分配内存
            int memType = VulkanMemory.findMemoryType(
                    device.physicalDevice().device(),
                    memReqs.memoryTypeBits(),
                    desc.memory());

            this.memoryHandle = VulkanMemory.allocate(vkDevice, memReqs.size(), memType);

            // 绑定
            result = vkBindBufferMemory(vkDevice, bufferHandle, memoryHandle, 0);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to bind buffer memory");
            }
        }

        // 注册 Cleaner
        CleanerRegistry.register(this, device, bufferHandle, CleanerRegistry.ResourceType.BUFFER);
        device.stats().onBufferCreated();
    }

    @Override
    public BufferDesc desc() {
        return desc;
    }

    @Override
    public int writeAligned(int offset, FloatBuffer data) {
        // 简化实现：直接映射内存写入
        // 实际应该用 FrameArena，这里先保证功能正确
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            int result = vkMapMemory(device.vkDevice(), memoryHandle, offset, data.remaining() * 4, 0, ppData);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to map buffer memory");
            }

            ByteBuffer mapped = ppData.getByteBuffer(0, data.remaining() * 4);
            mapped.asFloatBuffer().put(data);
            vkUnmapMemory(device.vkDevice(), memoryHandle);

            // 对齐到 256B
            return (offset + data.remaining() * 4 + 255) & ~255;
        }
    }

    @Override
    public void update(int offset, ByteBuffer data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            int result = vkMapMemory(device.vkDevice(), memoryHandle, offset, data.remaining(), 0, ppData);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to map buffer memory");
            }

            ByteBuffer mapped = ppData.getByteBuffer(0, data.remaining());
            mapped.put(data);
            vkUnmapMemory(device.vkDevice(), memoryHandle);
        }
    }

    @Override
    public void read(int offset, int length, ByteBuffer out) {
        if (length < 0 || out.remaining() < length) {
            throw new IllegalArgumentException("read length out of bounds: length=" + length
                    + " out.remaining=" + out.remaining());
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.PointerBuffer ppData = stack.mallocPointer(1);
            int result = vkMapMemory(device.vkDevice(), memoryHandle, offset, length, 0, ppData);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to map buffer memory");
            }

            ByteBuffer mapped = ppData.getByteBuffer(0, length);
            out.put(mapped);
            vkUnmapMemory(device.vkDevice(), memoryHandle);
        }
    }

    @Override
    public long handle() {
        return bufferHandle;
    }

    long memoryHandle() {
        return memoryHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        device.stats().onBufferDisposed();
        CleanerRegistry.markDisposed(bufferHandle);
        VulkanMemory.free(device.vkDevice(), memoryHandle);
        vkDestroyBuffer(device.vkDevice(), bufferHandle, null);
    }

    private static int toVkBufferUsage(java.util.Set<BufferUsage> usages) {
        int flags = 0;
        for (BufferUsage usage : usages) {
            switch (usage) {
                case TRANSFER_SRC -> flags |= VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
                case TRANSFER_DST -> flags |= VK_BUFFER_USAGE_TRANSFER_DST_BIT;
                case UNIFORM_TEXEL -> flags |= VK_BUFFER_USAGE_UNIFORM_TEXEL_BUFFER_BIT;
                case STORAGE_TEXEL -> flags |= VK_BUFFER_USAGE_STORAGE_TEXEL_BUFFER_BIT;
                case UNIFORM -> flags |= VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                case STORAGE -> flags |= VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
                case INDEX -> flags |= VK_BUFFER_USAGE_INDEX_BUFFER_BIT;
                case VERTEX -> flags |= VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;
                case INDIRECT -> flags |= VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
            }
        }
        return flags;
    }
}
