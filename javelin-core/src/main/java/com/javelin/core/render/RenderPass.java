package com.javelin.core.render;

import com.javelin.rhi.api.CommandBuffer;

/**
 * 引擎侧渲染 Pass —— 渲染管线中的单个阶段。
 *
 * <p>每个 Pass 在 {@link RenderingPipeline} 中按顺序执行一次，负责完成特定的渲染阶段。
 * Pass 直接操作 RHI {@link CommandBuffer} 录制 GPU 命令。</p>
 *
 * <p>典型 Pass 类型：
 * <ul>
 *   <li>ShadowPass —— 生成阴影贴图</li>
 *   <li>GBufferPass —— 填充 GBuffer</li>
 *   <li>LightingPass —— 全屏光照计算</li>
 *   <li>ForwardPass —— 前向渲染（透明/头发）</li>
 *   <li>PostProcessPass —— 后处理（Bloom/SSAO/ToneMap）</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public interface RenderPass {

    /**
     * 执行该 Pass。
     *
     * <p>实现类通过 {@link CameraContext} 获取当前相机数据（矩阵、渲染列表），
     * 通过 {@link FrameContext} 获取全局数据（时间、帧计数），
     * 向 {@link CommandBuffer} 录制绘制命令。</p>
     *
     * @param cameraContext 相机上下文（每相机独立）
     * @param frameContext  帧全局上下文（所有相机共享）
     * @param cmd           RHI 命令缓冲区
     */
    void execute(CameraContext cameraContext, FrameContext frameContext, CommandBuffer cmd);

    /**
     * 获取 Pass 名称（用于调试和统计）。
     *
     * @return Pass 名称
     */
    default String getName() {
        return getClass().getSimpleName();
    }
}
