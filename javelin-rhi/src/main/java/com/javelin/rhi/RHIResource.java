package com.javelin.rhi;

import java.lang.ref.Cleaner;

/**
 * GPU 资源抽象基类，所有 RHI 资源对象（缓冲区、纹理、管线等）均继承此类。
 * <p>
 * 通过 {@link Cleaner} 机制实现资源的延迟销毁，避免显式 close() 遗漏导致的内存泄漏。
 */
public abstract class RHIResource implements AutoCloseable {

    protected final long handle;
    protected final Cleaner.Cleanable cleanable;

    protected RHIResource(RHI rhi, long handle) {
        this.handle = handle;
        this.cleanable = rhi.cleaner().register(this, new DestroyTask(rhi, handle));
    }

    /** 返回底层 GPU 资源句柄。 */
    public long handle() {
        return handle;
    }

    /** 立即释放资源（通常由 Cleaner 自动调用，也可手动调用以提前释放）。 */
    @Override
    public void close() {
        cleanable.clean();
    }

    static final class DestroyTask implements Runnable {
        final RHI rhi;
        final long handle;

        DestroyTask(RHI rhi, long handle) {
            this.rhi = rhi;
            this.handle = handle;
        }

        @Override
        public void run() {
            rhi.enqueueDestroy(handle);
        }
    }
}
