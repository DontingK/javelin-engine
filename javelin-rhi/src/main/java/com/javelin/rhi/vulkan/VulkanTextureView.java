package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Texture;
import com.javelin.rhi.api.TextureView;
import com.javelin.rhi.desc.TextureViewDesc;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkImageViewCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 纹理视图实现 */
public class VulkanTextureView extends TextureView {

    private final VkDevice device;
    private final long handle;
    private final Texture texture;
    private final TextureViewType viewType;
    private final Format format;
    private final TextureViewDesc.TextureSubresourceRange subresourceRange;

    public VulkanTextureView(VkDevice device, Texture texture, TextureViewDesc desc) {
        this.device = device;
        this.texture = texture;
        this.viewType = desc.viewType();
        this.format = desc.format();
        this.subresourceRange = desc.subresourceRange();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO)
                .image(texture.handle())
                .viewType(toVkViewType(desc.viewType()))
                .format(VulkanFormats.toVkFormat(desc.format()))
                .components(components -> components
                    .r(toVkComponentSwizzle(desc.components().r()))
                    .g(toVkComponentSwizzle(desc.components().g()))
                    .b(toVkComponentSwizzle(desc.components().b()))
                    .a(toVkComponentSwizzle(desc.components().a())))
                .subresourceRange(range -> range
                    .aspectMask(desc.subresourceRange().aspectMask().value())
                    .baseMipLevel(desc.subresourceRange().baseMipLevel())
                    .levelCount(desc.subresourceRange().levelCount())
                    .baseArrayLayer(desc.subresourceRange().baseArrayLayer())
                    .layerCount(desc.subresourceRange().layerCount()));

            LongBuffer pView = stack.mallocLong(1);
            VulkanChecks.check(vkCreateImageView(this.device, createInfo, null, pView), "vkCreateImageView");
            this.handle = pView.get(0);
        }
    }

    private int toVkViewType(TextureViewType type) {
        return switch (type) {
            case TEXTURE_1D -> VK_IMAGE_VIEW_TYPE_1D;
            case TEXTURE_2D -> VK_IMAGE_VIEW_TYPE_2D;
            case TEXTURE_3D -> VK_IMAGE_VIEW_TYPE_3D;
            case TEXTURE_CUBE -> VK_IMAGE_VIEW_TYPE_CUBE;
            case TEXTURE_1D_ARRAY -> VK_IMAGE_VIEW_TYPE_1D_ARRAY;
            case TEXTURE_2D_ARRAY -> VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            case TEXTURE_CUBE_ARRAY -> VK_IMAGE_VIEW_TYPE_CUBE_ARRAY;
        };
    }

    private int toVkComponentSwizzle(TextureViewDesc.ComponentSwizzle swizzle) {
        return switch (swizzle) {
            case IDENTITY -> VK_COMPONENT_SWIZZLE_IDENTITY;
            case ZERO -> VK_COMPONENT_SWIZZLE_ZERO;
            case ONE -> VK_COMPONENT_SWIZZLE_ONE;
            case R -> VK_COMPONENT_SWIZZLE_R;
            case G -> VK_COMPONENT_SWIZZLE_G;
            case B -> VK_COMPONENT_SWIZZLE_B;
            case A -> VK_COMPONENT_SWIZZLE_A;
        };
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyImageView(device, handle, null);
    }

    @Override
    public Texture texture() {
        return texture;
    }

    @Override
    public TextureViewType viewType() {
        return viewType;
    }

    @Override
    public Format format() {
        return format;
    }

    @Override
    public TextureViewDesc.TextureSubresourceRange subresourceRange() {
        return subresourceRange;
    }
}
