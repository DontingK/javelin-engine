package com.javelin.rhi.api;

import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;

import java.nio.ByteBuffer;

/**
 * 命令缓冲区，录制 GPU 命令。
 */
public abstract class CommandBuffer extends GPUResource {

    // ========== 录制控制 ==========

    public abstract void begin();

    public abstract void beginSecondary(RenderPass renderPass, int subpass, Framebuffer framebuffer);

    public abstract void end();

    public abstract void reset();

    // ========== 动态渲染（Vulkan 1.3） ==========

    public abstract void beginRendering(RenderingInfo info);

    public abstract void endRendering();

    // ========== 传统渲染通道 ==========

    public abstract void beginRenderPass(RenderPassBeginInfo info);

    public abstract void nextSubpass();

    public abstract void endRenderPass();

    // ========== 管线绑定 ==========

    public abstract void bindPipeline(GraphicsPipeline pipeline);

    public abstract void bindPipeline(ComputePipeline pipeline);

    // ========== 描述符 ==========

    public abstract void bindDescriptorSets(PipelineBindPoint bindPoint, PipelineLayout layout,
                                            int firstSet, DescriptorSet[] sets, int[] dynamicOffsets);

    // ========== 顶点/索引 ==========

    public abstract void bindVertexBuffer(int binding, Buffer buffer, long offset);

    public abstract void bindVertexBuffers(int firstBinding, Buffer[] buffers, long[] offsets);

    public abstract void bindIndexBuffer(Buffer buffer, long offset, IndexType indexType);

    // ========== 动态状态 ==========

    public abstract void setViewport(int x, int y, int width, int height, float minDepth, float maxDepth);

    public abstract void setScissor(int x, int y, int width, int height);

    public abstract void setLineWidth(float width);

    public abstract void setDepthBias(float constant, float clamp, float slope);

    public abstract void setBlendConstants(float r, float g, float b, float a);

    public abstract void setDepthBounds(float min, float max);

    // ========== 绘制 ==========

    public abstract void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);

    public abstract void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                                     int vertexOffset, int firstInstance);

    public abstract void drawIndirect(Buffer buffer, long offset, int drawCount, int stride);

    public abstract void drawIndexedIndirect(Buffer buffer, long offset, int drawCount, int stride);

    // ========== 计算 ==========

    public abstract void dispatch(int groupX, int groupY, int groupZ);

    public abstract void dispatchIndirect(Buffer buffer, long offset);

    // ========== 复制 ==========

    public abstract void copyBuffer(Buffer src, Buffer dst, BufferCopy[] regions);

    public abstract void copyBufferToTexture(Buffer src, Texture dst, TextureLayout dstLayout,
                                             BufferTextureCopy[] regions);

    public abstract void copyTextureToBuffer(Texture src, TextureLayout srcLayout, Buffer dst,
                                             BufferTextureCopy[] regions);

    public abstract void copyTexture(Texture src, TextureLayout srcLayout,
                                     Texture dst, TextureLayout dstLayout, TextureCopy[] regions);

    public abstract void blitTexture(Texture src, TextureLayout srcLayout,
                                     Texture dst, TextureLayout dstLayout, TextureBlit[] regions,
                                     Filter filter);

    // ========== 屏障 ==========

    public abstract void pipelineBarrier(PipelineStage srcStage, PipelineStage dstStage,
                                         DependencyFlags dependencyFlags,
                                         MemoryBarrier[] memoryBarriers,
                                         BufferMemoryBarrier[] bufferBarriers,
                                         TextureMemoryBarrier[] textureBarriers);

    // ========== 推送常量 ==========

    public abstract void pushConstants(PipelineLayout layout, ShaderStage stages,
                                       int offset, byte[] data);

    // ========== 查询 ==========

    public abstract void resetQueryPool(QueryPool pool, int first, int count);

    public abstract void beginQuery(QueryPool pool, int query);

    public abstract void endQuery(QueryPool pool, int query);

    public abstract void writeTimestamp(PipelineStage stage, QueryPool pool, int query);

    // ========== 执行二级命令缓冲区 ==========

    public abstract void executeCommands(CommandBuffer[] secondary);

    // ========== 内部类型 ==========

    public record RenderingInfo(
            Rect2D renderArea,
            int layerCount,
            RenderingAttachmentInfo[] colorAttachments,
            RenderingAttachmentInfo depthAttachment,
            RenderingAttachmentInfo stencilAttachment
    ) {
    }

    public record RenderingAttachmentInfo(
            TextureView view,
            TextureLayout imageLayout,
            AttachmentLoadOp loadOp,
            AttachmentStoreOp storeOp,
            ClearValue clearValue,
            TextureView resolveView,
            TextureLayout resolveImageLayout
    ) {
    }

    public record RenderPassBeginInfo(
            RenderPass renderPass,
            Framebuffer framebuffer,
            Rect2D renderArea,
            ClearValue[] clearValues
    ) {
    }

    public record Rect2D(int x, int y, int width, int height) {
    }

    public record ClearValue(float r, float g, float b, float a) {
        public ClearValue(float depth, int stencil) {
            this(depth, stencil, 0, 0);
        }
    }

    public record BufferCopy(long srcOffset, long dstOffset, long size) {
    }

    public record BufferTextureCopy(
            long bufferOffset,
            int bufferRowLength,
            int bufferImageHeight,
            TextureSubresourceLayers imageSubresource,
            Offset3D imageOffset,
            Extent3D imageExtent
    ) {
    }

    public record TextureCopy(
            TextureSubresourceLayers srcSubresource,
            Offset3D srcOffset,
            TextureSubresourceLayers dstSubresource,
            Offset3D dstOffset,
            Extent3D extent
    ) {
    }

    public record TextureBlit(
            TextureSubresourceLayers srcSubresource,
            Offset3D srcOffsets0,
            Offset3D srcOffsets1,
            TextureSubresourceLayers dstSubresource,
            Offset3D dstOffsets0,
            Offset3D dstOffsets1
    ) {
    }

    public record MemoryBarrier(PipelineStage srcStage, PipelineStage dstStage, AccessFlags srcAccess,
                                AccessFlags dstAccess) {
    }

    public record BufferMemoryBarrier(
            PipelineStage srcStage, PipelineStage dstStage,
            AccessFlags srcAccess, AccessFlags dstAccess,
            int srcQueueFamily, int dstQueueFamily,
            Buffer buffer, long offset, long size
    ) {
    }

    public record TextureMemoryBarrier(
            PipelineStage srcStage, PipelineStage dstStage,
            AccessFlags srcAccess, AccessFlags dstAccess,
            TextureLayout oldLayout, TextureLayout newLayout,
            int srcQueueFamily, int dstQueueFamily,
            Texture texture, TextureSubresourceRange subresourceRange
    ) {
    }

    record TextureSubresourceLayers(TextureViewDesc.AspectFlags aspectMask, int mipLevel, int baseArrayLayer,
                                    int layerCount) {
    }

    public record TextureSubresourceRange(TextureViewDesc.AspectFlags aspectMask, int baseMipLevel, int levelCount,
                                          int baseArrayLayer, int layerCount) {
    }

    public record Offset3D(int x, int y, int z) {
    }

    public record Extent3D(int width, int height, int depth) {
    }
}
