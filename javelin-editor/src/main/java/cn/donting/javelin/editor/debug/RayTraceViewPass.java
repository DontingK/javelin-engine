package cn.donting.javelin.editor.debug;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderCamera;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.compute.ComputeShader;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.rt.RayTracingScene;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.ComputePipeline;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.Tlas;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.ComputePipelineDesc;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
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
import cn.donting.javelin.rhi.types.TextureUsage;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 光追求交直显 Pass（P4-06 B3，Engine_RayTracing_Plan §2 B3）。
 *
 * <p><b>两段</b>：①compute 半分辨率每像素发一条相机主射线，ray query 对 TLAS 求最近命中，
 * 把「命中距离 / farZ」（miss = -1）写进 r32f 存储图；②全屏大三角形把该图铺回视口，
 * 近蓝远橙渐变、miss 深蓝。目的是先验证几何与射线一致（偏移/自交/漏检），不是最终消费形态。</p>
 *
 * <p><b>每帧顺序</b>：compute 回调里先 {@code RayTracingScene.build}（TLAS 重建）再绑描述符
 * 派发——实例缓冲单份不双缓冲，依赖「上一帧 fence 已等」的时序约定（见方案档 §3.3）。</p>
 *
 * <p><b>后置</b>：主光方向求交（需 G-Buffer 世界位置，与 shadow map 逐像素对照）、替换/混合
 * CSM 的决策、半分辨率上采样优化。</p>
 */
