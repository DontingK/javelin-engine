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
 * G-Buffer 可视化覆盖 Pass（编辑器排障面，dbg-2，Engine_DebugTools_Plan §3；覆盖制——
 * LightingPass/SkyboxPass 同族先例）。
 *
 * <p><b>形态</b>：全屏大三角形（与 core {@code LightingPass} 同款 NDC 顶点），把目标视口
 * （编辑器相机）的 G-Buffer 附件（albedo / 世界法线 / 世界位置）按 {@code gl_FragCoord}
 * 绝对坐标 1:1 贴回本视口；覆盖制 LOAD，不消费材质 Pass 声明。着色器走资产导入链
 * （内置 {@code builtin-asset-editor/debug/gbufferview.vert/.frag}），<b>不带</b>光照
 * 注入标记——只采样三选一，无光照注入。</p>
 *
 * <p><b>模式通道</b>：{@code int mode} 走 push constants（4B，ShadowPass 专属 push 布局
 * 同法），三态选一；描述符为本 Pass 专属 set0（binding0/1/2 = 三张 G-Buffer 采样器，
 * debugtext.frag 自建 set0 先例）——<b>不依赖 set1/set2</b>，与照明/材质描述符布局完全
 * 解耦。附件换代（resize）即重建描述符集。</p>
 *
 * <p><b>挂载</b>：由 {@link GBufferDebugAppState} 按需 {@code addPass} 尾追加，仅 HYBRID
 * 档有消费意义（FORWARD 档 G-Buffer 不写，AppState 侧拒绝切 mode）。只作用于
 * {@code targetView}（编辑器相机视口），GAME 视口不进图。</p>
 */
public class GBufferViewPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(GBufferViewPass.class);

    /** 内置可视化着色器 loadAsset 路径（builtin-asset-editor/debug 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "editor/debug/gbufferview.vert";
    private static final String FRAG_SHADER_PATH = "editor/debug/gbufferview.frag";

    /** push 常量：int mode（4B，仅 fs 消费；管线 layout 的 stageFlags=ALL_GRAPHICS 覆盖） */
    private static final int PUSH_SIZE = Integer.BYTES;

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
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
    /** 自建 set0 布局 + 描述符集（三张 G-Buffer 采样器；附件换代重建） */
    private final DescriptorSetLayoutDesc samplerSetDesc;
    private DescriptorSetLayout samplerSetLayout;
    private DescriptorSet samplerSet;
    /** 描述符集缓存的附件身份（view 名 + 三张纹理；任一变化即重建） */
    private String setViewName;
    private Texture setAlbedo;
    private Texture setNormal;
    private Texture setWorldPos;
    /** 覆盖制 PSO 缓存：key = 颜色附件格式 */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final ByteBuffer modeBuffer = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 当前可视化模式（0=关闭 / 1=albedo / 2=世界法线 / 3=世界位置） */
    private volatile int mode;
    private boolean drawLogged;

    /**
     * @param device          RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager    资产管理器（装载内置 gbufferview.vert/.frag 的 SpvShader 产物）
     * @param renderingSystem 渲染系统（G-Buffer 附件与采样器来源）
     * @param targetView      目标视口名（编辑器相机视口；其余视口不进图）
     */
    public GBufferViewPass(Device device, AssetManager assetManager,
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
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /** 切换可视化模式（0=关闭；>0 才进图） */
    public void setMode(int mode) {
        this.mode = mode;
    }

    @Override
    public String name() {
        return "GBufferView";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "GBufferView";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        int current = mode;
        if (current == 0) {
            return; // 关闭态：不进图（图结构稳定，无调试节点）
        }
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return; // 只作用于编辑器相机视口（GAME 视口等不进图）
        }
        RenderingSystem rs = ctx.renderingSystem();
        Texture target = ctx.colorTarget();
        Texture albedo = rs.gbufferAlbedoFor(view.name());
        Texture normal = rs.gbufferNormalFor(view.name());
        Texture worldPos = rs.gbufferWorldPosFor(view.name());
        Format colorFormat = target.desc().format();

        graph.addGraphicsPass(view.name() + "_GBufferView", pass -> {
            // 覆盖制：保留场景画面，仅在视口处贴回调试附件
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            // 读声明：G-Buffer 三张（建 GBuffer→本 Pass 边 + SHADER_READ_ONLY 屏障）
            pass.addTextureRead(albedo);
            pass.addTextureRead(normal);
            pass.addTextureRead(worldPos);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 GBufferView 回调执行: 视口[{}] 模式={}", view.name(), current);
                }
                cmd.bindPipeline(pipelineFor(colorFormat));
                cmd.bindDescriptorSet(0, samplerSetFor(view.name(), albedo, normal, worldPos),
                        0, null);
                modeBuffer.clear();
                modeBuffer.putInt(current);
                modeBuffer.flip();
                cmd.pushConstants(0, modeBuffer);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 本 Pass 专属 set0：三张 G-Buffer 采样器（附件换代即重建，旧集显式释放） */
    private DescriptorSet samplerSetFor(String viewName, Texture albedo, Texture normal,
                                        Texture worldPos) {
        if (samplerSet != null && viewName.equals(setViewName) && albedo == setAlbedo
                && normal == setNormal && worldPos == setWorldPos) {
            return samplerSet;
        }
        if (samplerSetLayout == null) {
            samplerSetLayout = device.createDescriptorSetLayout(samplerSetDesc);
        }
        if (samplerSet != null) {
            samplerSet.dispose();
        }
        Sampler sampler = renderingSystem.gbufferSampler();
        samplerSet = device.createDescriptorSet(samplerSetLayout);
        samplerSet.update(
                DescriptorUpdate.sampledTexture(0, albedo, sampler),
                DescriptorUpdate.sampledTexture(1, normal, sampler),
                DescriptorUpdate.sampledTexture(2, worldPos, sampler));
        setViewName = viewName;
        setAlbedo = albedo;
        setNormal = normal;
        setWorldPos = worldPos;
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
                CullMode.NONE,                            // 全屏三角形绕序无关
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,             // 无深度附件，深度态关
                false,                                    // blend Off（不透明整块覆盖）
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,               // 无深度附件（depthAttachmentFormat=null）
                List.of(samplerSetDesc), PUSH_SIZE,       // set0 三采样器 + push int mode
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("gbufferview PSO created: colorFormat={}", colorFormat);
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

    /**
     * 装载内置 SPIR-V 着色器资产（红线 15——缺失/坏产物显性报错不静默兜底）。
     */
    private static byte[] loadSpv(AssetManager assetManager, String path) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException(EditorLang.text("gbufferView.assetMissing", path));
        }
        if (!spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("gbufferView.assetInvalid", path));
        }
        return spv.getSpvData();
    }
}
