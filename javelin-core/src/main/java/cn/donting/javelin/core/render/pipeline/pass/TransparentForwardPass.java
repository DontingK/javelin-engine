package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * TransparentForwardPass（Engine_RenderPipeline_Design.md 3.3/4.5）：
 * 本视口透明物体（场景层已由远及近排序，不属于 RenderGraph，RHI 14.5-3）
 * Forward 叠加到上游输出：终点 LOAD、depth LOAD 复用（4.4 深度复用决策），
 * alpha 混合 ON / 深度写入 OFF（PSO 侧）。
 */
public class TransparentForwardPass extends AbstractForwardPass {

    /** 首帧绘制一次性诊断（渲染链读数口，常驻） */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(TransparentForwardPass.class);
    private static boolean drawLogged;

    @Override
    public String name() {
        return "Transparent";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        Texture target = ctx.colorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();
        // 与 Opaque 同判定（isMsaaPath 契约）：上游 MSAA 则本 Pass 同附件 LOAD 叠加 +
        // 二次 resolve（全量覆盖幂等：MSAA 内容始终全量），上游 1x 则原 1x 路径
        boolean msaa = isMsaaPath(ctx);
        SampleCount samples = passSamples(ctx);
        // 始终声明（结构稳定；空列表时零绘制）
        graph.addGraphicsPass(view.name() + "_Transparent", pass -> {
            if (msaa) {
                pass.addColorOutput(ctx.msaaColor(), RHILoadOp.LOAD, null, target);
                pass.addDepthOutput(ctx.msaaDepth(), RHILoadOp.LOAD, null);
            } else {
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
            }
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            // set1 storage 读声明（本视口 lightList/clusterGrid/clusterIndices：依赖显式
            // 纳入 + 句柄进 structureHash——视口缓冲重建即失效图缓存）
            for (Buffer b : rs.viewStorages(view.name())) {
                pass.addBufferRead(b);
            }
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().transparent();
                if (!drawLogged) {
                    drawLogged = true;
                    LOGGER.info("首帧 Transparent 回调执行: 视口[{}] 物体={}",
                            view.name(), objects.size());
                }
                if (objects.isEmpty()) {
                    return;
                }
                cmd.bindPipeline(entryPipeline(rs, objects.get(0), samples));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                // set1 光照通道（批次③ per-view：binding0 灯参数 UBO 动态偏移 + 本视口
                // lightList/cluster 套件；未声明 set1 的材质 shader 不受影响）
                cmd.bindDescriptorSet(1, rs.lightingSet(view.name()), 1, new int[]{view.lightingOffset()});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, samples);
                }
            });
        });
    }
}
