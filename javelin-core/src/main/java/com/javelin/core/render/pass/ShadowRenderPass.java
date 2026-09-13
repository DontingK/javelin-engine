package com.javelin.core.render.pass;

import com.javelin.core.render.*;
import com.javelin.rhi.api.CommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阴影渲染 Pass —— 生成阴影贴图（占位实现）。
 *
 * <p>未来实现：
 * <ul>
 *   <li>Cascade Shadow Maps（CSM）多级联阴影</li>
 *   <li>阴影贴图渲染目标（Depth-only FBO）</li>
 *   <li>光源空间矩阵计算</li>
 *   <li>PCF / PCSS 软阴影</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class ShadowRenderPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(ShadowRenderPass.class);

    @Override
    public void execute(CameraContext cameraContext, FrameContext frameContext, CommandBuffer cmd) {
        // 占位：阴影渲染暂未实现
        log.trace("ShadowRenderPass: placeholder (not implemented yet)");
    }
}
