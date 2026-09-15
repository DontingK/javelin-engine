package com.javelin.rhi;

import com.javelin.rhi.data.RHIClearValue;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import java.util.function.Consumer;

/**
 * 图形渲染通道接口，定义一帧中的图形渲染任务。
 */
public interface RHIGraphicsPass {

    /** 设置此通道使用的图形管线。 */
    void setPipeline(RHIPipeline pipeline);

    /** 添加颜色输出目标纹理。 */
    void addColorOutput(RHITexture tex);

    /** 添加颜色输出目标纹理，并指定加载操作和清除值。 */
    void addColorOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear);

    /** 添加深度输出目标纹理。 */
    void addDepthOutput(RHITexture tex);

    /** 添加深度输出目标纹理，并指定加载操作和清除值。 */
    void addDepthOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear);

    /** 声明此通道会读取的纹理（用于依赖分析）。 */
    void addTextureRead(RHITexture tex);

    /** 声明此通道会读取的缓冲区（用于依赖分析）。 */
    void addBufferRead(RHIBuffer buf);

    /** 设置视口。 */
    void setViewport(RHIViewport viewport);

    /** 设置裁剪矩形。 */
    void setScissor(RHIScissor scissor);

    /** 设置 uniform 变量数据（通过推送常量或内部机制传递）。 */
    void setUniform(String name, float[] data);

    /** 设置渲染回调，在通道执行时录制命令。 */
    void setRenderCallback(Consumer<RHICmd> callback);
}
