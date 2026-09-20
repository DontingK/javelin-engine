package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.data.RHIScissor;

import java.util.function.Consumer;

/**
 * Pass 声明（声明式：读写意图 + 回调）。
 * 对照设计文档第 26.5 节 / 22.3 节（ResourceUsage 语义契约）。
 *
 * 仅在 addGraphicsPass / addComputePass 的回调内有效，
 * 用语义化用途声明读写（Attachment.Color / Depth / Sampled），
 * 不暴露 Vulkan 布局，屏障由 RHI 执行器统一生成（24.3）。
 */
public interface PassDef {

    /** 绑定图形管线（PSO），构建期声明 */
    void setPipeline(GraphicsPipeline pipeline);

    /** 绑定计算管线（PSO），构建期声明 */
    void setPipeline(ComputePipeline pipeline);

    /**
     * 声明颜色附件输出（写：Attachment.Color）。
     *
     * @param loadOp    LOAD / CLEAR / DONT_CARE
     * @param clearValue loadOp 为 CLEAR 时必填，否则必须为 null
     */
    void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearColor clearValue);

    /**
     * 声明深度附件输出（写：Attachment.Depth）。
     *
     * @param loadOp    LOAD / CLEAR / DONT_CARE
     * @param clearValue loadOp 为 CLEAR 时必填，否则必须为 null
     */
    void addDepthOutput(Texture texture, RHILoadOp loadOp, RHIClearDepthStencil clearValue);

    /** 声明纹理采样读取（读：Sampled） */
    void addTextureRead(Texture texture);

    void setViewport(RHIViewport viewport);

    void setScissor(RHIScissor scissor);

    /**
     * 构建期 uniform 记录（15.4）。
     * 注意：Stage 4 执行器不解析 name→binding 映射，
     * 多实例 uniform 请优先在回调内走 bindDescriptorSet（19.4）。
     */
    void setUniform(String name, float[] data);

    /**
     * 渲染回调：录制阶段调用，回调内只做绘制调用，禁止触碰屏障（22.4 迁移纪律）。
     */
    void setRenderCallback(Consumer<CommandBuffer> callback);
}
