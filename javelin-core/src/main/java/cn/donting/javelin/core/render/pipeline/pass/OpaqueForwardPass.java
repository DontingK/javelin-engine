package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHILoadOp;

import java.util.List;

/**
 * OpaqueForwardPass（Engine_RenderPipeline_Design.md 3.3 v0.1 实现形态）：
 * 本视口不透明物体直写管线场景颜色终点（CLEAR），深度写独立 depth RT（CLEAR）。
 *
 * <p>单相机全屏时本 Pass 即链尾之前最后一个颜色写者（无后处理链时即管线终点，无
 * Composite）；分屏/预览视口走区域清屏（loadOp CLEAR 无视 scissor，矩形外保留原内容）。</p>
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
        // 场景颜色终点：后处理链非空时为 HDR 缓冲（lightingRT），否则同 colorTarget
        Texture target = ctx.sceneColorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();
        graph.addGraphicsPass(view.name() + "_Opaque", pass -> {
            ClearFlags flags = view.clearFlags();
            if (!ctx.hdrEnabled() && flags == ClearFlags.DEPTH_ONLY) {
                // 只清深度：颜色 LOAD 保留已有画面——叠加相机合成（全屏 UI/Gizmo 层，配 depth 排序）
                // HDR 下不走此支：每视口独立 HDR 缓冲（LOAD 到的是本视口上一帧内容），
                // 跨相机颜色叠加不成立，v1 按整视口 CLEAR 独立输出（后处理档 §4.3）
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
            } else if (!ctx.hdrEnabled() && flags == ClearFlags.DONT_CLEAR) {
                // 都保留（LOAD）：残影类特效；附件从未清过时内容未定义（HDR 下同 DEPTH_ONLY 支的取舍）
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
            } else {
                // SOLID_COLOR / SKYBOX（清屏→不透明→天空→透明，Unity 同序；2026-11-05
                // 撤销「SKYBOX 色 LOAD 赌 SkyboxPass 全屏覆盖」——覆盖缺口像素保留
                // 上上帧内容（swapchain 多图轮换 LOAD 读到两张前旧帧），相机一动即拖影；
                // 天空盒画在不透明后靠 LEqual 被遮挡，清屏只影响无天空像素=背景色）：
                // 相机背景色优先（per-camera clear），未指定退管线全局默认色
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
            // set1 storage 读声明（本视口 lightList/clusterGrid/clusterIndices：依赖显式
            // 纳入 + 句柄进 structureHash——视口缓冲重建即失效图缓存）
            for (Buffer b : rs.viewStorages(view.name())) {
                pass.addBufferRead(b);
            }
            // 主光阴影图采样读（批次④）：set1 binding4 着色消费——依赖声明建 Shadow→Opaque
            // 边（图拓扑保证阴影先画）；恒声明保结构稳定（未采时零成本）
            pass.addTextureRead(rs.shadowMap());
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
                cmd.bindPipeline(entryPipeline(rs, objects.get(0)));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                // set1 光照通道（批次③ per-view：binding0 灯参数 UBO 动态偏移 + 本视口
                // lightList/cluster 套件；未声明 set1 的材质 shader 不受影响）
                cmd.bindDescriptorSet(1, rs.lightingSet(view.name()), 1, new int[]{view.lightingOffset()});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf);
                }
            });
        });
    }
}
