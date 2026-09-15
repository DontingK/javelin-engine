package com.javelin.rhi;

/**
 * 渲染命令录制接口，用于在渲染或计算通道中记录一系列 GPU 命令。
 */
public interface RHICmd {

    /** 绑定图形或计算管线。 */
    void bindPipeline(RHIPipeline pipeline);

    /** 绑定顶点缓冲区。 */
    void bindVertexBuffer(RHIBuffer buffer, long offset);

    /** 绑定索引缓冲区及索引类型。 */
    void bindIndexBuffer(RHIBuffer buffer, RHIIndexType type, long offset);

    /** 绑定描述符集。 */
    void bindDescriptorSet(int set, RHIDescriptorSet descriptorSet, int dynamicOffsetCount, int[] dynamicOffsets);

    /** 绑定统一缓冲区到指定描述符集和绑定槽。 */
    void bindUniformBuffer(int set, int binding, RHIBuffer buffer, long offset, long range);

    /** 绑定纹理及采样器到指定描述符集和绑定槽。 */
    void bindTexture(int set, int binding, RHITexture tex, RHISampler sampler);

    /** 推送常量数据到着色器。 */
    void pushConstants(int stageFlags, int offset, int size, byte[] data);

    /** 执行非索引绘制。 */
    void draw(int vertexCount, int instanceCount);

    /** 执行索引绘制。 */
    void drawIndexed(int indexCount, int instanceCount);

    /** 派发计算任务。 */
    void dispatch(int groupX, int groupY, int groupZ);
}
