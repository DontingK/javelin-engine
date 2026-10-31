package cn.donting.javelin.rhi.desc;

import cn.donting.javelin.rhi.types.*;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.types.*;

import java.util.List;
import java.util.Set;

/**
 * 图形管线描述。
 * 对照 VkGraphicsPipelineCreateInfo。
 *
 * 注意：PSO 创建是重量级操作，不能每帧新建，必须走 PipelineCache（第 16.4 节）。
 */
public final class GraphicsPipelineDesc {

    // 着色器
    private final ShaderDesc vertexShader;
    private final ShaderDesc fragmentShader;

    // 顶点输入
    private final VertexLayout inputLayout;
    private final PrimitiveTopology topology;

    // 光栅化
    private final CullMode cullMode;
    private final FrontFace frontFace;
    private final PolygonMode polygonMode;
    private final float lineWidth;

    // 深度模板
    private final boolean depthTestEnable;
    private final boolean depthWriteEnable;
    private final CompareOp depthCompareOp;

    // 混合
    private final boolean blendEnable;
    private final BlendFactor srcColorBlendFactor;
    private final BlendFactor dstColorBlendFactor;
    private final BlendOp colorBlendOp;
    private final BlendFactor srcAlphaBlendFactor;
    private final BlendFactor dstAlphaBlendFactor;
    private final BlendOp alphaBlendOp;
    private final Set<ColorComponentFlags> colorWriteMask;

    // 渲染附件格式
    private final List<Format> colorAttachmentFormats;
    private final Format depthAttachmentFormat;

    // 描述符布局
    private final List<DescriptorSetLayoutDesc> descriptorSetLayouts;
    private final int pushConstantSize;

    // 动态状态
    private final Set<DynamicState> dynamicStates;

    // 多重采样（MSAA：PSO rasterizationSamples 必须与本 Pass 附件采样数一致，
    // 不一致是验证层错误；COUNT_1 = 无 MSAA，与既有路径等价）
    private final SampleCount sampleCount;

