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
 * 后处理链中 SSAO 节点（Engine_SSAO_Design §3.2/§3.3）：世界空间半球核环境光遮蔽——
 * AO 计算（半分辨率，写 {@code ssaoRtFor(view,0)}）→ 双边模糊**可分离两遍**（水平写
 * {@code ssaoRtFor(view,1)}、竖直写 {@code ssaoRtFor(view,2)}，消噪声）→ 合成（场景色 ×
 * mix(1, AO, intensity)，写链输出）。
 *
 * <p><b>不读深度</b>（RHI 无深度采样路径，设计档 §2.1）：输入 = G-Buffer 世界法线 + 世界位置
 * （w=有效标志），相机矩阵走 set0 引擎 frameSet（动态偏移选本视口）——故**仅 HYBRID 档可用**
 * （FORWARD 档无 G-Buffer）；档位判定归组合根，本类不判断（§3.8）。</p>
 *
 * <p><b>覆盖制</b>（BloomPass/TonemapPass 同款先例）：不消费材质 Pass 声明，着色器走资产导入链
 * （内置 {@code builtin-asset-core/postprocess/ssao*.vert|frag} 经 SpvShaderImporter 编译）。
 * AO 半分辨率（viewport/scissor &gt;&gt;1）、合成全分辨率；跨视口坐标一律 push 常量 uvRect +
 * 顶点 vUV 换算（§2 表第 4 条——分屏与半分辨率下 gl_FragCoord 不成立）。</p>
 *
 * <p><b>三步全在节点内</b>（AO / 模糊 / 合成），中间 AO 全写自管 RT（{@code ssaoRtFor}），对链只暴露
 * 「input → output」（照 BloomPass 的「单节点多 Pass」形态）。</p>
 */
public class SsaoPass implements PostProcessNode {

    private static final Logger log = LoggerFactory.getLogger(SsaoPass.class);

    /** 内置 SSAO 着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/postprocess/ssao.vert";
    private static final String AO_FRAG_PATH = "core/postprocess/ssao.frag";
    private static final String BLUR_FRAG_PATH = "core/postprocess/ssao_blur.frag";
    private static final String COMPOSITE_FRAG_PATH = "core/postprocess/ssao_composite.frag";

    /** push 常量：vec4 uvRect + vec4 params（各 Pass 语义不同，见各 add*Pass 的注释），共 32B */
    private static final int PUSH_SIZE = 32;

