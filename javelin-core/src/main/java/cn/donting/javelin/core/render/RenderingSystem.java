package cn.donting.javelin.core.render;

import cn.donting.javelin.core.Time;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.MaterialBlock;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.MaterialPropertyType;
import cn.donting.javelin.core.render.material.DefaultMaterial;
import cn.donting.javelin.core.render.material.PassState;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.material.ShaderPass;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.mesh.MeshUpload;
import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.core.render.texture.TextureUpload;
import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.desc.BufferDesc;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.MemoryPreference;
import cn.donting.javelin.rhi.desc.SamplerDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.desc.TextureDesc;
import cn.donting.javelin.rhi.types.BufferUsage;
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
import cn.donting.javelin.rhi.types.TextureType;
import cn.donting.javelin.rhi.types.TextureUsage;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渲染系统（Engine_Rendering_Design.md 第 2 节）：注册表 + 每帧收集 + 帧描述组装 + 渲染侧常驻资源。
 *
 * <p>v0.1 形态：上层经 addCamera / addRenderer 直接注册（直注册模式），场景层由
 * SceneRenderBridge 每帧 pull 翻译成同一套注册 API（Engine_Implementation_Plan 拍板
 * 2026-09-25：pull，本类不感知 scene 包）。
 *
 * <p>材质桥（Engine_Material_Design.md §4，2026-09-25 落地）：
 * <ul>
 *   <li>set 2 布局按 Shader 缓存（binding0 = MaterialBlock UBO，binding1..n = 贴图槽
 *   按声明序；RHI 20.3 硬约束：所有 Pass 共用同一布局）；set 1 为 ObjectBlock 占位
 *   （引擎 per-object 走 push constants，v0.1 空布局）；</li>
 *   <li>materialSet(Material)：材质首渲染懒创建 UBO + 描述符集，贴图槽经
 *   {@link TextureUpload} 懒上传 + 占位贴图解析（white/bump/black，RHI 21.4）；
 *   数值参数（MaterialBlock std140）每次调用重打包写入，运行期 setFloat/setColor 即时生效；
 *   贴图槽仅在首绑解析（运行期换贴图 = 换材质实例，v0.1 语义）；</li>
 *   <li>pipelineFor(Material, VertexLayout)：PSO 按 Shader 的前向 Pass + 顶点布局缓存，
 *   固定状态取自 PassState（RHI 21.3）；SPIR-V 来自 .shader 编译产物
 *   （core 不含着色器源码，用户拍板 2026-09-25）。</li>
 * </ul>
 *
 * <p>持有的常驻资源：UBO1 帧环形缓冲（2 槽 × 槽容量，槽号 = frameIndex % slots，
 * 与 FrameState 环形同奇偶，复用前有 fence 闸门，RHI 24.5）、set0/set1(set2) 描述符
 * 布局、采样器缓存、每相机深度纹理（resize 时换绑，旧纹理交 Cleaner）。
 *
 * <p>线程模型：v0.1 单线程退化（27.1），collectFrame 在图声明回调内被渲染帧调用。
 */
