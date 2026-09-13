package com.javelin.core.render.pass;

import com.javelin.core.render.*;
import com.javelin.rhi.api.CommandBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 后处理渲染 Pass —— 全屏后处理效果（占位实现）。
 *
 * <p>未来实现：
 * <ul>
 *   <li>Bloom 泛光</li>
 *   <li>SSAO 环境光遮蔽</li>
 *   <li>ToneMapping + Gamma 校正</li>
 *   <li>FXAA / TAA 抗锯齿</li>
 *   <li>Color Grading 色彩分级</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class PostProcessRenderPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(PostProcessRenderPass.class);

    @Override
    public void execute(CameraContext cameraContext, FrameContext frameContext, CommandBuffer cmd) {
        // 占位：后处理暂未实现
        log.trace("PostProcessRenderPass: placeholder (not implemented yet)");
    }
}
