package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearColor;
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

    /**
     * 写入 push constants（用户拍板：model 矩阵、tpf 等每物件/每帧高频小量走此通道）。
     * 管线布局须按 GraphicsPipelineDesc.pushConstantSize 声明覆盖范围（offset + data.remaining() ≤ 声明值）。
     * 数据为原始字节，由上层按 shader 侧 block 布局（std140）打包。
     */
    void pushConstants(int offset, ByteBuffer data);

    void bindVertexBuffer(Buffer buffer, int binding);

    void bindIndexBuffer(Buffer buffer, IndexType type, int offset);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void dispatch(int x, int y, int z);

    void setViewport(RHIViewport viewport);

    void setScissor(RHIScissor scissor);

    /**
     * 渲染通道内对当前颜色附件的指定矩形区域清屏（vkCmdClearAttachments）。
     *
     * <p>rect 为 framebuffer 坐标且须在渲染区域内；只影响 rect 内像素，
     * 不受管线 viewport/scissor 状态影响。texture 必须是本 Pass 已声明的颜色附件。
     *
     * <p>典型用途：多相机各自清自己的视口矩形、帧中局部清屏（portal/工具）。
     * 整附件清屏仍优先用 addColorOutput 的 CLEAR loadOp（零命令开销）。
     */
    void clearColor(Texture texture, RHIClearColor color, RHIScissor rect);

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

    /**
     * 纹理 → 缓冲拷贝（读回路径）。
     * 由 RHI 内部读回路径（TextureReadbackRing 等）调用，上层业务禁止直接调用。
     * 调用方负责把纹理转换到 TRANSFER_SRC 布局（本方法内不插屏障）。
     */
    void copyImageToBuffer(Texture src, Buffer dst, TextureRegion region);
}
