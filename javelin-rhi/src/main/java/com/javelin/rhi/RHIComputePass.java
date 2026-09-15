package com.javelin.rhi;

import java.util.function.Consumer;

/**
 * 计算通道接口，定义一帧中的计算任务。
 */
public interface RHIComputePass {

    /** 声明此通道会读取的纹理（用于依赖分析）。 */
    void addTextureRead(RHITexture tex);

    /** 声明此通道会读取的缓冲区（用于依赖分析）。 */
    void addBufferRead(RHIBuffer buf);

    /** 设置此通道使用的计算管线。 */
    void setPipeline(RHIPipeline pipeline);

    /** 设置计算派发回调，在通道执行时录制命令。 */
    void setDispatchCallback(Consumer<RHICmd> callback);
}
