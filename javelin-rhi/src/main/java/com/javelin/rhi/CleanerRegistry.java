package com.javelin.rhi;

import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 延迟销毁队列（设计文档第 17 节）。
 *
 * Java 对象不可达 → Cleaner 触发 → 入队 → 两帧后真正 vkDestroy。
 * Cleaner 回调只入队，不碰 Vulkan（避免跨线程 / GPU 在用资源）。
 */
final class CleanerRegistry {

    /** 销毁任务：只持有 rhi + handle，不引用 RHIResource 实例（避免自引用陷阱） */
    static final class DestroyTask implements Runnable {
        final VulkanDevice device;
        final long handle;
        final ResourceType type;

        DestroyTask(VulkanDevice device, long handle, ResourceType type) {
            this.device = device;
            this.handle = handle;
            this.type = type;
        }

        @Override
        public void run() {
            DeferredDestroyQueue.enqueue(this);
        }
    }

    /** 资源类型 */
    enum ResourceType {
        BUFFER,
        TEXTURE,
        SAMPLER,
        SHADER_MODULE,
        PIPELINE,
        PIPELINE_LAYOUT,
        DESCRIPTOR_SET_LAYOUT,
        IMAGE_VIEW,
        SEMAPHORE,
        FENCE,
        COMMAND_POOL
    }

    /** 延迟销毁队列条目 */
    private static final class DestroyEntry {
        final DestroyTask task;
        final long frameIndex;

        DestroyEntry(DestroyTask task, long frameIndex) {
            this.task = task;
            this.frameIndex = frameIndex;
        }
    }

    /** 全局 Cleaner */
    private static final Cleaner cleaner = Cleaner.create();

    /** 延迟销毁队列 */
    static final class DeferredDestroyQueue {
        private static final ConcurrentLinkedQueue<DestroyEntry> queue = new ConcurrentLinkedQueue<>();
        private static volatile long currentFrame = 0;

        static void enqueue(DestroyTask task) {
            queue.add(new DestroyEntry(task, currentFrame));
        }

        /** 每帧由提交线程调用：处理两帧前的销毁请求 */
        static void process(long frame) {
            currentFrame = frame;
            DestroyEntry e;
            while ((e = queue.peek()) != null && e.frameIndex + 2 <= frame) {
                queue.poll();
                executeDestroy(e.task);
            }
        }

        private static void executeDestroy(DestroyTask task) {
            if (task.device.isDisposed()) return;

            switch (task.type) {
                case BUFFER -> org.lwjgl.vulkan.VK10.vkDestroyBuffer(task.device.vkDevice(), task.handle, null);
                case TEXTURE -> org.lwjgl.vulkan.VK10.vkDestroyImage(task.device.vkDevice(), task.handle, null);
                case SAMPLER -> org.lwjgl.vulkan.VK10.vkDestroySampler(task.device.vkDevice(), task.handle, null);
                case SHADER_MODULE -> org.lwjgl.vulkan.VK10.vkDestroyShaderModule(task.device.vkDevice(), task.handle, null);
                case PIPELINE -> org.lwjgl.vulkan.VK10.vkDestroyPipeline(task.device.vkDevice(), task.handle, null);
                case PIPELINE_LAYOUT -> org.lwjgl.vulkan.VK10.vkDestroyPipelineLayout(task.device.vkDevice(), task.handle, null);
                case DESCRIPTOR_SET_LAYOUT -> org.lwjgl.vulkan.VK10.vkDestroyDescriptorSetLayout(task.device.vkDevice(), task.handle, null);
                case IMAGE_VIEW -> org.lwjgl.vulkan.VK10.vkDestroyImageView(task.device.vkDevice(), task.handle, null);
                case SEMAPHORE -> org.lwjgl.vulkan.VK10.vkDestroySemaphore(task.device.vkDevice(), task.handle, null);
                case FENCE -> org.lwjgl.vulkan.VK10.vkDestroyFence(task.device.vkDevice(), task.handle, null);
                case COMMAND_POOL -> org.lwjgl.vulkan.VK10.vkDestroyCommandPool(task.device.vkDevice(), task.handle, null);
            }
        }
    }

    private CleanerRegistry() {}

    static Cleaner.Cleanable register(VulkanDevice device, long handle, ResourceType type) {
        return cleaner.register(new Object(), new DestroyTask(device, handle, type));
    }

    /** 帧推进时调用 */
    static void onFrameEnd(long frameIndex) {
        DeferredDestroyQueue.process(frameIndex);
    }
}
