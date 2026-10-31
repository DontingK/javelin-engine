package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * OpaqueForwardPass（Engine_RenderPipeline_Design.md 3.3 v0.1 实现形态）：
 * 本视口不透明物体直写管线终点（CLEAR），深度写独立 depth RT（CLEAR）。
 *
 * <p>单相机全屏时本 Pass 即管线终点，无 Composite；分屏/预览视口走区域清屏
 * （loadOp CLEAR 无视 scissor，矩形外保留原内容）。
 */
public class OpaqueForwardPass extends AbstractForwardPass {

    /** 首帧绘制一次性诊断（渲染链读数口，常驻） */
    private static final org.slf4j.Logger LOGGER =
            org.slf4j.LoggerFactory.getLogger(OpaqueForwardPass.class);
    private static boolean drawLogged;
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
        boolean msaa = isMsaaPath(ctx);
        SampleCount samples = passSamples(ctx);
        graph.addGraphicsPass(view.name() + "_Opaque", pass -> {
            ClearFlags flags = view.clearFlags();
            if (msaa) {
                // MSAA 路径（v0.1 仅全屏 SOLID_COLOR 视口）：多采样附件全量清屏 +
                // 通道结束硬件 resolve 回 1x 终点；深度为多采样配对（同一次渲染
                // 通道附件采样数必须一致）；分屏/stacking 判定见 isMsaaPath
                RHIClearColor clear = view.clearColor() != null ? view.clearColor() : clearColor;
                pass.addColorOutput(ctx.msaaColor(), RHILoadOp.CLEAR, clear, target);
                pass.addDepthOutput(ctx.msaaDepth(), RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            } else if (flags == ClearFlags.DEPTH_ONLY) {
                // 只清深度：颜色 LOAD 保留已有画面——叠加相机合成（全屏 UI/Gizmo 层，配 depth 排序）
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            } else if (flags == ClearFlags.DONT_CLEAR) {
                // 都保留（LOAD）：残影类特效；附件从未清过时内容未定义
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
            } else {
                // SOLID_COLOR：相机背景色优先（per-camera clear），未指定退管线全局默认色
                RHIClearColor clear = view.clearColor() != null ? view.clearColor() : clearColor;
                if (isRegionClear(view, target)) {
                    // 分屏/预览视口：区域清屏（loadOp CLEAR 无视 scissor，矩形外保留原内容）
                    pass.addColorOutput(target, RHILoadOp.CLEAR, clear, view.scissor());
                } else {
                    pass.addColorOutput(target, RHILoadOp.CLEAR, clear);
                }
                pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            }
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            // set1 帧级 storage 读声明（依赖推导纳入：compute 写 → 本 Pass 读的内存屏障由此生成）
            for (Buffer b : rs.frameStorages()) {
                pass.addBufferRead(b);
            }
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().opaque();
                if (!drawLogged) {
                    drawLogged = true;
                    LOGGER.info("首帧 Opaque 回调执行: 视口[{}] 物体={} clearFlags={}",
                            view.name(), objects.size(), view.clearFlags());
                }
                if (objects.isEmpty()) {
                    return;
                }
                // set0 绑定需要已绑 PSO 提供布局：先用首个物体 PSO 建立（全部材质 PSO 的
                // set0 同为帧布局，后续 drawObject 按材质/layout 切 PSO 不影响已绑描述符）
                cmd.bindPipeline(entryPipeline(rs, objects.get(0), samples));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                // set1 帧级 storage（有注册才绑；未声明 set1 的材质 shader 不受影响）
                DescriptorSet frameStorageSet = rs.frameStorageSet();
                if (frameStorageSet != null) {
                    cmd.bindDescriptorSet(1, frameStorageSet, 0, null);
                }
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, samples);
                }
            });
        });
    }
}
