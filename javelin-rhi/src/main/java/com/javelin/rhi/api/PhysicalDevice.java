package com.javelin.rhi.api;

import com.javelin.rhi.types.Feature;

/**
 * Vulkan 物理设备（GPU）封装。
 */
public interface PhysicalDevice {

    long handle();

    String deviceName();

    DeviceType deviceType();

    boolean supports(Feature feature);

    boolean supportsExtension(String extension);

    DeviceLimits limits();

    QueueFamilyProperties[] queueFamilies();

    // ========== 内部类型 ==========

    enum DeviceType {
        OTHER,
        INTEGRATED_GPU,
        DISCRETE_GPU,
        VIRTUAL_GPU,
        CPU
    }

    record DeviceLimits(
        int maxImageDimension2D,
        int maxImageDimension3D,
        int maxImageDimensionCube,
        int maxImageArrayLayers,
        int maxTexelBufferElements,
        int maxUniformBufferRange,
        int maxStorageBufferRange,
        int maxPushConstantsSize,
        int maxMemoryAllocationCount,
        int maxSamplerAllocationCount,
        long bufferImageGranularity,
        long sparseAddressSpaceSize,
        int maxBoundDescriptorSets,
        int maxPerStageDescriptorSamplers,
        int maxPerStageDescriptorUniformBuffers,
        int maxPerStageDescriptorStorageBuffers,
        int maxPerStageDescriptorSampledImages,
        int maxPerStageDescriptorStorageImages,
        int maxPerStageResources,
        int maxDescriptorSetSamplers,
        int maxDescriptorSetUniformBuffers,
        int maxDescriptorSetUniformBuffersDynamic,
        int maxDescriptorSetStorageBuffers,
        int maxDescriptorSetStorageBuffersDynamic,
        int maxDescriptorSetSampledImages,
        int maxDescriptorSetStorageImages,
        int maxVertexInputAttributes,
        int maxVertexInputBindings,
        int maxVertexInputAttributeOffset,
        int maxVertexInputBindingStride,
        int maxVertexOutputComponents,
        int maxTessellationGenerationLevel,
        int maxTessellationPatchSize,
        int maxTessellationControlPerVertexInputComponents,
        int maxTessellationControlPerVertexOutputComponents,
        int maxTessellationControlPerPatchOutputComponents,
        int maxTessellationControlTotalOutputComponents,
        int maxTessellationEvaluationInputComponents,
        int maxTessellationEvaluationOutputComponents,
        int maxGeometryShaderInvocations,
        int maxGeometryInputComponents,
        int maxGeometryOutputComponents,
        int maxGeometryOutputVertices,
        int maxGeometryTotalOutputComponents,
        int maxFragmentInputComponents,
        int maxFragmentOutputAttachments,
        int maxFragmentDualSrcAttachments,
        int maxFragmentCombinedOutputResources,
        int maxComputeSharedMemorySize,
        int[] maxComputeWorkGroupCount,
        int maxComputeWorkGroupInvocations,
        int[] maxComputeWorkGroupSize,
        int subPixelPrecisionBits,
        int subTexelPrecisionBits,
        int mipmapPrecisionBits,
        int maxDrawIndexedIndexValue,
        int maxDrawIndirectCount,
        float maxSamplerLodBias,
        float maxSamplerAnisotropy,
        int maxViewports,
        int[] maxViewportDimensions,
        float[] viewportBoundsRange,
        int viewportSubPixelBits,
        long minMemoryMapAlignment,
        long minTexelBufferOffsetAlignment,
        long minUniformBufferOffsetAlignment,
        long minStorageBufferOffsetAlignment,
        int minTexelOffset,
        int maxTexelOffset,
        int minTexelGatherOffset,
        int maxTexelGatherOffset,
        float minInterpolationOffset,
        float maxInterpolationOffset,
        int subPixelInterpolationOffsetBits,
        int maxFramebufferWidth,
        int maxFramebufferHeight,
        int maxFramebufferLayers,
        int framebufferColorSampleCounts,
        int framebufferDepthSampleCounts,
        int framebufferStencilSampleCounts,
        int framebufferNoAttachmentsSampleCounts,
        int maxColorAttachments,
        int sampledImageColorSampleCounts,
        int sampledImageIntegerSampleCounts,
        int sampledImageDepthSampleCounts,
        int sampledImageStencilSampleCounts,
        int storageImageSampleCounts,
        int maxSampleMaskWords,
        boolean timestampComputeAndGraphics,
        float timestampPeriod,
        int maxClipDistances,
        int maxCullDistances,
        int maxCombinedClipAndCullDistances,
        int discreteQueuePriorities,
        float[] pointSizeRange,
        float[] lineWidthRange,
        float pointSizeGranularity,
        float lineWidthGranularity,
        boolean strictLines,
        boolean standardSampleLocations,
        long optimalBufferCopyOffsetAlignment,
        long optimalBufferCopyRowPitchAlignment,
        long nonCoherentAtomSize
    ) {}

    record QueueFamilyProperties(
        int index,
        int queueFlags,
        int queueCount,
        int timestampValidBits,
        Extent3D minImageTransferGranularity
    ) {}

    record Extent3D(int width, int height, int depth) {}
}
