package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHIClearValue;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.data.RHIScissor;

import java.util.function.Consumer;

/**
 * Pass 声明（声明式：读写意图 + 回调）。
 * 对照设计文档第 26.5 节 / 22.3 节（ResourceUsage 语义契约）。
 *
 * 仅在 addGraphicsPass / addComputePass 的回调内有效，
 * 用语义化用途声明读写（Attachment.Color / Depth / Sampled / Storage），
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
    void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearValue clearValue);

    /**
     * 声明颜色附件输出，并限定清屏区域（分区域清屏，多相机/编辑器视口场景）。
     *
     * <p>loadOp 的 CLEAR 以区域形式执行：矩形外保留原内容。Vulkan 的 loadOp 清屏
     * 无视 scissor、始终清整个附件，区域清屏由执行器在渲染通道开始后以
     * vkCmdClearAttachments 实现（底层为 dynamic rendering，该命令在通道内合法）。
     *
     * @param loadOp      必须为 CLEAR（区域清屏语义即"以 clearValue 清 clearRegion"）
     * @param clearValue  清屏颜色，必填
     * @param clearRegion 清屏矩形（framebuffer 坐标），必填且不得为 null
     */
    void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearColor clearValue,
                        RHIScissor clearRegion);

    /**
     * 声明多采样颜色附件输出，并声明 resolve 终点（MSAA 抗锯齿）。
     *
     * <p>texture 必须为多采样纹理（sampleCount > 1），resolveTarget 必须单采样
     * （COUNT_1）且尺寸与附件一致；渲染通道结束时硬件按 AVERAGE 模式降采样写入
     * resolveTarget（Vulkan 原生 resolve attachment，VkRenderingAttachmentInfo）。
     * resolveTarget 按本 Pass 的颜色附件<b>写</b>参与依赖推导与屏障计划
     * （终态 PRESENT_SRC 转换由此白拿）。
     *
     * <p>v0.1 边界：区域清屏（clearRegion）与 resolve 不组合（多相机分屏走视口独立
     * MSAA 附件全量清屏，无需区域清屏版）；深度附件不做 resolve（场景深度仅在
     * MSAA Pass 内部消费，overlay 家族用自己的 1x 深度）。
     *
     * @param loadOp        附件本体的 LOAD / CLEAR / DONT_CARE
     * @param clearValue    loadOp 为 CLEAR 时必填，否则必须为 null
     * @param resolveTarget 单采样 resolve 终点（禁止与 texture 同一实例）
     */
    void addColorOutput(Texture texture, RHILoadOp loadOp, RHIClearColor clearValue,
                        Texture resolveTarget);

    /**
     * 声明深度附件输出（写：Attachment.Depth）。
     *
     * @param loadOp    LOAD / CLEAR / DONT_CARE
     * @param clearValue loadOp 为 CLEAR 时必填，否则必须为 null
     */
    void addDepthOutput(Texture texture, RHILoadOp loadOp, RHIClearDepthStencil clearValue);

    /** 声明纹理采样读取（读：Sampled → SHADER_READ_ONLY） */
    void addTextureRead(Texture texture);

    /**
     * 声明存储纹理读取（读：Storage，计算/片元着色器 storage image 读 → GENERAL）。
     * 与采样读不同：storage image 要求 GENERAL 布局（22.3 ResourceUsage 语义）。
     */
    void addTextureStorageRead(Texture texture);

    /** 声明存储纹理写入（写：Storage，计算着色器 storage image 写 → GENERAL） */
    void addTextureStorageWrite(Texture texture);

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
