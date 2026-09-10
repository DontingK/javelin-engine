package com.javelin.rhi.api;

/**
 * 栅栏（CPU-GPU 同步）。
 */
public abstract class Fence extends GPUResource {

    public abstract void await(long timeoutNanos);

    public abstract void reset();

    public abstract boolean isSignaled();
}
