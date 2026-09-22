package com.javelin.rhi;

import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.types.IndexType;
import com.javelin.rhi.types.TextureLayout;

import java.nio.ByteBuffer;
import java.util.List;

/**
 * 命令缓冲接口（即 RHICmd）。
 * 对照设计文档第 26.4 节。
 *
 * 仅在回调执行期间有效，不能被上层缓存。
 */
public interface CommandBuffer extends GPUResource {

    void begin();
    void end();

    /**
     * 开始渲染通道（dynamic rendering，Vulkan 1.3 核心）。
     * 由 RenderGraph 执行器根据 Pass 的附件声明自动调用（24.3），上层业务禁止直接调用。
     *
     * @param colorAttachments 颜色附件（可空 = 无颜色输出，如纯深度 / 计算）
     * @param depthAttachment  深度附件（可空）
     */
    void beginRenderPass(List<CompiledGraph.Attachment> colorAttachments,
                         CompiledGraph.Attachment depthAttachment);

    /** 结束渲染通道（与 beginRenderPass 配对） */
    void endRenderPass();

    void bindPipeline(GraphicsPipeline pipeline);

    void bindPipeline(ComputePipeline pipeline);

    /**
     * 资源屏障（布局转换）。
     * 由 RenderGraph 执行器自动插入（24.3），上层业务禁止直接调用（22.4 迁移纪律）。
     */
    default void pipelineBarrier(Texture texture, TextureLayout oldLayout, TextureLayout newLayout) {
        pipelineBarrier(texture, oldLayout, newLayout, false, false);
    }

    /**
     * 资源屏障（执行器专用：携带读写与目标 Pass 语义，用于推导精确同步参数）。
     * 由 RenderGraph 执行器自动插入（24.3），上层业务禁止直接调用（22.4 迁移纪律）。
     *
     * @param write       目标访问是否含写（附件输出 / Storage 写）
     * @param computePass 屏障服务的目标 Pass 是否为计算 Pass
     */
    void pipelineBarrier(Texture texture, TextureLayout oldLayout, TextureLayout newLayout,
                         boolean write, boolean computePass);

    void bindDescriptorSet(int setIndex, DescriptorSet set,
                           int dynamicOffsetCount, int[] dynamicOffsets);

    void bindVertexBuffer(Buffer buffer, int binding);

    void bindIndexBuffer(Buffer buffer, IndexType type, int offset);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void dispatch(int x, int y, int z);

    void setViewport(RHIViewport viewport);

    void setScissor(RHIScissor scissor);

    /**
     * 设备本地缓冲拷贝（staging 上传用，25 节）。
     * 由 RHI 内部上传路径调用，上层业务禁止直接调用。
     */
    void copyBuffer(Buffer src, Buffer dst, long size);

    /**
     * 缓冲 → 纹理拷贝（staging 上传用，25 节）。
     * 由 RHI 内部上传路径调用，上层业务禁止直接调用。
     * 调用方负责把纹理转换到 TRANSFER_DST 布局（本方法内不插屏障）。
     */
    void copyBufferToTexture(Buffer src, Texture dst, TextureRegion region);
}
