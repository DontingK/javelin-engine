package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * UnlitForwardPass（Engine_Material_LightingModel_Design.md §6）：非光照不透明物体的覆盖制单画。
 *
 * <p>消费 {@code RenderList.unlitOpaque()}（{@code LightingModel.UNLIT} + 不透明面型）。
 * 颜色/深度均 LOAD（不吃光照、不写 G-Buffer），深度 ZTest/ZWrite 由材质 Pass 状态决定——
 * 必须晚于覆盖制 {@code LightingPass} 与 Skybox，且早于 Transparent（遮挡后续透明物）。</p>
 */
public class UnlitForwardPass extends AbstractForwardPass {

    /** 首帧绘制一次性诊断（渲染链读数口，常驻） */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(UnlitForwardPass.class);
    private static boolean drawLogged;

    @Override
    public String name() {
        return "Unlit";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        Texture target = ctx.sceneColorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();
        // 始终声明（结构稳定；空列表时零绘制）
        graph.addGraphicsPass(view.name() + "_Unlit", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            // set1 绑定所需资源进图（unlit 的 shader 不声明光照段、不采样；声明保结构稳定，
            // 与 Transparent 同口径——未采时零成本）
            for (Buffer b : rs.viewStorages(view.name())) {
                pass.addBufferRead(b);
            }
            pass.addTextureRead(rs.shadowMapFor(view.name()));
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().unlitOpaque();
                if (!drawLogged) {
                    drawLogged = true;
                    LOGGER.info("首帧 Unlit 回调执行: 视口[{}] 物体={}", view.name(), objects.size());
                }
                if (objects.isEmpty()) {
                    return;
                }
                cmd.bindPipeline(entryPipeline(rs, objects.get(0)));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                cmd.bindDescriptorSet(1, rs.lightingSet(view.name()), 1, new int[]{view.lightingOffset()});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf);
                }
            });
        });
    }
}
