package com.javelin.rhi;

import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MAJOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_MINOR;
import static org.lwjgl.vulkan.VK10.VK_API_VERSION_PATCH;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_CPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
import static org.lwjgl.vulkan.VK10.VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU;

/**
 * GPU 静态信息（doc/Engine_RenderStats_Design.md §2.1）。
 *
 * <p>纯值对象：{@link VulkanDevice} 构造时从 {@code VkPhysicalDeviceProperties}
 * 一次性提取，不持有任何 LWJGL/Vulkan 对象，可安全跨层传递。构造器包私有，
 * 经 {@link Device#gpuInfo()} 只读访问。</p>
 */
public final class GPUInfo {

    private final String deviceName;
    private final int vendorId;
    private final int deviceId;
    /** DISCRETE / INTEGRATED / VIRTUAL / CPU / OTHER */
    private final String deviceType;
    private final int apiVersion;
    private final String apiVersionString;
    private final int driverVersion;
    private final String driverVersionString;

    GPUInfo(VkPhysicalDeviceProperties properties) {
        this.deviceName = properties.deviceNameString();
        this.vendorId = properties.vendorID();
        this.deviceId = properties.deviceID();
        this.deviceType = switch (properties.deviceType()) {
            case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> "DISCRETE";
            case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> "INTEGRATED";
            case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> "VIRTUAL";
            case VK_PHYSICAL_DEVICE_TYPE_CPU -> "CPU";
            default -> "OTHER";
        };
        this.apiVersion = properties.apiVersion();
        this.apiVersionString = VK_API_VERSION_MAJOR(apiVersion) + "."
                + VK_API_VERSION_MINOR(apiVersion) + "."
                + VK_API_VERSION_PATCH(apiVersion);
        this.driverVersion = properties.driverVersion();
        // driverVersion 格式厂商自定义；按 Vulkan 标准 10.10.12 位置拆位显示（NVIDIA 等语义不同，仅供参考）
        this.driverVersionString = ((driverVersion >>> 22) & 0x7F) + "."
                + ((driverVersion >>> 12) & 0x3FF) + "."
                + (driverVersion & 0xFFF);
    }

    /** 设备名（如 "NVIDIA GeForce RTX 4070"） */
    public String deviceName() {
        return deviceName;
    }

    /** 厂商 ID（PCI vendor，raw int；0x10DE = NVIDIA 等） */
    public int vendorId() {
        return vendorId;
    }

    /** 设备 ID（raw int） */
    public int deviceId() {
        return deviceId;
    }

    /** 设备类型：DISCRETE / INTEGRATED / VIRTUAL / CPU / OTHER */
    public String deviceType() {
        return deviceType;
    }

    /** 支持的 Vulkan API 版本（raw 位域） */
    public int apiVersion() {
        return apiVersion;
    }

    /** API 版本格式化 "major.minor.patch"（VK_API_VERSION_* 拆位） */
    public String apiVersionString() {
        return apiVersionString;
    }

    /** 驱动版本（raw 位域，厂商自定义格式） */
    public int driverVersion() {
        return driverVersion;
    }

    /** 驱动版本格式化（按 10.10.12 拆位显示，厂商含义自定义故仅供参考） */
    public String driverVersionString() {
        return driverVersionString;
    }

    @Override
    public String toString() {
        return deviceName + " [" + deviceType + "] vendor=0x"
                + Integer.toHexString(vendorId) + " API " + apiVersionString
                + " driver " + driverVersionString;
    }
}
