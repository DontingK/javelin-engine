package com.javelin.rhi.impl;

import com.javelin.rhi.*;
import com.javelin.rhi.data.RHIClearValue;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Vulkan 图形通道实现，封装图形渲染的一次绘制流程。
 * <p>
 * 图形通道定义了颜色/深度输出目标、视口裁剪、管线设置以及渲染回调，
 * 通过 {@link #execute()} 方法生成并提交图形绘制命令。
 */
public class VkGraphicsPassImpl implements RHIGraphicsPass {

    /** 通道名称，用于调试和渲染图分析 */
    private final String name;
    /** 所属的 Vulkan RHI 实例 */
    private final VulkanRHI rhi;

    /** 当前绑定的图形管线 */
    private RHIPipeline pipeline;
    /** 视口设置 */
    private RHIViewport viewport;
    /** 裁剪区域设置 */
    private RHIScissor scissor;
    /** 渲染回调，由用户设置，用于记录绘制命令 */
    private Consumer<RHICmd> renderCallback;

    /** 颜色输出目标列表 */
    private final List<ColorOutput> colorOutputs = new ArrayList<>();
    /** 深度输出目标列表 */
    private final List<DepthOutput> depthOutputs = new ArrayList<>();
    /** 该通道读取的纹理列表 */
    private final List<RHITexture> textureReads = new ArrayList<>();
    /** 该通道读取的缓冲区列表 */
    private final List<RHIBuffer> bufferReads = new ArrayList<>();

    /**
     * 创建图形通道实例。
     *
     * @param name 通道名称
     * @param rhi  Vulkan RHI 实例
     */
    public VkGraphicsPassImpl(String name, VulkanRHI rhi) {
        this.name = name;
        this.rhi = rhi;
    }

    /**
     * 设置该通道使用的图形管线。
     *
     * @param pipeline 图形管线
     */
    @Override
    public void setPipeline(RHIPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 添加颜色输出目标，默认使用 LOAD 操作。
     *
     * @param tex 颜色输出纹理
     */
    @Override
    public void addColorOutput(RHITexture tex) {
        colorOutputs.add(new ColorOutput(tex, RHILoadOp.LOAD, null));
    }

    /**
     * 添加颜色输出目标，指定加载操作和清除值。
     *
     * @param tex    颜色输出纹理
     * @param loadOp 加载操作
     * @param clear  清除值
     */
    @Override
    public void addColorOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear) {
        colorOutputs.add(new ColorOutput(tex, loadOp, clear));
    }

    /**
     * 添加深度输出目标，默认使用 LOAD 操作。
     *
     * @param tex 深度输出纹理
     */
    @Override
    public void addDepthOutput(RHITexture tex) {
        depthOutputs.add(new DepthOutput(tex, RHILoadOp.LOAD, null));
    }

    /**
     * 添加深度输出目标，指定加载操作和清除值。
     *
     * @param tex    深度输出纹理
     * @param loadOp 加载操作
     * @param clear  清除值
     */
    @Override
    public void addDepthOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear) {
        depthOutputs.add(new DepthOutput(tex, loadOp, clear));
    }

    /**
     * 声明该通道将读取的纹理资源。
     *
     * @param tex 被读取的纹理
     */
    @Override
    public void addTextureRead(RHITexture tex) {
        textureReads.add(tex);
    }

    /**
     * 声明该通道将读取的缓冲区资源。
     *
     * @param buf 被读取的缓冲区
     */
    @Override
    public void addBufferRead(RHIBuffer buf) {
        bufferReads.add(buf);
    }

    /**
     * 设置渲染视口。
     *
     * @param viewport 视口参数
     */
    @Override
    public void setViewport(RHIViewport viewport) {
        this.viewport = viewport;
    }

    /**
     * 设置渲染裁剪区域。
     *
     * @param scissor 裁剪区域参数
     */
    @Override
    public void setScissor(RHIScissor scissor) {
        this.scissor = scissor;
    }

    /**
     * 设置 Uniform 变量值。
     *
     * @param name Uniform 变量名
     * @param data 数据数组
     */
    @Override
    public void setUniform(String name, float[] data) {
        // TODO: Compile name to UBO offset at build time
    }

    /**
     * 设置渲染回调，用于记录实际的绘制命令。
     *
     * @param callback 渲染回调
     */
    @Override
    public void setRenderCallback(Consumer<RHICmd> callback) {
        this.renderCallback = callback;
    }

    /**
     * 执行图形通道，生成绘制命令。
     */
    public void execute() {
        if (renderCallback != null) {
            VkCmdImpl cmd = new VkCmdImpl(rhi);
            renderCallback.accept(cmd);
        }
    }

    /**
     * 获取通道名称。
     *
     * @return 通道名称
     */
    public String name() {
        return name;
    }

    /**
     * 获取当前绑定的管线。
     *
     * @return 图形管线
     */
    public RHIPipeline pipeline() {
        return pipeline;
    }

    /**
     * 获取颜色输出目标列表。
     *
     * @return 颜色输出列表
     */
    public List<ColorOutput> colorOutputs() {
        return colorOutputs;
    }

    /**
     * 获取深度输出目标列表。
     *
     * @return 深度输出列表
     */
    public List<DepthOutput> depthOutputs() {
        return depthOutputs;
    }

    /**
     * 获取该通道读取的纹理列表。
     *
     * @return 纹理列表
     */
    public List<RHITexture> textureReads() {
        return textureReads;
    }

    /**
     * 获取该通道读取的缓冲区列表。
     *
     * @return 缓冲区列表
     */
    public List<RHIBuffer> bufferReads() {
        return bufferReads;
    }

    /**
     * 颜色输出记录，包含目标纹理、加载操作和清除值。
     *
     * @param tex        目标纹理
     * @param loadOp     加载操作
     * @param clearValue 清除值
     */
    record ColorOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clearValue) {
    }

    /**
     * 深度输出记录，包含目标纹理、加载操作和清除值。
     *
     * @param tex        目标纹理
     * @param loadOp     加载操作
     * @param clearValue 清除值
     */
    record DepthOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clearValue) {
    }
}
