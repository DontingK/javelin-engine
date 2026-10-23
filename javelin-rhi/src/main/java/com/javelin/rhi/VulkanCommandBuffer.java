package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHIClearUintColor;
import com.javelin.rhi.data.RHIClearValue;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.desc.TextureDesc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK12.*;
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
    private List<CompiledGraph.Attachment> currentColorAttachments;
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
            int regionClearCount = 0;
            for (int i = 0; i < colorCount; i++) {
                if (colorAttachments.get(i).clearRegion() != null) {
                    regionClearCount++;
                }
            }
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

            // 区域清屏（CLEAR + clearRegion）：loadOp 已降为 LOAD 保住矩形外内容，
            // 这里按原附件索引批量发 vkCmdClearAttachments（dynamic rendering 通道内合法，
            // rect 为 framebuffer 坐标且在 renderArea 内）
            if (regionClearCount > 0) {
                VkClearAttachment.Buffer clearAttachments = VkClearAttachment.calloc(regionClearCount, stack);
                VkClearRect.Buffer clearRects = VkClearRect.calloc(regionClearCount, stack);
                int slot = 0;
                for (int i = 0; i < colorCount; i++) {
                    CompiledGraph.Attachment a = colorAttachments.get(i);
                    RHIScissor region = a.clearRegion();
                    if (region == null) {
                        continue;
                    }
                    RHIClearColor color = (RHIClearColor) a.clearValue();
                    clearAttachments.get(slot)
                            .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                            .colorAttachment(i)
                            .clearValue().color()
                            .float32(stack.floats(color.r(), color.g(), color.b(), color.a()));
                    clearRects.get(slot)
                            .rect(r -> r.offset(o -> o.x(region.x()).y(region.y()))
                                       .extent(e -> e.width(region.width()).height(region.height())))
                            .baseArrayLayer(0)
                            .layerCount(1);
                    slot++;
                }
                vkCmdClearAttachments(vkCommandBuffer, clearAttachments, clearRects);
            }
        }
        inRenderPass = true;
        currentColorAttachments = colorAttachments;
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
        // 区域清屏时 loadOp 降为 LOAD：矩形外内容须保留，矩形内由 vkCmdClearAttachments 清
        info.loadOp(attachment.clearRegion() != null
                ? VK_ATTACHMENT_LOAD_OP_LOAD
                : toVkLoadOp(attachment.loadOp()));
        info.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
        info.clearValue(toVkClearValue(attachment.clearValue(), stack));
        // MSAA resolve：多采样色彩附件通道结束时硬件按 AVERAGE 降采样写入 resolve target
        // （resolve 布局与屏障计划同 COLOR_ATTACHMENT；v0.1 深度不做 resolve）
        Texture resolveTarget = attachment.resolveTexture();
        if (resolveTarget != null) {
            long resolveView;
            if (resolveTarget instanceof VulkanTexture vkTex) {
                resolveView = vkTex.ensureView();
            } else if (resolveTarget instanceof VulkanSwapChain.SwapChainTexture scTex) {
                resolveView = scTex.ensureView();
            } else {
                resolveView = resolveTarget.handle();
            }
            info.resolveImageView(resolveView);
            info.resolveImageLayout(VulkanBarrierGenerator.toVkImageLayout(
                    com.javelin.rhi.types.TextureLayout.COLOR_ATTACHMENT));
            info.resolveMode(VK_RESOLVE_MODE_AVERAGE_BIT);
        }
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
        } else if (clearValue instanceof RHIClearUintColor uint) {
            // 整数附件（UINT/SINT）清屏必须用整数分量（Vulkan 规范）
            value.color().uint32(stack.ints(uint.r(), uint.g(), uint.b(), uint.a()));
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
        currentColorAttachments = null;
    }

    @Override
    public void clearColor(Texture texture, RHIClearColor color, RHIScissor rect) {
        checkRecording();
        if (!inRenderPass) {
            throw new IllegalStateException("clearColor must be called inside a render pass");
        }
        if (texture == null || color == null || rect == null) {
            throw new IllegalArgumentException("Texture, color and rect must not be null");
        }
        if (currentColorAttachments == null) {
            throw new IllegalStateException("No color attachments bound in the current render pass");
        }
        int index = -1;
        for (int i = 0; i < currentColorAttachments.size(); i++) {
            if (currentColorAttachments.get(i).texture() == texture) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            throw new IllegalArgumentException(
                    "Texture is not a color attachment of the current render pass");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkClearAttachment.Buffer attachment = VkClearAttachment.calloc(1, stack);
            attachment.get(0)
                    .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                    .colorAttachment(index)
                    .clearValue().color()
                    .float32(stack.floats(color.r(), color.g(), color.b(), color.a()));
            VkClearRect.Buffer clearRect = VkClearRect.calloc(1, stack);
            clearRect.get(0)
                    .rect(r -> r.offset(o -> o.x(rect.x()).y(rect.y()))
                               .extent(e -> e.width(rect.width()).height(rect.height())))
                    .baseArrayLayer(0)
                    .layerCount(1);
            vkCmdClearAttachments(vkCommandBuffer, attachment, clearRect);
        }
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
    public void pushConstants(int offset, ByteBuffer data) {
        checkRecording();
        if (data == null || data.remaining() == 0) {
            throw new IllegalArgumentException("Push constant data must not be null or empty");
        }
        long layout = currentPipelineLayout;
        if (layout == VK_NULL_HANDLE) {
            throw new IllegalStateException("No pipeline bound before pushConstants");
        }
        // 阶段标志与管线布局声明一致（VulkanGraphicsPipeline 用 ALL_GRAPHICS 声明范围）
        vkCmdPushConstants(vkCommandBuffer, layout, VK_SHADER_STAGE_ALL_GRAPHICS,
                offset, data);
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
        device.stats().onDrawn(vertexCount / 3, instanceCount);
    }

    @Override
    public void drawIndexed(int indexCount, int instanceCount) {
        checkInRenderPass();
        vkCmdDrawIndexed(vkCommandBuffer, indexCount, instanceCount, 0, 0, 0);
        device.stats().onDrawn(indexCount / 3, instanceCount);
    }

    @Override
    public void dispatch(int x, int y, int z) {
        checkRecording();
        vkCmdDispatch(vkCommandBuffer, x, y, z);
        device.stats().onDispatched();
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

    @Override
    public void copyImageToBuffer(Texture src, Buffer dst, TextureRegion region) {
        checkRecording();
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkBufferImageCopy.Buffer copy = VkBufferImageCopy.calloc(1, stack);
            copy.get(0)
                    .bufferOffset(0)
                    .bufferRowLength(0)   // 0 = 紧密打包
                    .bufferImageHeight(0)
                    .imageSubresource(VkImageSubresourceLayers.calloc(stack)
                            .aspectMask(aspectMaskOf(src.desc().format()))
                            .mipLevel(region.mipLevel())
                            .baseArrayLayer(region.baseLayer())
                            .layerCount(1))
                    .imageOffset(VkOffset3D.calloc(stack).set(region.x(), region.y(), region.z()))
                    .imageExtent(VkExtent3D.calloc(stack)
                            .set(region.width(), region.height(), region.depth()));

            vkCmdCopyImageToBuffer(vkCommandBuffer, src.handle(),
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, dst.handle(), copy);
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