public final class RenderingSystem implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RenderingSystem.class);

    /** 内置着色程序 push constants 声明范围：mat4 model(64B) + float tpf(4B)，按 4 对齐取 80 */
    public static final int PUSH_SIZE = 80;
    public static final int PUSH_TPF_OFFSET = 64;

    /** 单帧支持的最大相机数（UBO1 槽内相机块数） */
    public static final int MAX_CAMERAS = 4;
    /** 相机块步长：必须满足 minUniformBufferOffsetAlignment（256B，RHI 19.5-1） */
    private static final int CAMERA_STRIDE = 256;
    /** 槽内布局：相机块 × MAX_CAMERAS，其后 256B 时间块（timeSec/tpf，引擎私有约定） */
    private static final int TIME_OFFSET = MAX_CAMERAS * CAMERA_STRIDE;
    private static final int SLOT_SIZE = TIME_OFFSET + 256;

    private final Device device;
    private final FrameLoop frameLoop;
    private final int frameSlots;

    private final List<RenderCamera> cameras = new ArrayList<>();
    private final List<RenderObject> renderers = new ArrayList<>();
    /** depth 排序缓存（collectFrame 每帧复用：预分配 + clear，热路径规矩） */
    private final List<RenderCamera> sortedCameras = new ArrayList<>();
    /** 首帧收集一次性诊断日志（渲染链读音入口，常驻保留） */
    private boolean collectLogged;

    // UBO1 帧环形 + set0
    private final Buffer uboBuffer;
    private final DescriptorSetLayout frameSetLayout;
    private final DescriptorSet frameSet;
    // set 1 空布局预留光照数据（2026-10-23 拍板，原 ObjectBlock 占位语义作废，per-object 恒走 push constants）
    private final DescriptorSetLayout emptySetLayout;

    // 材质桥：set 2 布局按 Shader 缓存；材质资源按 Material 实例缓存；采样器按参数缓存
    private final Map<Shader, DescriptorSetLayout> materialLayouts = new HashMap<>();
    private final Map<Shader, DescriptorSetLayoutDesc> materialLayoutDescs = new HashMap<>();
    private final Map<Material, MaterialResources> materialResources = new HashMap<>();
    private final Map<SamplerDesc, Sampler> samplers = new HashMap<>();

    private Format colorAttachmentFormat;
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    // PSO 旁注（key 结构不动，对账判据用）：pipeline → 所属 Shader（资产热重载 reconcile 退役旧 Shader 的 PSO）
    private final Map<GraphicsPipeline, Shader> pipelineOwners = new HashMap<>();

    // 每相机深度纹理（视口独立，RHI 19.4-2）
    private final Map<String, Texture> depthTextures = new HashMap<>();
    // 每视口 MSAA 附件（视口独立，同 depthFor 模式；AA 关时惰性创建永不发生）
    private final Map<String, Texture> msaaColorTextures = new HashMap<>();
    private final Map<String, Texture> msaaDepthTextures = new HashMap<>();
    // 每视口拾取附件（GPU ID-buffer 拾取，Engine_Picking_Design；PickSystem 启用时 PickPass 惰性创建）
    private final Map<String, Texture> pickIdTextures = new HashMap<>();
    private final Map<String, Texture> pickDepthTextures = new HashMap<>();
    // 退役纹理（removeCamera 无 waitIdle 时机保证，两帧后 dispose）
    private final List<RetiredTexture> retiredTextures = new ArrayList<>();
    // 退役渲染资源（资产热重载对账：PSO/布局/set+UBO，两帧语义同 retiredTextures）
    private final List<RetiredResource> retiredResources = new ArrayList<>();
    // 资产热重载对账脏标记（requestReconcile 置位，collectFrame 帧首消费）
    private boolean reconcilePending;
    // 最近一次 collectFrame 的帧号（retire 条目时间戳基准）
    private long lastFrame = -1;
    /** 生效采样档（构造期由引擎钳制后传入；COUNT_1 = AA 关） */
    private final SampleCount effectiveSamples;
    private int depthWidth;
    private int depthHeight;

    private boolean closed;

    public RenderingSystem(Device device, FrameLoop frameLoop) {
        this(device, frameLoop, 2);
    }

    public RenderingSystem(Device device, FrameLoop frameLoop, int frameSlots) {
        this(device, frameLoop, frameSlots, SampleCount.COUNT_1);
    }

    /**
     * @param sampleCount 生效 MSAA 采样档（引擎启动期钳制后传入；
     *                    COUNT_1 = 关闭。运行期切换需 PSO/附件失效协议，后置）
     */
    public RenderingSystem(Device device, FrameLoop frameLoop, int frameSlots,
                           SampleCount sampleCount) {
        if (device == null || frameLoop == null) {
            throw new IllegalArgumentException("device / frameLoop must not be null");
        }
        if (frameSlots < 1) {
            throw new IllegalArgumentException("frameSlots must be >= 1, got: " + frameSlots);
        }
        if (sampleCount == null) {
            throw new IllegalArgumentException("sampleCount must not be null");
        }
        this.device = device;
        this.frameLoop = frameLoop;
        this.frameSlots = frameSlots;
        this.effectiveSamples = sampleCount;

        this.uboBuffer = device.createBuffer(
                new BufferDesc(frameSlots * SLOT_SIZE, Set.of(BufferUsage.UNIFORM),
                        MemoryPreference.hostVisibleCoherent()),
                null);
        this.frameSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX))));
        this.frameSet = device.createDescriptorSet(frameSetLayout);
        // range = 相机块步长（viewProj 64B 实需；槽尾时间块不占描述符范围，
        // 否则末槽末相机 offset+range 会越出缓冲）
        this.frameSet.update(DescriptorUpdate.dynamicUniformBuffer(0, uboBuffer, 0, CAMERA_STRIDE));

        this.emptySetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of()));
    }

    // ═══ 注册表（SceneRenderBridge pull / 直注册模式共用同一套 API） ═══

    public void addCamera(RenderCamera camera) {
        if (camera == null) {
            throw new IllegalArgumentException("camera must not be null");
        }
        if (cameras.size() >= MAX_CAMERAS) {
            throw new IllegalStateException("相机数超过 UBO1 槽容量 " + MAX_CAMERAS + "（MAX_CAMERAS）");
        }
        cameras.add(camera);
    }

    public void removeCamera(RenderCamera camera) {
        cameras.remove(camera);
        if (camera != null) {
            retire(depthTextures.remove(camera.name()));
            retire(msaaColorTextures.remove(camera.name()));
            retire(msaaDepthTextures.remove(camera.name()));
            retire(pickIdTextures.remove(camera.name()));
            retire(pickDepthTextures.remove(camera.name()));
        }
    }

    /** 注册渲染对象（触发 Mesh lazy 上传与材质 set 2 描述符集预建） */
    public void addRenderer(RenderObject object) {
        if (object == null) {
            throw new IllegalArgumentException("object must not be null");
        }
        MeshUpload.upload(device, object.mesh());
        materialSet(object.material());
        renderers.add(object);
    }

    public void removeRenderer(RenderObject object) {
        renderers.remove(object);
    }

    public List<RenderCamera> cameras() {
        return cameras;
    }

    public List<RenderObject> renderers() {
        return renderers;
    }

    // ═══ 材质桥（Engine_Material_Design.md §4） ═══

    /**
     * Pass 回调内取 PSO（按 shader Pass + 顶点布局 + 图元拓扑 + 采样档 + 材质面型缓存，
     * PSO 不每帧新建，RHI 16.4）：SPIR-V 与固定状态取自 shaderPassName 命中的
     * {@link ShaderPass}（严格制，拍板 2026-10-17：Pass 名即阶段归属，不声明不画），
     * 其中 Blend/depthWrite 按材质 surfaceType 覆盖（URP 口径，Material_Design §4.1：
     * TRANSPARENT → Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off，OPAQUE → Blend Off /
     * ZWrite On；Cull/ZTest/ColorMask 保留 Shader 声明，覆盖经 {@link PassState#copy()}
     * 不回写 Shader 资产）。采样档 = 本 Pass 附件 MSAA 档（rasterizationSamples 必须
     * 与附件一致，同材质双档并存不串）；图元类型 = Mesh 拓扑；set 布局 = set0 帧 +
     * set1 空占位 + set2 材质（RHI 20.3/20.4）。
     */
    public GraphicsPipeline pipelineFor(Material material, VertexLayout layout,
                                        PrimitiveTopology topology, SampleCount samples,
                                        String passName) {
        checkOpen();
        // 坏 shader 回退解析（DefaultMaterial，拍板 2026-10-27）：shader null / SPIR-V 缺失 → 默认材质
        material = DefaultMaterial.resolve(material);
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        Shader shader = material.getShader();
        String key = System.identityHashCode(shader) + ":" + layout.hashCode()
                + ":" + topology + ":" + samples + ":" + material.getSurfaceType()
                + ":" + passName;
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        ShaderPass pass = shader.pass(passName);
        if (pass == null) {
            throw new IllegalStateException("Shader[" + shader.getShaderName()
                    + "] 未声明 Pass: " + passName + "（严格制：Pass 名即阶段归属）");
        }
        PassState s = pass.getState().copy();
        if (material.getSurfaceType() == SurfaceType.TRANSPARENT) {
            s.setBlend(true, BlendFactor.SRC_ALPHA, BlendFactor.ONE_MINUS_SRC_ALPHA);
            s.setDepthWrite(false);
        } else {
            s.setBlend(false, BlendFactor.ONE, BlendFactor.ZERO);
            s.setDepthWrite(true);
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, pass.getVertexSpirv()),
                new ShaderDesc(ShaderStage.FRAGMENT, pass.getFragmentSpirv()),
                layout,
                topology,
                s.getCull(),
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                true, s.isDepthWrite(), s.getDepthTest(),
                s.isBlendEnabled(),
                s.getSrcColor(), s.getDstColor(),
                s.getBlendOp(),
                s.getSrcAlpha(), s.getDstAlpha(),
                s.getBlendOp(),
                s.getColorMask(),
                List.of(colorAttachmentFormat()), depthFormat(),
                List.of(frameLayoutDesc(), emptySetLayoutDesc(), materialLayoutDesc(shader)),
                PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                samples);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        pipelineOwners.put(created, shader);
        log.info("material PSO created: shader={} pass={} layout={} topology={} surfaceType={}",
                shader.getShaderName(), pass.getName(), layout, topology, material.getSurfaceType());
        return created;
    }

    /**
     * Pass 回调内取材质 set 2 描述符集（首渲染懒创建 + 按材质缓存）。
     *
     * <p>数值参数（MaterialBlock，std140）每次调用重打包写入 UBO——运行期
     * setFloat/setColor 即时生效；贴图槽按材质的 {@code textureRevision} 比对，变了即两帧
     * 安全退役旧资源重建——运行期 setTexture/setShader 自动生效（占位约定 white/bump/black，
     * RHI 21.4）。</p>
     */
    public DescriptorSet materialSet(Material material) {
        checkOpen();
        // 坏 shader 回退解析（DefaultMaterial，拍板 2026-10-27）：shader null / SPIR-V 缺失 → 默认材质
        Material resolved = DefaultMaterial.resolve(material);
        if (resolved == null || resolved.getShader() == null) {
            throw new IllegalArgumentException("material / material.shader must not be null");
        }
        material = resolved;
        // 贴图槽运行时变更（setTexture/setShader）：材质自记版本，不一致即两帧安全退役旧
        // 资源并重建——免去调用方记得触发对账（运行期脚本/检查器改贴图自动生效）
        MaterialResources resources = materialResources.get(material);
        if (resources != null && (resources.textureRevision != material.getTextureRevision()
                || resources.stale(material))) {
            MaterialResources retired = resources;
            retireResource(() -> {
                retired.set.dispose();
                retired.ubo.dispose();
            });
            materialResources.remove(material);
            resources = null;
        }
        if (resources == null) {
            resources = createMaterialResources(material);
            materialResources.put(material, resources);
        }
        resources.ubo.writeAligned(0, FloatBuffer.wrap(MaterialBlock.pack(material)));
        return resources.set;
    }

    /** Pass 回调内绑 set0（UBO1 帧槽，dynamic offset 由 RenderView.uboOffset 提供） */
    public DescriptorSet frameSet() {
        return frameSet;
    }

    /** 每相机深度纹理（视口独立；首次访问按当前交换链尺寸创建，resize 换绑） */
    public Texture depthFor(String cameraName) {
        return depthTextures.computeIfAbsent(cameraName, n ->
                device.createTexture(TextureDesc.depth2D(depthFormat(), depthWidth, depthHeight,
                        Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), null));
    }

    /**
     * 每视口 MSAA 颜色附件（视口独立；仅 AA 开启时创建，AA 关恒 null——
     * 上层以 null 判定走 1x 直写）。首次访问按当前交换链尺寸创建，resize 换绑。
     */
    public Texture msaaColorFor(String cameraName) {
        if (effectiveSamples == SampleCount.COUNT_1) {
            return null;
        }
        return msaaColorTextures.computeIfAbsent(cameraName, n -> device.createTexture(
                new TextureDesc(TextureType.TYPE_2D, colorAttachmentFormat(), depthWidth, depthHeight,
                        1, 1, 1, effectiveSamples, Set.of(TextureUsage.COLOR_ATTACHMENT),
                        MemoryPreference.deviceLocal()),
                null));
    }

    /**
     * 每视口 MSAA 深度附件（与 msaaColorFor 成对同档——同一次渲染通道内
     * 附件采样数必须一致，验证层抓混搭）。AA 关恒 null。
     */
    public Texture msaaDepthFor(String cameraName) {
        if (effectiveSamples == SampleCount.COUNT_1) {
            return null;
        }
        return msaaDepthTextures.computeIfAbsent(cameraName, n -> device.createTexture(
                new TextureDesc(TextureType.TYPE_2D, depthFormat(), depthWidth, depthHeight,
                        1, 1, 1, effectiveSamples, Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT),
                        MemoryPreference.deviceLocal()),
                null));
    }

    /** 生效采样档（COUNT_1 = AA 关） */
    public SampleCount effectiveSampleCount() {
        return effectiveSamples;
    }

    /**
     * 每视口拾取 ID 附件（GPU ID-buffer 拾取，R32_UINT：像素值 = 帧内分配的 pick ID，
     * 0 = 背景/未命中）。恒 1x（与主画面 MSAA 档解耦，点击像素与附件一一对应），
     * usage 含 TRANSFER_SRC（点击读回 CopyImageToBuffer）。PickSystem 启用时由
     * PickPass 惰性创建，resize 换绑同 depthFor。
     */
    public Texture pickIdFor(String cameraName) {
        return pickIdTextures.computeIfAbsent(cameraName, n -> device.createTexture(
                new TextureDesc(TextureType.TYPE_2D, Format.R32_UINT, depthWidth, depthHeight,
                        1, 1, 1, SampleCount.COUNT_1,
                        Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.TRANSFER_SRC),
                        MemoryPreference.deviceLocal()),
                null));
    }

    /** 每视口拾取深度附件（拾取 pass 自有深度，与主深度解耦——MSAA resolve 语义无关） */
    public Texture pickDepthFor(String cameraName) {
        return pickDepthTextures.computeIfAbsent(cameraName, n ->
                device.createTexture(TextureDesc.depth2D(depthFormat(), depthWidth, depthHeight,
                        Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), null));
    }

    // ═══ 每帧收集（逻辑侧帧首；单线程退化下由图声明回调驱动） ═══

    /**
     * 收集一帧：UBO1 写入 → 每相机 RenderList 分类排序 → 帧描述组装。
     *
     * <p>视锥剔除（AABB + FrustumIntersection）属引擎优化点，用户拍板后置（2026-09-25），
     * 当前全部渲染对象进列表（Demo 规模无压力）。
     *
     * @param frameIndex 单调帧号（与 FrameLoop.frameCounter 一致，槽位 = frameIndex % frameSlots）
     * @param backbuffer 本帧 acquire 到的交换链图像（帧描述终点）
     */
    public FrameDescription collectFrame(long frameIndex, Texture backbuffer) {
        checkOpen();
        int w = frameLoop.swapChain().width();
        int h = frameLoop.swapChain().height();
        ensureDepthSize(w, h);
        drainRetired(frameIndex);
        if (reconcilePending) {
            reconcilePending = false;
            reconcile();
        }
        lastFrame = frameIndex;

        int slotBase = (int) (frameIndex % frameSlots) * SLOT_SIZE;
        float tpf = Time.getDeltaTime();
        sortCamerasByDepth();

        List<RenderView> views = new ArrayList<>();
        int camIndex = 0;
        for (RenderCamera cam : sortedCameras) {
            cam.recalculate();
            uboBuffer.writeAligned(slotBase + camIndex * CAMERA_STRIDE,
                    FloatBuffer.wrap(cam.viewProj().get(new float[16])));
            RenderList list = collectList(cam);
            if (!collectLogged) {
                collectLogged = true;
                log.info("首帧收集: backbuffer={}x{} 相机[{}] viewport={}x{} 不透明={} 透明={}",
                        w, h, cam.name(), cam.viewportWidth(), cam.viewportHeight(),
                        list.opaque().size(), list.transparent().size());
            }
            views.add(new RenderView(cam.name(),
                    slotBase + camIndex * CAMERA_STRIDE,
                    new RHIViewport(cam.viewportX(), cam.viewportY(),
                            cam.viewportWidth(), cam.viewportHeight(), 0.0f, 1.0f),
                    new RHIScissor(cam.viewportX(), cam.viewportY(),
                            cam.viewportWidth(), cam.viewportHeight()),
                    list, null, cam.clearColor(), cam.clearFlags()));
            camIndex++;
        }
        uboBuffer.writeAligned(slotBase + TIME_OFFSET,
                FloatBuffer.wrap(new float[]{Time.getTime(), tpf}));

        return new FrameDescription(frameIndex, backbuffer, views,
                Time.getTime(), tpf, slotBase, CAMERA_STRIDE);
    }

    /** 相机渲染序 = depth 升序稳定排序（升序即"值越大越晚渲染"；同值保持注册序） */
    private void sortCamerasByDepth() {
        sortedCameras.clear();
        sortedCameras.addAll(cameras);
        // 插入排序：≤MAX_CAMERAS(4)；严格大于比较保证稳定性
        for (int i = 1; i < sortedCameras.size(); i++) {
            RenderCamera key = sortedCameras.get(i);
            int j = i - 1;
            while (j >= 0 && sortedCameras.get(j).depth() > key.depth()) {
                sortedCameras.set(j + 1, sortedCameras.get(j));
                j--;
            }
            sortedCameras.set(j + 1, key);
        }
    }

    private RenderList collectList(RenderCamera cam) {
        RenderList list = new RenderList();
        Vector3f worldPos = new Vector3f();
        Map<RenderObject, Float> viewZ = new HashMap<>();
        int cullingMask = cam.cullingMask();
        for (RenderObject ro : renderers) {
            if ((ro.layerMask() & cullingMask) == 0) {
                continue;   // 层级裁剪：对象 Layer 不在相机 cullingMask 内，跳过
            }
            // Pass 名即阶段归属（严格制，拍板 2026-10-17）："Forward" → 前向主通道按
            // 材质面型分桶；其他声明名 → 自定义 Pass 桶（管线有则消费、无则忽略）；
            // 无 Forward 声明不进主通道（gizmo 单 Pass Gizmo 即此——只被 GizmoPass
            // 单画，不再走"Opaque 先画 + 末尾重绘置顶"双画）
            for (ShaderPass pass : ro.material().getShader().getPasses()) {
                if (RenderPass.FORWARD_PASS_NAME.equals(pass.getName())) {
                    if (ro.isTransparent()) {
                        ro.world().getTranslation(worldPos);
                        worldPos.mulPosition(cam.view());
                        viewZ.put(ro, worldPos.z);
                        list.addTransparent(ro);
                    } else {
                        list.addOpaque(ro);
                    }
                } else {
                    list.addPass(pass.getName(), ro);
                }
            }
            if (ro.isCastShadow()) {
                list.addShadowCaster(ro);
            }
        }
        list.opaque().sort(Comparator.comparingLong(RenderObject::bucketKey));
        list.transparent().sort(Comparator.comparingDouble(o -> viewZ.getOrDefault(o, 0.0f)));
        return list;
    }

    // ═══ 内部 ═══

    /** 材质首渲染：建 set 2 布局（按 Shader 缓存）+ UBO + 描述符集（贴图懒上传 + 占位解析） */
    private MaterialResources createMaterialResources(Material material) {
        Shader shader = material.getShader();
        DescriptorSetLayout layout = materialLayouts.computeIfAbsent(shader,
                s -> device.createDescriptorSetLayout(materialLayoutDesc(s)));
        DescriptorSet set = device.createDescriptorSet(layout);

        int uboSize = MaterialBlock.uboByteSize(shader);
        Buffer ubo = device.createBuffer(
                new BufferDesc(uboSize, Set.of(BufferUsage.UNIFORM),
                        MemoryPreference.hostVisibleCoherent()),
                null);
        set.update(DescriptorUpdate.uniformBuffer(0, ubo, 0, uboSize));

        int binding = 1;
        List<String> textureSlots = new ArrayList<>();
        List<SlotBinding> slotBindings = new ArrayList<>();
        for (MaterialProperty property : shader.getProperties()) {
            if (property.getType() != MaterialPropertyType.TEXTURE_2D) {
                continue;
            }
            Texture2D assigned = material.getTexture(property.getName());
            Texture2D texture = assigned;
            // image 未引用的纹理壳（.texture 新建未选图）同未赋槽走占位——
            // 上传路径对 null image 会抛，这里挡掉
            if (texture == null || texture.getImage() == null) {
                texture = placeholderTexture(property.getTexturePlaceholder());
            }
            slotBindings.add(new SlotBinding(property.getName(), assigned,
                    assigned == null ? -1 : assigned.getRevision()));
            textureSlots.add(property.getName() + "="
                    + (assigned != null && assigned.getImage() != null ? assigned.getGuid() : "占位"));
            Texture gpu = TextureUpload.upload(device, texture);
            set.update(DescriptorUpdate.sampledTexture(binding++, gpu,
                    samplerFor(texture.getFilterMode(), texture.getWrapMode())));
        }
        log.info("material set created: shader={} uboBytes={} textureSlots={}",
                shader.getShaderName(), uboSize, textureSlots);
        return new MaterialResources(set, ubo, shader, slotBindings, material.getTextureRevision());
    }

    /** 未赋值贴图槽的占位解析（RHI 21.4：white / bump / black） */
    private static Texture2D placeholderTexture(String placeholder) {
        if ("black".equals(placeholder)) {
            return Texture2D.blackTexture();
        }
        if ("bump".equals(placeholder) || "normal".equals(placeholder)) {
            return Texture2D.normalTexture();
        }
        return Texture2D.whiteTexture();
    }

    /** 采样器按 (filter, wrap) 缓存（filter/wrap 参数在此消费，Engine_Material_Design.md §4.2） */
    private Sampler samplerFor(Texture2D.FilterMode filter, Texture2D.WrapMode wrap) {
        Filter f = filter == Texture2D.FilterMode.POINT ? Filter.NEAREST : Filter.LINEAR;
        SamplerAddressMode address = wrap == Texture2D.WrapMode.CLAMP
                ? SamplerAddressMode.CLAMP_TO_EDGE : SamplerAddressMode.REPEAT;
        SamplerDesc desc = new SamplerDesc(f, f, SamplerMipmapMode.LINEAR,
                address, address, address, 1.0f, 1000.0f);
        return samplers.computeIfAbsent(desc, device::createSampler);
    }

    /** set 2 布局元数据（按 Shader 缓存）：binding0 = MaterialBlock UBO，binding1..n = 贴图槽按声明序 */
    private DescriptorSetLayoutDesc materialLayoutDesc(Shader shader) {
        return materialLayoutDescs.computeIfAbsent(shader, s -> {
            List<DescriptorSetLayoutDesc.Binding> bindings = new ArrayList<>();
            bindings.add(new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER, 1,
                    ShaderStage.FRAGMENT));
            int binding = 1;
            for (MaterialProperty property : s.getProperties()) {
                if (property.getType() == MaterialPropertyType.TEXTURE_2D) {
                    bindings.add(new DescriptorSetLayoutDesc.Binding(binding++,
                            DescriptorType.COMBINED_IMAGE_SAMPLER, 1, ShaderStage.FRAGMENT));
                }
            }
            return new DescriptorSetLayoutDesc(bindings);
        });
    }

    private Format colorAttachmentFormat() {
        if (colorAttachmentFormat == null) {
            colorAttachmentFormat = frameLoop.swapChain().image(0).desc().format();
        }
        return colorAttachmentFormat;
    }

    private static Format depthFormat() {
        return Format.D32_SFLOAT;
    }

    /** set0（UBO1 相机）布局契约：覆盖制引擎 pass（拾取等）自建 PSO 复用，与材质管线 set0 同构 */
    public DescriptorSetLayoutDesc frameSetLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX)));
    }

    private DescriptorSetLayoutDesc frameLayoutDesc() {
        return frameSetLayoutDesc();
    }

    private DescriptorSetLayoutDesc emptySetLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of());
    }

    /**
     * 交换链尺寸变化 → 深度纹理全部换绑，旧纹理当场显式释放。
     * 尺寸变化仅发生于交换链 recreate 之后（w/h 读自 swapChain），recreate 首行
     * vkDeviceWaitIdle——帧首销毁时旧纹理必不在飞行中，且释放先于同帧新纹理分配，
     * 高频 resize 不累积设备内存。
     */
    private void ensureDepthSize(int w, int h) {
        if (w == depthWidth && h == depthHeight && !depthTextures.isEmpty()) {
            return;
        }
        if (!depthTextures.isEmpty()) {
            log.info("depth size changed: {}x{} → {}x{}, rebind all view depths", depthWidth, depthHeight, w, h);
            disposeAll(depthTextures);
            disposeAll(msaaColorTextures);
            disposeAll(msaaDepthTextures);
            disposeAll(pickIdTextures);
            disposeAll(pickDepthTextures);
        }
        depthWidth = w;
        depthHeight = h;
    }

    /** 手动 dispose 优先、Cleaner 仅兜底：map 内纹理显式释放后清空 */
    private static void disposeAll(Map<String, Texture> textures) {
        textures.values().forEach(Texture::dispose);
        textures.clear();
    }

    private void retire(Texture texture) {
        if (texture != null) {
            retiredTextures.add(new RetiredTexture(texture, lastFrame));
        }
    }

    /** 退役纹理到期销毁：stamp + 2 帧（maxFramesInFlight=2，帧 N 的栅栏在帧 N+2 首被 await） */
    private void drainRetired(long frameIndex) {
        if (!retiredTextures.isEmpty()) {
            retiredTextures.removeIf(r -> {
                if (frameIndex >= r.frame + 2) {
                    r.texture.dispose();
                    return true;
                }
                return false;
            });
        }
        if (!retiredResources.isEmpty()) {
            retiredResources.removeIf(r -> {
                if (frameIndex >= r.frame + 2) {
                    r.disposeAction.run();
                    return true;
                }
                return false;
            });
        }
    }

    // ═══ 资产热重载对账（Engine_AssetReload_Design.md §4.4） ═══

    /**
     * 资产热重载对账请求：置脏标记，下一帧 collectFrame 首执行 mark-and-sweep
     * 孤儿回收（单线程退化下帧首即安全点；重载是事件不是常态，帧路径零常驻成本）。
     * 全程不抛。
     */
    public void requestReconcile() {
        reconcilePending = true;
    }

    /**
     * 对账：活集 = renderers 的 Material 实例 + 其 Shader + 默认材质单例（坏 shader
     * 回退渲染体，DefaultMaterial.resolve 的资源键；materialSet/pipelineFor 消费方只有
     * AbstractForwardPass，均出自 renderers；PickPass/DebugLinePass 自建
     * PSO 不经缓存）。孤儿/烤死状态退役走两帧队列（GPU in-flight 安全）：
     * <ul>
     *   <li>materialResources：实例不在活集（.mat 换指针）/ builtFromShader 变
     *   （shader 被换）/ 任一贴图槽实例或占位状态变（贴图热重载——活描述符集强持
     *   旧 GPU 贴图既不上屏也永不释放）→ 退役重建；</li>
     *   <li>materialLayouts / materialLayoutDescs：key Shader 不在活集 → 退役；</li>
     *   <li>pipelines：旁注 Shader 不在活集 → 退役（identityHashCode 换代后旧条目
     *   永不再命中）。</li>
     * </ul>
     */
    private void reconcile() {
        Set<Material> liveMaterials = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Shader> liveShaders = Collections.newSetFromMap(new IdentityHashMap<>());
        for (RenderObject ro : renderers) {
            Material m = ro.material();
            if (m == null) {
                continue;
            }
            liveMaterials.add(m);
            if (m.getShader() != null) {
                liveShaders.add(m.getShader());
            }
        }
        // 默认材质单例（坏 shader 回退渲染体，DefaultMaterial.resolve 资源键）显式入活集——防对账误退役
        Material defaultMaterial = DefaultMaterial.current();
        if (defaultMaterial != null) {
            liveMaterials.add(defaultMaterial);
            if (defaultMaterial.getShader() != null) {
                liveShaders.add(defaultMaterial.getShader());
            }
        }
        materialResources.entrySet().removeIf(e -> {
            if (!liveMaterials.contains(e.getKey()) || e.getValue().stale(e.getKey())) {
                MaterialResources r = e.getValue();
                retireResource(() -> {
                    r.set.dispose();
                    r.ubo.dispose();
                });
                return true;
            }
            return false;
        });
        materialLayouts.entrySet().removeIf(e -> {
            if (!liveShaders.contains(e.getKey())) {
                DescriptorSetLayout layout = e.getValue();
                retireResource(layout::dispose);
                return true;
            }
            return false;
        });
        materialLayoutDescs.keySet().removeIf(s -> !liveShaders.contains(s));
        pipelines.entrySet().removeIf(e -> {
            Shader owner = pipelineOwners.get(e.getValue());
            if (owner != null && !liveShaders.contains(owner)) {
                GraphicsPipeline pipeline = e.getValue();
                retireResource(pipeline::dispose);
                pipelineOwners.remove(pipeline);
                return true;
            }
            return false;
        });
    }

    private void retireResource(Runnable disposeAction) {
        retiredResources.add(new RetiredResource(disposeAction, lastFrame));
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("RenderingSystem has been closed");
        }
    }

    /** 显式释放引擎持有的渲染资源；调用方须保证 device 已 waitIdle（FrameLoop.close 已做） */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        pipelineOwners.clear();
        materialResources.values().forEach(r -> {
            r.set.dispose();
            r.ubo.dispose();
        });
        materialResources.clear();
        materialLayouts.values().forEach(DescriptorSetLayout::dispose);
        materialLayouts.clear();
        disposeAll(depthTextures);
        disposeAll(msaaColorTextures);
        disposeAll(msaaDepthTextures);
        disposeAll(pickIdTextures);
        disposeAll(pickDepthTextures);
        retiredTextures.forEach(r -> r.texture.dispose());
        retiredTextures.clear();
        retiredResources.forEach(r -> r.disposeAction.run());
        retiredResources.clear();
        frameSet.dispose();
        frameSetLayout.dispose();
        emptySetLayout.dispose();
        samplers.values().forEach(Sampler::dispose);
        samplers.clear();
        uboBuffer.dispose();
    }

    /** 材质实例的渲染侧资源（set 2 描述符集 + MaterialBlock UBO + 首绑对账凭据） */
    private static final class MaterialResources {
        final DescriptorSet set;
        final Buffer ubo;
        /** 首绑 Shader（对账判据：被换即退役重建——UBO 尺寸/贴图槽数已变） */
        final Shader builtFromShader;
        /** 首绑贴图槽实例表（对账判据：任一槽实例/占位状态变 → 退役重建） */
        final List<SlotBinding> slotBindings;
        /** 建造时的材质贴图槽版本（materialSet 每帧比对：换了贴图即退役重建） */
        final int textureRevision;

        MaterialResources(DescriptorSet set, Buffer ubo, Shader builtFromShader,
                          List<SlotBinding> slotBindings, int textureRevision) {
            this.set = set;
            this.ubo = ubo;
            this.builtFromShader = builtFromShader;
            this.slotBindings = slotBindings;
            this.textureRevision = textureRevision;
        }

        /** 对账判据：shader 被换 或 任一贴图槽实例变、或该纹理内容/采样版本变（Texture2D.revision） */
        boolean stale(Material material) {
            if (builtFromShader != material.getShader()) {
                return true;
            }
            for (SlotBinding b : slotBindings) {
                Texture2D assigned = material.getTexture(b.slot);
                if (assigned != b.assigned) {
                    return true;
                }
                if (assigned != null && assigned.getRevision() != b.textureRevision) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 首绑贴图槽凭据（assigned 可 null = 占位；textureRevision = 首绑时该纹理的内容/采样版本） */
    private static final class SlotBinding {
        final String slot;
        final Texture2D assigned;
        final int textureRevision;

        SlotBinding(String slot, Texture2D assigned, int textureRevision) {
            this.slot = slot;
            this.assigned = assigned;
            this.textureRevision = textureRevision;
        }
    }

    /** 退役渲染资源条目（两帧 dispose，同 RetiredTexture 语义；disposeAction 须幂等安全） */
    private static final class RetiredResource {
        final Runnable disposeAction;
        final long frame;

        RetiredResource(Runnable disposeAction, long frame) {
            this.disposeAction = disposeAction;
            this.frame = frame;
        }
    }

    /** 退役纹理条目（stamp = 退役时最近一次已收集的帧号） */
    private static final class RetiredTexture {
        final Texture texture;
        final long frame;

        RetiredTexture(Texture texture, long frame) {
            this.texture = texture;
            this.frame = frame;
        }
    }
}
