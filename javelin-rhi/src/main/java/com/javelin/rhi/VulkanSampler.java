package com.javelin.rhi;

import com.javelin.rhi.desc.SamplerDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE;

/**
 * Sampler 实现。
 * 对照设计文档第 26.2 节。
 */
final class VulkanSampler implements Sampler {

    private final VulkanDevice device;
    private final SamplerDesc desc;
    private final long samplerHandle;
    /** Cleaner 注册句柄（dispose 触发延迟销毁） */
    private CleanerRegistry.Registration registration;
    private volatile boolean disposed;

    VulkanSampler(VulkanDevice device, SamplerDesc desc) {
        this.device = device;
        this.desc = desc;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkSamplerCreateInfo samplerInfo = VkSamplerCreateInfo.calloc(stack);
            samplerInfo.sType$Default();
            samplerInfo.magFilter(toVkFilter(desc.magFilter()));
            samplerInfo.minFilter(toVkFilter(desc.minFilter()));
            samplerInfo.mipmapMode(toVkMipmapMode(desc.mipmapMode()));
            samplerInfo.addressModeU(toVkAddressMode(desc.addressModeU()));
            samplerInfo.addressModeV(toVkAddressMode(desc.addressModeV()));
            samplerInfo.addressModeW(toVkAddressMode(desc.addressModeW()));
            samplerInfo.maxAnisotropy(desc.maxAnisotropy());
            samplerInfo.maxLod(desc.maxLod());

            LongBuffer samplerBuf = stack.mallocLong(1);
            int result = vkCreateSampler(device.vkDevice(), samplerInfo, null, samplerBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create sampler");
            }

            this.samplerHandle = samplerBuf.get(0);
        }

        registration = CleanerRegistry.register(this, device, samplerHandle, CleanerRegistry.ResourceType.SAMPLER);
        device.stats().onSamplerCreated();
    }

    @Override
    public SamplerDesc desc() {
        return desc;
    }

    @Override
    public long handle() {
        return samplerHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        device.stats().onSamplerDisposed();
        registration.dispose();
    }

    private static int toVkFilter(com.javelin.rhi.types.Filter filter) {
        return switch (filter) {
            case NEAREST -> VK_FILTER_NEAREST;
            case LINEAR -> VK_FILTER_LINEAR;
        };
    }

    private static int toVkMipmapMode(com.javelin.rhi.types.SamplerMipmapMode mode) {
        return switch (mode) {
            case NEAREST -> VK_SAMPLER_MIPMAP_MODE_NEAREST;
            case LINEAR -> VK_SAMPLER_MIPMAP_MODE_LINEAR;
        };
    }

    private static int toVkAddressMode(com.javelin.rhi.types.SamplerAddressMode mode) {
        return switch (mode) {
            case REPEAT -> VK_SAMPLER_ADDRESS_MODE_REPEAT;
            case MIRRORED_REPEAT -> VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
            case CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            case CLAMP_TO_BORDER -> VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
            case MIRROR_CLAMP_TO_EDGE -> VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE;
        };
    }
}
