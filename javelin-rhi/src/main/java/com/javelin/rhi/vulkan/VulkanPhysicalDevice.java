package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.PhysicalDevice;
import com.javelin.rhi.types.Feature;
import org.lwjgl.vulkan.VkExtensionProperties;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures2;
import org.lwjgl.vulkan.VkPhysicalDeviceVulkan13Features;
import org.lwjgl.vulkan.VkQueueFamilyProperties;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.HashSet;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Vulkan 物理设备实现。
 */
public class VulkanPhysicalDevice implements PhysicalDevice {

    private final VkPhysicalDevice device;
    private final String deviceName;
    private final DeviceType deviceType;
    private final DeviceLimits limits;
    private final QueueFamilyProperties[] queueFamilies;
    private final Set<String> extensions;
    private final VkPhysicalDeviceFeatures features;
    private final VkPhysicalDeviceVulkan13Features features13;

    public VulkanPhysicalDevice(VkPhysicalDevice device) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 属性
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
            vkGetPhysicalDeviceProperties(device, props);
            this.deviceName = props.deviceNameString();
            this.deviceType = switch (props.deviceType()) {
                case VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU -> DeviceType.INTEGRATED_GPU;
                case VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU -> DeviceType.DISCRETE_GPU;
                case VK_PHYSICAL_DEVICE_TYPE_VIRTUAL_GPU -> DeviceType.VIRTUAL_GPU;
                case VK_PHYSICAL_DEVICE_TYPE_CPU -> DeviceType.CPU;
                default -> DeviceType.OTHER;
            };

            // 限制
            var limits = props.limits();
            this.limits = new DeviceLimits(
                limits.maxImageDimension2D(),
                limits.maxImageDimension3D(),
                limits.maxImageDimensionCube(),
                limits.maxImageArrayLayers(),
                limits.maxTexelBufferElements(),
                limits.maxUniformBufferRange(),
                limits.maxStorageBufferRange(),
                limits.maxPushConstantsSize(),
                limits.maxMemoryAllocationCount(),
                limits.maxSamplerAllocationCount(),
                limits.bufferImageGranularity(),
                limits.sparseAddressSpaceSize(),
                limits.maxBoundDescriptorSets(),
                limits.maxPerStageDescriptorSamplers(),
                limits.maxPerStageDescriptorUniformBuffers(),
                limits.maxPerStageDescriptorStorageBuffers(),
                limits.maxPerStageDescriptorSampledImages(),
                limits.maxPerStageDescriptorStorageImages(),
                limits.maxPerStageResources(),
                limits.maxDescriptorSetSamplers(),
                limits.maxDescriptorSetUniformBuffers(),
                limits.maxDescriptorSetUniformBuffersDynamic(),
                limits.maxDescriptorSetStorageBuffers(),
                limits.maxDescriptorSetStorageBuffersDynamic(),
                limits.maxDescriptorSetSampledImages(),
                limits.maxDescriptorSetStorageImages(),
                limits.maxVertexInputAttributes(),
                limits.maxVertexInputBindings(),
                limits.maxVertexInputAttributeOffset(),
                limits.maxVertexInputBindingStride(),
                limits.maxVertexOutputComponents(),
                limits.maxTessellationGenerationLevel(),
                limits.maxTessellationPatchSize(),
                limits.maxTessellationControlPerVertexInputComponents(),
                limits.maxTessellationControlPerVertexOutputComponents(),
                limits.maxTessellationControlPerPatchOutputComponents(),
                limits.maxTessellationControlTotalOutputComponents(),
                limits.maxTessellationEvaluationInputComponents(),
                limits.maxTessellationEvaluationOutputComponents(),
                limits.maxGeometryShaderInvocations(),
                limits.maxGeometryInputComponents(),
                limits.maxGeometryOutputComponents(),
                limits.maxGeometryOutputVertices(),
                limits.maxGeometryTotalOutputComponents(),
                limits.maxFragmentInputComponents(),
                limits.maxFragmentOutputAttachments(),
                limits.maxFragmentDualSrcAttachments(),
                limits.maxFragmentCombinedOutputResources(),
                limits.maxComputeSharedMemorySize(),
                new int[]{limits.maxComputeWorkGroupCount(0), limits.maxComputeWorkGroupCount(1), limits.maxComputeWorkGroupCount(2)},
                limits.maxComputeWorkGroupInvocations(),
                new int[]{limits.maxComputeWorkGroupSize(0), limits.maxComputeWorkGroupSize(1), limits.maxComputeWorkGroupSize(2)},
                limits.subPixelPrecisionBits(),
                limits.subTexelPrecisionBits(),
                limits.mipmapPrecisionBits(),
                limits.maxDrawIndexedIndexValue(),
                limits.maxDrawIndirectCount(),
                limits.maxSamplerLodBias(),
                limits.maxSamplerAnisotropy(),
                limits.maxViewports(),
                new int[]{limits.maxViewportDimensions(0), limits.maxViewportDimensions(1)},
                new float[]{limits.viewportBoundsRange(0), limits.viewportBoundsRange(1)},
                limits.viewportSubPixelBits(),
                limits.minMemoryMapAlignment(),
                limits.minTexelBufferOffsetAlignment(),
                limits.minUniformBufferOffsetAlignment(),
                limits.minStorageBufferOffsetAlignment(),
                limits.minTexelOffset(),
                limits.maxTexelOffset(),
                limits.minTexelGatherOffset(),
                limits.maxTexelGatherOffset(),
                limits.minInterpolationOffset(),
                limits.maxInterpolationOffset(),
                limits.subPixelInterpolationOffsetBits(),
                limits.maxFramebufferWidth(),
                limits.maxFramebufferHeight(),
                limits.maxFramebufferLayers(),
                limits.framebufferColorSampleCounts(),
                limits.framebufferDepthSampleCounts(),
                limits.framebufferStencilSampleCounts(),
                limits.framebufferNoAttachmentsSampleCounts(),
                limits.maxColorAttachments(),
                limits.sampledImageColorSampleCounts(),
                limits.sampledImageIntegerSampleCounts(),
                limits.sampledImageDepthSampleCounts(),
                limits.sampledImageStencilSampleCounts(),
                limits.storageImageSampleCounts(),
                limits.maxSampleMaskWords(),
                limits.timestampComputeAndGraphics(),
                limits.timestampPeriod(),
                limits.maxClipDistances(),
                limits.maxCullDistances(),
                limits.maxCombinedClipAndCullDistances(),
                limits.discreteQueuePriorities(),
                new float[]{limits.pointSizeRange(0), limits.pointSizeRange(1)},
                new float[]{limits.lineWidthRange(0), limits.lineWidthRange(1)},
                limits.pointSizeGranularity(),
                limits.lineWidthGranularity(),
                limits.strictLines(),
                limits.standardSampleLocations(),
                limits.optimalBufferCopyOffsetAlignment(),
                limits.optimalBufferCopyRowPitchAlignment(),
                limits.nonCoherentAtomSize()
            );

