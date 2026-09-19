package com.javelin.rhi;

import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.exception.VulkanException;
import com.javelin.rhi.types.TextureType;
import com.javelin.rhi.types.TextureUsage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Texture 实现。
 * 对照设计文档第 26.2 节。
 */
final class VulkanTexture implements Texture {

    private final VulkanDevice device;
    private final TextureDesc desc;
    private final long imageHandle;
    private final long memoryHandle;
    private volatile boolean disposed;

    VulkanTexture(VulkanDevice device, TextureDesc desc) {
        this.device = device;
        this.desc = desc;

        VkDevice vkDevice = device.vkDevice();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 创建图像
            VkImageCreateInfo imageInfo = VkImageCreateInfo.calloc(stack);
            imageInfo.sType$Default();
            imageInfo.imageType(toVkImageType(desc.type()));
            imageInfo.format(toVkFormat(desc.format()));
            imageInfo.extent(VkExtent3D.calloc(stack)
                    .width(desc.width())
                    .height(desc.height())
                    .depth(desc.depth()));
            imageInfo.mipLevels(desc.mipLevels());
            imageInfo.arrayLayers(desc.arrayLayers());
            imageInfo.samples(toVkSampleCount(desc.sampleCount()));
            imageInfo.tiling(VK_IMAGE_TILING_OPTIMAL);
            imageInfo.usage(toVkImageUsage(desc.usage()));
            imageInfo.sharingMode(VK_SHARING_MODE_EXCLUSIVE);
            imageInfo.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);

            LongBuffer imgBuf = stack.mallocLong(1);
            int result = vkCreateImage(vkDevice, imageInfo, null, imgBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create image");
            }
            this.imageHandle = imgBuf.get(0);

            // 内存需求
            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(vkDevice, imageHandle, memReqs);

            // 分配内存
            int memType = VulkanMemory.findMemoryType(
                    device.physicalDevice().device(),
                    memReqs.memoryTypeBits(),
                    desc.memory());

            this.memoryHandle = VulkanMemory.allocate(vkDevice, memReqs.size(), memType);

