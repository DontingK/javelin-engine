package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.mesh.MeshDraw;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.Texture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Forward 系 Pass 基类：push constants 打包缓冲 + 单物体绘制样板。
 *
 * <p>pushData 随 Pass 实例走（每帧录制的私有暂存，非线程共享）；
 * Deferred 系的 GBuffer/Lighting Pass 不继承本类（着色路径不同）。</p>
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
     */
    protected void drawObject(CommandBuffer cmd, RenderingSystem rs, RenderObject ro, float tpf) {
        cmd.bindPipeline(rs.pipelineFor(ro.material(), ro.mesh().layout(), ro.mesh().getTopology(),
                shaderPassName()));
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
    protected GraphicsPipeline entryPipeline(RenderingSystem rs, RenderObject first) {
        return rs.pipelineFor(first.material(), first.mesh().layout(), first.mesh().getTopology(),
                shaderPassName());
    }

    /** 视口矩形小于终点附件 = 分屏/预览视口，需要区域清屏 */
    protected static boolean isRegionClear(RenderView view, Texture target) {
        return view.scissor().x() != 0 || view.scissor().y() != 0
                || view.scissor().width() != target.desc().width()
                || view.scissor().height() != target.desc().height();
    }
}
