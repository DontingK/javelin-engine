package com.javelin.rhi.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * GPU 资源延迟释放管理器。
 *
 * <p>解决 Vulkan 资源"当前帧仍在使用"问题：
 * <ul>
 *   <li>资源不再被引用时，不立即释放，而是加入延迟队列</li>
 *   <li>每帧推进队列，延迟 {@code framesInFlight} 帧后真正调用 {@link GPUResource#disposeNow()}</li>
 *   <li>与 {@link com.javelin.rhi.Renderer} 的帧同步机制配合</li>
 * </ul>
 *
 * <p>使用方式：
 * <pre>
 *   // 初始化（与 Renderer 的 framesInFlight 一致）
 *   DelayedResourceDisposer disposer = new DelayedResourceDisposer(2);
 *
 *   // 请求释放资源（通常由 GPUResource 的 Cleaner 回调触发）
 *   disposer.requestDispose(buffer);
 *
 *   // 每帧开始（推进队列并释放延迟到期的资源）
 *   disposer.beginFrame();
 * </pre>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class DelayedResourceDisposer {

    private static final Logger log = LoggerFactory.getLogger(DelayedResourceDisposer.class);

    /** 延迟帧数（通常等于 Renderer 的 maxFramesInFlight） */
    private final int delayFrames;

    /** 延迟队列：每个元素是一帧内需要释放的资源列表 */
    private final Deque<Deque<GPUResource>> frameQueues;

    /** 当前帧的待释放资源 */
    private Deque<GPUResource> currentFrameResources;

    /** 当前帧序号 */
    private long frameNumber = 0;

    /**
     * 构造延迟释放管理器。
     *
     * @param delayFrames 延迟帧数（必须 >= 1，推荐与 Renderer.framesInFlight 一致）
     */
    public DelayedResourceDisposer(int delayFrames) {
        if (delayFrames < 1) {
            throw new IllegalArgumentException("delayFrames must be >= 1");
        }
        this.delayFrames = delayFrames;
        this.frameQueues = new ArrayDeque<>(delayFrames);
        this.currentFrameResources = new ArrayDeque<>();

        // 预填充空队列
        for (int i = 0; i < delayFrames; i++) {
            frameQueues.addLast(new ArrayDeque<>());
        }
    }

    /**
     * 开始新帧。
     * <p>将当前帧队列加入延迟队列尾部，并处理到期的资源。</p>
     */
    public void beginFrame() {
        frameNumber++;

        // 将当前帧的待释放资源加入队列
        frameQueues.addLast(currentFrameResources);

        // 取出队列头部（延迟到期的资源）并释放
        Deque<GPUResource> readyToDispose = frameQueues.pollFirst();
        if (readyToDispose != null && !readyToDispose.isEmpty()) {
            int count = readyToDispose.size();
            for (GPUResource resource : readyToDispose) {
                disposeSafely(resource);
            }
            log.debug("Frame {}: disposed {} delayed resources", frameNumber, count);
        }

        // 创建新的当前帧队列
        currentFrameResources = new ArrayDeque<>();
    }

    /**
     * 请求延迟释放资源。
     * <p>资源不会立即释放，而是延迟 {@code delayFrames} 帧后释放。</p>
     *
     * @param resource 要释放的 GPU 资源
     */
    public void requestDispose(GPUResource resource) {
        if (resource == null || resource.isDisposed()) {
            return;
        }
        currentFrameResources.addLast(resource);
    }

    /**
     * 立即释放所有待延迟资源（用于引擎关闭）。
     * <p>等待所有延迟队列中的资源到期并释放。</p>
     */
    public void flush() {
        log.info("Flushing delayed resource disposer ({} frames pending)", frameQueues.size());

        // 先释放当前帧的
        for (GPUResource resource : currentFrameResources) {
            disposeSafely(resource);
        }
        currentFrameResources.clear();

        // 再释放队列中的所有
        for (Deque<GPUResource> queue : frameQueues) {
            for (GPUResource resource : queue) {
                disposeSafely(resource);
            }
            queue.clear();
        }
    }

    /**
     * 安全释放资源（捕获异常）。
     */
    private void disposeSafely(GPUResource resource) {
        try {
            resource.disposeNow();
        } catch (Exception e) {
            log.error("Failed to dispose resource: {}", resource.getClass().getSimpleName(), e);
        }
    }

    /**
     * 获取当前延迟队列中的资源总数（调试用）。
     */
    public int getPendingCount() {
        int count = currentFrameResources.size();
        for (Deque<GPUResource> queue : frameQueues) {
            count += queue.size();
        }
        return count;
    }

    /**
     * 获取当前帧序号。
     */
    public long getFrameNumber() {
        return frameNumber;
    }
}