    /** AO 自适应深度偏移倍率（内部常量，不占设置面）：偏置 = max(bias, 倍率 × 邻域视 z 步进) */
    private static final float BIAS_SLOPE_SCALE = 1.5f;

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    /** AO RT 清屏色（全屏覆盖写全部像素；白 = 无遮挡，兜底不误压暗） */
    private static final RHIClearColor CLEAR_WHITE = new RHIClearColor(1.0f, 1.0f, 1.0f, 1.0f);

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] aoSpv;
    private final byte[] blurSpv;
    private final byte[] compositeSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    /** AO 采样器：LINEAR（半分辨率 AO 上采样到全分辨率时平滑；G-Buffer 仍走引擎 NEAREST 采样器） */
    private final Sampler aoSampler;
    private DescriptorSetLayout dualLayout;
    /** PSO 缓存：key = passKind:输出格式（同 kind 各视口共用） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 描述符集缓存：key = view|kind；存纹理身份（resize 换代即重建） */
    private final Map<String, DescSlot> descSets = new HashMap<>();
    /** 每帧复用的 push 打包缓冲（录制串行，回调内填后即推） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** SSAO 参数（运行期由「项目设置」下发；录制回调按当前值 push） */
    private float radius = 0.5f;
    private float bias = 0.02f;
    private float intensity = 1.0f;
    /** 合成 Pass 默认清屏色（相机自带 clearColor 优先；AbstractRenderPipeline.setClearColor 下发） */
    private RHIClearColor defaultClearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/描述符集创建）
     * @param assetManager 资产管理器（装载内置 ssao*.vert/.frag 的 SpvShader 产物）
     */
    public SsaoPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "ssao.vert");
        this.aoSpv = loadSpv(assetManager, AO_FRAG_PATH, "ssao.frag");
        this.blurSpv = loadSpv(assetManager, BLUR_FRAG_PATH, "ssao_blur.frag");
        this.compositeSpv = loadSpv(assetManager, COMPOSITE_FRAG_PATH, "ssao_composite.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        this.aoSampler = device.createSampler(new SamplerDesc(
                Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
    }

    @Override
    public String name() {
        return "SSAO";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "SSAO";
    }

    /** 链内位置：最前（10）——AO 须乘在未泛光的 HDR 场景色上、且其结果被 Bloom/Tonemap 消费 */
    @Override
    public int order() {
        return 10;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        String viewName = view.name();
        Texture normal = rs.gbufferNormalFor(viewName);
        Texture worldPos = rs.gbufferWorldPosFor(viewName);
        Texture aoRaw = rs.ssaoRtFor(viewName, 0);
        Texture aoH = rs.ssaoRtFor(viewName, 1);
        Texture aoBlurred = rs.ssaoRtFor(viewName, 2);

        // uvRect：视口区域在「全分辨率源纹理」中的归一化矩形（G-Buffer 与场景色同尺寸）
        int fullW = worldPos.desc().width();
        int fullH = worldPos.desc().height();
        RHIViewport vp = view.viewport();
        float[] uvRect = {
                vp.x() / fullW,
                vp.y() / fullH,
                vp.width() / fullW,
                vp.height() / fullH,
        };

        addAoPass(graph, view, rs, normal, worldPos, aoRaw, uvRect);
        // 可分离双边模糊两遍：水平（raw → 1）→ 竖直（1 → 2）；合成消费 2
        addBlurPass(graph, view, rs, aoRaw, worldPos, aoH, uvRect, true);
        addBlurPass(graph, view, rs, aoH, worldPos, aoBlurred, uvRect, false);
        addCompositePass(graph, view, input, aoBlurred, output, uvRect);
    }

    /** 释放自建资源（须 device waitIdle 后；VB/PSO/采样器/描述符集与布局） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        descSets.values().forEach(slot -> slot.set.dispose());
        descSets.clear();
        if (dualLayout != null) {
            dualLayout.dispose();
        }
        aoSampler.dispose();
        vertexBuffer.dispose();
    }

    // ═══ Pass 录制 ═══

    /** AO 计算 Pass：G-Buffer 世界法线 + 世界位置 → 半分辨率 AO（viewport/scissor 均 >>1） */
    private void addAoPass(RenderGraphBuilder graph, RenderView view, RenderingSystem rs,
                           Texture normal, Texture worldPos, Texture ao, float[] uvRect) {
        RHIViewport halfVp = halfViewport(view.viewport());
        RHIScissor halfSc = halfScissor(view.scissor());
        Format colorFormat = ao.desc().format();
        String key = view.name() + "|ao";
        graph.addGraphicsPass(view.name() + "_SSAO_AO", pass -> {
            pass.addColorOutput(ao, RHILoadOp.CLEAR, CLEAR_WHITE);
            pass.addTextureRead(normal);
            pass.addTextureRead(worldPos);
            pass.setViewport(halfVp);
            pass.setScissor(halfSc);
            int uboOffset = view.uboOffset();
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("ao", colorFormat, rs));
                // set0 相机块（与光照/材质 PSO 同源布局；动态偏移选本视口相机）
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                // set1 采样：binding0 = 世界法线、binding1 = 世界位置（引擎 G-Buffer NEAREST 采样器）
                cmd.bindDescriptorSet(1, descSetFor(key, normal, worldPos, rs.gbufferSampler()), 0, null);
                // params = (radius, bias, biasSlopeScale, 0)——深度偏移自适应倍率
                pushParams(cmd, uvRect, radius, bias, BIAS_SLOPE_SCALE, 0.0f);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /**
     * 可分离双边模糊的单遍（水平或竖直）：上游 AO + 世界位置（保边权）→ 半分辨率平滑 AO。
     *
     * @param horizontal true = 水平轴（写 index 1）、false = 竖直轴（写 index 2）
     */
    private void addBlurPass(RenderGraphBuilder graph, RenderView view, RenderingSystem rs,
                             Texture aoIn, Texture worldPos, Texture aoOut, float[] uvRect,
                             boolean horizontal) {
        RHIViewport halfVp = halfViewport(view.viewport());
        RHIScissor halfSc = halfScissor(view.scissor());
        Format colorFormat = aoOut.desc().format();
        String suffix = horizontal ? "H" : "V";
        String key = view.name() + "|blur" + suffix;
        graph.addGraphicsPass(view.name() + "_SSAO_Blur" + suffix, pass -> {
            pass.addColorOutput(aoOut, RHILoadOp.CLEAR, CLEAR_WHITE);
            pass.addTextureRead(aoIn);
            pass.addTextureRead(worldPos);
            pass.setViewport(halfVp);
            pass.setScissor(halfSc);
            int uboOffset = view.uboOffset();
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("blur", colorFormat, rs));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                cmd.bindDescriptorSet(1, descSetFor(key, aoIn, worldPos, rs.gbufferSampler()), 0, null);
                // params = (radius, axis(1=水平/0=竖直), 0, 0)
                pushParams(cmd, uvRect, radius, horizontal ? 1.0f : 0.0f, 0.0f, 0.0f);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /** 合成 Pass：场景色 × mix(1, AO, intensity) → 链输出（全分辨率，沿用本视口 viewport/scissor） */
    private void addCompositePass(RenderGraphBuilder graph, RenderView view, Texture scene,
                                  Texture ao, Texture output, float[] uvRect) {
        RHIViewport vp = view.viewport();
        RHIScissor sc = view.scissor();
        Format colorFormat = output.desc().format();
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : defaultClearColor;
        boolean regionClear = AbstractForwardPass.isRegionClear(view, output);
        String key = view.name() + "|comp";
        graph.addGraphicsPass(view.name() + "_SSAO_Composite", pass -> {
            // 全屏覆盖写全部像素（CLEAR 与 Tonemap/Bloom 同口径，保持清屏职责一致）
            if (regionClear) {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear, sc);
            } else {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear);
            }
            pass.addTextureRead(scene);
            pass.addTextureRead(ao);
            pass.setViewport(vp);
            pass.setScissor(sc);
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("comp", colorFormat, null));
                cmd.bindDescriptorSet(0, descSetFor(key, scene, ao, aoSampler), 0, null);
                // params = (0, 0, intensity, 0)
                pushParams(cmd, uvRect, 0.0f, 0.0f, intensity, 0.0f);
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

    /** SSAO 参数下发（半径/偏移/强度；运行期由组合根 PostProcessSetup 调，录制回调按当前值 push） */
    public void setParams(float radius, float bias, float intensity) {
        this.radius = radius;
        this.bias = bias;
        this.intensity = intensity;
    }

    private void logFirstDraw(String viewName) {
        if (!drawLogged) {
            drawLogged = true;
            log.info("首帧 SSAO 回调执行: 视口[{}]", viewName);
        }
    }

    /**
     * push 块：vec4 uvRect + vec4 params，共 32B。
     * 参数语义按 Pass 分派（AO = radius/bias/biasSlopeScale/0；模糊 = radius/axis/0/0；
     * 合成 = 0/0/intensity/0）——同一 32B 布局，各着色器只取自己那几个槽。
     */
    private void pushParams(CommandBuffer cmd, float[] uvRect,
                            float px, float py, float pz, float pw) {
        pushData.clear();
        pushData.putFloat(uvRect[0]);
        pushData.putFloat(uvRect[1]);
        pushData.putFloat(uvRect[2]);
        pushData.putFloat(uvRect[3]);
        pushData.putFloat(px);
        pushData.putFloat(py);
        pushData.putFloat(pz);
        pushData.putFloat(pw);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
    }

    /** AO RT 上的半分辨率视口（divisor = 2） */
    private static RHIViewport halfViewport(RHIViewport vp) {
        return new RHIViewport(vp.x() / 2, vp.y() / 2,
                Math.max(1.0f, vp.width() / 2), Math.max(1.0f, vp.height() / 2),
                vp.minDepth(), vp.maxDepth());
    }

    /** AO RT 上的半分辨率裁剪矩形（divisor = 2） */
    private static RHIScissor halfScissor(RHIScissor sc) {
        return new RHIScissor(sc.x() / 2, sc.y() / 2,
                Math.max(1, sc.width() / 2), Math.max(1, sc.height() / 2));
    }

    // ═══ 资源懒创建 ═══

    /** 描述符集：纹理身份不变复用，换绑（resize）重建 */
    private DescriptorSet descSetFor(String key, Texture a, Texture b, Sampler sampler) {
        DescSlot slot = descSets.get(key);
        if (slot != null && slot.a == a && slot.b == b) {
            return slot.set;
        }
        DescriptorSet set = device.createDescriptorSet(dualLayout());
        set.update(DescriptorUpdate.sampledTexture(0, a, sampler),
                DescriptorUpdate.sampledTexture(1, b, sampler));
        if (slot != null) {
            slot.set.dispose();
        }
        descSets.put(key, new DescSlot(set, a, b));
        return set;
    }

    private DescriptorSetLayout dualLayout() {
        if (dualLayout == null) {
            dualLayout = device.createDescriptorSetLayout(dualLayoutDesc());
        }
        return dualLayout;
    }

    /** 双采样器布局（AO：法线 + 世界位置；合成：场景色 + AO） */
    private static DescriptorSetLayoutDesc dualLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /**
     * @param rs AO Pass 需要 set0 相机布局（合成 Pass 传 null——不消费相机）
     */
    private GraphicsPipeline pipelineFor(String kind, Format colorFormat, RenderingSystem rs) {
        String key = kind + ":" + colorFormat;
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        boolean needsCamera = !kind.equals("comp");
        byte[] frag;
        if (kind.equals("ao")) {
            frag = aoSpv;
        } else if (kind.equals("blur")) {
            frag = blurSpv;
        } else {
            frag = compositeSpv;
        }
        // AO/模糊：set0 帧相机布局（视空间 z 用）+ set1 采样；合成：仅 set0 采样（照 BloomPass 形态）
        List<DescriptorSetLayoutDesc> layouts = needsCamera
                ? List.of(rs.frameSetLayoutDesc(), dualLayoutDesc())
                : List.of(dualLayoutDesc());
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
                false,                                    // blend Off
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorFormat), null,               // 无深度附件
                layouts,
                PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("ssao PSO created: kind={} colorFormat={}", kind, colorFormat);
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
