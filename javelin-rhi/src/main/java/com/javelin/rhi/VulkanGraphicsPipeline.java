package com.javelin.rhi;

import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

/**
 * GraphicsPipeline 实现。
 * 对照设计文档第 26.3 节。
 *
 * 注意：PSO 创建是重量级操作，必须走 PipelineCache（第 16.4 节）。
 */
final class VulkanGraphicsPipeline implements GraphicsPipeline {

    private final VulkanDevice device;
    private final long pipelineHandle;
    private final long pipelineLayoutHandle;
    private volatile boolean disposed;

    VulkanGraphicsPipeline(VulkanDevice device, GraphicsPipelineDesc desc, long pipelineCache) {
        this.device = device;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 着色器阶段
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            // 顶点着色器
            VkPipelineShaderStageCreateInfo vsStage = stages.get(0);
            vsStage.sType$Default();
            vsStage.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vsStage.module(createShaderModule(desc.vertexShader().spirv()));
            vsStage.pName(stack.UTF8("main"));

            // 片元着色器
            VkPipelineShaderStageCreateInfo fsStage = stages.get(1);
            fsStage.sType$Default();
            fsStage.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fsStage.module(createShaderModule(desc.fragmentShader().spirv()));
            fsStage.pName(stack.UTF8("main"));

            // 顶点输入
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            if (desc.inputLayout() != null) {
                VkVertexInputBindingDescription.Buffer bindingDesc = VkVertexInputBindingDescription.calloc(1, stack);
                bindingDesc.get(0)
                        .binding(0)
                        .stride(desc.inputLayout().stride())
                        .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                var attrs = desc.inputLayout().attributes();
                VkVertexInputAttributeDescription.Buffer attrDescs =
                        VkVertexInputAttributeDescription.calloc(attrs.size(), stack);
                for (int i = 0; i < attrs.size(); i++) {
                    var attr = attrs.get(i);
                    attrDescs.get(i)
                            .location(attr.location())
                            .binding(0)
                            .format(VulkanTexture.toVkFormat(attr.format()))
                            .offset(attr.offset());
                }

                vertexInput.pVertexBindingDescriptions(bindingDesc);
                vertexInput.pVertexAttributeDescriptions(attrDescs);
            }

            // 输入装配
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack);
            inputAssembly.sType$Default();
            inputAssembly.topology(toVkTopology(desc.topology()));
            inputAssembly.primitiveRestartEnable(false);

