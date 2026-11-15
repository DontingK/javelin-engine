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
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 后处理链中 TAA 节点（Engine_TAA_Design §3.6）：重投影累积历史色（写 {@code taaRtFor(view, cur)}），
 * 再整幅拷贝到链输出并<b>去除本帧抖动</b>（采样 +j 锚回未抖动网格，§9.2-10）——对链只暴露「input → output」。
 *
 * <p>输入 = 链输入（本帧已抖动渲染的场景色）+ G-Buffer 世界位置 + 上帧历史色；相机矩阵不读
 * set0 引擎块，重投影用<b>上帧抖动后 viewProj</b>（{@code RenderingSystem.taaPrevViewProj}），
 * 参数走本类自管 UBO（set0 引擎布局零改动）。抗锯齿是<b>相机级</b>设置（Unity HDRP 口径）：
 * 本视口相机未启用 TAA 时只做一次直拷（不抖动、不累积、不建历史 RT）。仅 HYBRID 档可用，档位判定归组合根。</p>
 */
public class TaaPass implements PostProcessNode {

    private static final Logger log = LoggerFactory.getLogger(TaaPass.class);

    /** 内置 TAA 着色器 loadAsset 路径（builtin-asset-core 释放后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/postprocess/taa.vert";
    private static final String RESOLVE_FRAG_PATH = "core/postprocess/taa.frag";
    private static final String COPY_FRAG_PATH = "core/postprocess/taa_copy.frag";

    /**
     * 参数块（std140）：mat4 prevViewProj(0,64) + vec4 uvRect(64) + vec4 params(80) + vec4 extra(96)
     * = 实需 112B。动态偏移步长取 256（minUniformBufferOffsetAlignment 上限，RHI 19.5-1）；
     * 描述符 range 取 128（16 对齐且覆盖实需）。
     */
    private static final int PARAM_BLOCK_BYTES = 112;
    private static final int PARAM_RANGE = 128;
    private static final int PARAM_STRIDE = 256;
    private static final int PARAM_FLOATS = PARAM_BLOCK_BYTES / Float.BYTES;

    /** push 常量：copy Pass 的 vec4 uvRect + vec4 去抖偏移（32B；resolve 不推，但布局两 Pass 共用） */
    private static final int PUSH_SIZE = 32;

    /** 全屏大三角形 NDC 顶点（(-1,-1)/(3,-1)/(-1,3)，vec2、stride 8B） */
    private static final float[] TRIANGLE_VERTICES = {
            -1.0f, -1.0f,
            3.0f, -1.0f,
            -1.0f, 3.0f,
    };

