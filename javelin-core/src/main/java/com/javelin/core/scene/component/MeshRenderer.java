package com.javelin.core.scene.component;

import com.javelin.core.render.*;
import com.javelin.core.render.material.*;
import com.javelin.core.render.mesh.Mesh;
import com.javelin.core.scene.Component;
import com.javelin.core.scene.Node;
import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Mesh 渲染组件 —— 将 {@link Mesh} + {@link Material} 转换为渲染命令。
 *
 * <p>实现 {@link Renderable} 接口，被场景系统收集并提交到渲染管线。</p>
 *
 * <p><strong>资源共享</strong>：
 * <ul>
 *   <li>{@link Mesh} 是共享资产，可被多个 MeshRenderer 引用</li>
 *   <li>{@link Material} 是共享资产，可被多个 MeshRenderer 引用</li>
 *   <li>{@link MaterialInstance} 是每个 MeshRenderer 独立的运行时实例</li>
 * </ul>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public class MeshRenderer extends Component implements Renderable {

    private static final Logger log = LoggerFactory.getLogger(MeshRenderer.class);

    /** 共享几何资产 */
    private Mesh mesh;

    /** 共享材质资产 */
    private Material material;

    /** 是否需要重建 Pipeline */
    private boolean pipelineDirty = true;

    // ═══════════════════════════════════════════════════════════════════════
    //  Pass 条目 —— 每个 ShaderPass 对应一个
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Pass 渲染条目 —— 包含单个 Pass 的 GPU 资源。
     */
    private static class PassEntry {
        /** 对应的 ShaderPass */
        ShaderPass shaderPass;

        /** 图形管线 */
        GraphicsPipeline pipeline;

        /** 管线布局 */
        PipelineLayout pipelineLayout;

        /** 材质实例（独立的 UBO + DescriptorSet） */
        MaterialInstance materialInstance;

        PassEntry(ShaderPass shaderPass) {
            this.shaderPass = shaderPass;
        }

        void dispose() {
            if (materialInstance != null) {
                materialInstance.dispose();
                materialInstance = null;
            }
            if (pipeline != null) {
                pipeline.dispose();
                pipeline = null;
            }
            if (pipelineLayout != null) {
                pipelineLayout.dispose();
                pipelineLayout = null;
            }
        }
    }

    /** 所有 Pass 的条目（按 Shader 中 Pass 顺序） */
    private PassEntry[] passEntries = new PassEntry[0];

    // ═══════════════════════════════════════════════════════════════════════
    //  构造
    // ═══════════════════════════════════════════════════════════════════════

    public MeshRenderer() {
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  生命周期
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    protected void onAttach(Node node) {
        super.onAttach(node);
        markPipelineDirty();
    }

    @Override
    protected void onEnable() {
        super.onEnable();
        markPipelineDirty();
    }

    @Override
    protected void onDetach() {
        super.onDetach();
        // 释放所有 Pass 的 GPU 资源
        for (PassEntry entry : passEntries) {
            entry.dispose();
        }
        passEntries = new PassEntry[0];
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Renderable 实现
    // ═══════════════════════════════════════════════════════════════════════

    @Override
    public void prepare(Device device, DescriptorPoolManager descriptorPoolManager) {
        // 先确保 Mesh 数据已上传 GPU
        if (mesh != null && !mesh.isUploaded()) {
            log.info("MeshRenderer.prepare: uploading mesh to GPU, mesh={}", mesh);
            mesh.uploadToGpu(device);
            log.info("MeshRenderer.prepare: mesh uploaded, vertexBuffer={}, indexBuffer={}", mesh.getVertexBuffer(), mesh.getIndexBuffer());
        }

        if (pipelineDirty || passEntries.length == 0) {
            log.info("MeshRenderer.prepare: building pipeline, pipelineDirty={}, passEntries.length={}", pipelineDirty, passEntries.length);
            buildPipeline(device, descriptorPoolManager);
        }
    }

    @Override
    public void collect(List<RenderCommand> outCommands) {
        if (mesh == null || material == null) {
            log.warn("MeshRenderer.collect: mesh or material is null, mesh={}, material={}", mesh, material);
            return;
        }

        // 确保 Pipeline 已构建
        if (pipelineDirty || passEntries.length == 0) {
            log.warn("MeshRenderer.collect: Pipeline not built, skipping collect, pipelineDirty={}, passEntries.length={}", pipelineDirty, passEntries.length);
            return;
        }

        Node node = node();
        if (node == null) {
            log.warn("MeshRenderer.collect: node is null");
            return;
        }

        // 获取世界矩阵
        Matrix4f modelMatrix = node.transform().worldMatrix();
        log.info("MeshRenderer.collect: generating RenderCommand, mesh={}, vertexCount={}, indexCount={}, modelMatrix={}", mesh, mesh.getVertexCount(), mesh.getIndexCount(), modelMatrix);

        // 每个 Pass 生成一个 RenderCommand
        for (PassEntry entry : passEntries) {
            if (entry.pipeline == null) {
                log.warn("MeshRenderer.collect: entry.pipeline is null, skipping");
                continue;
            }

            RenderCommand cmd = new RenderCommand();
            cmd.pipeline = entry.pipeline;
            cmd.vertexBuffer = mesh.getVertexBuffer();
            cmd.vertexCount = mesh.getVertexCount();
            cmd.indexBuffer = mesh.getIndexBuffer();
            cmd.indexCount = mesh.getIndexCount();
            cmd.indexType = mesh.getIndexType();
            cmd.modelMatrix.set(modelMatrix);

            // 描述符集
            if (entry.materialInstance != null) {
                entry.materialInstance.uploadUboData();
                cmd.descriptorSets = new DescriptorSet[]{entry.materialInstance.getDescriptorSet()};
            }

            // 临时：把 pipelineLayout 传给 RenderCommand，用于 ForwardRenderPass push constants
            cmd.pipelineLayout = entry.pipelineLayout;

            cmd.renderLayer = determineRenderLayer(entry.shaderPass);

            log.info("MeshRenderer.collect: adding RenderCommand, pipeline={}, vertexBuffer={}, indexBuffer={}, renderLayer={}", cmd.pipeline, cmd.vertexBuffer, cmd.indexBuffer, cmd.renderLayer);
            outCommands.add(cmd);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Pipeline 构建
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * 重建 Pipeline 和 DescriptorSet。
     *
     * @param device                逻辑设备
     * @param descriptorPoolManager 描述符池管理器
     */
    public void buildPipeline(Device device, DescriptorPoolManager descriptorPoolManager) {
        if (mesh == null || material == null) {
            return;
        }

        com.javelin.core.render.material.Shader shader = material.getShader();
        if (shader == null || shader.getPasses().isEmpty()) {
            log.warn("Material has no shader or empty passes");
            return;
        }

        // 清理旧的 Pass 条目
        for (PassEntry old : passEntries) {
            old.dispose();
        }

        List<ShaderPass> passes = shader.getPasses();
        passEntries = new PassEntry[passes.size()];

        // 为每个 Pass 构建 GPU 资源
        for (int i = 0; i < passes.size(); i++) {
            ShaderPass pass = passes.get(i);
            PassEntry entry = new PassEntry(pass);
            passEntries[i] = entry;

            // 构建 PipelineLayout
            DescriptorSetLayoutDesc uboLayoutDesc = new DescriptorSetLayoutDesc(
                new DescriptorSetLayoutDesc.Binding[]{
                    new DescriptorSetLayoutDesc.Binding(
                        0, DescriptorType.UNIFORM_BUFFER, ShaderStage.VERTEX
                    )
                }
            );

            PipelineLayoutDesc layoutDesc = new PipelineLayoutDesc(
                new DescriptorSetLayoutDesc[]{uboLayoutDesc},
                new PipelineLayoutDesc.PushConstantRange[0]
            );
            entry.pipelineLayout = device.createPipelineLayout(layoutDesc);

            // 构建 GraphicsPipeline
            VertexLayout vertexLayout = mesh.buildVertexLayout();
            log.info("MeshRenderer.buildPipeline: vertexLayout stride={}, attributes={}", vertexLayout.stride(), vertexLayout.attributes().length);
            GraphicsPipelineDesc pipelineDesc = buildPipelineDesc(pass, vertexLayout, layoutDesc);
            log.info("MeshRenderer.buildPipeline: creating pipeline, vertexShaderSize={}, fragmentShaderSize={}",
                pass.getVertexBytecode() != null ? pass.getVertexBytecode().length : -1,
                pass.getFragmentBytecode() != null ? pass.getFragmentBytecode().length : -1);
            entry.pipeline = device.createGraphicsPipeline(pipelineDesc);
            log.info("MeshRenderer.buildPipeline: pipeline created successfully");

            // 创建 MaterialInstance
            entry.materialInstance = new MaterialInstance(material);
            if (descriptorPoolManager != null && entry.pipelineLayout != null) {
                entry.materialInstance.build(device, descriptorPoolManager.getDescriptorPool(), entry.pipelineLayout);
            }
        }

        pipelineDirty = false;
    }

    private GraphicsPipelineDesc buildPipelineDesc(ShaderPass pass, VertexLayout vertexLayout, PipelineLayoutDesc pipelineLayoutDesc) {
        // 着色器
        ShaderDesc vertexShader = new ShaderDesc(
            ShaderStage.VERTEX,
            pass.getVertexBytecode()
        );
        ShaderDesc fragmentShader = new ShaderDesc(
            ShaderStage.FRAGMENT,
            pass.getFragmentBytecode()
        );

        // 混合状态
        BlendDesc blend = pass.getBlend();
        GraphicsPipelineDesc.BlendState[] blendStates = new GraphicsPipelineDesc.BlendState[1];
        if (blend != null && blend.isEnabled()) {
            blendStates[0] = new GraphicsPipelineDesc.BlendState(
                true,
                mapBlendFactor(blend.getSrcFactor()),
                mapBlendFactor(blend.getDstFactor()),
                BlendOp.ADD,
                com.javelin.rhi.types.BlendFactor.ONE,
                com.javelin.rhi.types.BlendFactor.ZERO,
                BlendOp.ADD,
                ColorComponentFlags.ALL
            );
        } else {
            blendStates[0] = GraphicsPipelineDesc.BlendState.opaque();
        }

        // 深度模板
        GraphicsPipelineDesc.DepthStencilState depthStencil = new GraphicsPipelineDesc.DepthStencilState(
            pass.getZTest() != CompareFunction.Off,
            pass.isZWrite(),
            mapCompareOp(pass.getZTest()),
            false, 0, 1,
            false, null, null
        );

        return new GraphicsPipelineDesc(
            vertexShader,
            fragmentShader,
            null, null, null,
            vertexLayout,
            PrimitiveTopology.TRIANGLE_LIST,
            PolygonMode.FILL,
            com.javelin.rhi.types.CullMode.NONE,
            FrontFace.COUNTER_CLOCKWISE,
            1.0f,
            false, false, false, 0, 0, 0,
            SampleCount.COUNT_1,
            false, 0, 0, false, false,
            depthStencil,
            blendStates,
            false, LogicOp.COPY, new float[]{0, 0, 0, 0},
            new DynamicState[]{DynamicState.VIEWPORT, DynamicState.SCISSOR},
            pipelineLayoutDesc,
            null
        );
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  状态管理
    // ═══════════════════════════════════════════════════════════════════════

    public void setMesh(Mesh mesh) {
        this.mesh = mesh;
        markPipelineDirty();
    }

    public Mesh getMesh() {
        return mesh;
    }

    public void setMaterial(Material material) {
        this.material = material;
        markPipelineDirty();
    }

    public Material getMaterial() {
        return material;
    }

    /**
     * 获取指定 Pass 的材质实例（单 Pass 时 passIndex=0）。
     */
    public MaterialInstance getMaterialInstance(int passIndex) {
        if (passIndex < 0 || passIndex >= passEntries.length) return null;
        return passEntries[passIndex].materialInstance;
    }

    /**
     * 获取指定 Pass 的管线（单 Pass 时 passIndex=0）。
     */
    public GraphicsPipeline getPipeline(int passIndex) {
        if (passIndex < 0 || passIndex >= passEntries.length) return null;
        return passEntries[passIndex].pipeline;
    }

    public void markPipelineDirty() {
        pipelineDirty = true;
    }

    public boolean isPipelineDirty() {
        return pipelineDirty;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  辅助方法
    // ═══════════════════════════════════════════════════════════════════════

    private com.javelin.core.render.RenderLayer determineRenderLayer(ShaderPass pass) {
        if (pass == null) return com.javelin.core.render.RenderLayer.OPAQUE;

        return switch (pass.getRenderLayer()) {
            case Opaque -> com.javelin.core.render.RenderLayer.OPAQUE;
            case Transparent -> com.javelin.core.render.RenderLayer.TRANSPARENT;
            default -> com.javelin.core.render.RenderLayer.OPAQUE;
        };
    }

    // 枚举映射
    private static com.javelin.rhi.types.CullMode mapCullMode(com.javelin.core.render.material.CullMode cullMode) {
        if (cullMode == null) return com.javelin.rhi.types.CullMode.BACK;
        return switch (cullMode) {
            case Back -> com.javelin.rhi.types.CullMode.BACK;
            case Front -> com.javelin.rhi.types.CullMode.FRONT;
            case None -> com.javelin.rhi.types.CullMode.NONE;
        };
    }

    private static CompareOp mapCompareOp(CompareFunction func) {
        if (func == null) return CompareOp.LESS;
        return switch (func) {
            case Off -> CompareOp.ALWAYS;
            case Never -> CompareOp.NEVER;
            case Less -> CompareOp.LESS;
            case LessEqual -> CompareOp.LESS_OR_EQUAL;
            case Equal -> CompareOp.EQUAL;
            case Greater -> CompareOp.GREATER;
            case GreaterEqual -> CompareOp.GREATER_OR_EQUAL;
            case Always -> CompareOp.ALWAYS;
        };
    }

    private static com.javelin.rhi.types.BlendFactor mapBlendFactor(com.javelin.core.render.material.BlendFactor factor) {
        if (factor == null) return com.javelin.rhi.types.BlendFactor.ONE;
        return switch (factor) {
            case Zero -> com.javelin.rhi.types.BlendFactor.ZERO;
            case One -> com.javelin.rhi.types.BlendFactor.ONE;
            case SrcColor -> com.javelin.rhi.types.BlendFactor.SRC_COLOR;
            case OneMinusSrcColor -> com.javelin.rhi.types.BlendFactor.ONE_MINUS_SRC_COLOR;
            case DstColor -> com.javelin.rhi.types.BlendFactor.DST_COLOR;
            case OneMinusDstColor -> com.javelin.rhi.types.BlendFactor.ONE_MINUS_DST_COLOR;
            case SrcAlpha -> com.javelin.rhi.types.BlendFactor.SRC_ALPHA;
            case OneMinusSrcAlpha -> com.javelin.rhi.types.BlendFactor.ONE_MINUS_SRC_ALPHA;
            case DstAlpha -> com.javelin.rhi.types.BlendFactor.DST_ALPHA;
            case OneMinusDstAlpha -> com.javelin.rhi.types.BlendFactor.ONE_MINUS_DST_ALPHA;
            default -> com.javelin.rhi.types.BlendFactor.ONE;
        };
    }
}
