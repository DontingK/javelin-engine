package com.javelin.rhi;

/**
 * 所有 RHI 资源的基类。
 * 对照设计文档第 17.1 节 / 第 26.2 节。
 *
 * 生命周期：Java 对象不可达 → Cleaner 触发 → 延迟销毁队列（两帧规则）。
 * 也可显式调用 dispose() 确定性销毁。
 */
public interface GPUResource {

    /** 唯一手动销毁入口；不可达时 Cleaner 兜底 */
    void dispose();
}
