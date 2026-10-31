package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.exception.VulkanException;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRSurface.*;

/**
 * 物理设备选择。
 * 对照设计文档第 23.4a 节阶段 2。
 */
final class VulkanPhysicalDevice {

    private final VkPhysicalDevice device;
    private final VkPhysicalDeviceProperties properties;
    private final VkPhysicalDeviceFeatures features;
    private final List<QueueFamilyInfo> queueFamilies;
    private final int graphicsFamily;
    private final int presentFamily;

    private VulkanPhysicalDevice(VkPhysicalDevice device,
                                 VkPhysicalDeviceProperties properties,
                                 VkPhysicalDeviceFeatures features,
                                 List<QueueFamilyInfo> queueFamilies,
                                 int graphicsFamily, int presentFamily) {
        this.device = device;
        this.properties = properties;
        this.features = features;
        this.queueFamilies = queueFamilies;
        this.graphicsFamily = graphicsFamily;
        this.presentFamily = presentFamily;
    }

    VkPhysicalDevice device() { return device; }
    VkPhysicalDeviceProperties properties() { return properties; }
    VkPhysicalDeviceFeatures features() { return features; }
    int graphicsFamily() { return graphicsFamily; }
    int presentFamily() { return presentFamily; }
    List<QueueFamilyInfo> queueFamilies() { return queueFamilies; }

    String deviceName() {
        return properties.deviceNameString();
    }

    boolean isDiscreteGPU() {
        return properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
    }

    /**
     * 选择最佳物理设备。
     */
    static VulkanPhysicalDevice select(VkInstance instance, RHIDevicePrefs prefs, long surfaceHandle) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 枚举物理设备
            IntBuffer countBuf = stack.mallocInt(1);
            int result = vkEnumeratePhysicalDevices(instance, countBuf, null);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to enumerate physical devices");
            }

            int count = countBuf.get(0);
            if (count == 0) {
                throw new IllegalStateException("No Vulkan physical devices found");
            }

            PointerBuffer devicesBuf = stack.mallocPointer(count);
            result = vkEnumeratePhysicalDevices(instance, countBuf, devicesBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to enumerate physical devices");
            }

            // 评分并选择
            VulkanPhysicalDevice best = null;
            int bestScore = -1;

            for (int i = 0; i < count; i++) {
                VkPhysicalDevice physDevice = new VkPhysicalDevice(devicesBuf.get(i), instance);

                VulkanPhysicalDevice candidate = evaluate(physDevice, prefs, surfaceHandle, stack);
                if (candidate == null) continue;

                int score = score(candidate, prefs);
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }

            if (best == null) {
                throw new IllegalStateException("No suitable Vulkan physical device found");
            }

            return best;
        }
    }

    private static VulkanPhysicalDevice evaluate(VkPhysicalDevice physDevice,
                                                  RHIDevicePrefs prefs,
                                                  long surfaceHandle,
                                                  MemoryStack stack) {
        // 属性/特性：堆分配（无栈参 calloc）——引用被实例带出本方法后跨栈帧长期存活；
        // 栈上分配会在 select 栈帧弹出后被后续 stackPush 复用覆写（GPUInfo 构造期早读
        // 侥幸正确，limits/samplerAnisotropy 后期读全是覆写数据——MSAA 能力误判
        // [COUNT_1] 的根因，2026-10-06）
        VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc();
        vkGetPhysicalDeviceProperties(physDevice, props);

        VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.calloc();
        vkGetPhysicalDeviceFeatures(physDevice, feats);

        // 必需特性检查
        if (prefs.requireSamplerAnisotropy() && !feats.samplerAnisotropy()) {
            return null;
        }

        // 队列族
        IntBuffer qfCountBuf = stack.mallocInt(1);
        vkGetPhysicalDeviceQueueFamilyProperties(physDevice, qfCountBuf, null);
        int qfCount = qfCountBuf.get(0);

        VkQueueFamilyProperties.Buffer qfProps = VkQueueFamilyProperties.malloc(qfCount, stack);
        vkGetPhysicalDeviceQueueFamilyProperties(physDevice, qfCountBuf, qfProps);

        List<QueueFamilyInfo> families = new ArrayList<>();
        int graphicsFamily = -1;
        int presentFamily = -1;

        for (int i = 0; i < qfCount; i++) {
            VkQueueFamilyProperties qf = qfProps.get(i);
            boolean graphics = (qf.queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0;
            boolean compute = (qf.queueFlags() & VK_QUEUE_COMPUTE_BIT) != 0;
            boolean transfer = (qf.queueFlags() & VK_QUEUE_TRANSFER_BIT) != 0;

            // 检查 present 支持
            boolean present = false;
            if (surfaceHandle != 0) {
                IntBuffer supportedBuf = stack.mallocInt(1);
                int result = vkGetPhysicalDeviceSurfaceSupportKHR(physDevice, i, surfaceHandle, supportedBuf);
                if (result == VK_SUCCESS) {
                    present = supportedBuf.get(0) != 0;
                }
            }

            families.add(new QueueFamilyInfo(i, graphics, compute, transfer, present, qf.queueCount()));

            if (graphics && graphicsFamily < 0) {
                graphicsFamily = i;
            }
            if (present && presentFamily < 0) {
                presentFamily = i;
            }
        }

        // 必需队列族
        if (graphicsFamily < 0) {
            return null;
        }
        // surface 提供时必须找到 present 族
        if (surfaceHandle != 0 && presentFamily < 0) {
            return null;
        }

        return new VulkanPhysicalDevice(physDevice, props, feats, families, graphicsFamily, presentFamily);
    }

    private static int score(VulkanPhysicalDevice candidate, RHIDevicePrefs prefs) {
        int score = 0;

        // 独立显卡加分
        if (candidate.isDiscreteGPU()) {
            score += 1000;
        }

        // 能力近似评分
        score += candidate.properties.limits().maxImageDimension2D() / 100;

        // 队列族完整性
        if (candidate.graphicsFamily() == candidate.presentFamily()) {
            score += 100;
        }

        return score;
    }

    /**
     * 队列族信息。
     */
    static final class QueueFamilyInfo {
        final int index;
        final boolean graphics;
        final boolean compute;
        final boolean transfer;
        final boolean present;
        final int queueCount;

        QueueFamilyInfo(int index, boolean graphics, boolean compute,
                        boolean transfer, boolean present, int queueCount) {
            this.index = index;
            this.graphics = graphics;
            this.compute = compute;
            this.transfer = transfer;
            this.present = present;
            this.queueCount = queueCount;
        }

        @Override
        public String toString() {
            return "QueueFamily[" + index + "] gfx=" + graphics
                    + " comp=" + compute + " xfer=" + transfer
                    + " present=" + present + " count=" + queueCount;
        }
    }
}
