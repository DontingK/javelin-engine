package com.javelin.core.render.pipeline.pass;

import com.javelin.core.render.RenderObject;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * OpaqueForwardPass（Engine_RenderPipeline_Design.md 3.3 v0.1 实现形态）：
 * 本视口不透明物体直写管线终点（CLEAR），深度写独立 depth RT（CLEAR）。
 *
 * <p>单相机全屏时本 Pass 即管线终点，无 Composite；分屏/预览视口走区域清屏
 * （loadOp CLEAR 无视 scissor，矩形外保留原内容）。
 */
public class OpaqueForwardPass extends AbstractForwardPass {

    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);

    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    @Override
    public String name() {
        return "Opaque";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        Texture target = ctx.colorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();
        graph.addGraphicsPass(view.name() + "_Opaque", pass -> {
            if (isRegionClear(view, target)) {
                // 分屏/预览视口：区域清屏（loadOp CLEAR 无视 scissor，矩形外保留原内容）
                pass.addColorOutput(target, RHILoadOp.CLEAR, clearColor, view.scissor());
            } else {
                pass.addColorOutput(target, RHILoadOp.CLEAR, clearColor);
            }
            pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().opaque();
                if (objects.isEmpty()) {
                    return;
                }
                // set0 绑定需要已绑 PSO 提供布局：先用首个物体 PSO 建立（全部内置 PSO set 布局一致，
                // 后续 drawObject 按 layout 切换 PSO 不影响已绑描述符）
                cmd.bindPipeline(rs.pipelineFor(objects.get(0).mesh().layout(), false));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, false);
                }
            });
        });
    }
}
