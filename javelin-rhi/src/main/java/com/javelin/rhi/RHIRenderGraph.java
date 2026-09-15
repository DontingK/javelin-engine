package com.javelin.rhi;

import java.util.function.Consumer;

/**
 * 渲染图接口，负责管理和调度一帧中的所有渲染与计算通道。
 */
public interface RHIRenderGraph {

    /** 添加一个图形渲染通道。 */
    void addGraphicsPass(String name, Consumer<RHIGraphicsPass> builder);

    /** 添加一个计算通道。 */
    void addComputePass(String name, Consumer<RHIComputePass> builder);

    /** 提交并执行渲染图中的所有通道。 */
    void submit();

    /** 导出渲染图的 DOT 格式描述（用于调试）。 */
    String exportDot();

    /** 导出渲染图的 JSON 格式描述（用于调试）。 */
    String exportJson();

    /** 调试选项：禁用资源别名复用。 */
    void setDebugDisableAliasing(boolean disable);

    /** 调试选项：仅执行指定名称的单个通道。 */
    void setDebugSinglePass(String passName);
}
