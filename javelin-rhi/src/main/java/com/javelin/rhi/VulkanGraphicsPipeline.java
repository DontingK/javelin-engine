package com.javelin.rhi;

import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.exception.VulkanException;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

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
    /** 管线布局引用的描述符集布局（按 desc.descriptorSetLayouts 创建，随管线销毁） */
    private final List<VulkanDescriptorSetLayout> ownedSetLayouts = new ArrayList<>();
    private volatile boolean disposed;

    VulkanGraphicsPipeline(VulkanDevice device, GraphicsPipelineDesc desc, long pipelineCache) {
        this.device = device;

        VulkanShaderModule vsModule;
        VulkanShaderModule fsModule;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 着色器阶段
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);

            // 顶点着色器
            VkPipelineShaderStageCreateInfo vsStage = stages.get(0);
            vsStage.sType$Default();
            vsStage.stage(VK_SHADER_STAGE_VERTEX_BIT);
            vsModule = new VulkanShaderModule(device, desc.vertexShader().spirv());
            vsStage.module(vsModule.handle());
            vsStage.pName(stack.UTF8("main"));

            // 片元着色器
            VkPipelineShaderStageCreateInfo fsStage = stages.get(1);
            fsStage.sType$Default();
            fsStage.stage(VK_SHADER_STAGE_FRAGMENT_BIT);
            fsModule = new VulkanShaderModule(device, desc.fragmentShader().spirv());
            fsStage.module(fsModule.handle());
            fsStage.pName(stack.UTF8("main"));

            // 顶点输入
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack);
            vertexInput.sType$Default();
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
            // 与渲染附件采样数同源（dynamic rendering 附件 samples 不一致是验证层错误）
            multisample.rasterizationSamples(VulkanTexture.toVkSampleCount(desc.sampleCount()));
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

            // dynamic rendering：renderPass 为 NULL 时 pNext 必须挂 VkPipelineRenderingCreateInfo
            // 声明目标附件格式（规范要求；desc 的 colorAttachmentFormats/depthAttachmentFormat 在此送达驱动）
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack);
            renderingInfo.sType$Default();
            var colorFormats = desc.colorAttachmentFormats();
            renderingInfo.colorAttachmentCount(colorFormats.size());
            if (!colorFormats.isEmpty()) {
                IntBuffer vkFormats = stack.mallocInt(colorFormats.size());
                for (var f : colorFormats) {
                    vkFormats.put(VulkanTexture.toVkFormat(f));
                }
                vkFormats.flip();
                renderingInfo.pColorAttachmentFormats(vkFormats);
            }
            int depthVkFormat = desc.depthAttachmentFormat() != null
                    ? VulkanTexture.toVkFormat(desc.depthAttachmentFormat())
                    : VK_FORMAT_UNDEFINED;
            renderingInfo.depthAttachmentFormat(depthVkFormat);
            // 深度模板混合格式（含 stencil 方面）须同时声明 stencil 格式，纯深度格式则 UNDEFINED
            int stencilVkFormat = VK_FORMAT_UNDEFINED;
            if (desc.depthAttachmentFormat() != null) {
                switch (desc.depthAttachmentFormat()) {
                    case D24_UNORM_S8_UINT, D32_SFLOAT_S8_UINT, S8_UINT -> stencilVkFormat = depthVkFormat;
                    default -> { }
                }
            }
            renderingInfo.stencilAttachmentFormat(stencilVkFormat);
            pipelineInfo.pNext(renderingInfo);

            LongBuffer pipelineBuf = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(device.vkDevice(), pipelineCache,
                    pipelineInfos, null, pipelineBuf);
            if (result != VK_SUCCESS) {
                throw new VulkanException(result, "Failed to create graphics pipeline");
            }

            this.pipelineHandle = pipelineBuf.get(0);
        }

        // 规范：管线创建完成（消费完 SPIR-V）后即可销毁着色器模块——
        // 必须在 vkCreateGraphicsPipelines 成功之后 dispose，销毁早于创建会解引用悬空句柄直接崩溃
        vsModule.dispose();
        fsModule.dispose();

        CleanerRegistry.register(this, device, pipelineHandle, CleanerRegistry.ResourceType.PIPELINE);
        CleanerRegistry.register(this, device, pipelineLayoutHandle, CleanerRegistry.ResourceType.PIPELINE_LAYOUT);
        device.stats().onGraphicsPipelineCreated();
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
        device.stats().onGraphicsPipelineDisposed();
        CleanerRegistry.markDisposed(pipelineHandle);
        CleanerRegistry.markDisposed(pipelineLayoutHandle);
        vkDestroyPipeline(device.vkDevice(), pipelineHandle, null);
        vkDestroyPipelineLayout(device.vkDevice(), pipelineLayoutHandle, null);
        for (VulkanDescriptorSetLayout setLayout : ownedSetLayouts) {
            setLayout.dispose();
        }
        ownedSetLayouts.clear();
    }

    private long createPipelineLayout(GraphicsPipelineDesc desc) {
        // 按 desc.descriptorSetLayouts() 逐个创建 VkDescriptorSetLayout 并挂入管线布局；
        // 上层用相同 DescriptorSetLayoutDesc 经 createDescriptorSetLayout 创建的布局与之
        // 「定义一致」（identically defined），可安全用于分配描述符集并绑定（15.7-2）
        try (MemoryStack stack = MemoryStack.stackPush()) {
            VkPipelineLayoutCreateInfo layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack);
            layoutInfo.sType$Default();

            List<com.javelin.rhi.desc.DescriptorSetLayoutDesc> setDescs = desc.descriptorSetLayouts();
            if (!setDescs.isEmpty()) {
                long[] setLayoutHandles = new long[setDescs.size()];
                for (int i = 0; i < setDescs.size(); i++) {
                    VulkanDescriptorSetLayout setLayout = new VulkanDescriptorSetLayout(device, setDescs.get(i));
                    ownedSetLayouts.add(setLayout);
                    setLayoutHandles[i] = setLayout.handle();
                }
                layoutInfo.pSetLayouts(stack.longs(setLayoutHandles));
            }

            if (desc.pushConstantSize() > 0) {
                VkPushConstantRange.Buffer pushRange = VkPushConstantRange.calloc(1, stack);
                pushRange.get(0)
                        .stageFlags(VK_SHADER_STAGE_ALL_GRAPHICS)
                        .offset(0)
                        .size(desc.pushConstantSize());
                layoutInfo.pPushConstantRanges(pushRange);
            }

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
