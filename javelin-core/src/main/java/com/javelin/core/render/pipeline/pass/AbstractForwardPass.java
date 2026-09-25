package com.javelin.core.render.pipeline.pass;

import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.mesh.MeshDraw;
import com.javelin.rhi.CommandBuffer;
import com.javelin.rhi.Texture;

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
     * 单个物体绘制：PSO 按 layout 切换 → 顶点缓冲 → set1 贴图 →
     * push constants（model mat4 + tpf）→ drawIndexed。
     */
    protected void drawObject(CommandBuffer cmd, RenderingSystem rs, RenderObject ro,
                              float tpf, boolean blend) {
        cmd.bindPipeline(rs.pipelineFor(ro.mesh().layout(), blend));
        MeshDraw.bindBuffers(cmd, ro.mesh());
        cmd.bindDescriptorSet(1, rs.textureSet(ro.texture()), 0, null);
        pushData.clear();
        for (float v : ro.world().get(worldTmp)) {
            pushData.putFloat(v);
        }
        pushData.putFloat(RenderingSystem.PUSH_TPF_OFFSET, tpf);
        pushData.limit(RenderingSystem.PUSH_TPF_OFFSET + Float.BYTES);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
        MeshDraw.drawIndexed(cmd, ro.mesh());
    }

    /** 视口矩形小于终点附件 = 分屏/预览视口，需要区域清屏 */
    protected static boolean isRegionClear(RenderView view, Texture target) {
        return view.scissor().x() != 0 || view.scissor().y() != 0
                || view.scissor().width() != target.desc().width()
                || view.scissor().height() != target.desc().height();
    }
}
