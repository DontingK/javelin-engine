package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.JavelinEngine;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.ddgi.DdgiAtlases;
import cn.donting.javelin.core.render.ddgi.DdgiProbePass;
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
 * DDGI 探针 atlas 直显覆盖 Pass（编辑器排障面 dbg-3，Engine_DDGI_ProbeViz_Design §3 V2）。
 *
 * <p><b>形态</b>：全屏大三角形（与 {@link GBufferViewPass} 同款），把 DDGI 的一张 atlas
 * （辐照度 / 深度，按 mode 二选一）整张贴回编辑器相机视口；片元按 atlas 像素 1:1
 * {@code texelFetch}，并画 tile 分隔线（未扫过 = 红框）。<b>零 CPU 回读</b>——不碰
 * {@code TextureReadbackRing}，只走 GPU 采样。</p>
 *
 * <p><b>模式通道</b>：{@code int mode + float scale} 走 push constants（8B）；set0 为本 Pass
 * 专属单采样器（atlas 换代即重建描述符集），<b>不依赖 set1/set2</b>。</p>
 *
 * <p><b>挂载</b>：由 {@link DdgiProbeVizAppState} 按需 {@code addPass} 尾追加；DDGI 未就绪
 * （pass 为 null / atlas 为 null）时本 Pass 不进图（AppState 侧写提示行）。只作用于
 * {@code targetView}（编辑器相机视口）。</p>
 */
public class DdgiProbeViewPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(DdgiProbeViewPass.class);

    /** 顶点着色器复用 gbufferview.vert（全屏三角，与图无关），只新增片元 */
    private static final String VERT_SHADER_PATH = "editor/debug/gbufferview.vert";
    private static final String FRAG_SHADER_PATH = "editor/debug/ddgiView.frag";

    /** push 常量：int mode + float scale（8B） */
    private static final int PUSH_SIZE = Integer.BYTES + Float.BYTES;

    /** 全屏大三角形 NDC 顶点（vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private final Device device;
    private final JavelinEngine engine;
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
    /** 覆盖制 PSO 缓存：key = 颜色附件格式 */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final ByteBuffer pushBuffer = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 当前视图模式（0=关闭 / 2=辐照度 atlas / 3=深度 atlas；1=网格由 DebugDrawLine 走） */
    private volatile int mode;

    /**
     * @param device      RHI 设备
     * @param assetManager 资产管理器（装载内置 ddgiView.frag 的 SpvShader 产物）
     * @param engine      引擎实例（经 {@code ddgiProbePass()} 取 atlas；只读）
     * @param targetView  目标视口名（编辑器相机视口）
     */
    public DdgiProbeViewPass(Device device, AssetManager assetManager, JavelinEngine engine,
                            String targetView) {
        this.device = device;
        this.engine = engine;
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

    /** 切换视图模式（0=关闭；2/3 才进图） */
    public void setMode(int mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        return "DdgiProbeView";
    }

    @Override
    public String shaderPassName() {
        return "DdgiProbeView";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        int current = mode;
        if (current != DdgiProbeVizAppState.MODE_IRRADIANCE
                && current != DdgiProbeVizAppState.MODE_DEPTH) {
            return; // 关闭 / 网格视图：不进图
        }
        DdgiProbePass probePass = engine.ddgiProbePass();
        DdgiAtlases atlases = probePass == null ? null : probePass.atlases();
        if (atlases == null) {
            return; // 数据面未就绪：AppState 侧已写提示行，这里静默跳过本帧
        }
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return; // 只作用于编辑器相机视口
        }
        Texture atlas = current == DdgiProbeVizAppState.MODE_DEPTH
                ? atlases.depth() : atlases.irradiance();
        Texture target = ctx.colorTarget();
        Format colorFormat = target.desc().format();

        graph.addGraphicsPass(view.name() + "_DdgiProbeView", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addTextureRead(atlas);   // 建 compute→本 Pass 边 + SHADER_READ_ONLY 屏障
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                cmd.bindPipeline(pipelineFor(colorFormat));
                cmd.bindDescriptorSet(0, samplerSetFor(atlas), 0, null);
                pushBuffer.clear();
                pushBuffer.putInt(current);
                pushBuffer.putFloat(scaleFor(current));
                pushBuffer.flip();
                cmd.pushConstants(0, pushBuffer);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 归一化系数：辐照度恒 1（片元内部除 π）；深度 1/MAX_RAY_DISTANCE */
    private static float scaleFor(int mode) {
        return mode == DdgiProbeVizAppState.MODE_DEPTH
                ? 1f / DdgiProbePass.MAX_RAY_DISTANCE : 1f;
    }

    /** 本 Pass 专属 set0：单张 atlas 采样器（换代即重建，旧集释放） */
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
        log.info("ddgiView PSO created: colorFormat={}", colorFormat);
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

    /** 装载内置 SPIR-V 着色器资产（红线 15——缺失/坏产物显性报错，不静默兜底） */
    private static byte[] loadSpv(AssetManager assetManager, String path) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException(EditorLang.text("ddgiProbeView.assetMissing", path));
        }
        if (!spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("ddgiProbeView.assetInvalid", path));
        }
        return spv.getSpvData();
    }
}
