package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Texture;
import com.javelin.rhi.api.TextureView;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.desc.TextureViewDesc;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDevice;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkImageCreateInfo;
import org.lwjgl.vulkan.VkMemoryAllocateInfo;
import org.lwjgl.vulkan.VkMemoryRequirements;
import org.lwjgl.vulkan.VkPhysicalDeviceMemoryProperties;

import static org.lwjgl.vulkan.VK10.*;

public class VulkanTexture extends Texture {

    private final VkDevice device;
    private final long image;
    private final long memory;
    private final int width;
    private final int height;
    private final int depth;
    private final int mipLevels;
    private final int arrayLayers;
    private final Format format;
    private final TextureUsage usage;
    private final TextureType type;
    private final SampleCount samples;
    private TextureLayout currentLayout;
    private boolean ownsImage;

    public VulkanTexture(VkDevice device, long image, int width, int height, Format format) {
        this.device = device;
        this.image = image;
        this.memory = VK_NULL_HANDLE;
        this.width = width;
        this.height = height;
        this.depth = 1;
        this.mipLevels = 1;
        this.arrayLayers = 1;
        this.format = format;
        this.usage = TextureUsage.COLOR_ATTACHMENT;
        this.type = TextureType.TEXTURE_2D;
        this.samples = SampleCount.COUNT_1;
        this.currentLayout = TextureLayout.UNDEFINED;
        this.ownsImage = false;
    }

    public VulkanTexture(VulkanDevice device, TextureDesc desc) {
        this.device = device.vkDevice();
        this.width = desc.width();
        this.height = desc.height();
        this.depth = desc.depth();
        this.mipLevels = desc.mipLevels();
        this.arrayLayers = desc.arrayLayers();
        this.format = desc.format();
        this.usage = desc.usage();
        this.type = desc.type();
        this.samples = desc.samples();
        this.currentLayout = desc.initialLayout();
        this.ownsImage = true;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageCreateInfo createInfo = VkImageCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO)
                .imageType(toVkImageType(type))
                .format(VulkanFormats.toVkFormat(format))
                .extent(extent -> extent.width(width).height(height).depth(depth))
                .mipLevels(mipLevels)
                .arrayLayers(arrayLayers)
                .samples(toVkSampleCount(samples))
                .tiling(VK_IMAGE_TILING_OPTIMAL)
                .usage(usage.value())
                .sharingMode(VK_SHARING_MODE_EXCLUSIVE)
                .initialLayout(VulkanFormats.toVkImageLayout(currentLayout));

            LongBuffer pImage = stack.mallocLong(1);
            VulkanChecks.check(vkCreateImage(this.device, createInfo, null, pImage), "vkCreateImage");
            this.image = pImage.get(0);

            VkMemoryRequirements memReqs = VkMemoryRequirements.malloc(stack);
            vkGetImageMemoryRequirements(this.device, image, memReqs);

            int memoryTypeIndex = findMemoryType(device, memReqs.memoryTypeBits());

            VkMemoryAllocateInfo allocInfo = VkMemoryAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO)
                .allocationSize(memReqs.size())
                .memoryTypeIndex(memoryTypeIndex);

            LongBuffer pMemory = stack.mallocLong(1);
            VulkanChecks.check(vkAllocateMemory(this.device, allocInfo, null, pMemory), "vkAllocateMemory");
            this.memory = pMemory.get(0);

            VulkanChecks.check(vkBindImageMemory(this.device, image, memory, 0), "vkBindImageMemory");
        }
    }

    private int findMemoryType(VulkanDevice device, int typeFilter) {
        VkPhysicalDeviceMemoryProperties memProps = VkPhysicalDeviceMemoryProperties.malloc();
        vkGetPhysicalDeviceMemoryProperties(((VulkanPhysicalDevice) device.physicalDevice()).vkPhysicalDevice(), memProps);

        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeFilter & (1 << i)) != 0) {
                int flags = memProps.memoryTypes(i).propertyFlags();
                if ((flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) == VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) {
                    memProps.free();
                    return i;
                }
            }
        }

        memProps.free();
        throw new RuntimeException("Failed to find suitable memory type for texture");
    }

    private int toVkImageType(TextureType type) {
        return switch (type) {
            case TEXTURE_1D, TEXTURE_1D_ARRAY -> VK_IMAGE_TYPE_1D;
            case TEXTURE_2D, TEXTURE_2D_ARRAY, TEXTURE_CUBE, TEXTURE_CUBE_ARRAY -> VK_IMAGE_TYPE_2D;
            case TEXTURE_3D -> VK_IMAGE_TYPE_3D;
        };
    }

    private int toVkSampleCount(SampleCount samples) {
        return switch (samples) {
            case COUNT_1 -> VK_SAMPLE_COUNT_1_BIT;
            case COUNT_2 -> VK_SAMPLE_COUNT_2_BIT;
            case COUNT_4 -> VK_SAMPLE_COUNT_4_BIT;
            case COUNT_8 -> VK_SAMPLE_COUNT_8_BIT;
            case COUNT_16 -> VK_SAMPLE_COUNT_16_BIT;
            case COUNT_32 -> VK_SAMPLE_COUNT_32_BIT;
            case COUNT_64 -> VK_SAMPLE_COUNT_64_BIT;
        };
    }

    @Override public long handle() { return image; }

    @Override
    protected void doDispose() {
        if (ownsImage) {
            vkDestroyImage(device, image, null);
            if (memory != VK_NULL_HANDLE) {
                vkFreeMemory(device, memory, null);
            }
        }
    }

    @Override public int width() { return width; }
    @Override public int height() { return height; }
    @Override public int depth() { return depth; }
    @Override public int mipLevels() { return mipLevels; }
    @Override public int arrayLayers() { return arrayLayers; }
    @Override public Format format() { return format; }
    @Override public TextureUsage usage() { return usage; }
    @Override public TextureType type() { return type; }
    @Override public SampleCount samples() { return samples; }

    @Override
    public TextureView createView(TextureViewDesc desc) {
        return new VulkanTextureView(device, this, desc);
    }

    @Override public TextureLayout currentLayout() { return currentLayout; }

    void setCurrentLayout(TextureLayout layout) {
        this.currentLayout = layout;
    }
}
