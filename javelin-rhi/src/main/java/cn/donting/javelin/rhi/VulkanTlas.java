package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.TlasInstance;
import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.types.BufferUsage;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * TLAS 实现（P4-06 B2b）：实例缓冲（host-visible 直写、带设备地址）+ 固定容量 AS 存储 +
 * 预分配 scratch；每次 {@link #build} 只写实例数据并记录一次重建 + 构建后屏障。
 *
 * <p><b>时序前提</b>：实例缓冲单份不双缓冲——调用方必须保证上一帧已读到该缓冲
 * （B3 之前按「每帧开头、上一帧 fence 已等」用；上多帧飞行时改双缓冲，记 B3 后置项）。</p>
 */
final class VulkanTlas implements Tlas {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanTlas.class);

    private final VulkanDevice device;
    private final int capacity;
    private final long handle;
    private final VulkanBuffer storage;
    private final VulkanBuffer instanceBuffer;
    private final VulkanBuffer scratch;
    private final long scratchAddress;
    private final long deviceAddress;
    private final ByteBuffer staging;
    private volatile boolean disposed;
    private boolean buildLogged;

    VulkanTlas(VulkanDevice device, int capacity) {
        if (!device.rayQueryEnabled()) {
            // 红线 15：能力缺失显性报错
            throw new IllegalStateException(
                    "本设备未启用光追能力（VK_KHR_ray_query + acceleration_structure），不能构建 TLAS");
        }
        if (capacity <= 0) {
            throw new IllegalArgumentException("TLAS 容量必须为正: " + capacity);
        }
        this.device = device;
        this.capacity = capacity;
        this.staging = ByteBuffer.allocateDirect(capacity * TlasInstance.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        VkDevice vkDevice = device.vkDevice();

        this.instanceBuffer = new VulkanBuffer(device, new BufferDesc(capacity * TlasInstance.BYTES,
                Set.of(BufferUsage.STORAGE, BufferUsage.SHADER_DEVICE_ADDRESS,
                        BufferUsage.ACCELERATION_STRUCTURE_BUILD_INPUT),
                MemoryPreference.hostVisibleCoherent()));

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry =
                    VkAsGeometry.tlasGeometry(stack, instanceBuffer.deviceAddress());
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAsGeometry.tlasBuildInfo(stack, geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            IntBuffer maxInstances = stack.ints(capacity);
            vkGetAccelerationStructureBuildSizesKHR(vkDevice, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo, maxInstances, sizes);

            this.storage = new VulkanBuffer(device, new BufferDesc(sizes.accelerationStructureSize(),
                    Set.of(BufferUsage.STORAGE, BufferUsage.SHADER_DEVICE_ADDRESS,
                            BufferUsage.ACCELERATION_STRUCTURE_STORAGE),
                    MemoryPreference.deviceLocal()));

            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
            createInfo.size(sizes.accelerationStructureSize());
            createInfo.buffer(storage.handle());
            createInfo.offset(0);
            LongBuffer asBuf = stack.mallocLong(1);
            int result = vkCreateAccelerationStructureKHR(vkDevice, createInfo, null, asBuf);
            if (result != VK_SUCCESS) {
                storage.dispose();
                instanceBuffer.dispose();
                throw new VulkanException(result, "Failed to create top-level acceleration structure");
            }
            this.handle = asBuf.get(0);

            int alignment = Math.max(device.physicalDevice().accelerationStructureScratchAlignment(), 1);
            long scratchBytes = sizes.buildScratchSize() + alignment;
            this.scratch = new VulkanBuffer(device, new BufferDesc(scratchBytes,
                    Set.of(BufferUsage.STORAGE, BufferUsage.SHADER_DEVICE_ADDRESS),
                    MemoryPreference.deviceLocal()));
            this.scratchAddress = VkAsGeometry.alignUp(scratch.deviceAddress(), alignment);

            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default();
            addressInfo.accelerationStructure(handle);
            this.deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vkDevice, addressInfo);
        }
    }

    @Override
    public long deviceAddress() { return deviceAddress; }

    @Override
    public int capacity() { return capacity; }

    @Override
    public long handle() { return handle; }

    @Override
    public void build(CommandBuffer cmd, List<TlasInstance> instances) {
        if (disposed) {
            throw new IllegalStateException("TLAS 已销毁");
        }
        int count = instances.size();
        if (count > capacity) {
            throw new IllegalArgumentException(
                    "实例数超过 TLAS 容量: " + count + " > " + capacity);
        }
        staging.clear();
        for (int i = 0; i < count; i++) {
            TlasInstance instance = instances.get(i);
            Matrix4fc m = instance.transform();
            // 3×4 行主序（VkTransformMatrixKHR），w 行由 Vulkan 侧补 (0,0,0,1)
            staging.putFloat(m.m00()).putFloat(m.m01()).putFloat(m.m02()).putFloat(m.m03());
            staging.putFloat(m.m10()).putFloat(m.m11()).putFloat(m.m12()).putFloat(m.m13());
            staging.putFloat(m.m20()).putFloat(m.m21()).putFloat(m.m22()).putFloat(m.m23());
            // 实例自定义索引 24 位 + 掩码 8 位
            staging.putInt((instance.customIndex() & 0xFFFFFF) | ((instance.mask() & 0xFF) << 24));
            // SBT 记录偏移 24 位（恒 0）+ 实例标志 8 位（bit2 = FORCE_OPAQUE）
            int flags = instance.isForceOpaque() ? VK_GEOMETRY_INSTANCE_FORCE_OPAQUE_BIT_KHR : 0;
            staging.putInt(flags << 24);
            staging.putLong(instance.blasAddress());
        }
        staging.flip();
        instanceBuffer.update(0, staging);

        cmd.buildTlas(this, count, instanceBuffer.deviceAddress(), scratchAddress);

        if (!buildLogged) {
            buildLogged = true;
            LOGGER.info("TLAS 构建: 实例 {} 容量 {} AS {} B 设备地址 0x{}", count, capacity,
                    storage.desc().size(), Long.toHexString(deviceAddress));
        }
    }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
        storage.dispose();
        instanceBuffer.dispose();
        scratch.dispose();
    }
}
