package com.javelin.core.render;

import com.javelin.core.Time;
import com.javelin.core.render.material.Material;
import com.javelin.core.render.material.MaterialBlock;
import com.javelin.core.render.material.MaterialProperty;
import com.javelin.core.render.material.MaterialPropertyType;
import com.javelin.core.render.material.PassState;
import com.javelin.core.render.material.Shader;
import com.javelin.core.render.material.ShaderPass;
import com.javelin.core.render.mesh.MeshUpload;
import com.javelin.core.render.texture.Texture2D;
import com.javelin.core.render.texture.TextureUpload;
import com.javelin.rhi.Buffer;
import com.javelin.rhi.DescriptorSet;
import com.javelin.rhi.DescriptorSetLayout;
import com.javelin.rhi.DescriptorUpdate;
import com.javelin.rhi.Device;
import com.javelin.rhi.GraphicsPipeline;
import com.javelin.rhi.Sampler;
import com.javelin.rhi.Texture;
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
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.DescriptorType;
import com.javelin.rhi.types.DynamicState;
import com.javelin.rhi.types.Filter;
import com.javelin.rhi.types.Format;
import com.javelin.rhi.types.FrontFace;
import com.javelin.rhi.types.PolygonMode;
import com.javelin.rhi.types.PrimitiveTopology;
import com.javelin.rhi.types.SamplerAddressMode;
import com.javelin.rhi.types.SamplerMipmapMode;
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

    // UBO1 帧环形 + set0
    private final Buffer uboBuffer;
    private final DescriptorSetLayout frameSetLayout;
    private final DescriptorSet frameSet;
    // set 1 占位（ObjectBlock：per-object 走 push constants，v0.1 空布局，RHI 20.4 编号占位）
    private final DescriptorSetLayout emptySetLayout;

    // 材质桥：set 2 布局按 Shader 缓存；材质资源按 Material 实例缓存；采样器按参数缓存
    private final Map<Shader, DescriptorSetLayout> materialLayouts = new HashMap<>();
    private final Map<Shader, DescriptorSetLayoutDesc> materialLayoutDescs = new HashMap<>();
    private final Map<Material, MaterialResources> materialResources = new HashMap<>();
    private final Map<SamplerDesc, Sampler> samplers = new HashMap<>();

    private Format colorAttachmentFormat;
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();

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
            depthTextures.remove(camera.name());
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
     * Pass 回调内取 PSO（按 Shader 前向 Pass + 顶点布局缓存，PSO 不每帧新建，RHI 16.4）：
     * SPIR-V 与固定状态（PassState）取自 ShaderPass，set 布局 = set0 帧 + set1 空占位 +
     * set2 材质（RHI 20.3/20.4）。
     */
    public GraphicsPipeline pipelineFor(Material material, VertexLayout layout) {
        checkOpen();
        Shader shader = material.getShader();
        String key = System.identityHashCode(shader) + ":" + layout.hashCode();
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        ShaderPass pass = shader.forwardPass();
        PassState s = pass.getState();
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, pass.getVertexSpirv()),
                new ShaderDesc(ShaderStage.FRAGMENT, pass.getFragmentSpirv()),
                layout,
                PrimitiveTopology.TRIANGLE_LIST,
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
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR));
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("material PSO created: shader={} pass={} layout={}",
                shader.getShaderName(), pass.getName(), layout);
        return created;
    }

    /**
     * Pass 回调内取材质 set 2 描述符集（首渲染懒创建 + 按材质缓存）。
     *
     * <p>数值参数（MaterialBlock，std140）每次调用重打包写入 UBO——运行期
     * setFloat/setColor 即时生效；贴图槽仅在首绑解析（占位约定 white/bump/black，
     * RHI 21.4），运行期换贴图 = 换材质实例（v0.1 语义）。
     */
    public DescriptorSet materialSet(Material material) {
        checkOpen();
        if (material == null || material.getShader() == null) {
            throw new IllegalArgumentException("material / material.shader must not be null");
        }
        MaterialResources resources = materialResources.computeIfAbsent(material, this::createMaterialResources);
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
        for (MaterialProperty property : shader.getProperties()) {
            if (property.getType() != MaterialPropertyType.TEXTURE_2D) {
                continue;
            }
            Texture2D texture = material.getTexture(property.getName());
            if (texture == null) {
                texture = placeholderTexture(property.getTexturePlaceholder());
            }
            Texture gpu = TextureUpload.upload(device, texture);
            set.update(DescriptorUpdate.sampledTexture(binding++, gpu,
                    samplerFor(texture.getFilterMode(), texture.getWrapMode())));
        }
        log.info("material set created: shader={} uboBytes={}", shader.getShaderName(), uboSize);
        return new MaterialResources(set, ubo);
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

    private DescriptorSetLayoutDesc frameLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.VERTEX)));
    }

    private DescriptorSetLayoutDesc emptySetLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of());
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
        materialResources.values().forEach(r -> {
            r.set.dispose();
            r.ubo.dispose();
        });
        materialResources.clear();
        materialLayouts.values().forEach(DescriptorSetLayout::dispose);
        materialLayouts.clear();
        depthTextures.values().forEach(Texture::dispose);
        depthTextures.clear();
        frameSet.dispose();
        frameSetLayout.dispose();
        emptySetLayout.dispose();
        samplers.values().forEach(Sampler::dispose);
        samplers.clear();
        uboBuffer.dispose();
    }

    /** 材质实例的渲染侧资源（set 2 描述符集 + MaterialBlock UBO） */
    private static final class MaterialResources {
        final DescriptorSet set;
        final Buffer ubo;

        MaterialResources(DescriptorSet set, Buffer ubo) {
            this.set = set;
            this.ubo = ubo;
        }
    }
}
