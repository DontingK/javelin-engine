package com.javelin.editor.debug;

import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.core.render.pipeline.pass.AbstractForwardPass;
import com.javelin.rhi.Buffer;
import com.javelin.rhi.Device;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.SampleCount;
import com.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;

/**
 * 编辑器调试线 Pass（Editor_DebugLine_Design §3，覆盖制——PickPass 同款自建 PSO）。
 *
 * <p><b>深度咬合</b>：LEqual 测试 + ZWrite Off，线被不透明几何遮挡（Unity Gizmo
 * 观感）。MSAA 分支走 msaaColor/msaaDepth（场景深度在多采样附件，1x depth 无
 * 场景内容不能 LOAD）；resolve 终点 = colorTarget（AVERAGE 全量幂等）。
 * 目标视口过滤（编辑器相机 view）——GAME 视口天然无线。</p>
 *
 * <p><b>双帧槽</b>：顶点 buffer 按 frameIndex%2 轮转写（{@code Buffer.update}
 * 无 fence 防护是已知短板，UBO1/DebugText 同款取舍）。GPU 资源不显式 dispose
 * ——onDetach 早于 waitIdle，断引用交 Cleaner 两帧规则 + 进程退出兜底
 * （编辑器进程级生命周期，设计档 §5）。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public class DebugLinePass extends AbstractForwardPass {

    private static final Logger log = LoggerFactory.getLogger(DebugLinePass.class);

    private static final String VERT_SPV_RESOURCE = "/debugline/debugline.vert.spv";
    private static final String FRAG_SPV_RESOURCE = "/debugline/debugline.frag.spv";
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
    /** 覆盖制 PSO 缓存（≤2 枚：COUNT_1 / MSAA 档），键 = 采样档 */
    private final EnumMap<SampleCount, GraphicsPipeline> pipelines = new EnumMap<>(SampleCount.class);
    private final Buffer[] vertexSlots = new Buffer[FRAME_SLOTS];
    private boolean drawLogged;

    public DebugLinePass(Device device, RenderingSystem renderingSystem, DebugLineBatch batch,
                         String targetView) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.batch = batch;
        this.targetView = targetView;
        this.vertSpv = readSpv(VERT_SPV_RESOURCE);
        this.fragSpv = readSpv(FRAG_SPV_RESOURCE);
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

    /** 覆盖制（不消费材质 shader Pass；调试线全走内嵌 spv） */
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
        boolean msaa = isMsaaPath(ctx);
        SampleCount samples = passSamples(ctx);
        long frameIndex = ctx.frame().frameIndex();
        Buffer slot = vertexSlots[(int) (frameIndex % FRAME_SLOTS)];
        // 本帧顶点写入（构建期，引擎线程串行——batch 已由 update 段填好，DebugText 同时序）
        int vertexCount = batch.vertexCount();
        if (vertexCount > 0) {
            slot.update(0, batch.data());
        }
        GraphicsPipeline pso = pipelineFor(samples, target.desc().format());

        graph.addGraphicsPass(view.name() + "_DebugLine", pass -> {
            if (msaa) {
                // MSAA：场景深度在多采样附件（1x depth 无内容不能 LOAD）；
                // resolve 终点 = colorTarget（AVERAGE 全量 resolve 幂等）
                pass.addColorOutput(ctx.msaaColor(), RHILoadOp.LOAD, null, target);
                pass.addDepthOutput(ctx.msaaDepth(), RHILoadOp.LOAD, null);
            } else {
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
                pass.addDepthOutput(ctx.depth(), RHILoadOp.LOAD, null);
            }
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

    /** 覆盖制 PSO（键=采样档）：LINE_LIST / ZTest LEqual / ZWrite Off / Blend Off */
    private GraphicsPipeline pipelineFor(SampleCount samples, Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(samples);
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
                samples);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(samples, created);
        log.info("DebugLine PSO created: samples={}", samples);
        return created;
    }

    /** 读 classpath .spv 二进制资源（PickPass 同法） */
    private static byte[] readSpv(String resource) {
        try (InputStream stream = DebugLinePass.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("SPIR-V 资源不存在: " + resource + "（先跑 gradlew :javelin-tool:generateDebugLineSpv 生成）");
            }
            try (InputStream in = new BufferedInputStream(stream)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new IllegalStateException("DebugLine SPIR-V 加载失败: " + e.getMessage(), e);
        }
    }
}
