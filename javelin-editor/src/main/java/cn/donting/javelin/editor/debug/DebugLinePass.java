package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.core.render.pipeline.pass.AbstractForwardPass;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DynamicState;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 编辑器调试线 Pass（Editor_DebugLine_Design §3，覆盖制——PickPass 同款自建 PSO）。
 *
 * <p><b>深度咬合</b>：LEqual 测试 + ZWrite Off，线被不透明几何遮挡（Unity Gizmo
 * 观感）。颜色/深度附件 LOAD 复用场景 1x 附件（MSAA 已移除，2026-11-13）。
 * 目标视口过滤（编辑器相机 view）——GAME 视口天然无线。</p>
 *
 * <p><b>双帧槽</b>：顶点 buffer 按 frameIndex%2 轮转写（{@code Buffer.update}
 * 无 fence 防护是已知短板，UBO1/DebugText 同款取舍）。GPU 资源不显式 dispose
 * ——onDetach 早于 waitIdle，断引用交 Cleaner 两帧规则 + 进程退出兜底
 * （编辑器进程级生命周期，设计档 §5）。</p>
 *
 * <p><b>着色器装载走资产导入链</b>（2026-11-07 改向，ShadowPass/SkyboxPass/PickPass
 * 同款）：源码 {@code builtin-asset-editor/debugline/debugline.vert/.frag} 经
 * SpvShaderImporter 编译落 Library，本 Pass 经 {@link AssetManager#loadAsset} 按
 * 路径装载（原「tool CLI 编 spv 内嵌 editor resources 直读 classpath」路线清退）。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public class DebugLinePass extends AbstractForwardPass {

    private static final Logger log = LoggerFactory.getLogger(DebugLinePass.class);

    /** 内置调试线着色器 loadAsset 路径（builtin-asset-editor/debugline 释放到 .javelin/assets 后的相对路径） */
    private static final String VERT_SHADER_PATH = "editor/debugline/debugline.vert";
    private static final String FRAG_SHADER_PATH = "editor/debugline/debugline.frag";
    private static final int FRAME_SLOTS = 2;

    /** 顶点布局：loc0 pos vec3（世界空间）/ loc1 RGBA8（UNORM 解包 vec4） */
    private static final VertexLayout VERTEX_LAYOUT = VertexLayout.of(List.of(
            new VertexLayout.Attribute(0, Format.R32G32B32_SFLOAT, 0),
            new VertexLayout.Attribute(1, Format.R8G8B8A8_UNORM, 12)), DebugLineBatch.VERTEX_STRIDE);

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final DebugLineBatch batch;
    private final String targetView;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    /** 覆盖制 PSO 缓存（键 = 颜色附件格式；HDR 换格式即新建） */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final Buffer[] vertexSlots = new Buffer[FRAME_SLOTS];
    private boolean drawLogged;

    public DebugLinePass(Device device, AssetManager assetManager, RenderingSystem renderingSystem,
                         DebugLineBatch batch, String targetView) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.batch = batch;
        this.targetView = targetView;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH);
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH);
        for (int i = 0; i < FRAME_SLOTS; i++) {
            vertexSlots[i] = device.createBuffer(
                    new BufferDesc((long) DebugLineBatch.MAX_LINES * 2 * DebugLineBatch.VERTEX_STRIDE,
                            Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent()),
                    null);
        }
    }

    @Override
    public String name() {
        return "DebugLine";
    }

    /** 覆盖制（不消费材质 shader Pass；调试线着色器走资产导入链） */
    @Override
    public String shaderPassName() {
        return "DebugLine";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return; // 非目标视口（GAME 视口等）不进图——GAME 模式下编辑器 view 不在图，全场景零线
        }
        Texture target = ctx.colorTarget();
        long frameIndex = ctx.frame().frameIndex();
        Buffer slot = vertexSlots[(int) (frameIndex % FRAME_SLOTS)];
        // 本帧顶点写入（构建期，引擎线程串行——batch 已由 update 段填好，DebugText 同时序）
        int vertexCount = batch.vertexCount();
        if (vertexCount > 0) {
            slot.update(0, batch.data());
        }
        GraphicsPipeline pso = pipelineFor(target.desc().format());

        graph.addGraphicsPass(view.name() + "_DebugLine", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addDepthOutput(ctx.depth(), RHILoadOp.LOAD, null);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setPipeline(pso);
            int uboOffset = view.uboOffset();
            pass.setRenderCallback(cmd -> {
                if (vertexCount == 0) {
                    return; // 空常态：Pass 留在图内保结构哈希稳定
                }
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 DebugLine 回调执行: 视口[{}] 顶点={}", view.name(), vertexCount);
                }
                cmd.bindDescriptorSet(0, renderingSystem.frameSet(), 1, new int[]{uboOffset});
                cmd.bindVertexBuffer(slot, 0);
                cmd.draw(vertexCount, 1);
            });
        });
    }

    /** 覆盖制 PSO（键=颜色附件格式）：LINE_LIST / ZTest LEqual / ZWrite Off / Blend Off */
    private GraphicsPipeline pipelineFor(Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                VERTEX_LAYOUT, PrimitiveTopology.LINE_LIST,
                CullMode.NONE, FrontFace.COUNTER_CLOCKWISE, PolygonMode.FILL, 1.0f,
                true, false, CompareOp.LESS_OR_EQUAL,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,
                List.of(colorFormat), Format.D32_SFLOAT,
                List.of(renderingSystem.frameSetLayoutDesc()), 0,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("DebugLine PSO created: colorFormat={}", colorFormat);
        return created;
    }

    /**
     * 装载内置 SPIR-V 着色器资产（资产导入链产物；红线 15——缺失/坏产物显性报错
     * 不静默兜底：导入链未跑（内置资产未释放/未导入）或编译失败 stub 都在此爆出）。
     */
    private static byte[] loadSpv(AssetManager assetManager, String path) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException(EditorLang.text("debugLine.assetMissing", path));
        }
        if (!spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("debugLine.assetInvalid", path));
        }
        return spv.getSpvData();
    }
}
