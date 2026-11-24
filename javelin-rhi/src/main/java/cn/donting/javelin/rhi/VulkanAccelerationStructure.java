package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.BlasDesc;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.exception.VulkanException;
import cn.donting.javelin.rhi.types.BufferUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * BLAS 实现（P4-06 B2）：一次构建即固定；位置/索引缓冲变动时由调用方重建。
 *
 * <p>流程 = 查询尺寸 → 建存储缓冲（device-local + AS 存储 + 设备地址）→ 建 AS →
 * 建 scratch（多分配 alignment 字节，构建时取对齐后地址）→ 一次提交构建 → fence 等 → 取设备地址。</p>
 *
 * <p><b>生命周期</b>：显式 {@link #dispose()} 销毁 AS 与存储缓冲；不进 CleanerRegistry
 * （AS 与存储缓冲有释放顺序要求，交给持有者按批次关闭更可控）。</p>
 */
final class VulkanAccelerationStructure implements AccelerationStructure {

    private static final Logger LOGGER = LoggerFactory.getLogger(VulkanAccelerationStructure.class);

    private final VulkanDevice device;
    private final long handle;
    private final VulkanBuffer storage;
    private final long deviceAddress;
    private final long size;
    private volatile boolean disposed;

    VulkanAccelerationStructure(VulkanDevice device, BlasDesc desc) {
        if (!device.rayQueryEnabled()) {
            // 红线 15：能力缺失显性报错，不做软件回退
            throw new IllegalStateException(
                    "本设备未启用光追能力（VK_KHR_ray_query + acceleration_structure），不能构建 BLAS");
        }
        this.device = device;
        VkDevice vkDevice = device.vkDevice();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAsGeometry.blasGeometry(stack, desc);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAsGeometry.blasBuildInfo(stack, geometry);

            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            IntBuffer maxPrimitives = stack.ints(desc.primitiveCount());
            vkGetAccelerationStructureBuildSizesKHR(vkDevice, VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR,
                    buildInfo, maxPrimitives, sizes);

            this.size = sizes.accelerationStructureSize();
            this.storage = new VulkanBuffer(device, new BufferDesc(size,
                    Set.of(BufferUsage.STORAGE, BufferUsage.SHADER_DEVICE_ADDRESS,
                            BufferUsage.ACCELERATION_STRUCTURE_STORAGE),
                    MemoryPreference.deviceLocal()));

            VkAccelerationStructureCreateInfoKHR createInfo =
                    VkAccelerationStructureCreateInfoKHR.calloc(stack).sType$Default();
            createInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
            createInfo.size(size);
            createInfo.buffer(storage.handle());
            createInfo.offset(0);
            LongBuffer asBuf = stack.mallocLong(1);
            int result = vkCreateAccelerationStructureKHR(vkDevice, createInfo, null, asBuf);
            if (result != VK_SUCCESS) {
                storage.dispose();
                throw new VulkanException(result, "Failed to create bottom-level acceleration structure");
            }
            this.handle = asBuf.get(0);

            buildWithScratch(desc);

            VkAccelerationStructureDeviceAddressInfoKHR addressInfo =
                    VkAccelerationStructureDeviceAddressInfoKHR.calloc(stack).sType$Default();
            addressInfo.accelerationStructure(handle);
            this.deviceAddress = vkGetAccelerationStructureDeviceAddressKHR(vkDevice, addressInfo);

            LOGGER.info("BLAS 构建: 图元 {} AS {} B 设备地址 0x{}", desc.primitiveCount(), size,
                    Long.toHexString(deviceAddress));
        }
    }

    /** 建 scratch → 一次提交构建 → 释放 scratch（失败时销毁 AS 与存储再抛） */
    private void buildWithScratch(BlasDesc desc) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkAccelerationStructureBuildSizesInfoKHR sizes =
                    VkAccelerationStructureBuildSizesInfoKHR.calloc(stack).sType$Default();
            VkAccelerationStructureGeometryKHR.Buffer geometry = VkAsGeometry.blasGeometry(stack, desc);
            VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                    VkAsGeometry.blasBuildInfo(stack, geometry);
            IntBuffer maxPrimitives = stack.ints(desc.primitiveCount());
            vkGetAccelerationStructureBuildSizesKHR(device.vkDevice(),
                    VK_ACCELERATION_STRUCTURE_BUILD_TYPE_DEVICE_KHR, buildInfo, maxPrimitives, sizes);

            int alignment = Math.max(device.physicalDevice().accelerationStructureScratchAlignment(), 1);
            long scratchBytes = sizes.buildScratchSize() + alignment;
            VulkanBuffer scratch = new VulkanBuffer(device, new BufferDesc(scratchBytes,
                    Set.of(BufferUsage.STORAGE, BufferUsage.SHADER_DEVICE_ADDRESS),
                    MemoryPreference.deviceLocal()));
            try {
                long scratchAligned = VkAsGeometry.alignUp(scratch.deviceAddress(), alignment);
                CommandBuffer cmd = device.allocateCommandBuffer(false);
                try {
                    cmd.begin();
                    cmd.buildBlas(this, desc, scratchAligned);
                    cmd.end();
                    Fence fence = device.createFence();
                    try {
                        device.graphicsQueue().submit(cmd, SubmitInfo.of(fence));
                        fence.await();
                    } finally {
                        fence.dispose();
                    }
                } finally {
                    cmd.dispose();
                }
            } finally {
                scratch.dispose();
            }
        } catch (RuntimeException e) {
            vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
            storage.dispose();
            throw e;
        }
    }

    @Override
    public long handle() { return handle; }

    @Override
    public long deviceAddress() { return deviceAddress; }

    @Override
    public long size() { return size; }

    /** 背后存储缓冲（调试/统计用；不要外部释放） */
    Buffer storageBuffer() { return storage; }

    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        vkDestroyAccelerationStructureKHR(device.vkDevice(), handle, null);
        storage.dispose();
    }
}
