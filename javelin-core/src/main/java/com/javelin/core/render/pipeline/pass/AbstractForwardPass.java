package com.javelin.core.render.pipeline.pass;

import com.javelin.core.render.ClearFlags;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.mesh.MeshDraw;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.rhi.CommandBuffer;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.Texture;
import com.javelin.rhi.types.SampleCount;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Forward 系 Pass 基类：push constants 打包缓冲 + 单物体绘制样板。
 *
 * <p>pushData 随 Pass 实例走（每帧录制的私有暂存，非线程共享）；
 * Deferred 系的 GBuffer/Lighting Pass 不继承本类（着色路径不同）。
 */
public abstract class AbstractForwardPass implements RenderPass {

    /** 每帧复用的 push constants 打包缓冲（小端：mat4 + float，80B 声明） */
    protected final ByteBuffer pushData = ByteBuffer.allocateDirect(RenderingSystem.PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    protected final float[] worldTmp = new float[16];

    /**
     * 单个物体绘制：PSO 按材质 shader 的本管线 Pass 名（shaderPassName()，
     * 严格制 2026-10-17）+ layout 切换 → 顶点缓冲 →
     * set 2 材质描述符集 → push constants（model mat4 + tpf）→ drawIndexed。
     * samples = 本 Pass 附件采样档（MSAA 路径 N x / 1x 路径 COUNT_1），
     * PSO rasterizationSamples 必须与附件一致。
     */
    protected void drawObject(CommandBuffer cmd, RenderingSystem rs, RenderObject ro, float tpf,
                              SampleCount samples) {
        cmd.bindPipeline(rs.pipelineFor(ro.material(), ro.mesh().layout(), ro.mesh().getTopology(),
                samples, shaderPassName()));
        MeshDraw.bindBuffers(cmd, ro.mesh());
        cmd.bindDescriptorSet(2, rs.materialSet(ro.material()), 0, null);
        pushData.clear();
        for (float v : ro.world().get(worldTmp)) {
            pushData.putFloat(v);
        }
        pushData.putFloat(RenderingSystem.PUSH_TPF_OFFSET, tpf);
        pushData.limit(RenderingSystem.PUSH_TPF_OFFSET + Float.BYTES);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
        MeshDraw.drawIndexed(cmd, ro.mesh(), ro.submesh());
    }

    /**
     * 物体列表入口的 PSO 建立（bindDescriptorSet 前必须先 bindPipeline，RHI 命令序约束）。
     * 实例方法（2026-10-17）：按本管线 Pass 自己消费的 shader Pass 名取 PSO
     */
    protected GraphicsPipeline entryPipeline(RenderingSystem rs, RenderObject first,
                                             SampleCount samples) {
        return rs.pipelineFor(first.material(), first.mesh().layout(), first.mesh().getTopology(),
                samples, shaderPassName());
    }

    /** 视口矩形小于终点附件 = 分屏/预览视口，需要区域清屏 */
    protected static boolean isRegionClear(RenderView view, Texture target) {
        return view.scissor().x() != 0 || view.scissor().y() != 0
                || view.scissor().width() != target.desc().width()
                || view.scissor().height() != target.desc().height();
    }

    /**
     * MSAA 路径判定（v0.1 边界统一收口）：AA 开启（ctx 带 MSAA 附件对）且本视口为
     * 全屏 SOLID_COLOR 视口——stacking 相机（DEPTH_ONLY/DONT_CLEAR）、targetOverride
     * 预览视口、区域清屏分屏视口均降级 1x 直写（MSAA 附件内容保活/区域 resolve
     * 语义后置）。Opaque 与 Transparent 必须同判定（附件集合一致：MSAA 内容 LOAD
     * 叠加与深度复用都依赖上游走了 MSAA 路径）。
     */
    protected static boolean isMsaaPath(PipelineContext ctx) {
        if (ctx.msaaColor() == null || ctx.msaaDepth() == null) {
            return false;
        }
        RenderView view = ctx.view();
        return view.targetOverride() == null
                && view.clearFlags() == ClearFlags.SOLID_COLOR
                && !isRegionClear(view, ctx.colorTarget());
    }

    /** 本 Pass 的 PSO 采样档（MSAA 路径取 MSAA 附件档位，否则 COUNT_1） */
    protected static SampleCount passSamples(PipelineContext ctx) {
        return isMsaaPath(ctx) ? ctx.msaaColor().desc().sampleCount() : SampleCount.COUNT_1;
    }
}