    public GraphicsPipelineDesc(
            ShaderDesc vertexShader, ShaderDesc fragmentShader,
            VertexLayout inputLayout, PrimitiveTopology topology,
            CullMode cullMode, FrontFace frontFace, PolygonMode polygonMode, float lineWidth,
            boolean depthTestEnable, boolean depthWriteEnable, CompareOp depthCompareOp,
            boolean blendEnable,
            BlendFactor srcColorBlendFactor, BlendFactor dstColorBlendFactor, BlendOp colorBlendOp,
            BlendFactor srcAlphaBlendFactor, BlendFactor dstAlphaBlendFactor, BlendOp alphaBlendOp,
            Set<ColorComponentFlags> colorWriteMask,
            List<Format> colorAttachmentFormats, Format depthAttachmentFormat,
            List<DescriptorSetLayoutDesc> descriptorSetLayouts, int pushConstantSize,
            Set<DynamicState> dynamicStates, SampleCount sampleCount) {
        if (vertexShader == null) {
            throw new IllegalArgumentException("vertexShader must not be null");
        }
        if (fragmentShader == null) {
            throw new IllegalArgumentException("fragmentShader must not be null");
        }
        if (colorAttachmentFormats == null || colorAttachmentFormats.isEmpty()) {
            throw new IllegalArgumentException("colorAttachmentFormats must not be empty");
        }
        if (descriptorSetLayouts == null) {
            descriptorSetLayouts = List.of();
        }
        if (dynamicStates == null) {
            dynamicStates = Set.of();
        }
        if (colorWriteMask == null) {
            colorWriteMask = Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                    ColorComponentFlags.B, ColorComponentFlags.A);
        }
        if (sampleCount == null) {
            sampleCount = SampleCount.COUNT_1;
        }
        this.vertexShader = vertexShader;
        this.fragmentShader = fragmentShader;
        this.inputLayout = inputLayout;
        this.topology = topology;
        this.cullMode = cullMode;
        this.frontFace = frontFace;
        this.polygonMode = polygonMode;
        this.lineWidth = lineWidth;
        this.depthTestEnable = depthTestEnable;
        this.depthWriteEnable = depthWriteEnable;
        this.depthCompareOp = depthCompareOp;
        this.blendEnable = blendEnable;
        this.srcColorBlendFactor = srcColorBlendFactor;
        this.dstColorBlendFactor = dstColorBlendFactor;
        this.colorBlendOp = colorBlendOp;
        this.srcAlphaBlendFactor = srcAlphaBlendFactor;
        this.dstAlphaBlendFactor = dstAlphaBlendFactor;
        this.alphaBlendOp = alphaBlendOp;
        this.colorWriteMask = Set.copyOf(colorWriteMask);
        this.colorAttachmentFormats = List.copyOf(colorAttachmentFormats);
        this.depthAttachmentFormat = depthAttachmentFormat;
        this.descriptorSetLayouts = List.copyOf(descriptorSetLayouts);
        this.pushConstantSize = pushConstantSize;
        this.dynamicStates = Set.copyOf(dynamicStates);
        this.sampleCount = sampleCount;
    }

    // --- getters ---

    public ShaderDesc vertexShader() { return vertexShader; }
    public ShaderDesc fragmentShader() { return fragmentShader; }
    public VertexLayout inputLayout() { return inputLayout; }
    public PrimitiveTopology topology() { return topology; }
    public CullMode cullMode() { return cullMode; }
    public FrontFace frontFace() { return frontFace; }
    public PolygonMode polygonMode() { return polygonMode; }
    public float lineWidth() { return lineWidth; }
    public boolean depthTestEnable() { return depthTestEnable; }
    public boolean depthWriteEnable() { return depthWriteEnable; }
    public CompareOp depthCompareOp() { return depthCompareOp; }
    public boolean blendEnable() { return blendEnable; }
    public BlendFactor srcColorBlendFactor() { return srcColorBlendFactor; }
    public BlendFactor dstColorBlendFactor() { return dstColorBlendFactor; }
    public BlendOp colorBlendOp() { return colorBlendOp; }
    public BlendFactor srcAlphaBlendFactor() { return srcAlphaBlendFactor; }
    public BlendFactor dstAlphaBlendFactor() { return dstAlphaBlendFactor; }
    public BlendOp alphaBlendOp() { return alphaBlendOp; }
    public Set<ColorComponentFlags> colorWriteMask() { return colorWriteMask; }
    public List<Format> colorAttachmentFormats() { return colorAttachmentFormats; }
    public Format depthAttachmentFormat() { return depthAttachmentFormat; }
    public List<DescriptorSetLayoutDesc> descriptorSetLayouts() { return descriptorSetLayouts; }
    public int pushConstantSize() { return pushConstantSize; }
    public Set<DynamicState> dynamicStates() { return dynamicStates; }
    public SampleCount sampleCount() { return sampleCount; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof GraphicsPipelineDesc that)) return false;
        return vertexShader.equals(that.vertexShader)
                && fragmentShader.equals(that.fragmentShader)
                && inputLayout.equals(that.inputLayout)
                && topology == that.topology
                && cullMode == that.cullMode
                && frontFace == that.frontFace
                && polygonMode == that.polygonMode
                && Float.compare(lineWidth, that.lineWidth) == 0
                && depthTestEnable == that.depthTestEnable
                && depthWriteEnable == that.depthWriteEnable
                && depthCompareOp == that.depthCompareOp
                && blendEnable == that.blendEnable
                && srcColorBlendFactor == that.srcColorBlendFactor
                && dstColorBlendFactor == that.dstColorBlendFactor
                && colorBlendOp == that.colorBlendOp
                && srcAlphaBlendFactor == that.srcAlphaBlendFactor
                && dstAlphaBlendFactor == that.dstAlphaBlendFactor
                && alphaBlendOp == that.alphaBlendOp
                && colorWriteMask.equals(that.colorWriteMask)
                && colorAttachmentFormats.equals(that.colorAttachmentFormats)
                && depthAttachmentFormat == that.depthAttachmentFormat
                && descriptorSetLayouts.equals(that.descriptorSetLayouts)
                && pushConstantSize == that.pushConstantSize
                && dynamicStates.equals(that.dynamicStates)
                && sampleCount == that.sampleCount;
    }

    @Override
    public int hashCode() {
        int result = vertexShader.hashCode();
        result = 31 * result + fragmentShader.hashCode();
        result = 31 * result + inputLayout.hashCode();
        result = 31 * result + topology.hashCode();
        result = 31 * result + cullMode.hashCode();
        result = 31 * result + frontFace.hashCode();
        result = 31 * result + polygonMode.hashCode();
        result = 31 * result + Float.hashCode(lineWidth);
        result = 31 * result + Boolean.hashCode(depthTestEnable);
        result = 31 * result + Boolean.hashCode(depthWriteEnable);
        result = 31 * result + depthCompareOp.hashCode();
        result = 31 * result + Boolean.hashCode(blendEnable);
        result = 31 * result + srcColorBlendFactor.hashCode();
        result = 31 * result + dstColorBlendFactor.hashCode();
        result = 31 * result + colorBlendOp.hashCode();
        result = 31 * result + srcAlphaBlendFactor.hashCode();
        result = 31 * result + dstAlphaBlendFactor.hashCode();
        result = 31 * result + alphaBlendOp.hashCode();
        result = 31 * result + colorWriteMask.hashCode();
        result = 31 * result + colorAttachmentFormats.hashCode();
        result = 31 * result + depthAttachmentFormat.hashCode();
        result = 31 * result + descriptorSetLayouts.hashCode();
        result = 31 * result + pushConstantSize;
        result = 31 * result + dynamicStates.hashCode();
        result = 31 * result + sampleCount.hashCode();
        return result;
    }

    @Override
    public String toString() {
        return "GraphicsPipelineDesc{vs=" + vertexShader + ", fs=" + fragmentShader
                + ", topology=" + topology
                + ", cull=" + cullMode + ", front=" + frontFace
                + ", depthTest=" + depthTestEnable + ", depthWrite=" + depthWriteEnable
                + ", blend=" + blendEnable
                + ", colors=" + colorAttachmentFormats
                + ", depthFmt=" + depthAttachmentFormat
                + ", sets=" + descriptorSetLayouts.size()
                + ", pushConst=" + pushConstantSize
                + ", samples=" + sampleCount + "}";
    }
}
