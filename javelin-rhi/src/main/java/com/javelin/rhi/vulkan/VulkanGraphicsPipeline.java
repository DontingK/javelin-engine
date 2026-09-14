package com.javelin.rhi.vulkan;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.util.VulkanChecks;
import com.javelin.rhi.vulkan.util.VulkanFormats;
import org.lwjgl.system.MemoryStack;

import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Vulkan 图形管线实现 */
public class VulkanGraphicsPipeline extends GraphicsPipeline {

    private final VkDevice device;
    private final long handle;
    private final PipelineLayout layout;

    private final com.javelin.rhi.api.Shader vertexShader;
    private final com.javelin.rhi.api.Shader fragmentShader;
    private final com.javelin.rhi.api.Shader geometryShader;
    private final com.javelin.rhi.api.Shader tessellationControlShader;
    private final com.javelin.rhi.api.Shader tessellationEvaluationShader;

    private static final org.slf4j.Logger pipelineLog = org.slf4j.LoggerFactory.getLogger(VulkanGraphicsPipeline.class);

    public VulkanGraphicsPipeline(VulkanDevice device, GraphicsPipelineDesc desc) {
        this.device = device.vkDevice();
        this.layout = device.createPipelineLayout(desc.layout());

        // 创建并保存 Shader 引用，防止 GC 过早销毁 Shader Module
        this.vertexShader = desc.vertexShader() != null ? device.createShader(desc.vertexShader()) : null;
        this.fragmentShader = desc.fragmentShader() != null ? device.createShader(desc.fragmentShader()) : null;
        this.geometryShader = desc.geometryShader() != null ? device.createShader(desc.geometryShader()) : null;
        this.tessellationControlShader = desc.tessellationControlShader() != null ? device.createShader(desc.tessellationControlShader()) : null;
        this.tessellationEvaluationShader = desc.tessellationEvaluationShader() != null ? device.createShader(desc.tessellationEvaluationShader()) : null;

        if (desc.vertexShader() != null) {
            pipelineLog.info("VulkanGraphicsPipeline: vertexShader SPIR-V size={} bytes", desc.vertexShader().spirvBytecode().length);
        }
        if (desc.fragmentShader() != null) {
            pipelineLog.info("VulkanGraphicsPipeline: fragmentShader SPIR-V size={} bytes", desc.fragmentShader().spirvBytecode().length);
        }

        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 着色器阶段
            int shaderStageCount = 0;
            if (vertexShader != null) shaderStageCount++;
            if (fragmentShader != null) shaderStageCount++;
            if (geometryShader != null) shaderStageCount++;
            if (tessellationControlShader != null) shaderStageCount++;
            if (tessellationEvaluationShader != null) shaderStageCount++;

            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(shaderStageCount, stack);
            int stageIdx = 0;

            if (vertexShader != null) {
                stages.get(stageIdx++)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_VERTEX_BIT)
                    .module(vertexShader.handle())
                    .pName(stack.UTF8(desc.vertexShader().entryPoint()));
            }
            if (tessellationControlShader != null) {
                stages.get(stageIdx++)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT)
                    .module(tessellationControlShader.handle())
                    .pName(stack.UTF8(desc.tessellationControlShader().entryPoint()));
            }
            if (tessellationEvaluationShader != null) {
                stages.get(stageIdx++)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT)
                    .module(tessellationEvaluationShader.handle())
                    .pName(stack.UTF8(desc.tessellationEvaluationShader().entryPoint()));
            }
            if (geometryShader != null) {
                stages.get(stageIdx++)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_GEOMETRY_BIT)
                    .module(geometryShader.handle())
                    .pName(stack.UTF8(desc.geometryShader().entryPoint()));
            }
            if (fragmentShader != null) {
                stages.get(stageIdx++)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO)
                    .stage(VK_SHADER_STAGE_FRAGMENT_BIT)
                    .module(fragmentShader.handle())
                    .pName(stack.UTF8(desc.fragmentShader().entryPoint()));
            }

            // 顶点输入
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO);

            if (desc.vertexLayout() != null && desc.vertexLayout().attributes().length > 0) {
                VertexLayout layout = desc.vertexLayout();
                VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(1, stack);
                bindings.get(0)
                    .binding(0)
                    .stride(layout.stride())
                    .inputRate(VK_VERTEX_INPUT_RATE_VERTEX);

                VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(layout.attributes().length, stack);
                for (int i = 0; i < layout.attributes().length; i++) {
                    VertexLayout.VertexAttribute attr = layout.attributes()[i];
                    attributes.get(i)
                        .location(attr.location())
                        .binding(attr.binding())
                        .format(VulkanFormats.toVkVertexFormat(attr.format()))
                        .offset(attr.offset());
                }

                vertexInput
                    .pVertexBindingDescriptions(bindings)
                    .pVertexAttributeDescriptions(attributes);
            }

            // 输入装配
            VkPipelineInputAssemblyStateCreateInfo inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO)
                .topology(VulkanFormats.toVkPrimitiveTopology(desc.topology()))
                .primitiveRestartEnable(false);

            // 视口状态（动态）
            VkPipelineViewportStateCreateInfo viewportState = VkPipelineViewportStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO)
                .viewportCount(1)
                .scissorCount(1);

            // 光栅化
          VkPipelineRasterizationStateCreateInfo rasterizer = VkPipelineRasterizationStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO)
                .depthClampEnable(desc.depthClampEnable())
                .rasterizerDiscardEnable(desc.rasterizerDiscardEnable())
                .polygonMode(VulkanFormats.toVkPolygonMode(desc.polygonMode()))
                .cullMode(VulkanFormats.toVkCullMode(desc.cullMode()))
                .frontFace(VulkanFormats.toVkFrontFace(desc.frontFace()))
                .depthBiasEnable(desc.depthBiasEnable())
                .depthBiasConstantFactor(desc.depthBiasConstant())
                .depthBiasClamp(desc.depthBiasClamp())
                .depthBiasSlopeFactor(desc.depthBiasSlope())
                .lineWidth(desc.lineWidth());

            // 多重采样
            VkPipelineMultisampleStateCreateInfo multisampling = VkPipelineMultisampleStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO)
                .rasterizationSamples(1)
                .sampleShadingEnable(false);

            // 深度/模板
            VkPipelineDepthStencilStateCreateInfo depthStencil = null;
            if (desc.depthStencil() != null) {
                GraphicsPipelineDesc.DepthStencilState ds = desc.depthStencil();
                depthStencil = VkPipelineDepthStencilStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO)
                    .depthTestEnable(ds.depthTestEnable())
                    .depthWriteEnable(ds.depthWriteEnable())
                    .depthCompareOp(VulkanFormats.toVkCompareOp(ds.depthCompareOp()))
                    .depthBoundsTestEnable(ds.depthBoundsTestEnable())
                    .stencilTestEnable(ds.stencilTestEnable())
                    .minDepthBounds(ds.minDepthBounds())
                    .maxDepthBounds(ds.maxDepthBounds());

                if (ds.front() != null) {
                    depthStencil.front()
                        .failOp(VulkanFormats.toVkStencilOp(ds.front().failOp()))
                        .passOp(VulkanFormats.toVkStencilOp(ds.front().passOp()))
                        .depthFailOp(VulkanFormats.toVkStencilOp(ds.front().depthFailOp()))
                        .compareOp(VulkanFormats.toVkCompareOp(ds.front().compareOp()))
                        .compareMask(ds.front().compareMask())
                        .writeMask(ds.front().writeMask())
                        .reference(ds.front().reference());
                }
                if (ds.back() != null) {
                    depthStencil.back()
                        .failOp(VulkanFormats.toVkStencilOp(ds.back().failOp()))
                        .passOp(VulkanFormats.toVkStencilOp(ds.back().passOp()))
                        .depthFailOp(VulkanFormats.toVkStencilOp(ds.back().depthFailOp()))
                        .compareOp(VulkanFormats.toVkCompareOp(ds.back().compareOp()))
                        .compareMask(ds.back().compareMask())
                        .writeMask(ds.back().writeMask())
                        .reference(ds.back().reference());
                }
            }

            // 颜色混合
            VkPipelineColorBlendAttachmentState.Buffer colorBlendAttachments = null;
            if (desc.colorBlendAttachments() != null && desc.colorBlendAttachments().length > 0) {
                colorBlendAttachments = VkPipelineColorBlendAttachmentState.calloc(desc.colorBlendAttachments().length, stack);
                for (int i = 0; i < desc.colorBlendAttachments().length; i++) {
                    GraphicsPipelineDesc.BlendState blend = desc.colorBlendAttachments()[i];
                    colorBlendAttachments.get(i)
                        .blendEnable(blend.blendEnable())
                        .srcColorBlendFactor(VulkanFormats.toVkBlendFactor(blend.srcColorBlendFactor()))
                        .dstColorBlendFactor(VulkanFormats.toVkBlendFactor(blend.dstColorBlendFactor()))
                        .colorBlendOp(VulkanFormats.toVkBlendOp(blend.colorBlendOp()))
                        .srcAlphaBlendFactor(VulkanFormats.toVkBlendFactor(blend.srcAlphaBlendFactor()))
                        .dstAlphaBlendFactor(VulkanFormats.toVkBlendFactor(blend.dstAlphaBlendFactor()))
                        .alphaBlendOp(VulkanFormats.toVkBlendOp(blend.alphaBlendOp()))
                        .colorWriteMask(blend.colorWriteMask());
                }
            } else {
                colorBlendAttachments = VkPipelineColorBlendAttachmentState.calloc(1, stack);
                colorBlendAttachments.get(0)
                    .blendEnable(false)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);
            }

            VkPipelineColorBlendStateCreateInfo colorBlending = VkPipelineColorBlendStateCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO)
                .logicOpEnable(desc.logicOpEnable())
                .logicOp(desc.logicOp() != null ? VulkanFormats.toVkLogicOp(desc.logicOp()) : VK_LOGIC_OP_COPY)
                .pAttachments(colorBlendAttachments)
                .blendConstants(stack.floats(
                    desc.blendConstants()[0], desc.blendConstants()[1],
                    desc.blendConstants()[2], desc.blendConstants()[3]));

            // 动态状态
          VkPipelineDynamicStateCreateInfo dynamicState = null;
            if (desc.dynamicStates() != null && desc.dynamicStates().length > 0) {
                IntBuffer pDynamicStates = stack.mallocInt(desc.dynamicStates().length);
                for (DynamicState state : desc.dynamicStates()) {
                    pDynamicStates.put(toVkDynamicState(state));
                }
                pDynamicStates.flip();
                dynamicState = VkPipelineDynamicStateCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO)
                    .pDynamicStates(pDynamicStates);
            }

            // 管线创建
            VkGraphicsPipelineCreateInfo.Buffer pipelineInfo = VkGraphicsPipelineCreateInfo.calloc(1, stack)
                .sType(VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO)
                .pStages(stages)
                .pVertexInputState(vertexInput)
                .pInputAssemblyState(inputAssembly)
                .pViewportState(viewportState)
                .pRasterizationState(rasterizer)
                .pMultisampleState(multisampling)
                .pDepthStencilState(depthStencil)
                .pColorBlendState(colorBlending)
                .layout(layout.handle())
                .renderPass(VK_NULL_HANDLE)  // Dynamic rendering
                .subpass(0);

            if (dynamicState != null) {
                pipelineInfo.pDynamicState(dynamicState);
            }

            // 动态渲染管线创建需VkPipelineRenderingCreateInfo
            VkPipelineRenderingCreateInfo renderingInfo = VkPipelineRenderingCreateInfo.calloc(stack)
                .sType(VK_STRUCTURE_TYPE_PIPELINE_RENDERING_CREATE_INFO)
                .colorAttachmentCount(1)
                .pColorAttachmentFormats(stack.ints(VK_FORMAT_B8G8R8A8_UNORM));

            if (desc.depthStencil() != null && desc.depthStencil().depthTestEnable()) {
                renderingInfo.depthAttachmentFormat(VK_FORMAT_D32_SFLOAT);
            }

            pipelineInfo.pNext(renderingInfo.address());

            LongBuffer pPipeline = stack.mallocLong(1);
            int result = vkCreateGraphicsPipelines(device.vkDevice(), VK_NULL_HANDLE, pipelineInfo, null, pPipeline);
            if (result != VK_SUCCESS) {
                pipelineLog.error("vkCreateGraphicsPipelines FAILED with result={}", result);
                throw new RuntimeException("vkCreateGraphicsPipelines failed: " + result);
            }
            this.handle = pPipeline.get(0);
            pipelineLog.info("VulkanGraphicsPipeline: Pipeline created successfully, handle={}", this.handle);
        }
    }

    private int toVkDynamicState(DynamicState state) {
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
            case CULL_MODE -> 1000267000; // VK_DYNAMIC_STATE_CULL_MODE_EXT
            case FRONT_FACE -> 1000267001; // VK_DYNAMIC_STATE_FRONT_FACE_EXT
            case PRIMITIVE_TOPOLOGY -> 1000267002; // VK_DYNAMIC_STATE_PRIMITIVE_TOPOLOGY_EXT
            case VIEWPORT_WITH_COUNT -> 1000267003; // VK_DYNAMIC_STATE_VIEWPORT_WITH_COUNT_EXT
            case SCISSOR_WITH_COUNT -> 1000267004; // VK_DYNAMIC_STATE_SCISSOR_WITH_COUNT_EXT
            case VERTEX_INPUT_BINDING_STRIDE -> 1000267005; // VK_DYNAMIC_STATE_VERTEX_INPUT_BINDING_STRIDE_EXT
            case DEPTH_TEST_ENABLE -> 1000267006; // VK_DYNAMIC_STATE_DEPTH_TEST_ENABLE_EXT
            case DEPTH_WRITE_ENABLE -> 1000267007; // VK_DYNAMIC_STATE_DEPTH_WRITE_ENABLE_EXT
            case DEPTH_COMPARE_OP -> 1000267008; // VK_DYNAMIC_STATE_DEPTH_COMPARE_OP_EXT
            case DEPTH_BOUNDS_TEST_ENABLE -> 1000267009; // VK_DYNAMIC_STATE_DEPTH_BOUNDS_TEST_ENABLE_EXT
            case STENCIL_TEST_ENABLE -> 1000267010; // VK_DYNAMIC_STATE_STENCIL_TEST_ENABLE_EXT
            case STENCIL_OP -> 1000267011; // VK_DYNAMIC_STATE_STENCIL_OP_EXT
            case RASTERIZER_DISCARD_ENABLE -> 1000267012; // VK_DYNAMIC_STATE_RASTERIZER_DISCARD_ENABLE_EXT
            case DEPTH_BIAS_ENABLE -> 1000267013; // VK_DYNAMIC_STATE_DEPTH_BIAS_ENABLE_EXT
            default -> throw new IllegalArgumentException("Unknown dynamic state: " + state);
        };
    }

    @Override
    public long handle() {
        return handle;
    }

    @Override
    protected void doDispose() {
        vkDestroyPipeline(device, handle, null);
        layout.dispose();
        if (vertexShader != null) vertexShader.dispose();
        if (fragmentShader != null) fragmentShader.dispose();
        if (geometryShader != null) geometryShader.dispose();
        if (tessellationControlShader != null) tessellationControlShader.dispose();
        if (tessellationEvaluationShader != null) tessellationEvaluationShader.dispose();
    }

    @Override
    public PipelineLayout layout() {
        return layout;
    }
}
