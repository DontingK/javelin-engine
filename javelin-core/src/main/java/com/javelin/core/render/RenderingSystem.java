package com.javelin.core.render;

import com.javelin.core.Time;
import com.javelin.core.render.mesh.MeshUpload;
import com.javelin.core.render.texture.TextureLoader;
import com.javelin.rhi.Buffer;
import com.javelin.rhi.DescriptorSet;
import com.javelin.rhi.DescriptorSetLayout;
import com.javelin.rhi.DescriptorUpdate;
import com.javelin.rhi.Device;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.Sampler;
import com.javelin.rhi.Texture;
import com.javelin.rhi.UploadData;
import com.javelin.rhi.VertexLayout;
import com.javelin.rhi.data.RHIScissor;
import com.javelin.rhi.data.RHIViewport;
import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.DescriptorSetLayoutDesc;
import com.javelin.rhi.desc.GraphicsPipelineDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.desc.SamplerDesc;
import com.javelin.rhi.desc.ShaderDesc;
import com.javelin.rhi.desc.TextureDesc;
import com.javelin.rhi.types.BlendFactor;
import com.javelin.rhi.types.BlendOp;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.ColorComponentFlags;
import com.javelin.rhi.types.CompareOp;
import com.javelin.rhi.types.CullMode;
import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.ShaderStage;
import com.javelin.rhi.types.TextureUsage;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 渲染系统（Engine_Rendering_Design.md 第 2 节）：注册表 + 每帧收集 + 帧描述组装 + 渲染侧常驻资源。
 *
 * <p>v0.1 形态（场景系统落地前的直接注册模式，用户拍板 2026-09-24）：
 * 上层经 addCamera / addRenderer 直接注册；未来场景层（阶段②）由 scene.add
 * 按组件类型自动调用同一套注册 API，本类不变。
 *
 * <p>持有的常驻资源：UBO1 帧环形缓冲（2 槽 × 槽容量，槽号 = frameIndex % slots，
 * 与 FrameState 环形同奇偶，复用前有 fence 闸门，RHI 24.5）、set0/set1 描述符布局、
 * 采样器、白图占位纹理、内置无光照着色程序的 PSO 缓存（按 VertexLayout + 是否混合为键）、
 * 每相机深度纹理（resize 时换绑，旧纹理交 Cleaner）。
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

    // UBO1 帧环形 + set0
    private final Buffer uboBuffer;
    private final DescriptorSetLayout frameSetLayout;
    private final DescriptorSet frameSet;
    // set1（材质贴图，v0.1 直挂纹理）
    private final DescriptorSetLayout materialSetLayout;
    private final Sampler sampler;
    private final Texture whiteTexture;

    // 内置着色程序
    private byte[] builtinVs;
    private byte[] builtinFs;
    private Format colorAttachmentFormat;
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    private final Map<Texture, DescriptorSet> textureSets = new HashMap<>();

    // 每相机深度纹理（视口独立，RHI 19.4-2）
    private final Map<String, Texture> depthTextures = new HashMap<>();
    private int depthWidth;
    private int depthHeight;

    private boolean closed;

    public RenderingSystem(Device device, FrameLoop frameLoop) {
        this(device, frameLoop, 2);
    }

    public RenderingSystem(Device device, FrameLoop frameLoop, int frameSlots) {
        if (device == null || frameLoop == null) {
            throw new IllegalArgumentException("device / frameLoop must not be null");
        }
        if (frameSlots < 1) {
            throw new IllegalArgumentException("frameSlots must be >= 1, got: " + frameSlots);
        }
        this.device = device;
        this.frameLoop = frameLoop;
        this.frameSlots = frameSlots;

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

        this.materialSetLayout = device.createDescriptorSetLayout(new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT))));
        this.sampler = device.createSampler(SamplerDesc.linear());
        this.whiteTexture = TextureLoader.createSolid(device, 0xFF, 0xFF, 0xFF, 0xFF);
    }

    // ═══ 注册表（未来 scene.add 自动调用同一套 API） ═══

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
            depthTextures.remove(camera.name());
        }
    }

    /** 注册渲染对象（触发 Mesh lazy 上传与贴图描述符集预建） */
    public void addRenderer(RenderObject object) {
        if (object == null) {
            throw new IllegalArgumentException("object must not be null");
        }
        MeshUpload.upload(device, object.mesh());
        textureSet(object.texture());
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

    // ═══ 内置着色程序 ═══

    /**
     * 安装内置无光照着色程序的 SPIR-V（core 主代码不含 GLSL 源码：着色器源放启动/应用层
     * 资源，由调用方编译——测试工程为 src/test/resources/shaders/ 经 TestShaders）。
     *
     * <p>须在首次 collectFrame 之前调用。
     */
    public void installBuiltinShaders(byte[] vertexSpirv, byte[] fragmentSpirv) {
        if (vertexSpirv == null || fragmentSpirv == null) {
            throw new IllegalArgumentException("vertexSpirv / fragmentSpirv must not be null");
        }
        this.builtinVs = vertexSpirv.clone();
        this.builtinFs = fragmentSpirv.clone();
    }

    /** Pass 回调内取 PSO（按 VertexLayout + 是否混合缓存，PSO 不每帧新建，RHI 16.4） */
    public GraphicsPipeline pipelineFor(VertexLayout layout, boolean blend) {
        checkShaders();
        String key = layout.hashCode() + (blend ? ":blend" : ":opaque");
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, builtinVs),
                new ShaderDesc(ShaderStage.FRAGMENT, builtinFs),
                layout,
                PrimitiveTopology.TRIANGLE_LIST,
                CullMode.BACK,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                true, !blend, CompareOp.LESS,
                blend,
                blend ? BlendFactor.SRC_ALPHA : BlendFactor.ONE,
                blend ? BlendFactor.ONE_MINUS_SRC_ALPHA : BlendFactor.ZERO,
                BlendOp.ADD,
                blend ? BlendFactor.SRC_ALPHA : BlendFactor.ONE,
                blend ? BlendFactor.ONE_MINUS_SRC_ALPHA : BlendFactor.ZERO,
                BlendOp.ADD,
                Set.of(ColorComponentFlags.R, ColorComponentFlags.G,
                        ColorComponentFlags.B, ColorComponentFlags.A),
                List.of(colorAttachmentFormat()), depthFormat(),
                List.of(frameLayoutDesc(), materialLayoutDesc()),
                PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR));
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("builtin PSO created: layout={} blend={}", layout, blend);
        return created;
    }

    /** Pass 回调内取贴图描述符集（null 贴图 → 白图占位；按纹理缓存） */
    public DescriptorSet textureSet(Texture texture) {
        Texture key = texture != null ? texture : whiteTexture;
        return textureSets.computeIfAbsent(key, t -> {
            DescriptorSet set = device.createDescriptorSet(materialSetLayout);
            set.update(DescriptorUpdate.sampledTexture(0, t, sampler));
            return set;
        });
    }

    /** Pass 回调内绑 set0（UBO1 帧槽，dynamic offset 由 RenderView.uboOffset 提供） */
    public DescriptorSet frameSet() {
        return frameSet;
    }

    /** 每相机深度纹理（视口独立；首次访问按当前交换链尺寸创建，resize 换绑） */
    public Texture depthFor(String cameraName) {
        return depthTextures.computeIfAbsent(cameraName, n ->
                device.createTexture(TextureDesc.depth2D(depthFormat(), depthWidth, depthHeight,
                        Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), (UploadData) null));
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
        checkShaders();
        checkOpen();
        int w = frameLoop.swapChain().width();
        int h = frameLoop.swapChain().height();
        ensureDepthSize(w, h);

        int slotBase = (int) (frameIndex % frameSlots) * SLOT_SIZE;
        float tpf = Time.getDeltaTime();

        List<RenderView> views = new ArrayList<>();
        int camIndex = 0;
        for (RenderCamera cam : cameras) {
            cam.recalculate();
            uboBuffer.writeAligned(slotBase + camIndex * CAMERA_STRIDE,
                    FloatBuffer.wrap(cam.viewProj().get(new float[16])));
            RenderList list = collectList(cam);
            views.add(new RenderView(cam.name(),
                    slotBase + camIndex * CAMERA_STRIDE,
                    new RHIViewport(cam.viewportX(), cam.viewportY(),
                            cam.viewportWidth(), cam.viewportHeight(), 0.0f, 1.0f),
                    new RHIScissor(cam.viewportX(), cam.viewportY(),
                            cam.viewportWidth(), cam.viewportHeight()),
                    list));
            camIndex++;
        }
        uboBuffer.writeAligned(slotBase + TIME_OFFSET,
                FloatBuffer.wrap(new float[]{Time.getTime(), tpf}));

        return new FrameDescription(frameIndex, backbuffer, views,
                Time.getTime(), tpf, slotBase, CAMERA_STRIDE);
    }

    private RenderList collectList(RenderCamera cam) {
        RenderList list = new RenderList();
        Vector3f worldPos = new Vector3f();
        Map<RenderObject, Float> viewZ = new HashMap<>();
        for (RenderObject ro : renderers) {
            if (ro.isTransparent()) {
                ro.world().getTranslation(worldPos);
                worldPos.mulPosition(cam.view());
                viewZ.put(ro, worldPos.z);
                list.addTransparent(ro);
            } else {
                list.addOpaque(ro);
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

    private Format colorAttachmentFormat() {
        if (colorAttachmentFormat == null) {
            colorAttachmentFormat = frameLoop.swapChain().image(0).desc().format();
        }
        return colorAttachmentFormat;
    }

    private static Format depthFormat() {
        return Format.D32_SFLOAT;
    }

    private DescriptorSetLayoutDesc frameLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX)));
    }

    private DescriptorSetLayoutDesc materialLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    /** 交换链尺寸变化 → 深度纹理全部换绑（旧纹理引用丢弃，交 Cleaner 延迟销毁，RHI 17.1） */
    private void ensureDepthSize(int w, int h) {
        if (w == depthWidth && h == depthHeight && !depthTextures.isEmpty()) {
            return;
        }
        if (!depthTextures.isEmpty()) {
            log.info("depth size changed: {}x{} → {}x{}, rebind all view depths", depthWidth, depthHeight, w, h);
            depthTextures.clear();
        }
        depthWidth = w;
        depthHeight = h;
    }

    private void checkShaders() {
        if (builtinVs == null || builtinFs == null) {
            throw new IllegalStateException(
                    "内置着色程序未安装：先调用 installBuiltinShaders(vs, fs)（SPIR-V 由启动层经 shaderc 编译）");
        }
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
        textureSets.values().forEach(DescriptorSet::dispose);
        textureSets.clear();
        depthTextures.values().forEach(Texture::dispose);
        depthTextures.clear();
        frameSet.dispose();
        frameSetLayout.dispose();
        materialSetLayout.dispose();
        uboBuffer.dispose();
        sampler.dispose();
        whiteTexture.dispose();
    }
}