    /** 历史 RT 清屏色（全屏覆盖写本视口区域；视口外黑） */
    private static final RHIClearColor CLEAR_BLACK = new RHIClearColor(0.0f, 0.0f, 0.0f, 1.0f);

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] resolveSpv;
    private final byte[] copySpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    /** 历史色采样器：LINEAR（CR 9-tap 的取数基础——重投影重采样用带负瓣核压累积糊，§9.2-11） */
    private final Sampler historySampler;
    /** 场景色采样器：NEAREST（逐像素 1:1，3×3 邻域按纹素偏移取点） */
    private final Sampler sceneSampler;
    private DescriptorSetLayout resolveLayout;
    private DescriptorSetLayout copyLayout;
    /** PSO 缓存：key = passKind:输出格式（同 kind 各视口共用） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 每视口参数块 + 描述符集缓存（key = 视口名；纹理换代/尺寸变化即重建 set） */
    private final Map<String, TaaView> views = new HashMap<>();
    /** 参数打包暂存（矩阵 16 + 三个 vec4；录制串行，帧路径 exclusive） */
    private final float[] paramPack = new float[PARAM_FLOATS];
    /** 单位矩阵（历史无效时占位——着色器按 extra.x 跳过重投影，不读内容） */
    private final Matrix4f identityMatrix = new Matrix4f();
    /** copy Pass 的 push 打包缓冲（录制串行，回调内填后即推） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(PUSH_SIZE).order(ByteOrder.LITTLE_ENDIAN);
    /** 本帧抖动像素暂存（copy 去抖；addToGraph 从 RenderingSystem 取） */
    private final float[] jitterPixel = new float[2];
    /** copy Pass 默认清屏色（相机自带 clearColor 优先；AbstractRenderPipeline.setClearColor 下发） */
    private RHIClearColor defaultClearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    private boolean drawLogged;

    /**
     * @param device       RHI 设备（PSO/VB/参数缓冲/描述符集创建）
     * @param assetManager 资产管理器（装载内置 taa*.vert/.frag 的 SpvShader 产物）
     */
    public TaaPass(Device device, AssetManager assetManager) {
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "taa.vert");
        this.resolveSpv = loadSpv(assetManager, RESOLVE_FRAG_PATH, "taa.frag");
        this.copySpv = loadSpv(assetManager, COPY_FRAG_PATH, "taa_copy.frag");
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        this.historySampler = device.createSampler(new SamplerDesc(
                Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
        this.sceneSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
    }

    @Override
    public String name() {
        return "TAA";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "TAA";
    }

    /** 链内位置：SSAO 之后（15）、Bloom 之前——TAA 作用于加泛光前的场景色 */
    @Override
    public int order() {
        return 15;
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        String viewName = view.name();
        long frameIndex = ctx.frame().frameIndex();
        // uvRect：视口区域在「全分辨率源纹理」中的归一化矩形（场景色/历史/世界位置同尺寸）
        int fullW = input.desc().width();
        int fullH = input.desc().height();
        RHIViewport vp = view.viewport();
        float[] uvRect = {
                vp.x() / fullW,
                vp.y() / fullH,
                vp.width() / fullW,
                vp.height() / fullH,
        };
        // 相机级开关（Unity 口径：抗锯齿挂在相机上）：本视口未启用 TAA → 只做一次直拷，
        // 保证链输出不为空（不抖动、不累积、不创建历史 RT）
        if (!rs.taaViewEnabled(viewName)) {
            jitterPixel[0] = 0.0f;
            jitterPixel[1] = 0.0f;
            addCopyPass(graph, view, viewFor(viewName, rs), 0, input, output, uvRect,
                    sceneSampler);
            return;
        }
        // ping-pong：本帧写 current，读上一帧写入的 previous（两者必不同张）
        int current = (int) (frameIndex % 2);
        int previous = 1 - current;
        Texture history = rs.taaRtFor(viewName, previous);
        Texture resolved = rs.taaRtFor(viewName, current);
        Texture worldPos = rs.gbufferWorldPosFor(viewName);

        TaaView taaView = viewFor(viewName, rs);
        int paramOffset = (int) (frameIndex % rs.frameSlots()) * PARAM_STRIDE;
        boolean historyValid = rs.taaHistoryValid(viewName, frameIndex);
        writeParams(taaView, rs, viewName, uvRect, fullW, fullH, paramOffset, historyValid);
        rs.taaJitterFor(viewName, jitterPixel);

        addResolvePass(graph, view, rs, taaView, current, input, history, worldPos, resolved, paramOffset);
        addCopyPass(graph, view, taaView, current, resolved, output, uvRect, historySampler);

        if (frameIndex < 30) {
            Matrix4f prev = rs.taaPrevViewProj(viewName);
            log.info("TAA 帧诊断: 视口[{}] frame={} current={} valid={} blend={} clamp={} hist={} "
                            + "speedThr={} resolved#{} history#{} input#{} worldPos#{} prevOK={} prevM30/M31={}/{}",
                    viewName, frameIndex, current, historyValid,
                    rs.taaViewBlendWeight(viewName), rs.taaViewClampScale(viewName),
                    rs.taaViewHistorySharpening(viewName), rs.taaViewSpeedThreshold(viewName),
                    System.identityHashCode(resolved), System.identityHashCode(history),
                    System.identityHashCode(input), System.identityHashCode(worldPos),
                    prev != null,
                    prev == null ? "无" : String.format("%.4f", prev.m30()),
                    prev == null ? "无" : String.format("%.4f", prev.m31()));
        }
    }

    /** 释放自建资源（VB/PSO/采样器/参数缓冲/描述符集与布局须 device waitIdle 后） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        views.values().forEach(TaaView::dispose);
        views.clear();
        if (resolveLayout != null) {
            resolveLayout.dispose();
        }
        if (copyLayout != null) {
            copyLayout.dispose();
        }
        historySampler.dispose();
        sceneSampler.dispose();
        vertexBuffer.dispose();
    }

    /** 管线全局清屏色同步（AbstractRenderPipeline.setClearColor 下发） */
    public void setClearColor(RHIClearColor clearColor) {
        if (clearColor != null) {
            this.defaultClearColor = clearColor;
        }
    }

    // ═══ Pass 录制 ═══

    /** 重投影累积 Pass：本帧场景色 + 上帧历史 + 世界位置 → 历史 RT（全帧尺寸、本视口区域覆盖） */
    private void addResolvePass(RenderGraphBuilder graph, RenderView view, RenderingSystem rs,
                                TaaView taaView, int current, Texture scene, Texture history,
                                Texture worldPos, Texture resolved, int paramOffset) {
        Format colorFormat = resolved.desc().format();
        graph.addGraphicsPass(view.name() + "_TAA_Resolve", pass -> {
            pass.addColorOutput(resolved, RHILoadOp.CLEAR, CLEAR_BLACK);
            pass.addTextureRead(scene);
            pass.addTextureRead(history);
            pass.addTextureRead(worldPos);
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("resolve", colorFormat));
                cmd.bindDescriptorSet(0, resolveSetFor(taaView, current, scene, history, worldPos,
                        rs.gbufferSampler()), 1, new int[]{paramOffset});
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /**
     * 拷贝 + 去抖 Pass：源纹理 → 链输出（沿用本视口 viewport/scissor，跨视口坐标走 push uvRect + 抖动偏移）。
     *
     * <p>源 = 本帧历史 RT（启用 TAA 时）或链输入（本视口未启用时直拷）。采样器随之切换：
     * 历史用 LINEAR（重采样由着色器核负责）、直拷用 NEAREST（1:1 逐像素）。</p>
     */
    private void addCopyPass(RenderGraphBuilder graph, RenderView view, TaaView taaView, int slot,
                             Texture source, Texture output, float[] uvRect, Sampler sampler) {
        Format colorFormat = output.desc().format();
        RHIClearColor clear = view.clearColor() != null ? view.clearColor() : defaultClearColor;
        boolean regionClear = AbstractForwardPass.isRegionClear(view, output);
        RHIScissor sc = view.scissor();
        graph.addGraphicsPass(view.name() + "_TAA_Copy", pass -> {
            if (regionClear) {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear, sc);
            } else {
                pass.addColorOutput(output, RHILoadOp.CLEAR, clear);
            }
            pass.addTextureRead(source);
            pass.setViewport(view.viewport());
            pass.setScissor(sc);
            pass.setRenderCallback(cmd -> {
                logFirstDraw(view.name());
                cmd.bindPipeline(pipelineFor("copy", colorFormat));
                cmd.bindDescriptorSet(0, copySetFor(taaView, slot, source, sampler), 0, null);
                pushUvRect(cmd, uvRect, jitterPixel[0], jitterPixel[1]);
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    /**
     * 参数块写入：上帧抖动后 viewProj + uvRect + (权重, 钳制, 历史锐化, 速度阈值) +
     * (有效, 纹素U, 纹素V, 0)。四元组来源 = 相机级设置（RenderingSystem 每帧快照）。
     */
    private void writeParams(TaaView taaView, RenderingSystem rs, String viewName, float[] uvRect,
                             int fullW, int fullH, int offset, boolean historyValid) {
        Matrix4f prev = rs.taaPrevViewProj(viewName);
        if (historyValid && prev != null) {
            prev.get(paramPack);
        } else {
            identityMatrix.get(paramPack);
        }
        paramPack[16] = uvRect[0];
        paramPack[17] = uvRect[1];
        paramPack[18] = uvRect[2];
        paramPack[19] = uvRect[3];
        paramPack[20] = rs.taaViewBlendWeight(viewName);
        paramPack[21] = rs.taaViewClampScale(viewName);
        paramPack[22] = rs.taaViewHistorySharpening(viewName);
        paramPack[23] = rs.taaViewSpeedThreshold(viewName);
        paramPack[24] = historyValid ? 1.0f : 0.0f;
        paramPack[25] = 1.0f / Math.max(1, fullW);
        paramPack[26] = 1.0f / Math.max(1, fullH);
        paramPack[27] = 0.0f;
        taaView.params.writeAligned(offset, FloatBuffer.wrap(paramPack));
    }

    /** copy 块：vec4 uvRect + vec4 去抖偏移（32B；去抖 = +本帧抖动像素，Halton 值已是像素中心相对偏移） */
    private void pushUvRect(CommandBuffer cmd, float[] uvRect, float jitterX, float jitterY) {
        pushData.clear();
        pushData.putFloat(uvRect[0]);
        pushData.putFloat(uvRect[1]);
        pushData.putFloat(uvRect[2]);
        pushData.putFloat(uvRect[3]);
        pushData.putFloat(jitterX);
        pushData.putFloat(jitterY);
        pushData.putFloat(0.0f);
        pushData.putFloat(0.0f);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
    }

    private void logFirstDraw(String viewName) {
        if (!drawLogged) {
            drawLogged = true;
            log.info("首帧 TAA 回调执行: 视口[{}]", viewName);
        }
    }

    // ═══ 资源懒创建 ═══

    /** 每视口参数块（PARAM_STRIDE × 帧槽，动态偏移选槽） */
    private TaaView viewFor(String viewName, RenderingSystem rs) {
        return views.computeIfAbsent(viewName, n -> new TaaView(device.createBuffer(
                new BufferDesc(rs.frameSlots() * PARAM_STRIDE, Set.of(BufferUsage.UNIFORM),
                        MemoryPreference.hostVisibleCoherent()), null)));
    }

    /**
     * 重投影描述符集：按 ping-pong 代（current）各常驻一份——稳态零分配（每帧交替用不同那份）。
     * 只有纹理对象换代（resize / 开关切换 G-Buffer 换代）时才原地 update 采样 binding，
     * 绝不 create+dispose（历史/源纹理逐帧交替，重建会每帧吃一个描述符集，池很快耗尽）。
     */
    private DescriptorSet resolveSetFor(TaaView taaView, int current, Texture scene, Texture history,
                                        Texture worldPos, Sampler worldSampler) {
        DescriptorSet set = taaView.resolveSets[current];
        boolean created = set == null;
        if (created) {
            set = device.createDescriptorSet(resolveLayout());
            taaView.resolveSets[current] = set;
        }
        if (created || taaView.resolveScene[current] != scene
                || taaView.resolveHistory[current] != history
                || taaView.resolveWorldPos[current] != worldPos) {
            set.update(
                    DescriptorUpdate.dynamicUniformBuffer(0, taaView.params, 0, PARAM_RANGE),
                    DescriptorUpdate.sampledTexture(1, scene, sceneSampler),
                    DescriptorUpdate.sampledTexture(2, history, historySampler),
                    DescriptorUpdate.sampledTexture(3, worldPos, worldSampler));
            taaView.resolveScene[current] = scene;
            taaView.resolveHistory[current] = history;
            taaView.resolveWorldPos[current] = worldPos;
        }
        return set;
    }

    /** 拷贝描述符集：按 ping-pong 代各常驻一份（源 = 同代历史 RT 或链输入直拷），源/采样器换代时原地 update */
    private DescriptorSet copySetFor(TaaView taaView, int slot, Texture source, Sampler sampler) {
        DescriptorSet set = taaView.copySets[slot];
        boolean created = set == null;
        if (created) {
            set = device.createDescriptorSet(copyLayout());
            taaView.copySets[slot] = set;
        }
        if (created || taaView.copySource[slot] != source || taaView.copySampler[slot] != sampler) {
            set.update(DescriptorUpdate.sampledTexture(0, source, sampler));
            taaView.copySource[slot] = source;
            taaView.copySampler[slot] = sampler;
        }
        return set;
    }

    private DescriptorSetLayout resolveLayout() {
        if (resolveLayout == null) {
            resolveLayout = device.createDescriptorSetLayout(resolveLayoutDesc());
        }
        return resolveLayout;
    }

    private DescriptorSetLayout copyLayout() {
        if (copyLayout == null) {
            copyLayout = device.createDescriptorSetLayout(copyLayoutDesc());
        }
        return copyLayout;
    }

    /** 重投影布局：binding0 参数块（DYNAMIC）+ binding1 场景色 + binding2 历史 + binding3 世界位置 */
    private static DescriptorSetLayoutDesc resolveLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /** 拷贝布局：单采样（binding0） */
    private static DescriptorSetLayoutDesc copyLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /** PSO 缓存（kind = resolve / copy；两 Pass 各有独立 set 布局，无相机块） */
    private GraphicsPipeline pipelineFor(String kind, Format colorFormat) {
        String key = kind + ":" + colorFormat;
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        byte[] frag = kind.equals("resolve") ? resolveSpv : copySpv;
        List<DescriptorSetLayoutDesc> layouts = kind.equals("resolve")
                ? List.of(resolveLayoutDesc())
                : List.of(copyLayoutDesc());
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
        log.info("taa PSO created: kind={} colorFormat={}", kind, colorFormat);
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

    /** 每视口 TAA 资源（参数块 + 两代描述符集及其纹理身份凭据） */
    private static final class TaaView {
        final Buffer params;
        /** 重投影描述符集按 ping-pong 代各一份（[0] 写 t0 读 t1，[1] 写 t1 读 t0） */
        final DescriptorSet[] resolveSets = new DescriptorSet[2];
        final Texture[] resolveScene = new Texture[2];
        final Texture[] resolveHistory = new Texture[2];
        final Texture[] resolveWorldPos = new Texture[2];
        /** 拷贝描述符集按 ping-pong 代各一份（源 = 同代历史 RT / 直拷时的链输入） */
        final DescriptorSet[] copySets = new DescriptorSet[2];
        final Texture[] copySource = new Texture[2];
        /** 直拷与历史采样用不同采样器（NEAREST / LINEAR），随源一起记身份 */
        final Sampler[] copySampler = new Sampler[2];

        TaaView(Buffer params) {
            this.params = params;
        }

        void dispose() {
            for (DescriptorSet set : resolveSets) {
                if (set != null) {
                    set.dispose();
                }
            }
            for (DescriptorSet set : copySets) {
                if (set != null) {
                    set.dispose();
                }
            }
            params.dispose();
        }
    }
}
