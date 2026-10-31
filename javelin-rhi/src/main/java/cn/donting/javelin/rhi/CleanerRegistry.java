package cn.donting.javelin.rhi;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.Cleaner;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * 延迟销毁队列（设计文档第 17 节）：手动 dispose 与 Cleaner 兜底共用同一个两帧延迟队列，两条路都
 * 不立即 vkDestroy（GPU 异步：本帧提交的命令可能 N+1/N+2 帧才执行完）。手动经
 * {@link Registration#dispose} 触发（动作执行入队并注销 Cleaner，二者合一只触发一次）；不可达对象
 * 由 Cleaner 触发入队。设备销毁前 {@link #flush} 冲刷剩余条目。
 */
public final class CleanerRegistry {

    private static final Logger log = LoggerFactory.getLogger(CleanerRegistry.class);

    /**
     * 销毁任务：只持有 device + handle，不引用资源实例（避免自引用陷阱——
     * 若引用实例，Cleaner 会强持有任务而任务强持有实例，导致实例永远不可达）。
     */
    static final class DestroyTask implements Runnable {
        final VulkanDevice device;
        final long handle;
        /** 配套 VkDeviceMemory（BUFFER/TEXTURE 各自独占分配的显存；0 = 无）。销毁时先 destroy 对象再 free memory——规范序：内存不得在仍被资源绑定时释放 */
        final long memoryHandle;
        final ResourceType type;
        /** true = 显式 dispose 触发（Registration.dispose 在 clean() 前置位）；false = Cleaner 兜底 */
        boolean manual;

        DestroyTask(VulkanDevice device, long handle, long memoryHandle, ResourceType type) {
            this.device = device;
            this.handle = handle;
            this.memoryHandle = memoryHandle;
            this.type = type;
        }

        @Override
        public void run() {
            DeferredDestroyQueue.enqueue(this);
        }
    }

    /**
     * 注册句柄：{@link Cleaner.Cleanable}（显式 dispose 触发）+ DestroyTask（入队动作载体）。
     * 资源实例持它，{@code dispose()} 调 {@link #dispose()}——执行入队动作的同时注销 Cleaner，
     * 因此同一句柄只可能销毁一次（不需要额外的"已销毁句柄"登记）。
     */
    static final class Registration {
        private final Cleaner.Cleanable cleanable;
        private final DestroyTask task;

        private Registration(Cleaner.Cleanable cleanable, DestroyTask task) {
            this.cleanable = cleanable;
            this.task = task;
        }

        /** 显式 dispose：标来源 → clean()（入队销毁动作并注销 Cleaner，之后不再触发） */
        void dispose() {
            task.manual = true;
            cleanable.clean();
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

    /** 延迟销毁队列 */
    static final class DeferredDestroyQueue {
        private static final ConcurrentLinkedQueue<DestroyEntry> queue = new ConcurrentLinkedQueue<>();
        private static volatile long currentFrame = 0;

        static void enqueue(DestroyTask task) {
            queue.add(new DestroyEntry(task, currentFrame));
        }

        /** 立即执行全部待处理销毁（设备销毁前冲刷：帧循环已停、无在飞资源，须在 device 标记 disposed 之前调用） */
        static void flush() {
            DestroyEntry e;
            while ((e = queue.poll()) != null) {
                executeDestroy(e.task, currentFrame);
            }
        }

        /** 每帧由提交线程调用：处理两帧前的销毁请求 */
        static void process(long frame) {
            currentFrame = frame;
            DestroyEntry e;
            while ((e = queue.peek()) != null && e.frameIndex + 2 <= frame) {
                queue.poll();
                executeDestroy(e.task, frame);
            }
        }

        private static void executeDestroy(DestroyTask task, long frame) {
            if (task.device.isDisposed()) {
                log.debug("跳过释放（device 已销毁）[{}]: type={} handle=0x{}",
                        task.manual ? "手动" : "Cleaner", task.type, Long.toHexString(task.handle));
                return;
            }

            switch (task.type) {
                case BUFFER -> {
                    org.lwjgl.vulkan.VK10.vkDestroyBuffer(task.device.vkDevice(), task.handle, null);
                    if (task.memoryHandle != 0) {
                        VulkanMemory.free(task.device.vkDevice(), task.memoryHandle);
                    }
                }
                case TEXTURE -> {
                    org.lwjgl.vulkan.VK10.vkDestroyImage(task.device.vkDevice(), task.handle, null);
                    if (task.memoryHandle != 0) {
                        VulkanMemory.free(task.device.vkDevice(), task.memoryHandle);
                    }
                }
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
            log.info("释放 GPU 资源[{}]: type={} handle=0x{} memory=0x{} frame={}",
                    task.manual ? "手动" : "Cleaner", task.type,
                    Long.toHexString(task.handle), Long.toHexString(task.memoryHandle), frame);
        }
    }

    private CleanerRegistry() {}

    /**
     * 注册资源的 Cleaner 兜底。
     *
     * @param resource 被监护对象，必须为资源实例自身（Cleaner 在其不可达时触发任务）；
     *                 传其他对象会导致任务提前或永不触发
     */
    static Registration register(Object resource, VulkanDevice device, long handle, ResourceType type) {
        return register(resource, device, handle, 0L, type);
    }

    /** 带配套显存分配的资源注册（BUFFER / TEXTURE）：销毁时 memory 与对象一并释放 */
    static Registration register(Object resource, VulkanDevice device, long handle, long memoryHandle, ResourceType type) {
        DestroyTask task = new DestroyTask(device, handle, memoryHandle, type);
        return new Registration(cleaner.register(resource, task), task);
    }

    /** 冲刷延迟销毁队列（设备销毁前调用，见 DeferredDestroyQueue#flush） */
    static void flush() {
        DeferredDestroyQueue.flush();
    }

    /** 帧推进时调用（提交线程每帧一次；17 节两帧规则） */
    public static void onFrameEnd(long frameIndex) {
        DeferredDestroyQueue.process(frameIndex);
    }
}
