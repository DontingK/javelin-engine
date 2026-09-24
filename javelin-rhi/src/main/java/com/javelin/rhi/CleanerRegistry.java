package com.javelin.rhi;

import java.lang.ref.Cleaner;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 延迟销毁队列（设计文档第 17 节）。
 *
 * Java 对象不可达 → Cleaner 触发 → 入队 → 两帧后真正 vkDestroy。
 * Cleaner 回调只入队，不碰 Vulkan（避免跨线程 / GPU 在用资源）。
 *
 * 与显式 dispose 互斥：显式 dispose 把句柄记入 {@link #DISPOSED_HANDLES}，
 * Cleaner 触发时发现句柄已显式销毁则跳过入队，避免对同一句柄二次 vkDestroy。
 */
public final class CleanerRegistry {

    /**
     * 销毁任务：只持有 device + handle，不引用资源实例（避免自引用陷阱——
     * 若引用实例，Cleaner 会强持有任务而任务强持有实例，导致实例永远不可达）。
     */
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
            // 已显式 dispose：跳过入队，避免二次销毁同一句柄
            if (DISPOSED_HANDLES.contains(handle)) {
                return;
            }
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
        COMMAND_POOL,
        DESCRIPTOR_POOL
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

    /**
     * 已显式销毁的句柄集合。
     * 注意：句柄销毁后可能被驱动复用，陈旧条目的最坏影响是抑制复用者的 Cleaner 兜底（泄漏），
     * 不会造成二次销毁。
     */
    private static final Set<Long> DISPOSED_HANDLES = ConcurrentHashMap.newKeySet();

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
                case DESCRIPTOR_POOL -> org.lwjgl.vulkan.VK10.vkDestroyDescriptorPool(task.device.vkDevice(), task.handle, null);
            }
            // 句柄已真正释放，清理显式销毁登记
            DISPOSED_HANDLES.remove(task.handle);
        }
    }

    private CleanerRegistry() {}

    /**
     * 注册资源的 Cleaner 兜底。
     *
     * @param resource 被监护对象，必须为资源实例自身（Cleaner 在其不可达时触发任务）；
     *                 传其他对象会导致任务提前或永不触发
     */
    static Cleaner.Cleanable register(Object resource, VulkanDevice device, long handle, ResourceType type) {
        return cleaner.register(resource, new DestroyTask(device, handle, type));
    }

    /** 显式 dispose 时登记：Cleaner 兜底跳过该句柄 */
    static void markDisposed(long handle) {
        DISPOSED_HANDLES.add(handle);
    }

    /** 帧推进时调用（提交线程每帧一次；17 节两帧规则） */
    public static void onFrameEnd(long frameIndex) {
        DeferredDestroyQueue.process(frameIndex);
    }
}
