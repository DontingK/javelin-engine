package com.javelin.rhi.api;

/**
 * 帧缓冲（传统 Vulkan Framebuffer）。
 */
public abstract class Framebuffer extends GPUResource {

    public abstract RenderPass renderPass();

    public abstract int width();

    public abstract int height();

    public abstract int layers();

    public abstract TextureView[] attachments();
}
