package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.ColorComponentFlags;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.DynamicState;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 阴影图（CSM atlas）直显覆盖 Pass（编辑器排障面 DBG-03，Engine_Shadow_Quality_Design §4.7）。
 *
 * <p><b>形态</b>：全屏大三角形（{@link GBufferViewPass} 同款），把该视口的 CSM 深度 atlas
 * 贴回编辑器相机视口；片元按 atlas 像素 1:1 {@code texelFetch} 直读深度（<b>不走比较采样</b>
 * ——比较采样只给 0/1 可见性，看不出纹素吸附与漏光），并在 2×2 版面画象限分隔线。</p>
 *
 * <p><b>模式</b>：push {@code {int mode, int grid}}（8B）——mode 1 原始深度灰度（远=白、
 * 遮挡体=暗块）、mode 2 按象限给级联分色。set0 为本 Pass 专属单采样器（atlas 换代即重建）。</p>
 *
 * <p><b>挂载</b>：由 {@link ShadowMapDebugAppState} 按需 {@code addPass} 尾追加；该视口
 * 无阴影资源（关阴影 / 无主光 / 未建）时本 Pass 不进图（AppState 侧写提示行）。</p>
 */
public class ShadowMapViewPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(ShadowMapViewPass.class);

    /** 顶点复用 gbufferview.vert（全屏三角），只新增片元 */
    private static final String VERT_SHADER_PATH = "editor/debug/gbufferview.vert";
    private static final String FRAG_SHADER_PATH = "editor/debug/shadowview.frag";

    /** push 常量：int mode + int grid + vec4 viewport(x,y,w,h)（24B；视口尺寸与 atlas 尺寸不同，须按视口归一化） */
    private static final int PUSH_SIZE = Integer.BYTES * 2 + Float.BYTES * 4;

    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private final Device device;
    private final RenderingSystem renderingSystem;
    private final String targetView;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    private final DescriptorSetLayoutDesc samplerSetDesc;
    private DescriptorSetLayout samplerSetLayout;
    private DescriptorSet samplerSet;
    /** 描述符集缓存的 atlas 身份（换图即重建） */
    private Texture setAtlas;
    private Sampler nearestSampler;
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final ByteBuffer pushBuffer = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 当前视图模式（0=关闭 / 1=原始深度 / 2=级联分色） */
    private volatile int mode;

    public ShadowMapViewPass(Device device, AssetManager assetManager,
                            RenderingSystem renderingSystem, String targetView) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.targetView = targetView;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH);
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH);
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        this.samplerSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    public void setMode(int mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        return "ShadowMapView";
    }

    @Override
    public String shaderPassName() {
        return "ShadowMapView";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        int current = mode;
        if (current != ShadowMapDebugAppState.MODE_DEPTH
                && current != ShadowMapDebugAppState.MODE_CASCADE) {
            return;
        }
        Texture atlas = renderingSystem.shadowMapOrNull(targetView);
        if (atlas == null) {
            return; // 无阴影资源：AppState 侧已写提示行，这里静默跳过本帧
        }
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return;
        }
        int grid = renderingSystem.shadowAtlasGrid(view.name());
        Texture target = ctx.colorTarget();
        Format colorFormat = target.desc().format();

        graph.addGraphicsPass(view.name() + "_ShadowMapView", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addTextureRead(atlas);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                cmd.bindPipeline(pipelineFor(colorFormat));
                cmd.bindDescriptorSet(0, samplerSetFor(atlas), 0, null);
                pushBuffer.clear();
                pushBuffer.putInt(current);
                pushBuffer.putInt(Math.max(1, grid));
                pushBuffer.putFloat(view.viewport().x());
                pushBuffer.putFloat(view.viewport().y());
                pushBuffer.putFloat(view.viewport().width());
                pushBuffer.putFloat(view.viewport().height());
                pushBuffer.flip();
                cmd.pushConstants(0, pushBuffer);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 本 Pass 专属 set0：单张深度 atlas 采样器（NEAREST、非比较——只读深度值） */
    private DescriptorSet samplerSetFor(Texture atlas) {
        if (samplerSet != null && atlas == setAtlas) {
            return samplerSet;
        }
        if (samplerSetLayout == null) {
            samplerSetLayout = device.createDescriptorSetLayout(samplerSetDesc);
        }
        if (samplerSet != null) {
            samplerSet.dispose();
        }
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(SamplerDesc.nearest());
        }
        samplerSet = device.createDescriptorSet(samplerSetLayout);
        samplerSet.update(DescriptorUpdate.sampledTexture(0, atlas, nearestSampler));
        setAtlas = atlas;
        return samplerSet;
    }

    private GraphicsPipeline pipelineFor(Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                triangleLayout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,
                false,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,
                List.of(samplerSetDesc), PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("shadowview PSO created: colorFormat={}", colorFormat);
        return created;
    }

    private Buffer createVertexBuffer() {
        ByteBuffer data = ByteBuffer.allocateDirect(TRIANGLE_VERTICES.length * Float.BYTES)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (float v : TRIANGLE_VERTICES) {
            data.putFloat(v);
        }
        data.flip();
        return device.createBuffer(new BufferDesc(data.remaining(),
                Set.of(BufferUsage.VERTEX), MemoryPreference.hostVisibleCoherent()),
                UploadData.of(data));
    }

    private static byte[] loadSpv(AssetManager assetManager, String path) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException(EditorLang.text("shadowView.assetMissing", path));
        }
        if (!spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("shadowView.assetInvalid", path));
        }
        return spv.getSpvData();
    }
}
