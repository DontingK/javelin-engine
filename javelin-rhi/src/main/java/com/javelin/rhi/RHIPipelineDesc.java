package com.javelin.rhi;

import java.util.List;

/**
 * 管线描述结构体，用于创建图形或计算管线时传入配置参数。
 */
public class RHIPipelineDesc {

    /** 顶点着色器模块。 */
    public RHIShaderModule vertexShader;
    /** 片段着色器模块。 */
    public RHIShaderModule fragmentShader;
    /** 计算着色器模块。 */
    public RHIShaderModule computeShader;

    /** 顶点输入布局描述。 */
    public Object inputLayout;
    /** 深度模板状态描述。 */
    public Object depthStencilState;
    /** 混合状态描述。 */
    public Object blendState;
    /** 光栅化状态描述。 */
    public Object rasterizerState;

    /** 渲染目标格式列表。 */
    public List<Object> renderTargetFormats;
    /** 深度模板格式。 */
    public Object depthStencilFormat;

    /** 描述符集布局列表。 */
    public List<RHIDescriptorSetLayout> descriptorLayouts;
    /** 推送常量大小（字节）。 */
    public int pushConstantSize;

    public RHIPipelineDesc() {
    }
}
