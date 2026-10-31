package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.types.MemoryUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 内存分配工具。
 * 对照设计文档第 25.2 节（required / preferred 两级选择）。
 */
final class VulkanMemory {

    private VulkanMemory() {}

    /**
     * 查找合适的内存类型索引。
     *
     * @param physicalDevice 物理设备
     * @param typeBits       允许的内存类型位（来自 memoryRequirements.memoryTypeBits）
     * @param preference     内存偏好
     * @return 内存类型索引
     */
    static int findMemoryType(VkPhysicalDevice physicalDevice, int typeBits, MemoryPreference preference) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc();
        try {
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memProps);

            int requiredMask = toVkMemoryFlags(preference.required());
            int preferredMask = toVkMemoryFlags(preference.preferred());

            int bestType = -1;
            int bestScore = -1;

            for (int i = 0; i < memProps.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) == 0) continue;

                int props = memProps.memoryTypes(i).propertyFlags();

                // 检查 required
                if ((props & requiredMask) != requiredMask) continue;

                // 计算 preferred 命中数
                int score = Integer.bitCount(props & preferredMask);
                if (score > bestScore) {
                    bestScore = score;
                    bestType = i;
                }
            }

            if (bestType < 0) {
                throw new IllegalStateException(
                        "No suitable memory type found for typeBits=" + typeBits
                                + " required=" + preference.required());
            }

            return bestType;
        } finally {
            memProps.free();
        }
    }

    /**
     * 分配设备内存。
     */
    static long allocate(VkDevice device, long size, int memoryTypeIndex) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack);
            allocInfo.sType$Default();
            allocInfo.allocationSize(size);
            allocInfo.memoryTypeIndex(memoryTypeIndex);

            LongBuffer memBuf = stack.mallocLong(1);
            int result = vkAllocateMemory(device, allocInfo, null, memBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to allocate device memory");
            }

            return memBuf.get(0);
        }
    }

    /**
     * 释放设备内存。
     */
    static void free(VkDevice device, long memory) {
        vkFreeMemory(device, memory, null);
    }

    private static int toVkMemoryFlags(java.util.Set<MemoryUsage> usages) {
        int flags = 0;
        for (var usage : usages) {
            switch (usage) {
                case DEVICE_LOCAL -> flags |= VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT;
                case HOST_VISIBLE -> flags |= VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT;
                case HOST_COHERENT -> flags |= VK_MEMORY_PROPERTY_HOST_COHERENT_BIT;
                case HOST_CACHED -> flags |= VK_MEMORY_PROPERTY_HOST_CACHED_BIT;
            }
        }
        return flags;
    }
}
