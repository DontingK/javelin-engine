package cn.donting.javelin.rhi;

import cn.donting.javelin.rhi.desc.SwapChainDesc;

/**
 * 交换链接口。
 * 对照设计文档第 23.3 节 / 第 26.6 节。
 */
public interface SwapChain extends GPUResource {

    /**
     * 获取下一帧图像。
     *
     * @param timeoutNanos 超时（纳秒）
     * @param signalSemaphore acquire 完成信号
     * @return 图像索引，<0 表示需重建
     */
    int acquireNextImage(long timeoutNanos, Semaphore signalSemaphore);

    /** backbuffer 纹理（作 RenderGraph 终点） */
    Texture image(int index);

    int imageCount();
    int width();
    int height();
    SwapChainDesc.PresentMode presentMode();

    /** 呈现 */
    void present(int imageIndex, Semaphore waitSemaphore);

    /** 重建（resize / 设备恢复） */
    void recreate(SwapChainDesc desc);
}
