package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.ClearFlags;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.BufferUsage;
import cn.donting.javelin.rhi.types.ColorComponentFlags;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
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
 * 全屏光照 Pass（HYBRID 档不透明着色，Engine_RenderPipeline_Design §4.3/实装形态）。
 *
 * <p><b>覆盖制</b>（SkyboxPass/ShadowPass 同款先例）：不消费材质 Pass 声明，着色器走
 * 资产导入链（内置 {@code builtin-asset-core/lighting/lighting.vert/.frag} 经
 * SpvShaderImporter 编译——frag 带 {@code //@javelinLighting} 标记，导入期注入相机块 +
 * 光照段函数 + G-Buffer 三采样）。</p>
 *
 * <p><b>着色单源</b>：frag 调用的 {@code javelinLighting} 与材质 Forward fs 是同一常量
 * ——同一 set1 cluster 套件/阴影数据、同一公式，「两路光照一致」不靠比对而靠构造。</p>
 *
 * <p><b>清屏职责</b>：HYBRID 档颜色终点由本 Pass 清屏（OpaqueForward 的对应职责移交；
 * 区域清屏与 ClearFlags 分支照搬）。背景像素由 frag 按 G-Buffer worldPos.w==0 discard，
 * 保留清屏色，天空盒随后覆盖。</p>
 *
 * <p><b>PSO</b>：无深度附件（背景剔除靠 G-Buffer 有效标志，不靠深度测试）；
 * set0 = 帧相机布局、set1 = 光照套件布局（含 G-Buffer 三采样与 shadowMap）——
 * 与材质 PSO 同源布局，零 push。</p>
 */
public class LightingPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(LightingPass.class);

    /** 内置光照着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/lighting/lighting.vert";
    private static final String FRAG_SHADER_PATH = "core/lighting/lighting.frag";

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    /** 覆盖制 PSO 缓存：key = 颜色附件格式（换帧缓冲格式即新建） */
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB 创建）
     * @param assetManager 资产管理器（装载内置 lighting.vert/.frag 的 SpvShader 产物）
     */
    public LightingPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "lighting.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "lighting.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
    }

    /** 清屏色（管线全局默认色；相机自带 clearColor 优先） */
    public void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
    }

    @Override
    public String name() {
        return "Lighting";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "Lighting";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        // 场景颜色终点：后处理链非空时为 HDR 缓冲（lightingRT），否则同 colorTarget
        Texture target = ctx.sceneColorTarget();
        Texture albedo = rs.gbufferAlbedoFor(view.name());
        Texture normal = rs.gbufferNormalFor(view.name());
        Texture worldPos = rs.gbufferWorldPosFor(view.name());
        Format colorFormat = target.desc().format();
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : clearColor;
        boolean regionClear = AbstractForwardPass.isRegionClear(view, target);
        // HDR 下恒清屏（每视口独立 HDR 缓冲，LOAD 到的是本视口上一帧内容——跨相机
        // 颜色叠加不成立，v1 按整视口 CLEAR 独立输出，后处理档 §4.3）
        boolean loadColor = !ctx.hdrEnabled()
                && (view.clearFlags() == ClearFlags.DEPTH_ONLY
                    || view.clearFlags() == ClearFlags.DONT_CLEAR);
        graph.addGraphicsPass(view.name() + "_Lighting", pass -> {
            if (loadColor) {
                // 叠加相机（DEPTH_ONLY/DONT_CLEAR）：保留上一位画面，只写几何覆盖处
                pass.addColorOutput(target, RHILoadOp.LOAD, null);
            } else if (regionClear) {
                pass.addColorOutput(target, RHILoadOp.CLEAR, clear, view.scissor());
            } else {
                pass.addColorOutput(target, RHILoadOp.CLEAR, clear);
            }
            // 读声明：G-Buffer 三张（建 GBuffer→Lighting 边）+ shadowMap（建 Shadow→Lighting 边）
            pass.addTextureRead(albedo);
            pass.addTextureRead(normal);
            pass.addTextureRead(worldPos);
            pass.addTextureRead(rs.shadowMap());
            // set1 storage 读声明（本视口 cluster 套件：着色逐像素查询）
            for (Buffer b : rs.viewStorages(view.name())) {
                pass.addBufferRead(b);
            }
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            int uboOffset = view.uboOffset();
            pass.setRenderCallback(cmd -> {
                if (!drawLogged) {
                    drawLogged = true;
                    log.info("首帧 Lighting 回调执行: 视口[{}] colorFormat={}", view.name(), colorFormat);
                }
                cmd.bindPipeline(pipelineFor(colorFormat, rs));
                // set0 相机块（与材质 PSO 同源布局；动态偏移选本视口相机）
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                // set1 光照套件（binding0-4 光照/阴影 + binding5-7 G-Buffer）
                cmd.bindDescriptorSet(1, rs.lightingSet(view.name()), 1,
                        new int[]{view.lightingOffset()});
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 释放自建资源（须 device waitIdle 后；VB/PSO） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        vertexBuffer.dispose();
    }

    private GraphicsPipeline pipelineFor(Format colorFormat, RenderingSystem rs) {
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
                false,                                    // blend Off
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,               // 无深度附件（depthAttachmentFormat=null）
                // set0 帧相机布局 + set1 光照套件布局（与材质 PSO 同源）
                List.of(rs.frameSetLayoutDesc(), rs.frameStorageLayoutDesc()), 0,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(colorFormat, created);
        log.info("lighting PSO created: colorFormat={}", colorFormat);
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
    private static byte[] loadSpv(AssetManager assetManager, String path, String label) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException("光照着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/lighting/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("光照着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }
}
