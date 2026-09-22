package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHIClearValue;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.desc.TextureDesc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;
import static org.lwjgl.vulkan.VK14.*;

/**
 * CommandBuffer 实现（dynamic rendering，Vulkan 1.3 核心）。
 * 对照设计文档第 24.3 节（执行器）/ 第 26.4 节（RHICmd）。
 *
 * 录制模型：begin → [pipelineBarrier]* → beginRenderPass → 绘制 → endRenderPass → ... → end。
 * 渲染通道由执行器根据 Pass 附件声明自动包裹，上层回调只做绘制调用。
 */
final class VulkanCommandBuffer implements CommandBuffer {

    private final VulkanDevice device;
    private final VkCommandBuffer vkCommandBuffer;
    private boolean recording;
    private boolean inRenderPass;
    private volatile boolean disposed;

    VulkanCommandBuffer(VulkanDevice device, VkCommandBuffer vkCommandBuffer) {
        this.device = device;
        this.vkCommandBuffer = vkCommandBuffer;
    }

    VkCommandBuffer vkCommandBuffer() {
        return vkCommandBuffer;
    }

    @Override
    public void begin() {
        if (recording) {
            throw new IllegalStateException("Command buffer is already recording");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.calloc(stack);
            beginInfo.sType$Default();
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            int result = vkBeginCommandBuffer(vkCommandBuffer, beginInfo);
            if (result != VK_SUCCESS) {
                throw new com.javelin.rhi.exception.VulkanException(result, "Failed to begin command buffer");
            }
        }
        recording = true;
    }

    @Override
    public void end() {
        if (!recording) {
            throw new IllegalStateException("Command buffer is not recording");
        }
        if (inRenderPass) {
            throw new IllegalStateException("Render pass must be ended before command buffer end");
        }
        int result = vkEndCommandBuffer(vkCommandBuffer);
        if (result != VK_SUCCESS) {
            throw new com.javelin.rhi.exception.VulkanException(result, "Failed to end command buffer");
        }
        recording = false;
    }

    @Override
    public void beginRenderPass(List<CompiledGraph.Attachment> colorAttachments,
                                CompiledGraph.Attachment depthAttachment) {
        checkRecording();
        if (inRenderPass) {
            throw new IllegalStateException("Already in a render pass");
        }
        if ((colorAttachments == null || colorAttachments.isEmpty()) && depthAttachment == null) {
            throw new IllegalArgumentException("Render pass requires at least one attachment");
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            int colorCount = colorAttachments != null ? colorAttachments.size() : 0;
            VkRenderingAttachmentInfo.Buffer colorInfos = null;
            if (colorCount > 0) {
                colorInfos = VkRenderingAttachmentInfo.calloc(colorCount, stack);
                for (int i = 0; i < colorCount; i++) {
                    fillAttachmentInfo(colorInfos.get(i), colorAttachments.get(i), stack);
                }
            }

            VkRenderingAttachmentInfo depthInfo = null;
            if (depthAttachment != null) {
                depthInfo = VkRenderingAttachmentInfo.calloc(stack);
                fillAttachmentInfo(depthInfo, depthAttachment, stack);
            }

            VkRenderingInfo renderingInfo = VkRenderingInfo.calloc(stack);
            renderingInfo.sType$Default();
            if (colorInfos != null) {
                renderingInfo.pColorAttachments(colorInfos);
            }
            if (depthInfo != null) {
                renderingInfo.pDepthAttachment(depthInfo);
            }
            // 渲染区域：取首个附件纹理的完整尺寸（执行器已保证视口/裁剪在 Pass 内设置）
            CompiledGraph.Attachment first = depthAttachment != null
                    ? depthAttachment
                    : colorAttachments.get(0);
            TextureDesc extent = first.texture().desc();
            renderingInfo.renderArea(VkRect2D.calloc(stack)
                    .offset(VkOffset2D.calloc(stack).set(0, 0))
                    .extent(VkExtent2D.calloc(stack).set(extent.width(), extent.height())));
            renderingInfo.layerCount(1);

            vkCmdBeginRendering(vkCommandBuffer, renderingInfo);
        }
        inRenderPass = true;
    }

    private void fillAttachmentInfo(VkRenderingAttachmentInfo info,
                                    CompiledGraph.Attachment attachment,
                                    MemoryStack stack) {
        info.sType$Default();
        // dynamic rendering 需要 VkImageView；Texture.handle() 是 VkImage，
        // 交换链图像与常规纹理都经 ensureView() 获取或创建对应的全尺寸 view
        Texture texture = attachment.texture();
        long imageView;
        if (texture instanceof VulkanTexture vkTex) {
            imageView = vkTex.ensureView();
        } else if (texture instanceof VulkanSwapChain.SwapChainTexture scTex) {
            imageView = scTex.ensureView();
        } else {
            imageView = texture.handle();
        }
        info.imageView(imageView);
        info.imageLayout(VulkanBarrierGenerator.toVkImageLayout(
                attachment.kind() == CompiledGraph.Attachment.Kind.DEPTH
                        ? com.javelin.rhi.types.TextureLayout.DEPTH_STENCIL_ATTACHMENT
                        : com.javelin.rhi.types.TextureLayout.COLOR_ATTACHMENT));
        info.loadOp(toVkLoadOp(attachment.loadOp()));
        info.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        info.clearValue(toVkClearValue(attachment.clearValue(), stack));
    }