public class RayTraceViewPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(RayTraceViewPass.class);

    private static final String VERT_SHADER_PATH = "editor/debug/gbufferview.vert";
    private static final String FRAG_SHADER_PATH = "editor/debug/raytraceview.frag";
    private static final String COMPUTE_SHADER_PATH = "editor/debug/raytrace.compute";
    /** CSM 对照片元（P4-06 B4）：注入 javelinCsmCore，输出红绿差异图 */
    private static final String CSM_COMPARE_SHADER_PATH = "editor/debug/csmcompare.frag";

    /** compute push：mat4 invViewProj + vec4 camPos + vec4 params + vec4 lightDir（112B） */
    private static final int COMPUTE_PUSH_BYTES = 16 * Float.BYTES + 3 * 4 * Float.BYTES;
    /** 视图 push：ivec2 targetSize（8B） */
    private static final int VIEW_PUSH_BYTES = Integer.BYTES * 2;
    private static final int WORKGROUP = 8;

    /** 射线来源模式（与着色器常量一致） */
    public static final int MODE_CAMERA = 1;
    public static final int MODE_LIGHT = 2;
    /** 对照模式：compute 走主光方向求交，展示段换 csmcompare.frag 出红绿差异图 */
    public static final int MODE_COMPARE = 3;

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
    private final byte[] computeSpv;
    private final byte[] compareFragSpv;
    private final Buffer vertexBuffer;
    private final VertexLayout triangleLayout;
    private final DescriptorSetLayoutDesc computeSetDesc;
    private final DescriptorSetLayoutDesc viewSetDesc;
    private DescriptorSetLayout computeSetLayout;
    private DescriptorSet computeSet;
    private Texture computeSetTexture;
    private Texture computeSetWorldPos;
    private Tlas boundTlas;
    private DescriptorSetLayout viewSetLayout;
    private DescriptorSet viewSet;
    private Texture viewSetTexture;
    /** 对照段（MODE_COMPARE）专用：4 绑定（距离图 / 世界位置 / 阴影 atlas / CSM 参数 UBO） */
    private DescriptorSetLayoutDesc compareSetDesc;
    private DescriptorSetLayout compareSetLayout;
    private DescriptorSet compareSet;
    private Texture compareSetDistance;
    private Texture compareSetWorldPos;
    private Texture compareSetAtlas;
    private Sampler compareShadowSampler;
    private Buffer csmCompareUbo;
    private final ByteBuffer csmCompareData =
            ByteBuffer.allocateDirect(RenderingSystem.CSM_COMPARE_BYTES).order(ByteOrder.LITTLE_ENDIAN);
    private Sampler nearestSampler;
    private ComputePipeline computePipeline;
    private Texture distanceTexture;
    /** 模式 1 无 G-Buffer 时充 binding2 的占位图：1×1，避免拿距离图双绑（storage 要求 GENERAL、
     * sampled 要求 SHADER_READ_ONLY_OPTIMAL，同图两布局互斥 → VUID-00344） */
    private Texture placeholderWorldPos;
    /** 空 TLAS 日志：只在首次跳过时打一条，避免刷屏 */
    private boolean emptyTlasLogged;
    private final Map<Format, GraphicsPipeline> pipelines = new HashMap<>();
    private final Map<Format, GraphicsPipeline> comparePipelines = new HashMap<>();
    private final ByteBuffer computePush = ByteBuffer.allocateDirect(COMPUTE_PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer viewPush = ByteBuffer.allocateDirect(VIEW_PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer comparePush = ByteBuffer.allocateDirect(VIEW_PUSH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private volatile boolean enabled;
    /** 当前射线来源：1 = 相机主射线 / 2 = 主光方向（G-Buffer 世界位置起点） */
    private volatile int mode = MODE_CAMERA;

    public RayTraceViewPass(Device device, AssetManager assetManager,
                            RenderingSystem renderingSystem, String targetView) {
        this.device = device;
        this.renderingSystem = renderingSystem;
        this.targetView = targetView;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH);
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH);
        this.computeSpv = loadComputeSpv(assetManager, COMPUTE_SHADER_PATH);
        this.triangleLayout = VertexLayout.of(List.of(
                new VertexLayout.Attribute(0, Format.R32G32_SFLOAT, 0)), 8);
        this.vertexBuffer = createVertexBuffer();
        this.computeSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.ACCELERATION_STRUCTURE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_IMAGE, 1,
                        ShaderStage.COMPUTE),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.COMPUTE)));
        this.viewSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
        this.compareFragSpv = loadSpv(assetManager, CSM_COMPARE_SHADER_PATH);
        this.compareSetDesc = new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.UNIFORM_BUFFER, 1,
                        ShaderStage.FRAGMENT)));
        this.csmCompareUbo = device.createBuffer(new BufferDesc(RenderingSystem.CSM_COMPARE_BYTES,
                Set.of(BufferUsage.UNIFORM), MemoryPreference.hostVisibleCoherent()), null);
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /** 射线来源：1 = 相机主射线 / 2 = 主光方向 */
    public void setMode(int mode) {
        this.mode = (mode == MODE_LIGHT) ? MODE_LIGHT : MODE_CAMERA;
    }

    @Override
    public String name() {
        return "RayTraceView";
    }

    @Override
    public String shaderPassName() {
        return "RayTraceView";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        if (!enabled) {
            return;
        }
        RenderView view = ctx.view();
        if (!targetView.equals(view.name())) {
            return;
        }
        RayTracingScene scene = renderingSystem.rayTracingScene();
        RenderCamera camera = findCamera(view.name());
        if (scene == null || camera == null) {
            return;  // 无光追能力 / 相机未就绪：AppState 侧写提示行
        }
        boolean compareMode = mode == MODE_COMPARE;
        // 对照模式：求交仍走主光方向（mode 2），展示段换成红绿差异图
        boolean lightMode = mode == MODE_LIGHT || compareMode;
        final int computeMode = compareMode ? MODE_LIGHT : mode;
        Vector3f light = lightMode ? renderingSystem.sunDirectionOrNull() : null;
        if (lightMode && light == null) {
            return;  // 无主光：AppState 侧写提示行
        }
        final Vector3f rayDir = light == null ? null : new Vector3f(light).negate().normalize();
        final Texture worldPosTexture = lightMode
                ? renderingSystem.gbufferWorldPosFor(view.name()) : null;
        final Sampler worldPosTextureSampler = lightMode ? renderingSystem.gbufferSampler() : null;
        if (lightMode && (worldPosTexture == null || worldPosTextureSampler == null)) {
            return;  // FORWARD 档没有 G-Buffer：模式 2/3 不可用
        }
        // 对照还要求阴影 atlas（CSM 一侧）；阴影关/未建时不给假结论
        final Texture shadowAtlas = compareMode ? renderingSystem.shadowMapOrNull(view.name()) : null;
        if (compareMode && shadowAtlas == null) {
            return;
        }
        int width = Math.max(1, (int) view.viewport().width() / 2);
        int height = Math.max(1, (int) view.viewport().height() / 2);
        ensureDistanceTexture(width, height);
        Texture distance = distanceTexture;
        Texture target = ctx.colorTarget();
        Format colorFormat = target.desc().format();

        Matrix4f invViewProj = new Matrix4f(camera.viewProj()).invert();
        Vector3f position = new Vector3f(camera.position());
        float maxDistance = Math.max(1.0f, camera.farZ());
        float bias = lightMode ? 0.02f : 0f;
        List<cn.donting.javelin.core.render.RenderObject> renderers = renderingSystem.renderers();

        graph.addComputePass(view.name() + "_RayTrace", pass -> {
            pass.setPipeline(ensureComputePipeline());
            pass.addTextureStorageWrite(distance);
            if (worldPosTexture != null) {
                pass.addTextureRead(worldPosTexture);
            }
            pass.setRenderCallback(cmd -> {
                int instances = scene.build(cmd, renderers);
                if (instances == 0) {
                    // 空加速结构：dispatch 会全 miss、距离图整片刷成 far，看着像「什么都没命中」，
                    // 反而掩盖真正的问题（网格未上传/未进 renderers）。这一帧跳过、保留上一帧结果。
                    if (!emptyTlasLogged) {
                        emptyTlasLogged = true;
                        log.warn("光追 TLAS 无实例，跳过本帧求交（检查 mesh.isUploaded / hasTriangles / BLAS 是否建出）");
                    }
                    return;
                }
                cmd.bindPipeline(computePipeline);
                cmd.bindDescriptorSet(0,
                        computeSetFor(scene, distance, worldPosTexture, worldPosTextureSampler), 0, null);
                computePush.clear();
                putMatrix(computePush, invViewProj);
                computePush.putFloat(position.x).putFloat(position.y).putFloat(position.z).putFloat(1.0f);
                computePush.putFloat(maxDistance).putFloat((float) computeMode).putFloat(bias).putFloat(0f);
                if (rayDir != null) {
                    computePush.putFloat(rayDir.x).putFloat(rayDir.y).putFloat(rayDir.z).putFloat(0f);
                } else {
                    computePush.putFloat(0f).putFloat(0f).putFloat(0f).putFloat(0f);
                }
                computePush.flip();
                cmd.pushConstants(0, computePush);
                cmd.dispatch((width + WORKGROUP - 1) / WORKGROUP,
                        (height + WORKGROUP - 1) / WORKGROUP, 1);
            });
        });

        graph.addGraphicsPass(view.name() + "_RayTraceView", pass -> {
            pass.addColorOutput(target, RHILoadOp.LOAD, null);
            pass.addTextureRead(distance);
            if (worldPosTexture != null) {
                pass.addTextureRead(worldPosTexture);
            }
            if (shadowAtlas != null) {
                pass.addTextureRead(shadowAtlas);
            }
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            pass.setRenderCallback(cmd -> {
                if (compareMode) {
                    // CSM 快照每帧刷进 UBO（304B，与注入段 lighting 同名同序）
                    csmCompareData.clear();
                    renderingSystem.packCsmCompareParams(view.name(), csmCompareData);
                    csmCompareData.flip();
                    csmCompareUbo.update(0, csmCompareData);
                    cmd.bindPipeline(comparePipelineFor(colorFormat));
                    cmd.bindDescriptorSet(0,
                            compareSetFor(distance, worldPosTexture, shadowAtlas), 0, null);
                    comparePush.clear();
                    comparePush.putInt((int) view.viewport().width());
                    comparePush.putInt((int) view.viewport().height());
                    comparePush.flip();
                    cmd.pushConstants(0, comparePush);
                } else {
                    cmd.bindPipeline(pipelineFor(colorFormat));
                    cmd.bindDescriptorSet(0, viewSetFor(distance), 0, null);
                    viewPush.clear();
                    viewPush.putInt((int) view.viewport().width());
                    viewPush.putInt((int) view.viewport().height());
                    viewPush.flip();
                    cmd.pushConstants(0, viewPush);
                }
                cmd.bindVertexBuffer(vertexBuffer, 0);
                cmd.draw(3, 1);
            });
        });
    }

    private RenderCamera findCamera(String name) {
        for (RenderCamera camera : renderingSystem.cameras()) {
            if (name.equals(camera.name())) {
                return camera;
            }
        }
        return null;
    }

    /** 半分辨率距离图：尺寸变即换代（旧图交给 RenderingSystem 延迟释放） */
    private void ensureDistanceTexture(int width, int height) {
        if (distanceTexture != null
                && distanceTexture.desc().width() == width
                && distanceTexture.desc().height() == height) {
            return;
        }
        if (distanceTexture != null) {
            Texture old = distanceTexture;
            renderingSystem.retireResource(old::dispose);
        }
        distanceTexture = device.createTexture(TextureDesc.texture2D(Format.R32_SFLOAT, width, height,
                Set.of(TextureUsage.STORAGE, TextureUsage.SAMPLED)), null);
        // 图换代后旧集不再匹配：延迟释放（可能仍被上一帧命令引用）
        if (computeSet != null) {
            DescriptorSet oldCompute = computeSet;
            computeSet = null;
            renderingSystem.retireResource(oldCompute::dispose);
        }
        if (viewSet != null) {
            DescriptorSet oldView = viewSet;
            viewSet = null;
            renderingSystem.retireResource(oldView::dispose);
        }
        log.info("光追求交距离图: {}x{}", width, height);
    }

    private ComputePipeline ensureComputePipeline() {
        if (computePipeline == null) {
            computePipeline = device.createComputePipeline(new ComputePipelineDesc(
                    new ShaderDesc(ShaderStage.COMPUTE, computeSpv),
                    List.of(computeSetDesc), COMPUTE_PUSH_BYTES));
        }
        return computePipeline;
    }

    /**
     * compute 集（binding0 加速结构 / binding1 距离图 / binding2 世界位置）。
     *
     * <p>模式 1 的世界位置绑定依旧要合法（着色器只声明不采样），故传 1×1 占位图。**不能**再拿
     * 距离图充数：它在 binding1 是 storage（描述符布局恒 GENERAL）、到 binding2 又要求
     * SHADER_READ_ONLY_OPTIMAL，同一张图两种布局互斥 → VUID-00344。</p>
     */
    private DescriptorSet computeSetFor(RayTracingScene scene, Texture distance,
                                        Texture worldPos, Sampler worldPosSampler) {
        Tlas tlas = scene.tlas();
        Texture boundWorldPos = worldPos != null ? worldPos : placeholderWorldPos();
        if (computeSet != null && computeSetTexture == distance
                && computeSetWorldPos == boundWorldPos && boundTlas == tlas) {
            return computeSet;
        }
        if (computeSetLayout == null) {
            computeSetLayout = device.createDescriptorSetLayout(computeSetDesc);
        }
        // 绑定对象一变就**换新集**、旧集延迟释放，不做原地 update：上一帧提交的命令可能还引用
        // 这个集（切相机↔主光正好命中「worldPos 变、distance 不变」→ VUID-03047）
        if (computeSet != null) {
            DescriptorSet old = computeSet;
            renderingSystem.retireResource(old::dispose);
        }
        computeSet = device.createDescriptorSet(computeSetLayout);
        Sampler sampler = worldPosSampler != null
                ? worldPosSampler : nearestSampler();
        computeSet.update(DescriptorUpdate.accelerationStructure(0, tlas),
                DescriptorUpdate.storageTexture(1, distance),
                DescriptorUpdate.sampledTexture(2, boundWorldPos, sampler));
        computeSetTexture = distance;
        computeSetWorldPos = boundWorldPos;
        boundTlas = tlas;
        return computeSet;
    }

    /** 1×1 占位世界位置图（惰性创建，模式 1 无 G-Buffer 时充 binding2） */
    private Texture placeholderWorldPos() {
        if (placeholderWorldPos == null) {
            placeholderWorldPos = device.createTexture(TextureDesc.texture2D(Format.R8G8B8A8_UNORM,
                    1, 1, Set.of(TextureUsage.SAMPLED)), null);
            log.info("光追求交占位世界位置图: 1x1（避免距离图双绑触发 VUID-00344）");
        }
        return placeholderWorldPos;
    }

    private Sampler nearestSampler() {
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(SamplerDesc.nearest());
        }
        return nearestSampler;
    }

    private DescriptorSet viewSetFor(Texture distance) {
        if (viewSet != null && viewSetTexture == distance) {
            return viewSet;
        }
        if (viewSetLayout == null) {
            viewSetLayout = device.createDescriptorSetLayout(viewSetDesc);
        }
        if (viewSet != null) {
            DescriptorSet old = viewSet;
            renderingSystem.retireResource(old::dispose);
        }
        if (nearestSampler == null) {
            nearestSampler = device.createSampler(SamplerDesc.nearest());
        }
        viewSet = device.createDescriptorSet(viewSetLayout);
        viewSet.update(DescriptorUpdate.sampledTexture(0, distance, nearestSampler));
        viewSetTexture = distance;
        return viewSet;
    }

    private GraphicsPipeline pipelineFor(Format colorFormat) {
        GraphicsPipeline cached = pipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipeline created = buildPipeline(fragSpv, viewSetDesc, colorFormat);
        pipelines.put(colorFormat, created);
        log.info("光追求交视图 PSO created: colorFormat={}", colorFormat);
        return created;
    }

    /** 对照段 PSO：同顶点/同全屏三角形，只换片元与 set0 布局（4 绑定） */
    private GraphicsPipeline comparePipelineFor(Format colorFormat) {
        GraphicsPipeline cached = comparePipelines.get(colorFormat);
        if (cached != null) {
            return cached;
        }
        GraphicsPipeline created = buildPipeline(compareFragSpv, compareSetDesc, colorFormat);
        comparePipelines.put(colorFormat, created);
        log.info("CSM 对照 PSO created: colorFormat={}", colorFormat);
        return created;
    }

    private GraphicsPipeline buildPipeline(byte[] frag, DescriptorSetLayoutDesc setDesc, Format colorFormat) {
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, frag),
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
                List.of(setDesc), VIEW_PUSH_BYTES,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR), SampleCount.COUNT_1);
        return device.createGraphicsPipeline(desc);
    }

    /**
     * 对照段 set0：0 距离图 / 1 世界位置 / 2 阴影 atlas（比较采样器）/ 3 CSM 参数 UBO。
     *
     * <p>绑定对象变化即换新集、旧集延迟释放（同 computeSetFor 的口径）。</p>
     */
    private DescriptorSet compareSetFor(Texture distance, Texture worldPos, Texture atlas) {
        if (compareSet != null && compareSetDistance == distance
                && compareSetWorldPos == worldPos && compareSetAtlas == atlas) {
            return compareSet;
        }
        if (compareSetLayout == null) {
            compareSetLayout = device.createDescriptorSetLayout(compareSetDesc);
        }
        if (compareSet != null) {
            DescriptorSet old = compareSet;
            renderingSystem.retireResource(old::dispose);
        }
        compareSet = device.createDescriptorSet(compareSetLayout);
        compareSet.update(
                DescriptorUpdate.sampledTexture(0, distance, nearestSampler()),
                DescriptorUpdate.sampledTexture(1, worldPos, renderingSystem.gbufferSampler()),
                DescriptorUpdate.sampledTexture(2, atlas, compareShadowSampler()),
                DescriptorUpdate.uniformBuffer(3, csmCompareUbo, 0, RenderingSystem.CSM_COMPARE_BYTES));
        compareSetDistance = distance;
        compareSetWorldPos = worldPos;
        compareSetAtlas = atlas;
        return compareSet;
    }

    /** 阴影比较采样器（与 RenderingSystem.shadowSampler 同参数：LINEAR + LESS + CLAMP） */
    private Sampler compareShadowSampler() {
        if (compareShadowSampler == null) {
            compareShadowSampler = device.createSampler(new SamplerDesc(
                    Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
                    SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                    SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1000.0f, CompareOp.LESS));
        }
        return compareShadowSampler;
    }

    private static void putMatrix(ByteBuffer out, Matrix4fc m) {
        out.putFloat(m.m00()).putFloat(m.m01()).putFloat(m.m02()).putFloat(m.m03());
        out.putFloat(m.m10()).putFloat(m.m11()).putFloat(m.m12()).putFloat(m.m13());
        out.putFloat(m.m20()).putFloat(m.m21()).putFloat(m.m22()).putFloat(m.m23());
        out.putFloat(m.m30()).putFloat(m.m31()).putFloat(m.m32()).putFloat(m.m33());
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
        if (spv == null || !spv.isValid()) {
            throw new IllegalStateException(EditorLang.text("rayTraceView.assetMissing", path));
        }
        return spv.getSpvData();
    }

    private static byte[] loadComputeSpv(AssetManager assetManager, String path) {
        ComputeShader shader = assetManager.loadAsset(path, ComputeShader.class);
        if (shader == null || !shader.isValid()) {
            throw new IllegalStateException(EditorLang.text("rayTraceView.assetMissing", path));
        }
        return shader.getSpirv();
    }
}
