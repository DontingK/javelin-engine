package com.javelin.rhi;

import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.types.IndexType;

/**
 * 命令缓冲接口（即 RHICmd）。
 * 对照设计文档第 26.4 节。
 *
 * 仅在回调执行期间有效，不能被上层缓存。
 */
public interface CommandBuffer {

    void begin();
    void end();

    void bindPipeline(GraphicsPipeline pipeline);

    void bindDescriptorSet(int setIndex, DescriptorSet set,
                           int dynamicOffsetCount, int[] dynamicOffsets);

    void bindVertexBuffer(Buffer buffer, int binding);

    void bindIndexBuffer(Buffer buffer, IndexType type, int offset);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void dispatch(int x, int y, int z);

    void setViewport(RHIViewport viewport);

    void setScissor(RHIScissor scissor);
}
