package com.javelin.core.render.pipeline;

import com.javelin.core.render.pipeline.pass.OpaqueForwardPass;
import com.javelin.core.render.pipeline.pass.RenderPass;
import com.javelin.core.render.pipeline.pass.TransparentForwardPass;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.data.RHIClearColor;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认混合渲染管线（Engine_RenderPipeline_Design.md 全文；RHI 14 节引擎侧兑现）。
 *
 * <p><b>v0.1 实现形态（已拍板：实现先 Forward）</b>：每视口两个 Pass——
 * {@link OpaqueForwardPass}（直写终点，区域 CLEAR，深度 CLEAR）→
 * {@link TransparentForwardPass}（终点 LOAD 叠加，深度 LOAD 复用，alpha 混合）。
 * 无阴影、无 GBuffer、后处理恒空链（直通）；单相机全屏时终点即最后一个 Pass，无 Composite。
 * Shadow/GBuffer/Lighting 的 Pass 设计与资源规划见设计文档第 4 节，落地为第二步。
 *
 * <p>编排 = 有序 {@link RenderPass} 列表（设计文档 6.2）：buildGraph 按序调用各 Pass 的
 * addToGraph；扩展 = 向 {@link #passes()} 增删/插入自定义 Pass（RHI 14.3 方式 1，
 * 列表组成变化 = 图缓存 structureHash 正常失效，RHI 18.5）。
 *
 * <p>着色器约定（core 主代码不含 GLSL 源码，用户拍板 2026-09-25）：内置无光照着色程序
 * 的 GLSL 放启动/应用层资源（测试工程为 {@code src/test/resources/shaders/builtin_quad.*}，
 * 顶点输入按 VertexAttribute 规范序 location 表：POSITION=0、UV0=3），
 * 由启动层编译为 SPIR-V 后交 {@code RenderingSystem.installBuiltinShaders}。
 * uniform 通道：set0 UBO1（viewProj，动态偏移切视口）+ push（model/tpf）+ set1 贴图。
 */
public class DefaultHybridRenderPipeline implements RenderPipeline {

    private final List<RenderPass> passes = new ArrayList<>();
    private final PostProcessChain postProcess = new PostProcessChain();
    private final OpaqueForwardPass opaque;

    public DefaultHybridRenderPipeline() {
        opaque=new OpaqueForwardPass();
        passes.add(opaque);
        passes.add(new TransparentForwardPass());
    }

    @Override
    public PipelineProfile profile() {
        return PipelineProfile.FORWARD;
    }

    /** 后处理链（v0.1 恒空链 = 直通，节点接口落地后处理时定义，4.6） */
    public PostProcessChain postProcess() {
        return postProcess;
    }

    public void setClearColor(RHIClearColor clearColor) {
        opaque.setClearColor(clearColor);
    }

    @Override
    public void buildGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        // v0.1：后处理空链直通 + 单相机省略 Composite（设计文档 3.3/4.7）——
        // 管线终点即 TransparentForward；多相机由下一视口的区域 CLEAR/LOAD 叠加衔接。
        for (RenderPass pass : passes) {
            pass.addToGraph(graph, ctx);
        }
    }

    @Override
    public void addPass(RenderPass pass) {
        passes.add(pass);
    }

    @Override
    public void addPass(RenderPass pass, String afterPass) {
        for (int i = 0; i < passes.size(); i++) {
            if (passes.get(i).name().equalsIgnoreCase(afterPass)) {
                passes.add(i+1,pass);
            }
        }

    }

    @Override
    public void removePass(RenderPass pass) {
        passes.remove(pass);
    }
}
