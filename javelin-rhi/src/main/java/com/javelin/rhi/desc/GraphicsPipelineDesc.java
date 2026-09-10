package com.javelin.rhi.desc;

import com.javelin.rhi.types.*;

/**
 * 图形管线创建描述符。
 */
public record GraphicsPipelineDesc(
    ShaderDesc vertexShader,
    ShaderDesc fragmentShader,
    ShaderDesc geometryShader,
    ShaderDesc tessellationControlShader,
    ShaderDesc tessellationEvaluationShader,

    VertexLayout vertexLayout,
    PrimitiveTopology topology,

    // 光栅化
    PolygonMode polygonMode,
    CullMode cullMode,
    FrontFace frontFace,
    float lineWidth,
    boolean depthClampEnable,
    boolean rasterizerDiscardEnable,
    boolean depthBiasEnable,
    float depthBiasConstant,
    float depthBiasClamp,
    float depthBiasSlope,

    // 多重采样
    SampleCount sampleCount,
    boolean sampleShadingEnable,
    float minSampleShading,
    int sampleMask,
    boolean alphaToCoverageEnable,
    boolean alphaToOneEnable,

    // 深度/模板
    DepthStencilState depthStencil,

    // 混合
    BlendState[] colorBlendAttachments,
    boolean logicOpEnable,
    LogicOp logicOp,
    float[] blendConstants,

    // 动态状态
    DynamicState[] dynamicStates,

    // 布局
    PipelineLayoutDesc layout,

    // 视口（可选，动态状态优先）
    ViewportState viewport
) {
    public GraphicsPipelineDesc {
        if (vertexShader == null) throw new IllegalArgumentException("Vertex shader is required");
        if (topology == null) topology = PrimitiveTopology.TRIANGLE_LIST;
        if (polygonMode == null) polygonMode = PolygonMode.FILL;
        if (cullMode == null) cullMode = CullMode.BACK;
        if (frontFace == null) frontFace = FrontFace.COUNTER_CLOCKWISE;
        if (lineWidth <= 0) lineWidth = 1.0f;
        if (sampleCount == null) sampleCount = SampleCount.COUNT_1;
        if (colorBlendAttachments == null) colorBlendAttachments = new BlendState[0];
        if (blendConstants == null) blendConstants = new float[]{0, 0, 0, 0};
        if (dynamicStates == null) dynamicStates = new DynamicState[0];
    }

    // ========== 内部类型 ==========

    public record DepthStencilState(
        boolean depthTestEnable,
        boolean depthWriteEnable,
        CompareOp depthCompareOp,
        boolean depthBoundsTestEnable,
        float minDepthBounds,
        float maxDepthBounds,
        boolean stencilTestEnable,
        StencilOpState front,
        StencilOpState back
    ) {
        public static DepthStencilState disabled() {
            return new DepthStencilState(false, false, CompareOp.ALWAYS, false, 0, 1, false, null, null);
        }

        public static DepthStencilState standard() {
            return new DepthStencilState(true, true, CompareOp.LESS, false, 0, 1, false, null, null);
        }
    }

    public record StencilOpState(
        StencilOp failOp,
        StencilOp passOp,
        StencilOp depthFailOp,
        CompareOp compareOp,
        int compareMask,
        int writeMask,
        int reference
    ) {}

    public record BlendState(
        boolean blendEnable,
        BlendFactor srcColorBlendFactor,
        BlendFactor dstColorBlendFactor,
        BlendOp colorBlendOp,
        BlendFactor srcAlphaBlendFactor,
        BlendFactor dstAlphaBlendFactor,
        BlendOp alphaBlendOp,
        int colorWriteMask
    ) {
        public BlendState {
            if (colorWriteMask == 0) colorWriteMask = ColorComponentFlags.ALL;
        }

        public static BlendState opaque() {
            return new BlendState(false, BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                ColorComponentFlags.R.value() | ColorComponentFlags.G.value() | ColorComponentFlags.B.value() | ColorComponentFlags.A.value());
        }

        public static BlendState alphaBlend() {
            return new BlendState(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ONE_MINUS_SRC_ALPHA, BlendOp.ADD,
                ColorComponentFlags.R.value() | ColorComponentFlags.G.value() | ColorComponentFlags.B.value() | ColorComponentFlags.A.value());
        }

        public static BlendState additive() {
            return new BlendState(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD,
                ColorComponentFlags.R.value() | ColorComponentFlags.G.value() | ColorComponentFlags.B.value() | ColorComponentFlags.A.value());
        }
    }

    public record ViewportState(
        int x, int y, int width, int height,
        float minDepth, float maxDepth
    ) {
        public ViewportState(int width, int height) {
            this(0, 0, width, height, 0.0f, 1.0f);
        }
    }
}
