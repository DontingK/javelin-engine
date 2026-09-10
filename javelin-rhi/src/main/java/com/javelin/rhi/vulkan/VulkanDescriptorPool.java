package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.DescriptorPool;
import com.javelin.rhi.api.DescriptorSet;
import com.javelin.rhi.api.DescriptorSetLayout;
import com.javelin.rhi.desc.DescriptorPoolDesc;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import org.lwjgl.system.MemoryStack;
import java.nio.LongBuffer;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDevice;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Vulkan 描述符池实现 */
public class VulkanDescriptorPool extends DescriptorPool {

    private final VkDevice device;
    private final long handle;
    private final boolean freeIndividualSets;

    public VulkanDescriptorPool(VulkanDevice device, DescriptorPoolDesc desc) {
        this.device = device.vkDevice();
        this.freeIndividualSets = desc.freeIndividualSets();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkDescriptorPoolSize.Buffer poolSizes = VkDescriptorPoolSize.calloc(desc.poolSizes().length, stack);
            for (int i = 0; i < desc.poolSizes().length; i++) {
                DescriptorPoolDesc.PoolSize size = desc.poolSizes()[i];
                poolSizes.get(i)
                    .type(toVkDescriptorType(size.type()))
                    .descriptorCount(size.count());
            }

            VkDescriptorPoolCreateInfo createInfo = VkDescriptorPoolCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO)
                .maxSets(desc.maxSets())
                .pPoolSizes(poolSizes);

            if (freeIndividualSets) {
                createInfo.flags(VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT);
            }

            LongBuffer pPool = stack.mallocLong(1);
            VulkanChecks.check(vkCreateDescriptorPool(this.device, createInfo, null, pPool), "vkCreateDescriptorPool");
            this.handle = pPool.get(0);
        }
    }

    private int toVkDescriptorType(com.javelin.rhi.types.DescriptorType type) {
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
        vkDestroyDescriptorPool(device, handle, null);
    }

    @Override
    public DescriptorSet allocateSet(DescriptorSetLayout layout) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorSetAllocateInfo allocInfo = org.lwjgl.vulkan.VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(handle)
                .pSetLayouts(stack.longs(layout.handle()));

            LongBuffer pSet = stack.mallocLong(1);
            VulkanChecks.check(vkAllocateDescriptorSets(device, allocInfo, pSet), "vkAllocateDescriptorSets");
            return new VulkanDescriptorSet(device, pSet.get(0), layout, this);
        }
    }

    @Override
    public DescriptorSet[] allocateSets(DescriptorSetLayout[] layouts) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            org.lwjgl.vulkan.VkDescriptorSetAllocateInfo allocInfo = org.lwjgl.vulkan.VkDescriptorSetAllocateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO)
                .descriptorPool(handle)
                .pSetLayouts(stack.longs(java.util.Arrays.stream(layouts).mapToLong(DescriptorSetLayout::handle).toArray()));

            LongBuffer pSets = stack.mallocLong(layouts.length);
            VulkanChecks.check(vkAllocateDescriptorSets(device, allocInfo, pSets), "vkAllocateDescriptorSets");

            DescriptorSet[] sets = new DescriptorSet[layouts.length];
            for (int i = 0; i < layouts.length; i++) {
                sets[i] = new VulkanDescriptorSet(device, pSets.get(i), layouts[i], this);
            }
            return sets;
        }
    }

    @Override
    public void freeSets(DescriptorSet[] sets) {
        if (!freeIndividualSets) {
            throw new IllegalStateException("Cannot free individual sets from this pool");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSets = stack.mallocLong(sets.length);
            for (DescriptorSet set : sets) {
                pSets.put(set.handle());
            }
            pSets.flip();
            vkFreeDescriptorSets(device, handle, pSets);
        }
    }

    @Override
    public void reset() {
        vkResetDescriptorPool(device, handle, 0);
    }
}
