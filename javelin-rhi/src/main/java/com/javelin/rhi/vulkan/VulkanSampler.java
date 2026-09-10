package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Sampler;
import com.javelin.rhi.desc.SamplerDesc;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 采样器实现 */
public class VulkanSampler extends Sampler {

    private final VkDevice device;
    private final long handle;
    private final SamplerDesc desc;

    public VulkanSampler(VulkanDevice device, SamplerDesc desc) {
        this.device = device.vkDevice();
        this.desc = desc;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                .magFilter(VulkanFormats.toVkFilter(desc.magFilter()))
                .minFilter(VulkanFormats.toVkFilter(desc.minFilter()))
                .mipmapMode(VulkanFormats.toVkSamplerMipmapMode(desc.mipmapMode()))
                .addressModeU(VulkanFormats.toVkSamplerAddressMode(desc.addressModeU()))
                .addressModeV(VulkanFormats.toVkSamplerAddressMode(desc.addressModeV()))
                .addressModeW(VulkanFormats.toVkSamplerAddressMode(desc.addressModeW()))
                .mipLodBias(desc.mipLodBias())
                .anisotropyEnable(desc.anisotropyEnable())
                .maxAnisotropy(desc.maxAnisotropy())
                .compareEnable(desc.compareEnable())
                .compareOp(VulkanFormats.toVkCompareOp(desc.compareOp()))
                .minLod(desc.minLod())
                .maxLod(desc.maxLod())
                .borderColor(toVkBorderColor(desc.borderColor()))
                .unnormalizedCoordinates(desc.unnormalizedCoordinates());

            LongBuffer pSampler = stack.mallocLong(1);
            VulkanChecks.check(vkCreateSampler(this.device, createInfo, null, pSampler), "vkCreateSampler");
            this.handle = pSampler.get(0);
        }
    }

    private int toVkBorderColor(Sampler.BorderColor color) {
        return switch (color) {
            case FLOAT_TRANSPARENT_BLACK -> VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
            case INT_TRANSPARENT_BLACK -> VK_BORDER_COLOR_INT_TRANSPARENT_BLACK;
            case FLOAT_OPAQUE_BLACK -> VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK;
            case INT_OPAQUE_BLACK -> VK_BORDER_COLOR_INT_OPAQUE_BLACK;
            case FLOAT_OPAQUE_WHITE -> VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
            case INT_OPAQUE_WHITE -> VK_BORDER_COLOR_INT_OPAQUE_WHITE;
        };
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroySampler(device, handle, null);
    }

    @Override public Filter minFilter() { return desc.minFilter(); }
    @Override public Filter magFilter() { return desc.magFilter(); }
    @Override public SamplerMipmapMode mipmapMode() { return desc.mipmapMode(); }
    @Override public SamplerAddressMode addressModeU() { return desc.addressModeU(); }
    @Override public SamplerAddressMode addressModeV() { return desc.addressModeV(); }
    @Override public SamplerAddressMode addressModeW() { return desc.addressModeW(); }
    @Override public float mipLodBias() { return desc.mipLodBias(); }
    @Override public boolean anisotropyEnable() { return desc.anisotropyEnable(); }
    @Override public float maxAnisotropy() { return desc.maxAnisotropy(); }
    @Override public boolean compareEnable() { return desc.compareEnable(); }
    @Override public CompareOp compareOp() { return desc.compareOp(); }
    @Override public float minLod() { return desc.minLod(); }
    @Override public float maxLod() { return desc.maxLod(); }
    @Override public BorderColor borderColor() { return desc.borderColor(); }
    @Override public boolean unnormalizedCoordinates() { return desc.unnormalizedCoordinates(); }
}