    private static int toVkLoadOp(RHILoadOp loadOp) {
        return switch (loadOp) {
            case LOAD -> VK_ATTACHMENT_LOAD_OP_LOAD;
            case CLEAR -> VK_ATTACHMENT_LOAD_OP_CLEAR;
            case DONT_CARE -> VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        };
    }

    private static VkClearValue toVkClearValue(RHIClearValue clearValue, MemoryStack stack) {
        VkClearValue value = VkClearValue.calloc(stack);
        if (clearValue == null) {
            return value;
        }
        if (clearValue instanceof RHIClearColor color) {
            value.color().float32(stack.floats(color.r(), color.g(), color.b(), color.a()));
        } else if (clearValue instanceof RHIClearDepthStencil depth) {
            value.depthStencil().set(depth.depth(), depth.stencil());
        }
        return value;
    }

    @Override
    public void endRenderPass() {
        checkRecording();
        if (!inRenderPass) {
            throw new IllegalStateException("Not in a render pass");
        }
        vkCmdEndRendering(vkCommandBuffer);
        inRenderPass = false;
    }

    @Override
    public void bindPipeline(GraphicsPipeline pipeline) {
        checkRecording();
        vkCmdBindPipeline(vkCommandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline.handle());
        if (pipeline instanceof VulkanGraphicsPipeline vkPipeline) {
            onPipelineBound(vkPipeline.layoutHandle(), VK_PIPELINE_BIND_POINT_GRAPHICS);
        }
    }

    @Override
    public void bindPipeline(ComputePipeline pipeline) {
        checkRecording();
        vkCmdBindPipeline(vkCommandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.handle());
        if (pipeline instanceof VulkanComputePipeline vkPipeline) {
            onPipelineBound(vkPipeline.layoutHandle(), VK_PIPELINE_BIND_POINT_COMPUTE);
        }
    }