            // 特性
            this.features = VkPhysicalDeviceFeatures.malloc(stack);
            vkGetPhysicalDeviceFeatures(device, features);

            // Vulkan 1.3 特性
            this.features13 = VkPhysicalDeviceVulkan13Features.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES);
            VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2)
                .pNext(features13);
            vkGetPhysicalDeviceFeatures2(device, features2);

            // 队列族
            IntBuffer pCount = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, null);
            int queueCount = pCount.get(0);
            VkQueueFamilyProperties.Buffer queueProps = VkQueueFamilyProperties.malloc(queueCount, stack);
            vkGetPhysicalDeviceQueueFamilyProperties(device, pCount, queueProps);

            this.queueFamilies = new QueueFamilyProperties[queueCount];
            for (int i = 0; i < queueCount; i++) {
                VkQueueFamilyProperties prop = queueProps.get(i);
                queueFamilies[i] = new QueueFamilyProperties(
                    i,
                    prop.queueFlags(),
                    prop.queueCount(),
                    prop.timestampValidBits(),
                    new Extent3D(
                        prop.minImageTransferGranularity().width(),
                        prop.minImageTransferGranularity().height(),
                        prop.minImageTransferGranularity().depth()
                    )
                );
            }

            // 扩展
            this.extensions = new HashSet<>();
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pCount, null);
            VkExtensionProperties.Buffer extProps = VkExtensionProperties.malloc(pCount.get(0), stack);
            vkEnumerateDeviceExtensionProperties(device, (ByteBuffer) null, pCount, extProps);
            for (int i = 0; i < pCount.get(0); i++) {
                extensions.add(extProps.get(i).extensionNameString());
            }
        }
    }

    @Override
    public long handle() {
        return device.address();
    }

    @Override
    public String deviceName() {
        return deviceName;
    }

    @Override
    public DeviceType deviceType() {
        return deviceType;
    }

    @Override
    public boolean supports(Feature feature) {
        return switch (feature) {
            case SAMPLER_ANISOTROPY -> features.samplerAnisotropy();
            case MULTI_DRAW_INDIRECT -> features.multiDrawIndirect();
            case DRAW_INDIRECT_COUNT -> false; // features.drawIndirectCount() 在部分 LWJGL 版本中不存在
            case TEXTURE_COMPRESSION_BC -> features.textureCompressionBC();
            case DYNAMIC_RENDERING -> features13.dynamicRendering();
            case SYNCHRONIZATION_2 -> features13.synchronization2();
            case BUFFER_DEVICE_ADDRESS -> false; // 需要检查 1.2 特性
            case DESCRIPTOR_INDEXING -> false;
            case TIMELINE_SEMAPHORE -> false;
            case RAY_TRACING -> extensions.contains("VK_KHR_ray_tracing_pipeline");
            case MESH_SHADER -> extensions.contains("VK_EXT_mesh_shader");
            case VARIABLE_RATE_SHADING -> extensions.contains("VK_KHR_fragment_shading_rate");
            case TEXTURE_COMPRESSION_ASTC -> features.textureCompressionASTC_LDR();
            case TEXTURE_COMPRESSION_ETC2 -> features.textureCompressionETC2();
            case FULL_SCREEN_EXCLUSIVE -> extensions.contains("VK_KHR_win32_keyed_mutex");
        };
    }

    @Override
    public boolean supportsExtension(String extension) {
        return extensions.contains(extension);
    }

    @Override
    public DeviceLimits limits() {
        return limits;
    }

    @Override
    public QueueFamilyProperties[] queueFamilies() {
        return queueFamilies;
    }

    VkPhysicalDevice vkPhysicalDevice() {
        return device;
    }

    VkPhysicalDeviceFeatures features() {
        return features;
    }

    VkPhysicalDeviceVulkan13Features features13() {
        return features13;
    }
}
