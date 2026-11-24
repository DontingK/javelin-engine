package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.BlasDesc;
import cn.donting.javelin.rhi.types.IndexType;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * BLAS 几何声明装配（P4-06 B2）：尺寸查询与真正构建共用同一段声明，避免两处漂移。
 */
final class VkBlasGeometry {

    private VkBlasGeometry() {
    }

    /** 三角形几何声明（顶点走设备地址 + 位置偏移，索引同理） */
    static VkAccelerationStructureGeometryKHR.Buffer geometry(MemoryStack stack, BlasDesc desc) {
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

    /** 构建信息（type/mode/flags/geometryCount/pGeometries；dst 与 scratch 由调用方填） */
    static VkAccelerationStructureBuildGeometryInfoKHR buildInfo(MemoryStack stack, BlasDesc desc,
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

    /** 构建范围（整条几何一次构建） */
    static VkAccelerationStructureBuildRangeInfoKHR.Buffer range(MemoryStack stack, BlasDesc desc) {
        VkAccelerationStructureBuildRangeInfoKHR.Buffer range =
                VkAccelerationStructureBuildRangeInfoKHR.calloc(1, stack);
        range.get(0).primitiveCount(desc.primitiveCount());
        range.get(0).primitiveOffset(0);
        range.get(0).firstVertex(0);
        range.get(0).transformOffset(0);
        return range;
    }

    static int toVkIndexType(IndexType type) {
        return switch (type) {
            case UINT16 -> VK_INDEX_TYPE_UINT16;
            case UINT32 -> VK_INDEX_TYPE_UINT32;
            case UINT8 -> VK_INDEX_TYPE_UINT8_KHR;
        };
    }

    static long alignUp(long value, int alignment) {
        long a = alignment;
        return (value + a - 1) / a * a;
    }
}
