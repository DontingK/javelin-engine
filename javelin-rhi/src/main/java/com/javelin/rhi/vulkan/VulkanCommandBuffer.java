package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Vulkan 命令缓冲区实现 */
public class VulkanCommandBuffer extends CommandBuffer {

    private final VkDevice device;
    private final long handle;
    private final VkCommandBuffer vkCmd;
    private final VulkanCommandPool pool;
    private final boolean primary;
    private boolean recording = false;

    public VulkanCommandBuffer(VkDevice device, long handle, VulkanCommandPool pool, boolean primary) {
        this.device = device;
        this.handle = handle;
        this.vkCmd = new VkCommandBuffer(handle, device);
        this.pool = pool;
        this.primary = primary;
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        // Command buffers are freed with the pool
    }

    @Override
    public void begin() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO)
                .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VulkanChecks.check(vkBeginCommandBuffer(vkCmd, beginInfo), "vkBeginCommandBuffer");
            recording = true;
        }
    }

    @Override
    public void beginSecondary(RenderPass renderPass, int subpass, Framebuffer framebuffer) {
        throw new UnsupportedOperationException("Secondary command buffers not yet implemented");
    }

    @Override
    public void end() {
        VulkanChecks.check(vkEndCommandBuffer(vkCmd), "vkEndCommandBuffer");
        recording = false;
    }

    @Override
    public void reset() {
        VulkanChecks.check(vkResetCommandBuffer(vkCmd, 0), "vkResetCommandBuffer");
        recording = false;
    }

    @Override
    public void beginRendering(RenderingInfo info) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Color attachments
            VkRenderingAttachmentInfo.Buffer colorAttachments = null;
            if (info.colorAttachments() != null && info.colorAttachments().length > 0) {
                colorAttachments = VkRenderingAttachmentInfo.calloc(info.colorAttachments().length, stack);
                for (int i = 0; i < info.colorAttachments().length; i++) {
                    RenderingAttachmentInfo att = info.colorAttachments()[i];
                    VkClearValue clearValue = VkClearValue.calloc(stack);
                    if (att.clearValue() != null) {
                        clearValue.color().float32(stack.floats(
                            att.clearValue().r(), att.clearValue().g(),
                            att.clearValue().b(), att.clearValue().a()));
                    }
                    colorAttachments.get(i)
                        .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                        .imageView(att.view().handle())
                        .imageLayout(VulkanFormats.toVkImageLayout(att.imageLayout()))
                        .loadOp(VulkanFormats.toVkAttachmentLoadOp(att.loadOp()))
                        .storeOp(VulkanFormats.toVkAttachmentStoreOp(att.storeOp()))
                        .clearValue(clearValue);
                    if (att.resolveView() != null) {
                        colorAttachments.get(i)
                            .resolveImageView(att.resolveView().handle())
                            .resolveImageLayout(VulkanFormats.toVkImageLayout(att.resolveImageLayout()));
                    }
                }
            }

            // Depth attachment
            VkRenderingAttachmentInfo depthAttachment = null;
            if (info.depthAttachment() != null) {
                RenderingAttachmentInfo att = info.depthAttachment();
                depthAttachment = VkRenderingAttachmentInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_RENDERING_ATTACHMENT_INFO)
                    .imageView(att.view().handle())
                    .imageLayout(VulkanFormats.toVkImageLayout(att.imageLayout()))
                    .loadOp(VulkanFormats.toVkAttachmentLoadOp(att.loadOp()))
                    .storeOp(VulkanFormats.toVkAttachmentStoreOp(att.storeOp()));
                if (att.clearValue() != null) {
                    VkClearValue clearValue = VkClearValue.calloc(stack);
                    clearValue.depthStencil().depth(att.clearValue().r()).stencil((int) att.clearValue().g());
                    depthAttachment.clearValue(clearValue);
                }
            }

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_RENDERING_INFO)
                .renderArea(VkRect2D.calloc(stack).offset(VkOffset2D.calloc(stack).x(info.renderArea().x()).y(info.renderArea().y()))
                    .extent(VkExtent2D.calloc(stack).width(info.renderArea().width()).height(info.renderArea().height())))
                .layerCount(info.layerCount())
                .pColorAttachments(colorAttachments)
                .pDepthAttachment(depthAttachment);

            vkCmdBeginRendering(vkCmd, renderingInfo);
        }
    }

    @Override
    public void endRendering() {
        vkCmdEndRendering(vkCmd);
    }

    @Override
    public void beginRenderPass(RenderPassBeginInfo info) {
        throw new UnsupportedOperationException("Traditional render pass not yet implemented, use dynamic rendering");
    }

    @Override
    public void nextSubpass() {
        throw new UnsupportedOperationException("Traditional render pass not yet implemented");
    }

    @Override
    public void endRenderPass() {
        throw new UnsupportedOperationException("Traditional render pass not yet implemented");
    }

    @Override
    public void bindPipeline(GraphicsPipeline pipeline) {
        vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
    }

    @Override
    public void bindPipeline(ComputePipeline pipeline) {
        vkCmdBindPipeline(vkCmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
    }

    @Override
    public void bindDescriptorSets(PipelineBindPoint bindPoint, PipelineLayout layout,
                                   int firstSet, DescriptorSet[] sets, int[] dynamicOffsets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pSets = stack.mallocLong(sets.length);
            for (DescriptorSet set : sets) {
                pSets.put(set.handle());
            }
            pSets.flip();

            IntBuffer pOffsets = null;
            if (dynamicOffsets != null && dynamicOffsets.length > 0) {
                pOffsets = stack.ints(dynamicOffsets);
            }

            vkCmdBindDescriptorSets(vkCmd,
                bindPoint == PipelineBindPoint.GRAPHICS ? VK_PIPELINE_BIND_POINT_GRAPHICS : VK_PIPELINE_BIND_POINT_COMPUTE,
                layout.handle(), firstSet, pSets, pOffsets);
        }
    }

    @Override
    public void bindVertexBuffer(int binding, Buffer buffer, long offset) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            vkCmdBindVertexBuffers(vkCmd, binding, stack.longs(buffer.handle()), stack.longs(offset));
        }
    }

    @Override
    public void bindVertexBuffers(int firstBinding, Buffer[] buffers, long[] offsets) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer pBuffers = stack.mallocLong(buffers.length);
            LongBuffer pOffsets = stack.mallocLong(offsets.length);
            for (Buffer buffer : buffers) pBuffers.put(buffer.handle());
            for (long offset : offsets) pOffsets.put(offset);
            pBuffers.flip();
            pOffsets.flip();
            vkCmdBindVertexBuffers(vkCmd, firstBinding, pBuffers, pOffsets);
        }
    }

    @Override
    public void bindIndexBuffer(Buffer buffer, long offset, IndexType indexType) {
        vkCmdBindIndexBuffer(vkCmd, buffer.handle(), offset, VulkanFormats.toVkIndexType(indexType));
    }

    @Override
    public void setViewport(int x, int y, int width, int height, float minDepth, float maxDepth) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport viewport = VkViewport.calloc(stack)
                .x(x)
                .y(y + height)  // Vulkan Y is inverted
                .width(width)
                .height(-height)
                .minDepth(minDepth)
                .maxDepth(maxDepth);
            vkCmdSetViewport(vkCmd, 0, VkViewport.malloc(1, stack).put(0, viewport));
        }
    }

    @Override
    public void setScissor(int x, int y, int width, int height) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRect2D scissor = VkRect2D.calloc(stack)
                .offset(VkOffset2D.calloc(stack).x(x).y(y))
                .extent(VkExtent2D.calloc(stack).width(width).height(height));
            vkCmdSetScissor(vkCmd, 0, VkRect2D.malloc(1, stack).put(0, scissor));
        }
    }

    @Override
    public void setLineWidth(float width) {
        vkCmdSetLineWidth(vkCmd, width);
    }

    @Override
    public void setDepthBias(float constant, float clamp, float slope) {
        vkCmdSetDepthBias(vkCmd, constant, clamp, slope);
    }

    @Override
    public void setBlendConstants(float r, float g, float b, float a) {
        vkCmdSetBlendConstants(vkCmd, new float[]{r, g, b, a});
    }

    @Override
    public void setDepthBounds(float min, float max) {
        vkCmdSetDepthBounds(vkCmd, min, max);
    }

    @Override
    public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
        vkCmdDraw(vkCmd, vertexCount, instanceCount, firstVertex, firstInstance);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                           int vertexOffset, int firstInstance) {
        vkCmdDrawIndexed(vkCmd, indexCount, instanceCount, firstIndex, vertexOffset, firstInstance);
    }

    @Override
    public void drawIndirect(Buffer buffer, long offset, int drawCount, int stride) {
        vkCmdDrawIndirect(vkCmd, buffer.handle(), offset, drawCount, stride);
    }

    @Override
    public void drawIndexedIndirect(Buffer buffer, long offset, int drawCount, int stride) {
        vkCmdDrawIndexedIndirect(vkCmd, buffer.handle(), offset, drawCount, stride);
    }

    @Override
    public void dispatch(int groupX, int groupY, int groupZ) {
        vkCmdDispatch(vkCmd, groupX, groupY, groupZ);
    }

    @Override
    public void dispatchIndirect(Buffer buffer, long offset) {
        vkCmdDispatchIndirect(vkCmd, buffer.handle(), offset);
    }

    @Override
    public void copyBuffer(Buffer src, Buffer dst, BufferCopy[] regions) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer vkRegions = VkBufferCopy.calloc(regions.length, stack);
            for (int i = 0; i < regions.length; i++) {
                BufferCopy region = regions[i];
                vkRegions.get(i)
                    .srcOffset(region.srcOffset())
                    .dstOffset(region.dstOffset())
                    .size(region.size());
            }
            vkCmdCopyBuffer(vkCmd, src.handle(), dst.handle(), vkRegions);
        }
    }

    @Override
    public void copyBufferToTexture(Buffer src, Texture dst, TextureLayout dstLayout,
                                    BufferTextureCopy[] regions) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void copyTextureToBuffer(Texture src, TextureLayout srcLayout, Buffer dst,
                                    BufferTextureCopy[] regions) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void copyTexture(Texture src, TextureLayout srcLayout,
                           Texture dst, TextureLayout dstLayout, TextureCopy[] regions) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void blitTexture(Texture src, TextureLayout srcLayout,
                           Texture dst, TextureLayout dstLayout, TextureBlit[] regions,
                           Filter filter) {
        throw new UnsupportedOperationException("Not yet implemented");
    }

    @Override
    public void pipelineBarrier(PipelineStage srcStage, PipelineStage dstStage,
                               DependencyFlags dependencyFlags,
                               MemoryBarrier[] memoryBarriers,
                               BufferMemoryBarrier[] bufferBarriers,
                               TextureMemoryBarrier[] textureBarriers) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // Memory barriers
            VkMemoryBarrier.Buffer vkMemoryBarriers = null;
            if (memoryBarriers != null && memoryBarriers.length > 0) {
                vkMemoryBarriers = VkMemoryBarrier.calloc(memoryBarriers.length, stack);
                for (int i = 0; i < memoryBarriers.length; i++) {
                    MemoryBarrier barrier = memoryBarriers[i];
                    vkMemoryBarriers.get(i)
                        .sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER)
                        .srcAccessMask(barrier.srcAccess().value())
                        .dstAccessMask(barrier.dstAccess().value());
                }
            }

            // Buffer barriers
            VkBufferMemoryBarrier.Buffer vkBufferBarriers = null;
            if (bufferBarriers != null && bufferBarriers.length > 0) {
                vkBufferBarriers = VkBufferMemoryBarrier.calloc(bufferBarriers.length, stack);
                for (int i = 0; i < bufferBarriers.length; i++) {
                    BufferMemoryBarrier barrier = bufferBarriers[i];
                    vkBufferBarriers.get(i)
                        .sType(VK_STRUCTURE_TYPE_BUFFER_MEMORY_BARRIER)
                        .srcAccessMask(barrier.srcAccess().value())
                        .dstAccessMask(barrier.dstAccess().value())
                        .srcQueueFamilyIndex(barrier.srcQueueFamily())
                        .dstQueueFamilyIndex(barrier.dstQueueFamily())
                        .buffer(barrier.buffer().handle())
                        .offset(barrier.offset())
                        .size(barrier.size());
                }
            }

            // Texture barriers
            VkImageMemoryBarrier.Buffer vkImageBarriers = null;
            if (textureBarriers != null && textureBarriers.length > 0) {
                vkImageBarriers = VkImageMemoryBarrier.calloc(textureBarriers.length, stack);
                for (int i = 0; i < textureBarriers.length; i++) {
                    TextureMemoryBarrier barrier = textureBarriers[i];
                    TextureSubresourceRange range = barrier.subresourceRange();
                    vkImageBarriers.get(i)
                        .sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER)
                        .srcAccessMask(barrier.srcAccess().value())
                        .dstAccessMask(barrier.dstAccess().value())
                        .oldLayout(VulkanFormats.toVkImageLayout(barrier.oldLayout()))
                        .newLayout(VulkanFormats.toVkImageLayout(barrier.newLayout()))
                        .srcQueueFamilyIndex(barrier.srcQueueFamily())
                        .dstQueueFamilyIndex(barrier.dstQueueFamily())
                        .image(barrier.texture().handle())
                        .subresourceRange(VkImageSubresourceRange.calloc(stack)
                            .aspectMask(range.aspectMask().value())
                            .baseMipLevel(range.baseMipLevel())
                            .levelCount(range.levelCount())
                            .baseArrayLayer(range.baseArrayLayer())
                            .layerCount(range.layerCount()));
                }
            }

            vkCmdPipelineBarrier(vkCmd,
                srcStage.value(), dstStage.value(),
                dependencyFlags != null ? dependencyFlags.value() : 0,
                vkMemoryBarriers, vkBufferBarriers, vkImageBarriers);
        }
    }

    @Override
    public void pushConstants(PipelineLayout layout, ShaderStage stages,
                             int offset, byte[] data) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer pData = stack.malloc(data.length);
            pData.put(data);
            pData.flip();
            vkCmdPushConstants(vkCmd, layout.handle(), VulkanFormats.toVkShaderStage(stages), offset, pData);
        }
    }

    @Override
    public void resetQueryPool(QueryPool pool, int first, int count) {
        vkCmdResetQueryPool(vkCmd, pool.handle(), first, count);
    }

    @Override
    public void beginQuery(QueryPool pool, int query) {
        vkCmdBeginQuery(vkCmd, pool.handle(), query, 0);
    }

    @Override
    public void endQuery(QueryPool pool, int query) {
        vkCmdEndQuery(vkCmd, pool.handle(), query);
    }

    @Override
    public void writeTimestamp(PipelineStage stage, QueryPool pool, int query) {
        vkCmdWriteTimestamp(vkCmd, stage.value(), pool.handle(), query);
    }

    @Override
    public void executeCommands(CommandBuffer[] secondary) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer pBuffers = stack.mallocPointer(secondary.length);
            for (CommandBuffer cmd : secondary) {
                pBuffers.put(cmd.handle());
            }
            pBuffers.flip();
            vkCmdExecuteCommands(vkCmd, pBuffers);
        }
    }
}
