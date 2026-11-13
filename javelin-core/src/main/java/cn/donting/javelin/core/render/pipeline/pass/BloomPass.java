package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.PostProcessNode;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.CommandBuffer;
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
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
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
import cn.donting.javelin.rhi.types.Filter;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.PrimitiveTopology;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.SamplerAddressMode;
import cn.donting.javelin.rhi.types.SamplerMipmapMode;
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
 * 后处理链中 Bloom Pass（Engine_PostProcess_Design §3.7）：阈值提取 → 多级降采样+模糊 →
 * 上采样加权累加 → 与场景色相加，链尾交 Tonemap（Tonemap 关时直写显示目标）。
 *
 * <p><b>覆盖制</b>（TonemapPass/LightingPass 同款先例）：不消费材质 Pass 声明，着色器走资产
 * 导入链（内置 {@code builtin-asset-core/postprocess/bloom*.vert|.frag} 经 SpvShaderImporter
 * 编译）。单节点内录制 8 个全屏 Pass（1 prefilter + 3 down + 3 up + 1 composite），逐视口独立。</p>
 *
 * <p><b>跨视口/多级坐标（uvRect）</b>：顶点着色器输出 vUV（视口内 0..1），片元按 push 常量
 * {@code uvRect = (viewportX/fullW, viewportY/fullH, viewportW/fullW, viewportH/fullH)} 换算源纹理
 * 绝对坐标——分屏视口（区域写入）与多级降采样（各级 RT 尺寸递减）共用同一 shader，无需按级改
 * 采样公式；各 Pass 的 viewport/scissor 随级数同步缩放。</p>
 *
 * <p><b>描述符</b>：set0 为本 Pass 专属布局——prefilter/down/up 单采样器（binding0 = 源），
 * composite 双采样器（binding0 = 场景色、binding1 = Bloom 结果）。无相机块、无光照块。</p>
 *
 * <p><b>Bloom RT</b>：由 {@link RenderingSystem#bloomRtFor} 提供（每视口、尺寸递减、resize 换绑、
 * removeCamera 回收）。参数（阈值/强度/级数）起步常量，接设置后置（§3.7）。</p>
 */
public class BloomPass implements PostProcessNode {

    private static final Logger log = LoggerFactory.getLogger(BloomPass.class);

    /** 内置 Bloom 着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/postprocess/bloom.vert";
    private static final String PREFILTER_FRAG_PATH = "core/postprocess/bloom_prefilter.frag";
    private static final String DOWN_FRAG_PATH = "core/postprocess/bloom_down.frag";
    private static final String UP_FRAG_PATH = "core/postprocess/bloom_up.frag";
    private static final String COMPOSITE_FRAG_PATH = "core/postprocess/bloom_composite.frag";

    /** Bloom 级数（1/2、1/4、1/8、1/16） */
    private static final int LEVELS = 4;

    /** push 常量：vec4 uvRect（16B） */
    private static final int PUSH_SIZE = 16;

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    /** Bloom 中间 RT 清屏色（预过滤/降采样 Pass 全量 CLEAR，实际内容由全屏覆盖写入） */
    private static final RHIClearColor CLEAR_BLACK = new RHIClearColor(0.0f, 0.0f, 0.0f, 1.0f);

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] prefilterSpv;
    private final byte[] downSpv;
    private final byte[] upSpv;
    private final byte[] compositeSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    /** Bloom 采样器：LINEAR（降采样/上采样靠双线性参与滤波，与 Tonemap 的 NEAREST 不同） */
    private final Sampler bloomSampler;
    private DescriptorSetLayout singleLayout;
    private DescriptorSetLayout dualLayout;
    /** PSO 缓存：key = passKind:输出格式（同 kind 各级/各视口共用） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 描述符集缓存：key = view|kind|level；存纹理身份（resize 换代即重建） */
    private final Map<String, DescSlot> descSets = new HashMap<>();
    /** 每帧复用的 push 打包缓冲（录制串行，回调内填后即推） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 合成 Pass 默认清屏色（相机自带 clearColor 优先；AbstractRenderPipeline.setClearColor 下发） */
    private RHIClearColor defaultClearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager 资产管理器（装载内置 bloom*.vert/.frag 的 SpvShader 产物）
     */
    public BloomPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "bloom.vert");
        this.prefilterSpv = loadSpv(assetManager, PREFILTER_FRAG_PATH, "bloom_prefilter.frag");
        this.downSpv = loadSpv(assetManager, DOWN_FRAG_PATH, "bloom_down.frag");
        this.upSpv = loadSpv(assetManager, UP_FRAG_PATH, "bloom_up.frag");
        this.compositeSpv = loadSpv(assetManager, COMPOSITE_FRAG_PATH, "bloom_composite.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        // LINEAR：Bloom 各级缩放采样靠双线性参与滤波（NEAREST 会出块状）
        this.bloomSampler = device.createSampler(new SamplerDesc(
                Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
    }

    @Override
    public String name() {
        return "Bloom";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "Bloom";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        String viewName = view.name();

        // uvRect：视口区域在「全分辨率源纹理」中的归一化矩形（input = 全分辨率场景色）
        int fullW = input.desc().width();
        int fullH = input.desc().height();
        RHIViewport vp = view.viewport();
        float[] uvRect = {
                vp.x() / fullW,
                vp.y() / fullH,
                vp.width() / fullW,
                vp.height() / fullH,
        };

        // 每视口多级 Bloom RT（尺寸递减，惰性创建）
        Texture[] bloom = new Texture[LEVELS];
        for (int level = 0; level < LEVELS; level++) {
            bloom[level] = rs.bloomRtFor(viewName, level);
        }

        // 1. 阈值提取：场景色 → bloom[0]
        addScalePass(graph, view, "prefilter", 0, input, bloom[0], uvRect);
        // 2. 降采样+模糊：bloom[k-1] → bloom[k]
        for (int level = 1; level < LEVELS; level++) {
            addScalePass(graph, view, "down", level, bloom[level - 1], bloom[level], uvRect);
        }
        // 3. 上采样累加（加性混合）：bloom[k+1] → bloom[k]，自最小级逐级回加
        for (int level = LEVELS - 2; level >= 0; level--) {
            addScalePass(graph, view, "up", level, bloom[level + 1], bloom[level], uvRect);
        }
        // 4. 合成：场景色 + bloom[0] → 链输出
        addCompositePass(graph, view, input, bloom[0], output, uvRect);
    }

    /** 释放自建资源（须 device waitIdle 后；VB/PSO/采样器/描述符集与布局） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        descSets.values().forEach(slot -> slot.set.dispose());
        descSets.clear();
        if (singleLayout != null) {
            singleLayout.dispose();
        }
        if (dualLayout != null) {
            dualLayout.dispose();
        }
        bloomSampler.dispose();
        vertexBuffer.dispose();
    }

    // ═══ Pass 录制 ═══

    /**
     * 逐级全屏 Pass（prefilter / down / up）：写 {@code dst}（Bloom RT，尺寸随级数递减），
     * viewport/scissor 随级数同步缩放（divisor = 2^(level+1)）。
     */
    private void addScalePass(RenderGraphBuilder graph, RenderView view, String kind, int level,
                              Texture src, Texture dst, float[] uvRect) {
        boolean additive = kind.equals("up");
        RHIViewport sv = scaledViewport(view.viewport(), level);
        RHIScissor sc = scaledScissor(view.scissor(), level);
        Format colorFormat = dst.desc().format();
        String key = view.name() + "|" + kind + level;
        graph.addGraphicsPass(view.name() + "_Bloom_" + kind + level, pass -> {
            if (additive) {
                // 上采样累加：保留本级降采样结果并叠加（dst = dst + src）
                pass.addColorOutput(dst, RHILoadOp.LOAD, null);
            } else {
                pass.addColorOutput(dst, RHILoadOp.CLEAR, CLEAR_BLACK);
            }
            pass.addTextureRead(src);
            pass.setViewport(sv);
            pass.setScissor(sc);
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor(kind, colorFormat));
                cmd.bindDescriptorSet(0, descSetFor(key, src, null), 0, null);
                pushUvRect(cmd, uvRect);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 合成 Pass：场景色 + bloom[0]×强度 → 链输出（全分辨率，沿用本视口 viewport/scissor）。 */
    private void addCompositePass(RenderGraphBuilder graph, RenderView view, Texture scene,
                                  Texture bloom0, Texture output, float[] uvRect) {
        RHIViewport vp = view.viewport();
        RHIScissor sc = view.scissor();
        Format colorFormat = output.desc().format();
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : defaultClearColor;
        boolean regionClear = AbstractForwardPass.isRegionClear(view, output);
        String key = view.name() + "|comp";
        graph.addGraphicsPass(view.name() + "_Bloom_Composite", pass -> {
            // 全屏覆盖写全部像素（CLEAR 与 Tonemap 同口径，保持清屏职责一致）
            if (regionClear) {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear, sc);
            } else {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear);
            }
            pass.addTextureRead(scene);
            pass.addTextureRead(bloom0);
            pass.setViewport(vp);
            pass.setScissor(sc);
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("comp", colorFormat));
                cmd.bindDescriptorSet(0, descSetFor(key, scene, bloom0), 0, null);
                pushUvRect(cmd, uvRect);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 管线全局清屏色同步（AbstractRenderPipeline.setClearColor 下发） */
    public void setClearColor(RHIClearColor clearColor) {
        if (clearColor != null) {
            this.defaultClearColor = clearColor;
        }
    }

    private void logFirstDraw(String viewName) {
        if (!drawLogged) {
            drawLogged = true;
            log.info("首帧 Bloom 回调执行: 视口[{}] levels={}", viewName, LEVELS);
        }
    }

    private void pushUvRect(CommandBuffer cmd, float[] uvRect) {
        pushData.clear();
        pushData.putFloat(uvRect[0]);
        pushData.putFloat(uvRect[1]);
        pushData.putFloat(uvRect[2]);
        pushData.putFloat(uvRect[3]);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
    }

    /** 视口在 level 级 Bloom RT 上的缩放视口（divisor = 2^(level+1)） */
    private static RHIViewport scaledViewport(RHIViewport vp, int level) {
        int d = 1 << (level + 1);
        return new RHIViewport(vp.x() / d, vp.y() / d,
                Math.max(1.0f, vp.width() / d), Math.max(1.0f, vp.height() / d),
                vp.minDepth(), vp.maxDepth());
    }

    /** 视口在 level 级 Bloom RT 上的缩放裁剪矩形（divisor = 2^(level+1)） */
    private static RHIScissor scaledScissor(RHIScissor sc, int level) {
        int d = 1 << (level + 1);
        return new RHIScissor(sc.x() / d, sc.y() / d,
                Math.max(1, sc.width() / d), Math.max(1, sc.height() / d));
    }

    // ═══ 资源懒创建 ═══

    /** 逐级 Pass 描述符集：纹理身份不变复用，换绑（resize）重建 */
    private DescriptorSet descSetFor(String key, Texture a, Texture b) {
        DescSlot slot = descSets.get(key);
        if (slot != null && slot.a == a && slot.b == b) {
            return slot.set;
        }
        DescriptorSetLayout layout = b != null ? dualLayout() : singleLayout();
        DescriptorSet set = device.createDescriptorSet(layout);
        if (b != null) {
            set.update(DescriptorUpdate.sampledTexture(0, a, bloomSampler),
                    DescriptorUpdate.sampledTexture(1, b, bloomSampler));
        } else {
            set.update(DescriptorUpdate.sampledTexture(0, a, bloomSampler));
        }
        if (slot != null) {
            slot.set.dispose();
        }
        descSets.put(key, new DescSlot(set, a, b));
        return set;
    }

    private DescriptorSetLayout singleLayout() {
        if (singleLayout == null) {
            singleLayout = device.createDescriptorSetLayout(singleLayoutDesc());
        }
        return singleLayout;
    }

    private DescriptorSetLayout dualLayout() {
        if (dualLayout == null) {
            dualLayout = device.createDescriptorSetLayout(dualLayoutDesc());
        }
        return dualLayout;
    }

    /** set0 单采样器布局（prefilter/down/up：binding0 = 源） */
    private static DescriptorSetLayoutDesc singleLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /** set0 双采样器布局（composite：binding0 = 场景色、binding1 = Bloom 结果） */
    private static DescriptorSetLayoutDesc dualLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    private GraphicsPipeline pipelineFor(String kind, Format colorFormat) {
        String key = kind + ":" + colorFormat;
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] frag = fragmentSpv(kind);
        boolean additive = kind.equals("up");
        boolean composite = kind.equals("comp");
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, frag),
                triangleLayout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.NONE,                            // 全屏三角形绕序无关
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                false, false, CompareOp.LESS,             // 无深度附件，深度态关
                additive,                                 // up = 加性混合（dst + src）
                BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ONE, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,               // 无深度附件
                composite ? List.of(dualLayoutDesc()) : List.of(singleLayoutDesc()),
                PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("bloom PSO created: kind={} colorFormat={}", kind, colorFormat);
        return created;
    }

    private byte[] fragmentSpv(String kind) {
        if (kind.equals("prefilter")) {
            return prefilterSpv;
        }
        if (kind.equals("comp")) {
            return compositeSpv;
        }
        if (kind.equals("up")) {
            return upSpv;
        }
        return downSpv;
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
            throw new IllegalStateException("后处理着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/postprocess/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("后处理着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }

    /** 描述符集槽（纹理换代即重建） */
    private static final class DescSlot {
        final DescriptorSet set;
        final Texture a;
        final Texture b;

        DescSlot(DescriptorSet set, Texture a, Texture b) {
            this.set = set;
            this.a = a;
            this.b = b;
        }
    }
}
