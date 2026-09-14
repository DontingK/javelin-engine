package com.javelin.core.render;

import com.javelin.rhi.api.Buffer;
import com.javelin.rhi.api.DescriptorSet;
import com.javelin.rhi.api.GraphicsPipeline;
import com.javelin.rhi.api.Texture;
import com.javelin.rhi.types.IndexType;
import org.joml.Matrix4f;

import java.util.Arrays;

/**
 * 单次绘制命令 —— 描述一个需要 GPU 执行的绘制调用。
 *
 * <p>由 {@link Renderable#collect} 生成，{@link RenderList} 收集并排序后，
 * 由 {@link RenderPass} 提交到 RHI {@link com.javelin.rhi.api.CommandBuffer}。</p>
 *
 * <p>直接持有 RHI 资源引用，生命周期由外部管理（通常由 Asset 系统持有）。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class RenderCommand {

    /** 图形管线 */
    public GraphicsPipeline pipeline;

    /** 顶点缓冲 */
    public Buffer vertexBuffer;

    /** 顶点缓冲偏移（字节） */
    public long vertexOffset;

    /** 索引缓冲，无索引绘制时为 null */
    public Buffer indexBuffer;

    /** 索引缓冲偏移（字节） */
    public long indexOffset;

    /** 索引类型 */
    public IndexType indexType = IndexType.UINT32;

    /** 顶点数量（无索引绘制时使用） */
    public int vertexCount;

    /** 索引数量（有索引绘制时使用） */
    public int indexCount;

    /** 实例数量 */
    public int instanceCount = 1;

    /** 模型变换矩阵 */
    public final Matrix4f modelMatrix = new Matrix4f();

    /** 渲染层分类 */
    public RenderLayer renderLayer = RenderLayer.OPAQUE;

    /** 描述符集（材质参数、纹理等），可为 null */
    public DescriptorSet[] descriptorSets;

    /** 动态偏移（用于 dynamic uniform buffer），与 descriptorSets 一一对应 */
    public int[] dynamicOffsets;

    /** 额外绑定的纹理（用于简单场景，不通过 DescriptorSet） */
    public Texture[] textures;

    /** PipelineLayout，用于 push constants（临时方案） */
    public com.javelin.rhi.api.PipelineLayout pipelineLayout;

    public RenderCommand() {
    }

    /**
     * 构造无索引绘制命令。
     */
    public RenderCommand(GraphicsPipeline pipeline, Buffer vertexBuffer,
                         int vertexCount, Matrix4f modelMatrix) {
        this.pipeline = pipeline;
        this.vertexBuffer = vertexBuffer;
        this.vertexCount = vertexCount;
        this.modelMatrix.set(modelMatrix);
    }

    /**
     * 构造有索引绘制命令。
     */
    public RenderCommand(GraphicsPipeline pipeline, Buffer vertexBuffer,
                         Buffer indexBuffer, int indexCount,
                         Matrix4f modelMatrix) {
        this.pipeline = pipeline;
        this.vertexBuffer = vertexBuffer;
        this.indexBuffer = indexBuffer;
        this.indexCount = indexCount;
        this.modelMatrix.set(modelMatrix);
    }

    /**
     * 构造完整绘制命令（带描述符集）。
     */
    public RenderCommand(GraphicsPipeline pipeline, Buffer vertexBuffer,
                         Buffer indexBuffer, long indexOffset, int indexCount,
                         Matrix4f modelMatrix, DescriptorSet[] descriptorSets) {
        this(pipeline, vertexBuffer, indexBuffer, indexCount, modelMatrix);
        this.indexOffset = indexOffset;
        this.descriptorSets = descriptorSets;
    }

    /**
     * 是否使用索引绘制。
     */
    public boolean isIndexed() {
        return indexBuffer != null;
    }

    @Override
    public String toString() {
        return "RenderCommand{" +
               "pipeline=" + (pipeline != null ? pipeline.getClass().getSimpleName() : "null") +
               ", layer=" + renderLayer +
               ", indexed=" + isIndexed() +
               ", count=" + (isIndexed() ? indexCount : vertexCount) +
               '}';
    }
}
