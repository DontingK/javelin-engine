package com.javelin.rhi.impl;

import com.javelin.rhi.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Vulkan 计算通道实现，封装计算着色器的执行流程。
 * <p>
 * 计算通道定义了一组计算分派操作，包括管线设置、资源绑定和分派回调。
 * 通过 {@link #execute()} 方法生成并提交计算命令。
 */
public class VkComputePassImpl implements RHIComputePass {

    /** 通道名称，用于调试和渲染图分析 */
    private final String name;
    /** 所属的 Vulkan RHI 实例 */
    private final VulkanRHI rhi;

    /** 当前绑定的计算管线 */
    private RHIPipeline pipeline;
    /** 计算分派回调，由用户设置，用于记录计算命令 */
    private Consumer<RHICmd> dispatchCallback;

    /** 该通道读取的纹理列表 */
    private final List<RHITexture> textureReads = new ArrayList<>();
    /** 该通道读取的缓冲区列表 */
    private final List<RHIBuffer> bufferReads = new ArrayList<>();

    /**
     * 创建计算通道实例。
     *
     * @param name 通道名称
     * @param rhi  Vulkan RHI 实例
     */
    public VkComputePassImpl(String name, VulkanRHI rhi) {
        this.name = name;
        this.rhi = rhi;
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
     * 设置该通道使用的计算管线。
     *
     * @param pipeline 计算管线
     */
    @Override
    public void setPipeline(RHIPipeline pipeline) {
        this.pipeline = pipeline;
    }

    /**
     * 设置计算分派回调，用于记录实际的计算命令。
     *
     * @param callback 分派回调
     */
    @Override
    public void setDispatchCallback(Consumer<RHICmd> callback) {
        this.dispatchCallback = callback;
    }

    /**
     * 执行计算通道，生成分派命令。
     */
    public void execute() {
        if (dispatchCallback != null) {
            VkCmdImpl cmd = new VkCmdImpl(rhi);
            dispatchCallback.accept(cmd);
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
     * @return 计算管线
     */
    public RHIPipeline pipeline() {
        return pipeline;
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
}
