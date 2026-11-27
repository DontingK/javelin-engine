package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.BlasDesc;
import cn.donting.javelin.rhi.types.IndexType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;
import static org.lwjgl.vulkan.VK14.*;

/**
 * 加速结构几何声明装配（P4-06 B2）：尺寸查询、构建、范围三件共用一处声明，避免漂移。
 */
final class VkAsGeometry {

    private VkAsGeometry() {
    }

    // ═══ BLAS（三角几何） ═══

    /** 三角形几何声明（顶点走设备地址 + 位置偏移，索引同理） */
    static VkAccelerationStructureGeometryKHR.Buffer blasGeometry(MemoryStack stack, BlasDesc desc) {
        VkAccelerationStructureGeometryTrianglesDataKHR triangles =
                VkAccelerationStructureGeometryTrianglesDataKHR.calloc(stack).sType$Default();
        triangles.vertexFormat(VK_FORMAT_R32G32B32_SFLOAT);
        triangles.vertexData(VkDeviceOrHostAddressConstKHR.calloc(stack)
                .deviceAddress(desc.vertexBuffer().deviceAddress() + desc.positionOffset()));
        triangles.vertexStride(desc.vertexStride());
        triangles.maxVertex(desc.vertexCount() - 1);
        triangles.indexType(toVkIndexType(desc.indexType()));
        triangles.indexData(VkDeviceOrHostAddressConstKHR.calloc(stack)
                .deviceAddress(desc.indexBuffer().deviceAddress()));

        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0).sType$Default();
        geometry.get(0).geometryType(VK_GEOMETRY_TYPE_TRIANGLES_KHR);
        geometry.get(0).flags(VK_GEOMETRY_OPAQUE_BIT_KHR);
        geometry.get(0).geometry().triangles(triangles);
        return geometry;
    }

    static VkAccelerationStructureBuildGeometryInfoKHR blasBuildInfo(MemoryStack stack,
                                                                    VkAccelerationStructureGeometryKHR.Buffer geometry) {
        VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default();
        buildInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_BOTTOM_LEVEL_KHR);
        buildInfo.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
        buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
        buildInfo.geometryCount(1);
        buildInfo.pGeometries(geometry);
        return buildInfo;
    }

    // ═══ TLAS（实例几何） ═══

    /** 实例几何声明（实例缓冲按设备地址直读，`arrayOfPointers=false`） */
    static VkAccelerationStructureGeometryKHR.Buffer tlasGeometry(MemoryStack stack, long instancesAddress) {
        VkAccelerationStructureGeometryInstancesDataKHR instancesData =
                VkAccelerationStructureGeometryInstancesDataKHR.calloc(stack).sType$Default();
        instancesData.arrayOfPointers(false);
        instancesData.data(VkDeviceOrHostAddressConstKHR.calloc(stack).deviceAddress(instancesAddress));

        VkAccelerationStructureGeometryKHR.Buffer geometry =
                VkAccelerationStructureGeometryKHR.calloc(1, stack);
        geometry.get(0).sType$Default();
        geometry.get(0).geometryType(VK_GEOMETRY_TYPE_INSTANCES_KHR);
        geometry.get(0).geometry().instances(instancesData);
        return geometry;
    }

    static VkAccelerationStructureBuildGeometryInfoKHR tlasBuildInfo(MemoryStack stack,
                                                                    VkAccelerationStructureGeometryKHR.Buffer geometry) {
        VkAccelerationStructureBuildGeometryInfoKHR buildInfo =
                VkAccelerationStructureBuildGeometryInfoKHR.calloc(stack).sType$Default();
        buildInfo.type(VK_ACCELERATION_STRUCTURE_TYPE_TOP_LEVEL_KHR);
        buildInfo.flags(VK_BUILD_ACCELERATION_STRUCTURE_PREFER_FAST_TRACE_BIT_KHR);
        buildInfo.mode(VK_BUILD_ACCELERATION_STRUCTURE_MODE_BUILD_KHR);
        buildInfo.geometryCount(1);
        buildInfo.pGeometries(geometry);
        return buildInfo;
    }

    // ═══ 共用 ═══

    /** 构建范围（整条几何一次构建） */
    static VkAccelerationStructureBuildRangeInfoKHR.Buffer range(MemoryStack stack, int primitiveCount) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(primitiveCount);
        range.get(0).primitiveOffset(0);
        range.get(0).firstVertex(0);
        range.get(0).transformOffset(0);
        return range;
    }

    /** 构建完成后的可见性屏障：AS 写 → 着色器读 / 下一 AS 构建（P4-06 B2b，2026-11-27 扩）
     *
     * <p>dst 阶段必须含 {@code ACCELERATION_STRUCTURE_BUILD}：TLAS 每帧被多个 RT Pass
     * 各构建一次（同一命令缓冲、同一 scratch），两次构建之间若缺 BUILD→BUILD 同步，
     * GPU 并发写同一 scratch = 规范违规，TLAS 内容未定义（实测射线全落空、阴影全亮）。</p> */
    static void buildToReadBarrier(org.lwjgl.vulkan.VkCommandBuffer cmd, MemoryStack stack) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.calloc(1, stack);
        barrier.get(0).sType$Default();
        barrier.get(0).srcAccessMask(VK_ACCESS_MEMORY_WRITE_BIT);
        barrier.get(0).dstAccessMask(VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR,
                VK_PIPELINE_STAGE_ACCELERATION_STRUCTURE_BUILD_BIT_KHR
                        | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                        | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, barrier, null, null);
    }

    static int toVkIndexType(IndexType type) {
        return switch (type) {
            case UINT16 -> VK_INDEX_TYPE_UINT16;
            case UINT32 -> VK_INDEX_TYPE_UINT32;
            case UINT8 -> VK_INDEX_TYPE_UINT8;
        };
    }

    static long alignUp(long value, int alignment) {
        long a = alignment;
        return (value + a - 1) / a * a;
    }
}
