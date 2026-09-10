package com.javelin.rhi.api;

import com.javelin.rhi.types.AttachmentLoadOp;
import com.javelin.rhi.types.AttachmentStoreOp;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.SampleCount;
import com.javelin.rhi.types.TextureLayout;

/**
 * 渲染通道（传统 Vulkan RenderPass）。
 */
public abstract class RenderPass extends GPUResource {

    public abstract AttachmentDescription[] attachments();

    public abstract SubpassDescription[] subpasses();

    public abstract SubpassDependency[] dependencies();

    // ========== 内部类型 ==========

    public record AttachmentDescription(
            Format format,
            SampleCount samples,
            AttachmentLoadOp loadOp,
            AttachmentStoreOp storeOp,
            AttachmentLoadOp stencilLoadOp,
            AttachmentStoreOp stencilStoreOp,
            TextureLayout initialLayout,
            TextureLayout finalLayout
    ) {
    }

    public record SubpassDescription(
            AttachmentReference[] colorAttachments,
            AttachmentReference[] resolveAttachments,
            AttachmentReference depthStencilAttachment,
            AttachmentReference[] inputAttachments,
            int[] preserveAttachments
    ) {
    }

    public record AttachmentReference(int attachment, TextureLayout layout) {
    }

    public record SubpassDependency(
            int srcSubpass,
            int dstSubpass,
            int srcStageMask,
            int dstStageMask,
            int srcAccessMask,
            int dstAccessMask,
            int dependencyFlags
    ) {
    }
}
