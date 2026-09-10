package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.Shader;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 着色器模块实现 */
public class VulkanShader extends Shader {

    private final VkDevice device;
    private final long handle;
    private final ShaderStage stage;

    public VulkanShader(VulkanDevice device, ShaderDesc desc) {
        this.device = device.vkDevice();
        this.stage = desc.stage();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // SPIR-V 必须4 字节对齐
            byte[] spirv = desc.spirvBytecode();
            if (spirv.length % 4 != 0) {
                throw new IllegalArgumentException("SPIR-V bytecode length must be a multiple of 4");
            }

            ByteBuffer pCode = stack.malloc(spirv.length);
            pCode.put(spirv);
            pCode.flip();

            VkShaderModuleCreateInfo createInfo = VkShaderModuleCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO)
                .pCode(pCode);

            LongBuffer pShader = stack.mallocLong(1);
            VulkanChecks.check(vkCreateShaderModule(this.device, createInfo, null, pShader), "vkCreateShaderModule");
            this.handle = pShader.get(0);
        }
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyShaderModule(device, handle, null);
    }

    @Override
    public ShaderStage stage() {
        return stage;
    }
}
