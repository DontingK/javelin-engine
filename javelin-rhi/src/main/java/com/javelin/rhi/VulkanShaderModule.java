package com.javelin.rhi;

import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * ShaderModule 实现。
 * 对照设计文档第 16.2 节 / 第 26.3 节。
 */
final class VulkanShaderModule implements ShaderModule {

    private final VulkanDevice device;
    private final long moduleHandle;
    private volatile boolean disposed;

    VulkanShaderModule(VulkanDevice device, byte[] spirv) {
        this.device = device;

        // 校验 SPIR-V magic number
        if (spirv.length < 4 || (spirv[0] & 0xFF) != 0x03 || (spirv[1] & 0xFF) != 0x02
                || (spirv[2] & 0xFF) != 0x23 || (spirv[3] & 0xFF) != 0x07) {
            throw new IllegalArgumentException("Invalid SPIR-V magic number");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer spirvBuf = stack.malloc(spirv.length);
            spirvBuf.put(spirv).flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack);
            createInfo.sType$Default();
            createInfo.pCode(spirvBuf);

            LongBuffer moduleBuf = stack.mallocLong(1);
            int result = vkCreateShaderModule(device.vkDevice(), createInfo, null, moduleBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create shader module");
            }

            this.moduleHandle = moduleBuf.get(0);
        }

        CleanerRegistry.register(device, moduleHandle, CleanerRegistry.ResourceType.SHADER_MODULE);
    }

    @Override
    public long handle() {
        return moduleHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        vkDestroyShaderModule(device.vkDevice(), moduleHandle, null);
    }
}
