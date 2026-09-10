package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.DescriptorSetLayout;
import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
	import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 描述符集布局实现 */
public class VulkanDescriptorSetLayout extends DescriptorSetLayout {

    private final VkDevice device;
    private final long handle;
    private final Binding[] bindings;

    public VulkanDescriptorSetLayout(VulkanDevice device, DescriptorSetLayoutDesc desc) {
        this.device = device.vkDevice();

        this.bindings = new Binding[desc.bindings().length];
        for (int i = 0; i < desc.bindings().length; i++) {
            DescriptorSetLayoutDesc.Binding b = desc.bindings()[i];
            bindings[i] = new Binding(b.binding(), b.descriptorType(), b.descriptorCount(), b.stageFlags(), null);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer vkBindings = VkDescriptorSetLayoutBinding.calloc(bindings.length, stack);
            for (int i = 0; i < bindings.length; i++) {
                Binding b = bindings[i];
                vkBindings.get(i)
                    .binding(b.binding())
                    .descriptorType(toVkDescriptorType(b.descriptorType()))
                    .descriptorCount(b.descriptorCount())
                    .stageFlags(b.stageFlags().value());
            }

            VkDescriptorSetLayoutCreateInfo createInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO)
                .pBindings(vkBindings);

            LongBuffer pLayout = stack.mallocLong(1);
            VulkanChecks.check(vkCreateDescriptorSetLayout(device.vkDevice(), createInfo, null, pLayout), "vkCreateDescriptorSetLayout");
            this.handle = pLayout.get(0);
        }
    }

    private int toVkDescriptorType(DescriptorType type) {
        return switch (type) {
            case SAMPLER -> VK_DESCRIPTOR_TYPE_SAMPLER;
            case COMBINED_IMAGE_SAMPLER -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case SAMPLED_IMAGE -> VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
            case STORAGE_IMAGE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case UNIFORM_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
            case STORAGE_TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_TEXEL_BUFFER;
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case UNIFORM_BUFFER_DYNAMIC -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case STORAGE_BUFFER_DYNAMIC -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
            case INPUT_ATTACHMENT -> VK_DESCRIPTOR_TYPE_INPUT_ATTACHMENT;
        };
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyDescriptorSetLayout(device, handle, null);
    }

    @Override
    public Binding[] bindings() {
        return bindings;
    }
}
