package com.javelin.core.render.pipeline;

import com.javelin.core.render.FrameDescription;
import com.javelin.core.render.RenderObject;
import com.javelin.core.render.mesh.MeshDraw;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.rhi.CommandBuffer;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHIClearDepthStencil;
import com.javelin.rhi.data.RHILoadOp;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * 默认混合渲染管线（Engine_RenderPipeline_Design.md 全文；RHI 14 节引擎侧兑现）。
 *
 * <p><b>v0.1 实现形态（已拍板：实现先 Forward）</b>：每视口两个 Pass——
 * {@code OpaqueForward}（直写终点，区域 CLEAR，深度 CLEAR）→
 * {@code TransparentForward}（终点 LOAD 叠加，深度 LOAD 复用，alpha 混合）。
 * 无阴影、无 GBuffer、后处理恒空链（直通）；单相机全屏时终点即最后一个 Pass，无 Composite。
 * Shadow/GBuffer/Lighting 的 Pass 设计与资源规划见设计文档第 4 节，落地为第二步。
 *
 * <p>着色器约定（core 主代码不含 GLSL 源码，用户拍板 2026-09-25）：内置无光照着色程序
 * 的 GLSL 放启动/应用层资源（测试工程为 {@code src/test/resources/shaders/builtin_quad.*}，
 * 顶点输入按 VertexAttribute 规范序 location 表：POSITION=0、UV0=3），
 * 由启动层编译为 SPIR-V 后交 {@code RenderingSystem.installBuiltinShaders}。
 * uniform 通道：set0 UBO1（viewProj，动态偏移切视口）+ push（model/tpf）+ set1 贴图。
 */
public class DefaultHybridRenderPipeline implements RenderPipeline {

    private final Map<PipelineHook, List<BiConsumer<RenderGraphBuilder, PipelineContext>>> hooks =
            new EnumMap<>(PipelineHook.class);
    private final PostProcessChain postProcess = new PostProcessChain();
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);

    /** 每帧复用的 push constants 打包缓冲（小端：mat4 + float，80B 声明） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(RenderingSystem.PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] worldTmp = new float[16];

    @Override
    public PipelineProfile profile() {
        return PipelineProfile.FORWARD;
    }

    /** 后处理链（v0.1 恒空链 = 直通，节点接口落地后处理时定义，4.6） */
    public PostProcessChain postProcess() {
        return postProcess;
    }

    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    @Override
    public void addHook(PipelineHook hook, BiConsumer<RenderGraphBuilder, PipelineContext> hookFn) {
        if (hook == null || hookFn == null) {
            throw new IllegalArgumentException("hook / hookFn must not be null");
        }
        hooks.computeIfAbsent(hook, h -> new ArrayList<>()).add(hookFn);
    }

    @Override
    public void buildGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        FrameDescription frame = ctx.frame();
        RenderView view = ctx.view();
        Texture target = ctx.colorTarget();
        Texture depth = ctx.depth();
        RenderingSystem rs = ctx.renderingSystem();

        runHooks(graph, ctx, PipelineHook.PRE_GBUFFER);

        // —— OpaqueForward：本视口不透明物体，直写终点 ——
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
            float tpf = frame.tpf();
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

        runHooks(graph, ctx, PipelineHook.POST_GBUFFER);
        runHooks(graph, ctx, PipelineHook.PRE_TRANSPARENT);

        // —— TransparentForward：由远及近叠加（始终声明，结构稳定；空列表时零绘制） ——
        graph.addGraphicsPass(view.name() + "_Transparent", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addDepthOutput(depth, RHILoadOp.LOAD, null);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            int uboOffset = view.uboOffset();
            float tpf = frame.tpf();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> objects = view.list().transparent();
                if (objects.isEmpty()) {
                    return;
                }
                cmd.bindPipeline(rs.pipelineFor(objects.get(0).mesh().layout(), true));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                for (RenderObject ro : objects) {
                    drawObject(cmd, rs, ro, tpf, true);
                }
            });
        });

        runHooks(graph, ctx, PipelineHook.POST_TRANSPARENT);
        runHooks(graph, ctx, PipelineHook.PRE_POST_PROCESS);
        runHooks(graph, ctx, PipelineHook.PRE_COMPOSITE);
        // v0.1：后处理空链直通 + 单相机省略 Composite（设计文档 3.3/4.7）——
        // 管线终点即 TransparentForward；多相机由下一视口的区域 CLEAR/LOAD 叠加衔接。
    }

    private void drawObject(CommandBuffer cmd, RenderingSystem rs, RenderObject ro,
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
    private static boolean isRegionClear(RenderView view, Texture target) {
        return view.scissor().x() != 0 || view.scissor().y() != 0
                || view.scissor().width() != target.desc().width()
                || view.scissor().height() != target.desc().height();
    }

    private void runHooks(RenderGraphBuilder graph, PipelineContext ctx, PipelineHook hook) {
        List<BiConsumer<RenderGraphBuilder, PipelineContext>> fns = hooks.get(hook);
        if (fns != null) {
            for (BiConsumer<RenderGraphBuilder, PipelineContext> fn : fns) {
                fn.accept(graph, ctx);
            }
        }
    }
}