            // 绑定
            result = vkBindImageMemory(vkDevice, imageHandle, memoryHandle, 0);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to bind image memory");
            }
        }

        // 注册 Cleaner
        CleanerRegistry.register(device, imageHandle, CleanerRegistry.ResourceType.TEXTURE);
    }

    @Override
    public TextureDesc desc() {
        return desc;
    }

    @Override
    public void update(TextureRegion region, ByteBuffer data) {
        // Stage 5 实现（需要 staging buffer + 命令缓冲）
        throw new UnsupportedOperationException("Texture update not yet implemented (Stage 5)");
    }

    @Override
    public ByteBuffer readback() {
        // P2
        throw new UnsupportedOperationException("Texture readback not yet implemented (P2)");
    }

    @Override
    public long handle() {
        return imageHandle;
    }

    long memoryHandle() {
        return memoryHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        VulkanMemory.free(device.vkDevice(), memoryHandle);
        vkDestroyImage(device.vkDevice(), imageHandle, null);
    }

    static int toVkFormat(com.javelin.rhi.types.Format format) {
        return switch (format) {
            case UNDEFINED -> VK_FORMAT_UNDEFINED;
            case R8_UNORM -> VK_FORMAT_R8_UNORM;
            case R8_SNORM -> VK_FORMAT_R8_SNORM;
            case R8_UINT -> VK_FORMAT_R8_UINT;
            case R8_SINT -> VK_FORMAT_R8_SINT;
            case R8G8_UNORM -> VK_FORMAT_R8G8_UNORM;
            case R8G8_SNORM -> VK_FORMAT_R8G8_SNORM;
            case R8G8_UINT -> VK_FORMAT_R8G8_UINT;
            case R8G8_SINT -> VK_FORMAT_R8G8_SINT;
            case R8G8B8A8_UNORM -> VK_FORMAT_R8G8B8A8_UNORM;
            case R8G8B8A8_SNORM -> VK_FORMAT_R8G8B8A8_SNORM;
            case R8G8B8A8_UINT -> VK_FORMAT_R8G8B8A8_UINT;
            case R8G8B8A8_SINT -> VK_FORMAT_R8G8B8A8_SINT;
            case R8G8B8A8_SRGB -> VK_FORMAT_R8G8B8A8_SRGB;
            case B8G8R8A8_UNORM -> VK_FORMAT_B8G8R8A8_UNORM;
            case B8G8R8A8_SRGB -> VK_FORMAT_B8G8R8A8_SRGB;
            case R16_SFLOAT -> VK_FORMAT_R16_SFLOAT;
            case R16G16_SFLOAT -> VK_FORMAT_R16G16_SFLOAT;
            case R16G16B16A16_SFLOAT -> VK_FORMAT_R16G16B16A16_SFLOAT;
            case R16_UINT -> VK_FORMAT_R16_UINT;
            case R16_SINT -> VK_FORMAT_R16_SINT;
            case R16G16_UINT -> VK_FORMAT_R16G16_UINT;
            case R16G16_SINT -> VK_FORMAT_R16G16_SINT;
            case R16G16B16A16_UINT -> VK_FORMAT_R16G16B16A16_UINT;
            case R16G16B16A16_SINT -> VK_FORMAT_R16G16B16A16_SINT;
            case R32_SFLOAT -> VK_FORMAT_R32_SFLOAT;
            case R32G32_SFLOAT -> VK_FORMAT_R32G32_SFLOAT;
            case R32G32B32_SFLOAT -> VK_FORMAT_R32G32B32_SFLOAT;
            case R32G32B32A32_SFLOAT -> VK_FORMAT_R32G32B32A32_SFLOAT;
            case R32_UINT -> VK_FORMAT_R32_UINT;
            case R32_SINT -> VK_FORMAT_R32_SINT;
            case R32G32_UINT -> VK_FORMAT_R32G32_UINT;
            case R32G32_SINT -> VK_FORMAT_R32G32_SINT;
            case R32G32B32A32_UINT -> VK_FORMAT_R32G32B32A32_UINT;
            case R32G32B32A32_SINT -> VK_FORMAT_R32G32B32A32_SINT;
            case D16_UNORM -> VK_FORMAT_D16_UNORM;
            case D32_SFLOAT -> VK_FORMAT_D32_SFLOAT;
            case D24_UNORM_S8_UINT -> VK_FORMAT_D24_UNORM_S8_UINT;
            case D32_SFLOAT_S8_UINT -> VK_FORMAT_D32_SFLOAT_S8_UINT;
            case S8_UINT -> VK_FORMAT_S8_UINT;
            case BC1_RGB_UNORM -> VK_FORMAT_BC1_RGB_UNORM_BLOCK;
            case BC1_RGB_SRGB -> VK_FORMAT_BC1_RGB_SRGB_BLOCK;
            case BC1_RGBA_UNORM -> VK_FORMAT_BC1_RGBA_UNORM_BLOCK;
            case BC1_RGBA_SRGB -> VK_FORMAT_BC1_RGBA_SRGB_BLOCK;
            case BC2_UNORM -> VK_FORMAT_BC2_UNORM_BLOCK;
            case BC2_SRGB -> VK_FORMAT_BC2_SRGB_BLOCK;
            case BC3_UNORM -> VK_FORMAT_BC3_UNORM_BLOCK;
            case BC3_SRGB -> VK_FORMAT_BC3_SRGB_BLOCK;
            case BC4_UNORM -> VK_FORMAT_BC4_UNORM_BLOCK;
            case BC4_SNORM -> VK_FORMAT_BC4_SNORM_BLOCK;
            case BC5_UNORM -> VK_FORMAT_BC5_UNORM_BLOCK;
            case BC5_SNORM -> VK_FORMAT_BC5_SNORM_BLOCK;
            case BC6H_UFLOAT -> VK_FORMAT_BC6H_UFLOAT_BLOCK;
            case BC6H_SFLOAT -> VK_FORMAT_BC6H_SFLOAT_BLOCK;
            case BC7_UNORM -> VK_FORMAT_BC7_UNORM_BLOCK;
            case BC7_SRGB -> VK_FORMAT_BC7_SRGB_BLOCK;
        };
    }

    private static int toVkImageType(TextureType type) {
        return switch (type) {
            case TYPE_1D -> VK_IMAGE_TYPE_1D;
            case TYPE_2D, CUBE, CUBE_ARRAY -> VK_IMAGE_TYPE_2D;
            case TYPE_3D -> VK_IMAGE_TYPE_3D;
        };
    }

    private static int toVkSampleCount(com.javelin.rhi.types.SampleCount count) {
        return switch (count) {
            case COUNT_1 -> VK_SAMPLE_COUNT_1_BIT;
            case COUNT_2 -> VK_SAMPLE_COUNT_2_BIT;
            case COUNT_4 -> VK_SAMPLE_COUNT_4_BIT;
            case COUNT_8 -> VK_SAMPLE_COUNT_8_BIT;
            case COUNT_16 -> VK_SAMPLE_COUNT_16_BIT;
            case COUNT_32 -> VK_SAMPLE_COUNT_32_BIT;
            case COUNT_64 -> VK_SAMPLE_COUNT_64_BIT;
        };
    }

    private static int toVkImageUsage(java.util.Set<TextureUsage> usages) {
        int flags = 0;
        for (TextureUsage usage : usages) {
            switch (usage) {
                case TRANSFER_SRC -> flags |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                case TRANSFER_DST -> flags |= VK_IMAGE_USAGE_TRANSFER_DST_BIT;
                case SAMPLED -> flags |= VK_IMAGE_USAGE_SAMPLED_BIT;
                case STORAGE -> flags |= VK_IMAGE_USAGE_STORAGE_BIT;
                case COLOR_ATTACHMENT -> flags |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                case DEPTH_STENCIL_ATTACHMENT -> flags |= VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT;
                case TRANSIENT_ATTACHMENT -> flags |= VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
                case INPUT_ATTACHMENT -> flags |= VK_IMAGE_USAGE_INPUT_ATTACHMENT_BIT;
            }
        }
        return flags;
    }
}
