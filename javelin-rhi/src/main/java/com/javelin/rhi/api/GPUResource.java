package com.javelin.rhi.api;

import java.lang.ref.Cleaner;

/**
 * 所有 GPU 资源的抽象基类。
 * <p>
 * 通过 {@link Cleaner} 实现 GC 自动释放：当资源不再被引用时，
 * 自动请求 {@link DelayedResourceDisposer} 延迟释放，防止当前帧仍在使用。
 * <p>
 * 也支持手动调用 {@link #dispose()} 请求释放：不会立即释放，
 * 而是加入延迟队列，延迟 {@code framesInFlight} 帧后真正释放，保证帧安全。
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class GPUResource {

    private static final Cleaner cleaner = Cleaner.create();

    /** 全局延迟释放管理器，由 Renderer 初始化时设置 */
    private static DelayedResourceDisposer disposer;

    private final Cleaner.Cleanable cleanable;
    private volatile boolean disposed = false;

    protected GPUResource() {
        this.cleanable = cleaner.register(this, new DisposeAction(this));
    }

    /**
     * 设置全局延迟释放管理器。
     * <p>
     * 由 {@link com.javelin.rhi.Renderer} 在初始化时调用。
     *
     * @param disposer 延迟释放管理器
     */
    public static void setDisposer(DelayedResourceDisposer disposer) {
        GPUResource.disposer = disposer;
    }

    /**
     * 强制释放所有待延迟资源（用于引擎关闭等拆卸场景）。
     * <p>
     * 调用前必须确保 GPU 已空闲（{@code waitIdle}）。
     */
    public static void flushDisposals() {
        if (disposer != null) {
            disposer.flush();
        }
    }

    /**
     * 返回原生 Vulkan 句柄（如 VkBuffer、VkImage 等）。
     */
    public abstract long handle();

    /**
     * 释放 GPU 资源。
     * <p>
     * <strong>注意</strong>：此方法由延迟释放系统调用，不应直接调用。
     * 资源生命周期由 GC 管理。
     */
    protected abstract void doDispose();

    /**
     * 请求释放 GPU 资源（可手动调用，帧安全）。
     * <p>
     * 不会立即释放：若已设置全局延迟释放管理器，则加入延迟队列，
     * 延迟 {@code framesInFlight} 帧后真正释放；否则立即释放。
     * GC 回收时 Cleaner 也会自动触发同样的流程。
     * 重复调用是安全的（幂等）。
     */
    public final void dispose() {
        if (disposed) {
            return;
        }
        DelayedResourceDisposer d = disposer;
        if (d != null) {
            d.requestDispose(this);
        } else {
            disposeNow();
        }
    }

    /**
     * 立即真正释放资源（由延迟释放系统到期时调用）。
     * <p>
     * 调用 {@link #doDispose()} 并注销 Cleaner，防止 GC 时重复释放。
     */
    final void disposeNow() {
        if (disposed) {
            return;
        }
        markDisposed();
        doDispose();
        cleanable.clean();
    }

    /**
     * 资源是否已释放。
     */
    public boolean isDisposed() {
        return disposed;
    }

    /**
     * 标记为已释放（由延迟释放系统调用）。
     */
    void markDisposed() {
        this.disposed = true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Cleaner 动作
    // ═══════════════════════════════════════════════════════════════════════

    private static class DisposeAction implements Runnable {

        private final GPUResource resource;

        DisposeAction(GPUResource resource) {
            this.resource = resource;
        }

        @Override
        public void run() {
            if (disposer != null) {
                disposer.requestDispose(resource);
            } else {
                // 没有延迟释放器，直接释放（不安全，但比泄漏好）
                resource.disposeNow();
            }
        }
    }
}
