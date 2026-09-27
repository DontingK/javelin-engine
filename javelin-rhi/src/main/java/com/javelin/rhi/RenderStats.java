package com.javelin.rhi;

/**
 * 渲染统计（doc/Engine_RenderStats_Design.md §2.2）。
 *
 * <p>由 {@link VulkanDevice} 持有的计数器，两类统计：</p>
 * <ul>
 *   <li><b>存活资源计数</b>：11 类资源「创建 − 释放」，打点在资源类构造器末尾
 *       与 {@code dispose()} 的 disposed guard 之后（覆盖 FrameArena/staging 等绕过
 *       {@code Device.createXxx} 的创建路径），只增减不重置；</li>
 *   <li><b>帧统计</b>：drawCalls / triangles / instances / dispatches。
 *       录制时累加 runtime 组，{@link Device#resetFrameStats()}（引擎每帧录制收尾后
 *       显式调用）把 runtime 快照进 lastFrame 组并清零；公开 getter 一律读 lastFrame
 *       快照——图声明（overlay 装配）先于录制，读到的天然是上一帧（bgfx stats 同款语义）。
 *       跳帧（最小化）重复快照显示值保持上一实帧，不闪零。</li>
 * </ul>
 *
 * <p>三角形口径：{@code draw = vertexCount/3}、{@code drawIndexed = indexCount/3}，
 * 各 × instanceCount；topology 按 triangle list 计。单线程退化模型（RHI 27.1），
 * 普通 long 计数。</p>
 */
public final class RenderStats {

    // ── 存活资源计数（只增减不重置；打点见类注释） ──
    private long textures, buffers, graphicsPipelines, computePipelines, samplers,
            shaderModules, pipelineLayouts, descriptorSetLayouts, descriptorSets,
            fences, semaphores;

    // ── 本帧累计（VulkanCommandBuffer 录制时打点） ──
    private long runtimeDrawCalls, runtimeTriangles, runtimeInstances, runtimeDispatches;

    // ── 上一帧快照（公开 getter 读这组） ──
    private long lastDrawCalls, lastTriangles, lastInstances, lastDispatches;

    // ═══════════════ 公开只读：存活资源计数 ═══════════════

    public long textureCount() {
        return textures;
    }

    public long bufferCount() {
        return buffers;
    }

    public long graphicsPipelineCount() {
        return graphicsPipelines;
    }

    public long computePipelineCount() {
        return computePipelines;
    }

    public long samplerCount() {
        return samplers;
    }

    public long shaderModuleCount() {
        return shaderModules;
    }

    public long pipelineLayoutCount() {
        return pipelineLayouts;
    }

    public long descriptorSetLayoutCount() {
        return descriptorSetLayouts;
    }

    public long descriptorSetCount() {
        return descriptorSets;
    }

    public long fenceCount() {
        return fences;
    }

    public long semaphoreCount() {
        return semaphores;
    }

    // ═══════════════ 公开只读：上一帧 draw 统计快照 ═══════════════

    /** 上一帧 draw call 次数（draw + drawIndexed + 无视 instancing） */
    public long drawCalls() {
        return lastDrawCalls;
    }

    /** 上一帧三角形总数（口径见类注释，含实例化倍乘） */
    public long triangles() {
        return lastTriangles;
    }

    /** 上一帧 instance 总数 */
    public long instances() {
        return lastInstances;
    }

    /** 上一帧 compute dispatch 次数 */
    public long dispatches() {
        return lastDispatches;
    }

    @Override
    public String toString() {
        return "RenderStats{alive: tex=" + textures + " buf=" + buffers
                + " pso=" + graphicsPipelines + "/" + computePipelines
                + " sampler=" + samplers + " shader=" + shaderModules
                + " setLayout=" + descriptorSetLayouts + " set=" + descriptorSets
                + " layout=" + pipelineLayouts + " fence=" + fences + " sem=" + semaphores
                + "; lastFrame: draws=" + lastDrawCalls + " tris=" + lastTriangles
                + " inst=" + lastInstances + " dispatch=" + lastDispatches + "}";
    }

    // ═══════════════ 包私有打点（构造器末尾 ++ / dispose guard 后 --） ═══════════════

    void onTextureCreated() {
        textures++;
    }

    void onTextureDisposed() {
        textures--;
    }

    void onBufferCreated() {
        buffers++;
    }

    void onBufferDisposed() {
        buffers--;
    }

    void onGraphicsPipelineCreated() {
        graphicsPipelines++;
    }

    void onGraphicsPipelineDisposed() {
        graphicsPipelines--;
    }

    void onComputePipelineCreated() {
        computePipelines++;
    }

    void onComputePipelineDisposed() {
        computePipelines--;
    }

    void onSamplerCreated() {
        samplers++;
    }

    void onSamplerDisposed() {
        samplers--;
    }

    void onShaderModuleCreated() {
        shaderModules++;
    }

    void onShaderModuleDisposed() {
        shaderModules--;
    }

    void onPipelineLayoutCreated() {
        pipelineLayouts++;
    }

    void onPipelineLayoutDisposed() {
        pipelineLayouts--;
    }

    void onDescriptorSetLayoutCreated() {
        descriptorSetLayouts++;
    }

    void onDescriptorSetLayoutDisposed() {
        descriptorSetLayouts--;
    }

    void onDescriptorSetCreated() {
        descriptorSets++;
    }

    /** 由池管理的描述符集：dispose 仅向池释放登记（生命周期由池回收） */
    void onDescriptorSetDisposed() {
        descriptorSets--;
    }

    void onFenceCreated() {
        fences++;
    }

    void onFenceDisposed() {
        fences--;
    }

    void onSemaphoreCreated() {
        semaphores++;
    }

    void onSemaphoreDisposed() {
        semaphores--;
    }

    // ═══════════════ 包私有打点：帧统计（VulkanCommandBuffer 录制时） ═══════════════

    /** draw / drawIndexed 录制打点：triangles = 顶点或索引数 / 3，instances 同报 */
    void onDrawn(int triangles, int instances) {
        runtimeDrawCalls++;
        runtimeTriangles += triangles;
        runtimeInstances += instances;
    }

    /** compute dispatch 录制打点 */
    void onDispatched() {
        runtimeDispatches++;
    }

    /** 帧尾快照+清零（Device.resetFrameStats，引擎 mainLoop 每帧显式调用） */
    void snapshotFrame() {
        lastDrawCalls = runtimeDrawCalls;
        lastTriangles = runtimeTriangles;
        lastInstances = runtimeInstances;
        lastDispatches = runtimeDispatches;
        runtimeDrawCalls = 0;
        runtimeTriangles = 0;
        runtimeInstances = 0;
        runtimeDispatches = 0;
    }
}