            // 视口状态（动态）
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack);
            viewportState.sType$Default();
            viewportState.viewportCount(1);
            viewportState.scissorCount(1);

            // 光栅化
            VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack);
            rasterizer.sType$Default();
            rasterizer.depthClampEnable(false);
            rasterizer.rasterizerDiscardEnable(false);
            rasterizer.polygonMode(toVkPolygonMode(desc.polygonMode()));
            rasterizer.cullMode(toVkCullMode(desc.cullMode()));
            rasterizer.frontFace(toVkFrontFace(desc.frontFace()));
            rasterizer.lineWidth(desc.lineWidth());

            // 多重采样
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack);
            multisample.sType$Default();
            multisample.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            multisample.sampleShadingEnable(false);

            // 深度模板
            VkPipelineDepthStencilStateCreateInfo depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack);
            depthStencil.sType$Default();
            depthStencil.depthTestEnable(desc.depthTestEnable());
            depthStencil.depthWriteEnable(desc.depthWriteEnable());
            depthStencil.depthCompareOp(toVkCompareOp(desc.depthCompareOp()));

            // 颜色混合
            VkPipelineColorBlendAttachmentState.Buffer blendAttachment =
                    VkPipelineColorBlendAttachmentState.calloc(desc.colorAttachmentFormats().size(), stack);
            for (int i = 0; i < desc.colorAttachmentFormats().size(); i++) {
                VkPipelineColorBlendAttachmentState attachment = blendAttachment.get(i);
                attachment.blendEnable(desc.blendEnable());
                attachment.srcColorBlendFactor(toVkBlendFactor(desc.srcColorBlendFactor()));
                attachment.dstColorBlendFactor(toVkBlendFactor(desc.dstColorBlendFactor()));
                attachment.colorBlendOp(toVkBlendOp(desc.colorBlendOp()));
                attachment.srcAlphaBlendFactor(toVkBlendFactor(desc.srcAlphaBlendFactor()));
                attachment.dstAlphaBlendFactor(toVkBlendFactor(desc.dstAlphaBlendFactor()));
                attachment.alphaBlendOp(toVkBlendOp(desc.alphaBlendOp()));
                attachment.colorWriteMask(toVkColorComponentFlags(desc.colorWriteMask()));
            }

            VkPipelineColorBlendStateCreateInfo colorBlend = VkPipelineColorBlendStateCreateInfo.calloc(stack);
            colorBlend.sType$Default();
            colorBlend.logicOpEnable(false);
            colorBlend.pAttachments(blendAttachment);

            // 动态状态
            VkPipelineDynamicStateCreateInfo dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack);
            dynamicState.sType$Default();
            if (!desc.dynamicStates().isEmpty()) {
                int[] states = new int[desc.dynamicStates().size()];
                int idx = 0;
                for (var ds : desc.dynamicStates()) {
                    states[idx++] = toVkDynamicState(ds);
                }
                dynamicState.pDynamicStates(stack.ints(states));
            }

            // 管线布局
            this.pipelineLayoutHandle = createPipelineLayout(desc);

            // 管线创建信息
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfos = VkGraphicsPipelineCreateInfo.calloc(1, stack);
            VkGraphicsPipelineCreateInfo pipelineInfo = pipelineInfos.get(0);
            pipelineInfo.sType$Default();
            pipelineInfo.stageCount(2);
            pipelineInfo.pStages(stages);
            pipelineInfo.pVertexInputState(vertexInput);
            pipelineInfo.pInputAssemblyState(inputAssembly);
            pipelineInfo.pViewportState(viewportState);
            pipelineInfo.pRasterizationState(rasterizer);
            pipelineInfo.pMultisampleState(multisample);
            pipelineInfo.pDepthStencilState(depthStencil);
            pipelineInfo.pColorBlendState(colorBlend);
            pipelineInfo.pDynamicState(dynamicState);
            pipelineInfo.layout(pipelineLayoutHandle);
            pipelineInfo.renderPass(VK_NULL_HANDLE); // Dynamic Rendering
            pipelineInfo.subpass(0);

            LongBuffer pipelineBuf = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(device.vkDevice(), pipelineCache,
                    pipelineInfos, null, pipelineBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create graphics pipeline");
            }

            this.pipelineHandle = pipelineBuf.get(0);
        }

        CleanerRegistry.register(this, device, pipelineHandle, CleanerRegistry.ResourceType.PIPELINE);
    }

    @Override
    public long handle() {
        return pipelineHandle;
    }

    long layoutHandle() {
        return pipelineLayoutHandle;
    }

    @Override
    public void dispose() {
        if (disposed) return;
        disposed = true;
        CleanerRegistry.markDisposed(pipelineHandle);
        vkDestroyPipeline(device.vkDevice(), pipelineHandle, null);
    }

    private long createShaderModule(byte[] spirv) {
        VulkanShaderModule module = new VulkanShaderModule(device, spirv);
        return module.handle();
    }

    private long createPipelineLayout(GraphicsPipelineDesc desc) {
        // 简化：创建一个空的 pipeline layout
        // 实际应该根据 desc.descriptorSetLayouts() 创建
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();

            LongBuffer layoutBuf = stack.mallocLong(1);
            int result = vkCreatePipelineLayout(device.vkDevice(), layoutInfo, null, layoutBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create pipeline layout");
            }
            return layoutBuf.get(0);
        }
    }

    private static int toVkTopology(com.javelin.rhi.types.PrimitiveTopology topology) {
        return switch (topology) {
            case POINT_LIST -> VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
            case LINE_LIST -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
            case LINE_STRIP -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
            case TRIANGLE_LIST -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            case TRIANGLE_STRIP -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
            case TRIANGLE_FAN -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_FAN;
            case LINE_LIST_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_LIST_WITH_ADJACENCY;
            case LINE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_LINE_STRIP_WITH_ADJACENCY;
            case TRIANGLE_LIST_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST_WITH_ADJACENCY;
            case TRIANGLE_STRIP_ADJACENCY -> VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP_WITH_ADJACENCY;
            case PATCH_LIST -> VK_PRIMITIVE_TOPOLOGY_PATCH_LIST;
        };
    }

    private static int toVkPolygonMode(com.javelin.rhi.types.PolygonMode mode) {
        return switch (mode) {
            case FILL -> VK_POLYGON_MODE_FILL;
            case LINE -> VK_POLYGON_MODE_LINE;
            case POINT -> VK_POLYGON_MODE_POINT;
        };
    }

    private static int toVkCullMode(com.javelin.rhi.types.CullMode mode) {
        return switch (mode) {
            case NONE -> VK_CULL_MODE_NONE;
            case FRONT -> VK_CULL_MODE_FRONT_BIT;
            case BACK -> VK_CULL_MODE_BACK_BIT;
            case FRONT_AND_BACK -> VK_CULL_MODE_FRONT_AND_BACK;
        };
    }

    private static int toVkFrontFace(com.javelin.rhi.types.FrontFace face) {
        return switch (face) {
            case COUNTER_CLOCKWISE -> VK_FRONT_FACE_COUNTER_CLOCKWISE;
            case CLOCKWISE -> VK_FRONT_FACE_CLOCKWISE;
        };
    }

    private static int toVkCompareOp(com.javelin.rhi.types.CompareOp op) {
        return switch (op) {
            case NEVER -> VK_COMPARE_OP_NEVER;
            case LESS -> VK_COMPARE_OP_LESS;
            case EQUAL -> VK_COMPARE_OP_EQUAL;
            case LESS_OR_EQUAL -> VK_COMPARE_OP_LESS_OR_EQUAL;
            case GREATER -> VK_COMPARE_OP_GREATER;
            case NOT_EQUAL -> VK_COMPARE_OP_NOT_EQUAL;
            case GREATER_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            case ALWAYS -> VK_COMPARE_OP_ALWAYS;
        };
    }

    private static int toVkBlendFactor(com.javelin.rhi.types.BlendFactor factor) {
        return switch (factor) {
            case ZERO -> VK_BLEND_FACTOR_ZERO;
            case ONE -> VK_BLEND_FACTOR_ONE;
            case SRC_COLOR -> VK_BLEND_FACTOR_SRC_COLOR;
            case ONE_MINUS_SRC_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
            case DST_COLOR -> VK_BLEND_FACTOR_DST_COLOR;
            case ONE_MINUS_DST_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
            case SRC_ALPHA -> VK_BLEND_FACTOR_SRC_ALPHA;
            case ONE_MINUS_SRC_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
            case DST_ALPHA -> VK_BLEND_FACTOR_DST_ALPHA;
            case ONE_MINUS_DST_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            case CONSTANT_COLOR -> VK_BLEND_FACTOR_CONSTANT_COLOR;
            case ONE_MINUS_CONSTANT_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR;
            case CONSTANT_ALPHA -> VK_BLEND_FACTOR_CONSTANT_ALPHA;
            case ONE_MINUS_CONSTANT_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA;
            case SRC_ALPHA_SATURATE -> VK_BLEND_FACTOR_SRC_ALPHA_SATURATE;
            case SRC1_COLOR -> VK_BLEND_FACTOR_SRC1_COLOR;
            case ONE_MINUS_SRC1_COLOR -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_COLOR;
            case SRC1_ALPHA -> VK_BLEND_FACTOR_SRC1_ALPHA;
            case ONE_MINUS_SRC1_ALPHA -> VK_BLEND_FACTOR_ONE_MINUS_SRC1_ALPHA;
        };
    }

    private static int toVkBlendOp(com.javelin.rhi.types.BlendOp op) {
        return switch (op) {
            case ADD -> VK_BLEND_OP_ADD;
            case SUBTRACT -> VK_BLEND_OP_SUBTRACT;
            case REVERSE_SUBTRACT -> VK_BLEND_OP_REVERSE_SUBTRACT;
            case MIN -> VK_BLEND_OP_MIN;
            case MAX -> VK_BLEND_OP_MAX;
        };
    }

    private static int toVkColorComponentFlags(java.util.Set<com.javelin.rhi.types.ColorComponentFlags> flags) {
        int result = 0;
        for (var flag : flags) {
            switch (flag) {
                case R -> result |= VK_COLOR_COMPONENT_R_BIT;
                case G -> result |= VK_COLOR_COMPONENT_G_BIT;
                case B -> result |= VK_COLOR_COMPONENT_B_BIT;
                case A -> result |= VK_COLOR_COMPONENT_A_BIT;
            }
        }
        return result;
    }

    private static int toVkDynamicState(com.javelin.rhi.types.DynamicState state) {
        return switch (state) {
            case VIEWPORT -> VK_DYNAMIC_STATE_VIEWPORT;
            case SCISSOR -> VK_DYNAMIC_STATE_SCISSOR;
            case LINE_WIDTH -> VK_DYNAMIC_STATE_LINE_WIDTH;
            case DEPTH_BIAS -> VK_DYNAMIC_STATE_DEPTH_BIAS;
            case BLEND_CONSTANTS -> VK_DYNAMIC_STATE_BLEND_CONSTANTS;
            case DEPTH_BOUNDS -> VK_DYNAMIC_STATE_DEPTH_BOUNDS;
            case STENCIL_COMPARE_MASK -> VK_DYNAMIC_STATE_STENCIL_COMPARE_MASK;
            case STENCIL_WRITE_MASK -> VK_DYNAMIC_STATE_STENCIL_WRITE_MASK;
            case STENCIL_REFERENCE -> VK_DYNAMIC_STATE_STENCIL_REFERENCE;
            default -> throw new UnsupportedOperationException("Dynamic state not supported: " + state);
        };
    }
}
