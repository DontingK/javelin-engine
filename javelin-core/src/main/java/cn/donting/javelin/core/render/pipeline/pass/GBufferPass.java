package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.DefaultMaterial;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.PassDef;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.SampleCount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * G-Buffer 几何 Pass（HYBRID 档不透明几何，Engine_RenderPipeline_Design §4.2/实装形态）。
 *
 * <p><b>覆盖对象</b>：严格制「Pass 名即桶」——消费 {@code view.list().passObjects("GBuffer")}
 * （与 GizmoPass 同构），并剔透明（声明了 GBuffer 的透明材质不写 G-Buffer，透明仍走
 * Forward）。不透明材质须声明 {@code Pass GBuffer}，未声明者不进桶、不绘制，收集期
 * 一次性 WARN 显性化（红线 15）。
 *
 * <p><b>输出</b>：三张 MRT（albedo R8G8B8A8_UNORM / 世界法线 RGBA16F / 世界位置 RGBA32F
 * 且 w=有效标志）+ 复用本视口 depth。G-Buffer 三张恒 CLEAR（视口私有暂存，分屏走区域
 * 清屏）；深度按 ClearFlags 分支（CLEAR / LOAD）。
 *
 * <p><b>无光照</b>：本 Pass 只写 G-Buffer，光照由 {@link LightingPass} 全屏读回计算——
 * 两者共用同一 set1 cluster 套件与阴影数据（两路光照一致由构造保证）。
 * HYBRID 强制 1x（MSAA 归四期），PSO 采样档恒 COUNT_1。
 */
public class GBufferPass extends AbstractForwardPass {

    private static final Logger log = LoggerFactory.getLogger(GBufferPass.class);

    /** 本 Pass 消费的 shader Pass 名（RenderList 同名桶 / PSO 查找键） */
    public static final String SHADER_PASS_NAME = "GBuffer";

    /** G-Buffer 三色附件格式（顺序 = MRT location 0/1/2，PSO 声明与图附件同序） */
    private static final List<Format> COLOR_FORMATS = List.of(
            Format.R8G8B8A8_UNORM,
            Format.R16G16B16A16_SFLOAT,
            Format.R32G32B32A32_SFLOAT);

    /** 清屏值：albedo 黑 / 法线 +Z / 世界位置 w=0（背景标志，LightingPass 据此跳过） */
    private static final RHIClearColor CLEAR_ALBEDO = new RHIClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    private static final RHIClearColor CLEAR_NORMAL = new RHIClearColor(0.0f, 0.0f, 1.0f, 0.0f);
    private static final RHIClearColor CLEAR_WORLD_POS = new RHIClearColor(0.0f, 0.0f, 0.0f, 0.0f);

    private boolean drawLogged;
    private boolean missingPassWarned;

    @Override
    public String name() {
        return SHADER_PASS_NAME;
    }

    @Override
    public String shaderPassName() {
        return SHADER_PASS_NAME;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        Texture depth = ctx.depth();
        Texture albedo = rs.gbufferAlbedoFor(view.name());
        Texture normal = rs.gbufferNormalFor(view.name());
        Texture worldPos = rs.gbufferWorldPosFor(view.name());
        // PSO 颜色格式按 Pass 名登记（三张 MRT）——图构建期先于回调执行
        rs.registerPassColorFormats(SHADER_PASS_NAME, COLOR_FORMATS);
        List<RenderObject> objects = opaqueGbufferObjects(view);
        warnMissingPassIfNeeded(view);
        if (isMsaaPath(ctx)) {
            // HYBRID 强制 1x（引擎启动期已按档位钳制）；此分支仅防未来档位组合漏改
            log.warn("GBufferPass 不支持 MSAA 路径（视口[{}]），按 1x 绘制——HYBRID 档应禁用 AA",
                    view.name());
        }
        SampleCount samples = SampleCount.COUNT_1;
        graph.addGraphicsPass(view.name() + "_GBuffer", pass -> {
            declareAttachments(pass, view, albedo, normal, worldPos, depth);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            // set1 storage 读声明（本视口 cluster 套件）：依赖显式纳入 + 句柄进 structureHash
            for (Buffer b : rs.viewStorages(view.name())) {
                pass.addBufferRead(b);
            }
            int uboOffset = view.uboOffset();
            float tpf = ctx.frame().tpf();
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 GBuffer 回调执行: 视口[{}] 物体={} clearFlags={}",
                            view.name(), objects.size(), view.clearFlags());
                }
                if (objects.isEmpty()) {
                    return;
                }
                cmd.bindPipeline(entryPipeline(rs, objects.get(0), samples));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                cmd.bindDescriptorSet(1, rs.lightingSet(view.name()), 1,
                        new int[]{view.lightingOffset()});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, samples);
                }
            });
        });
    }

    /** G-Buffer 三色附件 + 深度附件声明（清屏语义照 OpaqueForward 的分支口径） */
    private void declareAttachments(PassDef pass, RenderView view, Texture albedo, Texture normal,
                                    Texture worldPos, Texture depth) {
        // G-Buffer 附件为交换链尺寸（与 depth 同档）——区域判定基准即附件自身尺寸
        if (isRegionClear(view, albedo)) {
            pass.addColorOutput(albedo, RHILoadOp.CLEAR, CLEAR_ALBEDO, view.scissor());
            pass.addColorOutput(normal, RHILoadOp.CLEAR, CLEAR_NORMAL, view.scissor());
            pass.addColorOutput(worldPos, RHILoadOp.CLEAR, CLEAR_WORLD_POS, view.scissor());
        } else {
            pass.addColorOutput(albedo, RHILoadOp.CLEAR, CLEAR_ALBEDO);
            pass.addColorOutput(normal, RHILoadOp.CLEAR, CLEAR_NORMAL);
            pass.addColorOutput(worldPos, RHILoadOp.CLEAR, CLEAR_WORLD_POS);
        }
        // 深度：DONT_CLEAR 复用上一位深度（残影类特效），其余分支清屏
        if (view.clearFlags() == ClearFlags.DONT_CLEAR) {
            pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
        } else {
            pass.addDepthOutput(depth, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f, 0));
        }
    }

    /** 本视口 G-Buffer 桶（严格制同名桶）剔透明 */
    static List<RenderObject> opaqueGbufferObjects(RenderView view) {
        return view.list().passObjects(SHADER_PASS_NAME).stream()
                .filter(ro -> !ro.isTransparent())
                .toList();
    }

    /**
     * 不透明材质缺 {@code Pass GBuffer} 的一次性告警（红线 15：给「不声明即不画」的
     * 静默失效一个可见出口——HYBRID 档下这些物体会整体消失，必须让用户看见）。
     */
    private void warnMissingPassIfNeeded(RenderView view) {
        if (missingPassWarned) {
            return;
        }
        int missing = 0;
        for (RenderObject ro : view.list().opaque()) {
            Material material = DefaultMaterial.resolve(ro.material());
            if (material == null || material.getShader() == null
                    || material.getShader().pass(SHADER_PASS_NAME) == null) {
                missing++;
            }
        }
        if (missing > 0) {
            missingPassWarned = true;
            log.warn("HYBRID 档不透明物体缺 Pass GBuffer，未绘制: 视口[{}] {} 个"
                            + "（材质 shader 需声明 Pass GBuffer；FORWARD 档不受影响）",
                    view.name(), missing);
        }
    }
}