    @Override
    public void pipelineBarrier(Texture texture, com.javelin.rhi.types.TextureLayout oldLayout,
                                com.javelin.rhi.types.TextureLayout newLayout,
                                boolean write, boolean computePass) {
        checkRecording();
        if (oldLayout == newLayout) {
            return; // 冗余消除（24.3）
        }
        VulkanBarrierGenerator.BarrierSync sync =
                VulkanBarrierGenerator.syncFor(oldLayout, newLayout, write, computePass);

        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.calloc(1, stack);
            barrier.get(0)
                    .sType$Default()
                    .srcAccessMask(VulkanBarrierGenerator.toVkAccess(sync.srcAccess))
                    .dstAccessMask(VulkanBarrierGenerator.toVkAccess(sync.dstAccess))
                    .oldLayout(VulkanBarrierGenerator.toVkImageLayout(oldLayout))
                    .newLayout(VulkanBarrierGenerator.toVkImageLayout(newLayout))
                    .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                    .image(texture.handle())
                    .subresourceRange(VkImageSubresourceRange.calloc(stack)
                            .aspectMask(aspectMaskOf(texture.desc().format()))
                            .baseMipLevel(0)
                            .levelCount(texture.desc().mipLevels())
                            .baseArrayLayer(0)
                            .layerCount(texture.desc().arrayLayers()));

            vkCmdPipelineBarrier(vkCommandBuffer,
                    VulkanBarrierGenerator.toVkStage(sync.srcStage),
                    VulkanBarrierGenerator.toVkStage(sync.dstStage),
                    0, null, null, barrier);
        }
    }

    private static int aspectMaskOf(com.javelin.rhi.types.Format format) {
        return switch (format) {
            case D16_UNORM, D32_SFLOAT -> VK_IMAGE_ASPECT_DEPTH_BIT;
            case D24_UNORM_S8_UINT, D32_SFLOAT_S8_UINT -> VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT;
            case S8_UINT -> VK_IMAGE_ASPECT_STENCIL_BIT;
            default -> VK_IMAGE_ASPECT_COLOR_BIT;
        };
    }

    @Override
    public void bindDescriptorSet(int setIndex, DescriptorSet set,
                                  int dynamicOffsetCount, int[] dynamicOffsets) {
        checkRecording();
        // 管线布局暂由执行器从当前绑定的管线获取（Stage 5 简化：默认布局）
        // 实际应在 PipelineLayout 中保存句柄，此处通过 bindPipeline 时缓存
        long layout = currentPipelineLayout;
        if (layout == VK_NULL_HANDLE) {
            throw new IllegalStateException("No pipeline bound before bindDescriptorSet");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer sets = stack.longs(set.handle());
            IntBuffer offsets = dynamicOffsets != null && dynamicOffsetCount > 0
                    ? stack.ints(dynamicOffsets)
                    : null;
            vkCmdBindDescriptorSets(vkCommandBuffer,
                    currentBindPoint, layout,
                    setIndex, sets,
                    offsets);
        }
    }

    @Override
    public void bindVertexBuffer(Buffer buffer, int binding) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            LongBuffer buffers = stack.longs(buffer.handle());
            LongBuffer offsets = stack.longs(0);
            vkCmdBindVertexBuffers(vkCommandBuffer, binding, buffers, offsets);
        }
    }

    @Override
    public void bindIndexBuffer(Buffer buffer, com.javelin.rhi.types.IndexType type, int offset) {
        checkRecording();
        vkCmdBindIndexBuffer(vkCommandBuffer, buffer.handle(), offset, toVkIndexType(type));
    }

    private static int toVkIndexType(com.javelin.rhi.types.IndexType type) {
        return switch (type) {
            case UINT16 -> VK_INDEX_TYPE_UINT16;
            case UINT32 -> VK_INDEX_TYPE_UINT32;
            case UINT8 -> VK_INDEX_TYPE_UINT8;
        };
    }

    @Override
    public void draw(int vertexCount, int instanceCount) {
        checkInRenderPass();
        vkCmdDraw(vkCommandBuffer, vertexCount, instanceCount, 0, 0);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount) {
        checkInRenderPass();
        vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, 0, 0, 0);
    }

    @Override
    public void dispatch(int x, int y, int z) {
        checkRecording();
        vkCmdDispatch(vkCommandBuffer, x, y, z);
    }

    @Override
    public void setViewport(com.javelin.rhi.data.RHIViewport viewport) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkViewport vp = VkViewport.calloc(stack)
                    .x(viewport.x())
                    .y(viewport.y())
                    .width(viewport.width())
                    .height(viewport.height())
                    .minDepth(viewport.minDepth())
                    .maxDepth(viewport.maxDepth());
            nvkCmdSetViewport(vkCommandBuffer, 0, 1, vp.address());
        }
    }

    @Override
    public void setScissor(com.javelin.rhi.data.RHIScissor scissor) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkRect2D rect = VkRect2D.calloc(stack)
                    .offset(VkOffset2D.calloc(stack).set(scissor.x(), scissor.y()))
                    .extent(VkExtent2D.calloc(stack).set(scissor.width(), scissor.height()));
            nvkCmdSetScissor(vkCommandBuffer, 0, 1, rect.address());
        }
    }

    @Override
    public void copyBuffer(Buffer src, Buffer dst, long size) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferCopy.Buffer copy = VkBufferCopy.calloc(1, stack);
            copy.get(0).srcOffset(0).dstOffset(0).size(size);
            vkCmdCopyBuffer(vkCommandBuffer, src.handle(), dst.handle(), copy);
        }
    }

    @Override
    public void copyBufferToTexture(Buffer src, Texture dst, TextureRegion region) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(0)   // 0 = 紧密打包
                    .bufferImageHeight(0)
                    .imageSubresource(VkImageSubresourceLayers.calloc(stack)
                            .aspectMask(aspectMaskOf(dst.desc().format()))
                            .mipLevel(region.mipLevel())
                            .baseArrayLayer(region.baseLayer())
                            .layerCount(1))
                    .imageOffset(VkOffset3D.calloc(stack).set(region.x(), region.y(), region.z()))
                    .imageExtent(VkExtent3D.calloc(stack)
                            .set(region.width(), region.height(), region.depth()));

            vkCmdCopyBufferToImage(vkCommandBuffer, src.handle(), dst.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, copy);
        }
    }

    // --- 管线布局跟踪（bindPipeline 时缓存，bindDescriptorSet 消费） ---
    // Stage 5 简化：管线创建时的 layout 句柄未暴露，暂用空布局（与 VulkanGraphicsPipeline 的简化一致）
    private long currentPipelineLayout = VK_NULL_HANDLE;
    private int currentBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;

    void onPipelineBound(long pipelineLayoutHandle, int bindPoint) {
        this.currentPipelineLayout = pipelineLayoutHandle;
        this.currentBindPoint = bindPoint;
    }

    private void checkRecording() {
        if (!recording) {
            throw new IllegalStateException("Command buffer is not recording");
        }
    }

    private void checkInRenderPass() {
        checkRecording();
        if (!inRenderPass) {
            throw new IllegalStateException("Draw commands require an active render pass");
        }
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        // 命令缓冲由命令池管理，dispose 仅为满足 GPUResource 契约
        // 实际回收通过 vkResetCommandPool 或 vkFreeCommandBuffers 由上层控制
    }
}
