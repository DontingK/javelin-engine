package cn.donting.javelin.rhi;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.KHRAccelerationStructure.*;

/**
 * DescriptorSet 实现。
 * 对照设计文档第 26.3 节。
 */
final class VulkanDescriptorSet implements DescriptorSet {

    private final VulkanDevice device;
    private final long setHandle;
    private final CleanerRegistry.Registration registration;
    private volatile boolean disposed;

    VulkanDescriptorSet(VulkanDevice device, long setHandle) {
        this.device = device;
        this.setHandle = setHandle;
        this.registration = CleanerRegistry.register(this, device, setHandle,
                CleanerRegistry.ResourceType.DESCRIPTOR_SET);
        device.stats().onDescriptorSetCreated();
    }

    @Override
    public void update(DescriptorUpdate... updates) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkWriteDescriptorSet.Buffer writeSets = VkWriteDescriptorSet.calloc(updates.length, stack);

            for (int i = 0; i < updates.length; i++) {
                DescriptorUpdate update = updates[i];
                VkWriteDescriptorSet write = writeSets.get(i);
                write.sType$Default();
                write.dstSet(setHandle);
                write.dstBinding(update.binding());
                write.descriptorType(toVkDescriptorType(update.type()));
                // DescriptorUpdate 各工厂方法均为单描述符更新；calloc 零初始化下不设就是 0，
                // vkUpdateDescriptorSets 会按 0 条写入（等于静默不写，VUID 亦要求 >0）
                write.descriptorCount(1);

                switch (update.type()) {
                    case UNIFORM_BUFFER, DYNAMIC_UNIFORM_BUFFER, STORAGE_BUFFER -> {
                        VkDescriptorBufferInfo.Buffer bufInfo = VkDescriptorBufferInfo.calloc(1, stack);
                        bufInfo.get(0)
                                .buffer(update.buffer().handle())
                                .offset(update.offset())
                                .range(update.range());
                        write.pBufferInfo(bufInfo);
                    }
                    case SAMPLED_TEXTURE, STORAGE_TEXTURE -> {
                        // 描述符要的是 VkImageView（Texture.handle() 是 VkImage，须经 ensureView 取默认视图）
                        long imageView = update.texture() instanceof VulkanTexture vkTex
                                ? vkTex.ensureView()
                                : update.texture().handle();
                        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
                        imgInfo.get(0)
                                .sampler(update.sampler() != null ? update.sampler().handle() : VK_NULL_HANDLE)
                                .imageView(imageView)
                                .imageLayout(update.type() == DescriptorUpdate.Type.STORAGE_TEXTURE
                                        ? VK_IMAGE_LAYOUT_GENERAL
                                        : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        write.pImageInfo(imgInfo);
                    }
                    case SAMPLER -> {
                        VkDescriptorImageInfo.Buffer imgInfo = VkDescriptorImageInfo.calloc(1, stack);
                        imgInfo.get(0)
                                .sampler(update.sampler().handle())
                                .imageView(VK_NULL_HANDLE)
                                .imageLayout(VK_IMAGE_LAYOUT_UNDEFINED);
                        write.pImageInfo(imgInfo);
                    }
                    case ACCELERATION_STRUCTURE -> {
                        // 加速结构描述符走独立的 pNext 结构（P4-06 B3）
                        VkWriteDescriptorSetAccelerationStructureKHR accel =
                                VkWriteDescriptorSetAccelerationStructureKHR.calloc(stack).sType$Default();
                        accel.accelerationStructureCount(1);
                        accel.pAccelerationStructures(stack.longs(update.tlas().handle()));
                        write.pNext(accel);
                    }
                }
            }

            vkUpdateDescriptorSets(device.vkDevice(), writeSets, null);
        }
    }

    @Override
    public long handle() {
        return setHandle;
    }

    @Override
    public void dispose() {
        // 归还池子走 CleanerRegistry 两帧延迟队列（VUID 00309：vkFreeDescriptorSets 要求所有
        // 引用该 set 的已提交命令缓冲执行完毕——立即 free 会撞上还在 in-flight 的帧；入队后由
        // onFrameEnd 在 submit 之后的帧末推进，窗口成立）。不 free 的话池只出不回，高频换代方
        // （材质贴图 revision / DDGI 换绑）会把 maxSets 烧穿 → OUT_OF_POOL_MEMORY
        if (disposed) return;
        disposed = true;
        registration.dispose();
        device.stats().onDescriptorSetDisposed();
    }

    private static int toVkDescriptorType(DescriptorUpdate.Type type) {
        return switch (type) {
            case UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case DYNAMIC_UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
            case STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            case SAMPLED_TEXTURE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case STORAGE_TEXTURE -> VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
            case SAMPLER -> VK_DESCRIPTOR_TYPE_SAMPLER;
            case ACCELERATION_STRUCTURE -> VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR;
        };
    }
}
