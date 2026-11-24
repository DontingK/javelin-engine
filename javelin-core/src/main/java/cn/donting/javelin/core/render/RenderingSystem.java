package cn.donting.javelin.core.render;

import cn.donting.javelin.core.Time;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.MaterialBlock;
import cn.donting.javelin.core.render.material.MaterialProperty;
import cn.donting.javelin.core.render.material.MaterialPropertyType;
import cn.donting.javelin.core.render.material.DefaultMaterial;
import cn.donting.javelin.core.render.material.LightingModel;
import cn.donting.javelin.core.render.material.PassState;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.rt.RayTracingScene;
import cn.donting.javelin.core.render.material.ShaderPass;
import cn.donting.javelin.core.render.material.SurfaceType;
import cn.donting.javelin.core.render.ddgi.DdgiAtlases;
import cn.donting.javelin.core.render.ddgi.DdgiProbeGrid;
import cn.donting.javelin.core.render.pipeline.pass.GBufferPass;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.mesh.MeshUpload;
import cn.donting.javelin.core.render.texture.CubemapImage;
import cn.donting.javelin.core.render.texture.Texture2D;
import cn.donting.javelin.core.render.texture.TextureUpload;
import cn.donting.javelin.core.settings.DdgiSettings;
import cn.donting.javelin.core.settings.ShadowSettings;

import cn.donting.javelin.rhi.Buffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.Sampler;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.UploadData;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.CompareOp;
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
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
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
 *   按声明序；RHI 20.3 硬约束：所有 Pass 共用同一布局）；set 1 = 帧级光照通道
 *   （批次②，Engine_Lighting_Design §3.4：binding0 灯参数 UBO + binding1 lightList）；</li>
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

    /**
     * 内置着色程序 push constants 声明范围：mat4 model(64B) + float tpf(4B) +
     * float receiveShadow(4B)，按 4 对齐取 80。
     */
    public static final int PUSH_SIZE = 80;
    public static final int PUSH_TPF_OFFSET = 64;
    /**
     * per-object 阴影接收标志位（四期阴影质量分级 §3.6，FORWARD 档承载）：
     * 注入段读 {@code pc.receiveShadow}，1=接影 / 0=不接影；本批前该位为 padding（恒 0），
     * 旧产物不重导时读到 0 = 全场景不接影——故注入器改动须强制重导。
     */
    public static final int PUSH_RECEIVE_SHADOW_OFFSET = 68;

    /** 单帧支持的最大相机数（UBO1 槽内相机块数） */
    public static final int MAX_CAMERAS = 4;
    /**
     * 场景 HDR 颜色格式（Engine_PostProcess_Design §3.3）：后处理链生效时场景色缓冲
     * （lightingRT / 后处理中间 RT）与**场景材质 PSO 的颜色附件**同用此格式。
     */
    public static final Format HDR_SCENE_COLOR_FORMAT = Format.R16G16B16A16_SFLOAT;
    /**
     * SSAO 单通道 AO 格式（Engine_SSAO_Design §3.4）：AO 值域 [0,1]、半分辨率。
     * **2026-11-21 由 `R8_UNORM` 升 `R16_SFLOAT`**（BUG-12 修法④）：8 位只有 256 级，
     * 合成时 `intensity > 1` 把量化台阶放大成可见带状；半分辨率下带宽代价可忽略。
     */
    public static final Format SSAO_AO_FORMAT = Format.R16_SFLOAT;
    /** 相机块步长：必须满足 minUniformBufferOffsetAlignment（256B，RHI 19.5-1） */
    private static final int CAMERA_STRIDE = 256;
    /** 槽内布局：相机块 × MAX_CAMERAS，其后 256B 时间块（timeSec/tpf，引擎私有约定） */
    private static final int TIME_OFFSET = MAX_CAMERAS * CAMERA_STRIDE;
    private static final int SLOT_SIZE = TIME_OFFSET + 256;

    // ── 光照帧级通道（三期批次②，Engine_Lighting_Design §3.3/§3.4）──
    /** LightingParamsBlock 帧槽步长（std140 实需 672B，256 对齐进取 768；动态偏移单位，四期 CSM §4.6） */
    static final int LIGHTING_STRIDE = 768;
    /**
     * LightingParamsBlock 实需尺寸（四期 CSM §4.6 + REND-21-C2）：方向光 256 + counts/grid/zParams/viewport/ambient
     * 各 16（80）+ sunViewProj[4] 256 + cascadeSplits 16 + shadowParams 16 + shadowParams2 16
     * + cascadeInvZ 16 + contactParams 16 = 672。
     */
    static final int LIGHTING_BLOCK_BYTES = 672;

    /** 接触阴影最大步进距离（世界单位，约 35cm；REND-21-C2 §4 P4） */
    static final float CONTACT_SHADOW_MAX_DISTANCE = 0.35f;
    /** 接触阴影步数（每像素 texture 采样次数；REND-21-C2 §4 P4） */
    static final float CONTACT_SHADOW_STEPS = 10.0f;
    /**
     * 环境项（rgb + 强度）：v0.x 起由 `Environment.ambientMode` 推送（四期 P4-04 步骤一
     * 的常量档）；`OFF`/无环境组件 = 纯黑（Unity 口径——无环境源即纯黑，2026-11-01
     * 用户拍板）。天空盒球谐（SH9）环境光为后续步骤（灯光档 §3.3/§10）。
     */
    private final Color ambientColor = new Color(0.0f, 0.0f, 0.0f, 1.0f);
    /** 环境项强度（着色端 ambient.rgb * ambient.a 的 a） */
    private float ambientIntensity = 0.0f;
    /**
     * 环境光模式（四期 P4-04 步骤一 → P4-05 B3 扩 DDGI）：{@code OFF}/{@code CONSTANT} 走
     * {@code ambient.rgb * ambient.a}；{@code DDGI} 由探针辐照度接管（打包进 set1 binding9 的
     * {@code ddgiParams.x}，探针数据面未就绪时恒 0 = 回落常量项）。
     */
    private AmbientMode ambientMode = AmbientMode.OFF;

    // ── 主光阴影（批次④ → 四期 CSM，Engine_Lighting_Design §4.5 / Engine_Shadow_Quality_Design §4）──
    /**
     * 阴影图缺省单级联边长（主光组件 {@code shadowResolution} 缺省值；实际分辨率随组件参数
     * 按需重建，per-view atlas 边长 = 分辨率 × grid）。原「全局单张 2048²」口径已被 CSM per-view 取代。
     */
    public static final int SHADOW_MAP_SIZE = 2048;

    /**
     * 材质 alpha 裁剪阈值属性名（REND-21-A，Engine_Shadow_Cutout_Design §5）：与内置
     * {@code Lit.shader} 的 {@code _AlphaCutoff ("Alpha Cutoff", Range(0,1))} 同名同义，
     * 缺省 0 = 不裁剪。阴影 Pass 与主渲染注入的 {@code javelinAlphaClip} 共用此阈值。
     */
    public static final String ALPHA_CUTOFF_PROPERTY = "_AlphaCutoff";
    /** alpha 裁剪取样纹理槽名（与内置 Lit 的 albedo 槽一致） */
    public static final String ALBEDO_TEXTURE_PROPERTY = "_MainTex";

    private final Device device;
    private final FrameLoop frameLoop;
    private final int frameSlots;
    /** 光追场景（P4-06 B2b；设备支持才建，否则 null——B3 消费前每帧 build 不占开销） */
    private RayTracingScene rayTracingScene;

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
    // set1 帧级光照通道（批次②落光照数据面、批次③ per-view 化）：binding0 = LightingParamsBlock
    // UBO（DYNAMIC，每视口偏移，引擎常驻）；binding1/2/3 = lightList/clusterGrid/clusterIndices
    // 按<b>视口</b>各自缓冲（首视口口径挂账随批次③解除；registerFrameStorage 通用通道已清退）
    private final DescriptorSetLayout frameStorageSetLayout;
    /** 每视口灯光资源：binding1/2/3 三缓冲 + set1 描述符集（视口像素尺寸变化重建） */
    private final Map<String, ViewLighting> viewLights = new HashMap<>();

    private final ClusterBuilder clusterBuilder = new ClusterBuilder();
    /** grid 序列化暂存（cells×2 int，尺寸变化重分配；小端直写 hostVisible） */
    private ByteBuffer gridPack;
    /** 索引池序列化暂存（poolUsed int；小端直写 hostVisible） */
    private ByteBuffer poolPack;

    // 主光阴影资源（批次④→四期 CSM）：per-view 资源族 + 深度比较采样器（set1 binding4 消费）。
    // CSM 级联依赖各相机视锥 → shadowMap 由「全局单张」改 per-view（computeShadow(cam) 每帧
    // 拟合；分辨率/级联数变化即重建、旧图两帧退役）。多级联共图 = atlas（cascadeCount≥2 时 2×2）。
    private final Map<String, ViewShadow> viewShadows = new HashMap<>();
    private final Sampler shadowSampler;
    /**
     * 阴影原始深度采样器（REND-21-C1 PCSS 阻挡搜索，2026-11-25）：与 {@link #shadowSampler}
     * 绑同一张 atlas，但**不带深度比较**、NEAREST 取样——阻挡搜索要真实深度值而不是 0/1
     * 可见性。NEAREST 而非 LINEAR：深度格式的线性过滤支持因设备而异（RHI-15 能力表不校验采样
     * 滤波），NEAREST 全平台安全，16/24 tap 圆盘本身已足够平滑。
     */
    private final Sampler shadowRawSampler;
    /**
     * DDGI 消费面采样器（set1 binding8/10：辐照度 / 深度矩 atlas）。consumption 侧手写
     * 双线性（tile 内区 clamp，不跨 tile 渗漏），故 NEAREST + CLAMP 足够。
     */
    private final Sampler ddgiSampler;
    /** DDGI 参数 UBO 尺寸（std140：网格 origin+spacing / counts+tilesPerRow / tile 参数 / 运行态） */
    static final int DDGI_PARAM_BYTES = 96;
    /** atlas 未就绪时 set1 binding8/10 的占位图（1×1；仅保证描述符完整，着色端按 mode 门控不采样） */
    private Texture ddgiPlaceholder;
    /** G-Buffer 采样器（HYBRID 档 LightingPass 消费；逐像素一一对应，NEAREST + CLAMP） */
    private final Sampler gbufferSampler;
    /** 末帧主光阴影是否生效（主光存在且 castShadow）——ShadowPass 挂点判据（全局，与视口无关） */
    private boolean shadowSunActive;
    /** 阴影参数（品质档/强度/PCF 半径/参考深度偏移/bias 两项/CSM 级联三项，Engine_Shadow_Quality_Design §3.2/§4.8） */
    private ShadowSettings shadowSettings = new ShadowSettings();
    /** DDGI 参数（体素场分辨率/开关/探针网格/刷新节奏，Engine_DDGI_Design §3/§4） */
    private DdgiSettings ddgiSettings = new DdgiSettings();
    /** 消费面接管状态上一次的打印键（只在变化时落日志，见 {@code writeDdgiParams}） */
    private String ddgiStateLog;
    // 阴影收集域复用暂存（帧路径 exclusive，热路径规矩：预分配复用）
    /** 逐级联光空间 z 范围回填暂存（CSM，1/zRange 折算除数来源） */
    private final float[] shadowZRangeTmp = new float[4];
    /** CSM 级联混合带比例（相邻级联接缝软化；Babylon cascadeBlendPercentage 口径） */
    private static final float SHADOW_CASCADE_BLEND = 0.1f;
    /** 矩阵→float[] 拆解暂存 */
    private final float[] matTmp = new float[16];
    /** CSM 相机参数复用暂存（帧路径 exclusive） */
    private final ShadowMath.CascadeParams cascadeParams = new ShadowMath.CascadeParams();

    /**
     * 每视口阴影资源（Engine_Shadow_Quality_Design §4.4）：级联共图 atlas + 逐级联
     * 矩阵/切分/光空间 z 范围（参考深度偏移逐级联折算）。渲染域私有。
     */
    private static final class ViewShadow {
        Texture map;
        int resolution;
        int cascadeCount;
        /** atlas 每轴象限数（1 = 单张；2 = 2×2，cascadeCount 2 时只占前两象限） */
        int grid;
        int atlasSize;
        final Matrix4f[] matrices = new Matrix4f[4];
        final float[] splits = new float[4];
        /** 1 / 光空间 z 范围（各级联正交盒深度；参考深度偏移折算除数） */
        final float[] invZ = new float[4];

        ViewShadow() {
            for (int i = 0; i < 4; i++) {
                matrices[i] = new Matrix4f();
            }
        }

        void dispose() {
            map.dispose();
        }
    }

    // 视口天空盒（批次⑤）：RHI cubemap 纹理引用——skyboxAsset 非空时本系统持有
    // （setSkybox(CubemapImage) 懒上传产物）并负责退役/关闭释放；为空时调用方持有
    // （Demo 测试形态 setSkybox(Texture) 直灌）。场景级单引用——多视口共享，无 skybox
    // 时 null（ClearFlags.SKYBOX 视口回退 SOLID_COLOR 清屏，正常域空状态不弹错）
    private Texture skybox;
    /** 当前天空盒资产（桥按引用身份推送；null = 场景未挂，资产路径幂等判据） */
    private CubemapImage skyboxAsset;

    // 灯注册表 + 收集打包（批次②；SceneRenderBridge pull 翻译 Light → RenderLight）
    private final List<RenderLight> lights = new ArrayList<>();
    private final LightCollector lightCollector = new LightCollector();
    private final Buffer lightingBuffer;
    /** 每帧复用的打包暂存（小端直写 hostVisible） */
    private final ByteBuffer lightingPack = ByteBuffer.allocateDirect(LIGHTING_BLOCK_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer lightListPack = ByteBuffer.allocateDirect(
            LightCollector.MAX_POINT_LIGHTS * LightCollector.LIGHT_ITEM_FLOATS * 4)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** DDGI 参数 UBO 打包暂存（96B，P4-05 B3 消费面） */
    private final ByteBuffer ddgiPack = ByteBuffer.allocateDirect(DDGI_PARAM_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN);
    /** 末帧首视口灯数快照（F3 在册灯数） */
    private int lastPointLightCount;
    private int lastDirectionalCount;
    /** 超容 WARN 状态位（条件解除复位，周期性超限不重复刷屏） */
    private boolean pointOverflowActive;
    private boolean directionalOverflowActive;
    // 末帧 cluster 读数（首视口口径；F3 行 9 消费）
    private long clusterBuildNanos;
    private int clusterPoolUsed;
    /** cluster 索引池溢出 WARN 状态位（截断保护发生过的帧显性化） */
    private boolean clusterPoolOverflowActive;

    // 材质桥：set 2 布局按 Shader 缓存；材质资源按 Material 实例缓存；采样器按参数缓存
    private final Map<Shader, DescriptorSetLayout> materialLayouts = new HashMap<>();
    private final Map<Shader, DescriptorSetLayoutDesc> materialLayoutDescs = new HashMap<>();
    private final Map<Material, MaterialResources> materialResources = new HashMap<>();
    private final Map<SamplerDesc, Sampler> samplers = new HashMap<>();

    private Format colorAttachmentFormat;
    /** 场景色是否走 HDR 缓冲（后处理链生效；帧构建每帧设置，见 setHdrSceneColor） */
    private boolean hdrSceneColor;
    /** shader Pass 名 → PSO 颜色附件格式列表（未登记 = 单张 colorAttachmentFormat；
     *  HYBRID 的 "GBuffer" 登记三张 MRT——图缓存键已含 passName，无需扩键） */
    private final Map<String, List<Format>> passColorFormats = new HashMap<>();
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    // PSO 旁注（key 结构不动，对账判据用）：pipeline → 所属 Shader（资产热重载 reconcile 退役旧 Shader 的 PSO）
    private final Map<GraphicsPipeline, Shader> pipelineOwners = new HashMap<>();

    // 每相机深度纹理（视口独立，RHI 19.4-2）
    private final Map<String, Texture> depthTextures = new HashMap<>();
    // 每视口拾取附件（GPU ID-buffer 拾取，Engine_Picking_Design；PickSystem 启用时 PickPass 惰性创建）
    private final Map<String, Texture> pickIdTextures = new HashMap<>();
    private final Map<String, Texture> pickDepthTextures = new HashMap<>();
    // 每视口 G-Buffer 三张（HYBRID 档，Engine_RenderPipeline_Design §4.2/5.1；
    // 惰性创建于 GBufferPass 首次挂载，resize 同 depth 换绑）
    private final Map<String, Texture> gbufferAlbedoTextures = new HashMap<>();
    private final Map<String, Texture> gbufferNormalTextures = new HashMap<>();
    private final Map<String, Texture> gbufferWorldPosTextures = new HashMap<>();
    // 每视口 HDR 场景颜色（后处理链非空时，Engine_PostProcess_Design §3.3；惰性创建）
    private final Map<String, Texture> lightingRtTextures = new HashMap<>();
    // 每视口后处理中间 RT（链内非首尾节点输出，§3.4；key = cameraName#节点序）
    private final Map<String, Texture> postRtTextures = new HashMap<>();
    // 每视口 Bloom 多级 RT（Engine_PostProcess_Design §3.7；尺寸递减 1/2..1/16，key = cameraName#b级）
    private final Map<String, Texture> bloomRtTextures = new HashMap<>();
    // 每视口 SSAO RT（Engine_SSAO_Design §3.4；半分辨率 R8，key = cameraName#s序：0=AO 原始，1=模糊后）
    private final Map<String, Texture> ssaoRtTextures = new HashMap<>();
    // 每视口 TAA 历史色（Engine_TAA_Design §3.4；RGBA16F 全帧 ping-pong，key = cameraName#t序：0/1）
    private final Map<String, Texture> taaRtTextures = new HashMap<>();
    // 每视口 TAA 历史状态（上帧抖动后 viewProj + 帧号；TaaPass 重投影与失效判据的来源）
    private final Map<String, TaaHistory> taaHistories = new HashMap<>();
    // 每视口 DDGI 探针 atlas 对（Engine_DDGI_Design §3；按网格版面换代，key = cameraName）
    private final Map<String, DdgiAtlases> ddgiAtlasSets = new HashMap<>();
    /** TAA 投影抖动开关：仅 TAA 在链上才抖（RenderingSystem 不认链，由 JavelinEngine 下发）；相机级再叠加 */
    private boolean taaJitterEnabled;
    /** 抖动幅度（像素；内部常量 0.5 = thinTAA 同款半像素，满幅会把画面拉糊；不暴露给相机） */
    private static final float TAA_JITTER_SCALE = 0.5f;
    /** 历史权重（内部常量 0.95 = 1 - Babylon thinTAA `factor` 0.05；不暴露给相机） */
    private static final float TAA_BLEND_WEIGHT = 0.95f;
    /** Halton(2,3) 前 8 相亚像素偏移（基值已减 0.5，值域 [-0.5,0.5] 像素；frameIndex 取相） */
    private static final float[] TAA_JITTER_X =
            {0.0f, -0.25f, 0.25f, -0.375f, 0.125f, -0.125f, 0.375f, -0.4375f};
    private static final float[] TAA_JITTER_Y =
            {-0.166667f, 0.166667f, -0.388889f, -0.055556f, 0.277778f, -0.277778f, 0.055556f, 0.388889f};
    /** 抖动矩阵复用暂存（NDC 平移 T × P × V；帧路径 exclusive） */
    private final Matrix4f jitterMatrix = new Matrix4f();
    /** 写 UBO 的抖动后 viewProj 暂存（避免每帧分配数组） */
    private final float[] jitteredViewProjTmp = new float[16];
    /** 本帧抖动像素偏移暂存 {x,y} */
    private final float[] jitterPixel = new float[2];
    /** 相机位姿变化容差（未抖动 viewProj 逐元素比较；thinTAA 口径：移动即丢历史、停抖） */
    private static final float TAA_MOVE_EPS = 1e-5f;
    /** 位姿比较暂存（帧路径 exclusive） */
    private final float[] taaRawA = new float[16];
    private final float[] taaRawB = new float[16];
    /** 抖动注入首帧诊断（一次性） */
    private boolean taaJitterLogged;
    /** 历史失效告警（一次性，定位「抖动未被平均」） */
    private boolean taaMissingValidLogged;
    // 退役纹理（removeCamera 无 waitIdle 时机保证，两帧后 dispose）
    private final List<RetiredTexture> retiredTextures = new ArrayList<>();
    // 退役渲染资源（资产热重载对账：PSO/布局/set+UBO，两帧语义同 retiredTextures）
    private final List<RetiredResource> retiredResources = new ArrayList<>();
    // 资产热重载对账脏标记（requestReconcile 置位，collectFrame 帧首消费）
    private boolean reconcilePending;
    // 最近一次 collectFrame 的帧号（retire 条目时间戳基准）
    private long lastFrame = -1;
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
        // 光追场景只在设备真的启用 ray query 时创建（B3 之前不 build，无 GPU 开销）
        this.rayTracingScene = device.rayQueryEnabled() ? new RayTracingScene(device) : null;

        this.uboBuffer = device.createBuffer(
                new BufferDesc(frameSlots * SLOT_SIZE, Set.of(BufferUsage.UNIFORM),
                        MemoryPreference.hostVisibleCoherent()),
                null);
        this.frameSetLayout = device.createDescriptorSetLayout(frameSetLayoutDesc());
        this.frameSet = device.createDescriptorSet(frameSetLayout);
        // range = 相机块步长（viewProj 64B 实需；槽尾时间块不占描述符范围，
        // 否则末槽末相机 offset+range 会越出缓冲）
        this.frameSet.update(DescriptorUpdate.dynamicUniformBuffer(0, uboBuffer, 0, CAMERA_STRIDE));

        // set1 光照布局（批次② binding0 UBO + binding1 lightList；批次③ per-view 化后
        // binding2/3 = clusterGrid/clusterIndices——frameStorageLayoutDesc）
        this.frameStorageSetLayout = device.createDescriptorSetLayout(frameStorageLayoutDesc());
        // 光照 UBO（批次②）：binding0 帧槽 × 相机块，动态偏移——唯一引擎常驻光照缓冲；
        // lightList/clusterGrid/clusterIndices 归每视口（viewLightsFor 惰性创建）
        this.lightingBuffer = device.createBuffer(new BufferDesc(
                frameSlots * MAX_CAMERAS * LIGHTING_STRIDE, Set.of(BufferUsage.UNIFORM),
                MemoryPreference.hostVisibleCoherent()), null);

        // 主光阴影采样器（深度比较）：shadowMap 由 per-view 资源族按需创建（computeShadow(cam)），
        // 此处只建常驻比较采样器。LINEAR=硬件双线性 PCF：比较采样器配 LINEAR 滤波时 4 纹素
        // 比较结果按双线性权重平均，一次采样免费 2x2 软化（2026-11-01 实机边缘块状锯齿反馈
        // 换装，NEAREST 作废）；CLAMP_TO_EDGE 盒界外深度恒 1.0 采出受光、compare LESS 未遮挡=1.0
        this.shadowSampler = device.createSampler(new SamplerDesc(
                Filter.LINEAR, Filter.LINEAR, SamplerMipmapMode.LINEAR,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1000.0f, CompareOp.LESS));
        // 阴影原始深度采样器（PCSS 阻挡搜索，REND-21-C1）：同 atlas、无 compare、NEAREST
        this.shadowRawSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1000.0f));
        // G-Buffer 采样器（HYBRID 档）：LightingPass 全屏逐像素读 G-Buffer——
        // 无缩放无 mip，NEAREST + CLAMP 最省且与写入像素一一对应
        this.gbufferSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
        // DDGI 采样器（P4-05 B3）：与 G-Buffer 同档（NEAREST + CLAMP；双线性在着色端手写，
        // 因为 tile 内区外的边框不参与插值，硬件 LINEAR 会跨 tile 渗漏）
        this.ddgiSampler = device.createSampler(new SamplerDesc(
                Filter.NEAREST, Filter.NEAREST, SamplerMipmapMode.NEAREST,
                SamplerAddressMode.CLAMP_TO_EDGE, SamplerAddressMode.CLAMP_TO_EDGE,
                SamplerAddressMode.CLAMP_TO_EDGE, 1.0f, 1.0f));
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
            ViewLighting viewLighting = viewLights.remove(camera.name());
            if (viewLighting != null) {
                retireResource(viewLighting::dispose);
            }
            retire(depthTextures.remove(camera.name()));
            retire(pickIdTextures.remove(camera.name()));
            retire(pickDepthTextures.remove(camera.name()));
            ViewShadow viewShadow = viewShadows.remove(camera.name());
            if (viewShadow != null) {
                retire(viewShadow.map);
            }
            retire(lightingRtTextures.remove(camera.name()));
            retirePostRts(camera.name());
            retireBloomRts(camera.name());
            retireSsaoRts(camera.name());
            retireTaaRts(camera.name());
            retireDdgiAtlases(camera.name());
            taaHistories.remove(camera.name());
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

    /** 光追场景（P4-06 B2b；设备不支持光追时为 null） */
    public RayTracingScene rayTracingScene() {
        return rayTracingScene;
    }

    // ═══ 灯注册表（SceneRenderBridge pull 翻译；Engine_Lighting_Design §4 批次②） ═══

    /**
     * 注册灯（桥层过滤 isActiveAndEnabled 后调用；位姿/参数由桥每帧同步进 RenderLight）。
     */
    public void addLight(RenderLight light) {
        if (light == null) {
            throw new IllegalArgumentException("light must not be null");
        }
        lights.add(light);
    }

    public void removeLight(RenderLight light) {
        lights.remove(light);
    }

    /** 引擎侧灯注册表（只读；统计/调试消费） */
    public List<RenderLight> lights() {
        return lights;
    }

    /** 末帧首视口点/聚光在册数（剔除后，F3 overlay 口径） */
    public int pointLightCount() {
        return lastPointLightCount;
    }

    /** 末帧方向光在册数（含主光） */
    public int directionalLightCount() {
        return lastDirectionalCount;
    }

    /** 末帧首视口 cluster 构建耗时（纳秒；F3 行 9 读数） */
    public long clusterBuildNanos() {
        return clusterBuildNanos;
    }

    /** 末帧首视口 cluster 索引池占用（uint 数；分母=ClusterBuilder.MAX_POOL_UINTS） */
    public int clusterPoolUsed() {
        return clusterPoolUsed;
    }

    // ═══ 材质桥（Engine_Material_Design.md §4） ═══

    /**
     * Pass 回调内取 PSO（按 shader Pass + 顶点布局 + 图元拓扑 + 采样档 + 材质面型缓存，
     * PSO 不每帧新建，RHI 16.4）：SPIR-V 与固定状态取自 shaderPassName 命中的
     * {@link ShaderPass}（严格制，拍板 2026-10-17：Pass 名即阶段归属，不声明不画），
     * 其中 Blend/depthWrite 按材质 surfaceType 覆盖（URP 口径，Material_Design §4.1：
     * TRANSPARENT → Blend SrcAlpha OneMinusSrcAlpha / ZWrite Off，OPAQUE → Blend Off /
     * ZWrite On；Cull/ZTest/ColorMask 保留 Shader 声明，覆盖经 {@link PassState#copy()}
     * 不回写 Shader 资产）。附件恒单采样（MSAA 已移除，2026-11-13）；图元类型 = Mesh
     * 拓扑；set 布局 = set0 帧 + set1 帧级光照/通用通道（批次②）+ set2 材质（RHI 20.3/20.4）。
     */
    public GraphicsPipeline pipelineFor(Material material, VertexLayout layout,
                                        PrimitiveTopology topology, String passName) {
        checkOpen();
        // 坏 shader 回退解析（DefaultMaterial，拍板 2026-10-27）：shader null / SPIR-V 缺失 → 默认材质
        material = DefaultMaterial.resolve(material);
        if (material == null) {
            throw new IllegalArgumentException("material must not be null");
        }
        Shader shader = material.getShader();
        String key = System.identityHashCode(shader) + ":" + layout.hashCode()
                + ":" + topology + ":" + material.getSurfaceType()
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
                passColorFormats.getOrDefault(passName, List.of(colorAttachmentFormat())),
                depthFormat(),
                List.of(frameLayoutDesc(), frameStorageLayoutDesc(), materialLayoutDesc(shader)),
                PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                SampleCount.COUNT_1);
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

    // ═══ 阴影 alpha 裁剪取数口（REND-21-A，Engine_Shadow_Cutout_Design §5.2）═══

    /**
     * 材质 alpha 裁剪阈值：材质未声明 {@code _AlphaCutoff}（shader 无此属性）或未赋值 → 0。
     *
     * <p>0 = 不裁剪，阴影 Pass 据此跳过贴图采样（省一次 per-fragment fetch）；
     * 与主渲染注入段 {@code javelinAlphaClip} 读的是同一个 shader 属性，两路阈值同源。</p>
     */
    public float alphaCutoff(Material material) {
        if (material == null) {
            return 0.0f;
        }
        Shader shader = material.getShader();
        if (shader == null || shader.findProperty(ALPHA_CUTOFF_PROPERTY) == null) {
            return 0.0f;
        }
        material.fillMissingDefaults();
        return material.getFloat(ALPHA_CUTOFF_PROPERTY);
    }

    /**
     * 阴影 alpha 裁剪用 albedo 纹理（材质 {@code _MainTex} 的 GPU 句柄）。
     * 未声明槽 / 未赋图 / 空纹理壳 → 1×1 白图（alpha 恒 1，等价于不裁剪）。
     */
    public Texture shadowAlbedoTexture(Material material) {
        return TextureUpload.upload(device, shadowAlbedoTexture2D(material));
    }

    /** 阴影 alpha 裁剪用 albedo 采样器（按该纹理的 filter/wrap 取缓存采样器） */
    public Sampler shadowAlbedoSampler(Material material) {
        Texture2D texture = shadowAlbedoTexture2D(material);
        return samplerFor(texture.getFilterMode(), texture.getWrapMode());
    }

    private Texture2D shadowAlbedoTexture2D(Material material) {
        if (material == null || material.getShader() == null
                || material.getShader().findProperty(ALBEDO_TEXTURE_PROPERTY) == null) {
            return Texture2D.whiteTexture();
        }
        Texture2D assigned = material.getTexture(ALBEDO_TEXTURE_PROPERTY);
        if (assigned == null || assigned.getImage() == null) {
            return Texture2D.whiteTexture();
        }
        return assigned;
    }

    /** 每相机深度纹理（视口独立；首次访问按当前交换链尺寸创建，resize 换绑） */
    public Texture depthFor(String cameraName) {
        return depthTextures.computeIfAbsent(cameraName, n ->
                device.createTexture(TextureDesc.depth2D(depthFormat(), depthWidth, depthHeight,
                        Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), null));
    }

    /**
     * 每视口拾取 ID 附件（GPU ID-buffer 拾取，R32_UINT：像素值 = 帧内分配的 pick ID，
     * 0 = 背景/未命中）。恒 1x，usage 含 TRANSFER_SRC（点击读回 CopyImageToBuffer）。
     * PickSystem 启用时由 PickPass 惰性创建，resize 换绑同 depthFor。
     */
    public Texture pickIdFor(String cameraName) {
        return pickIdTextures.computeIfAbsent(cameraName, n -> device.createTexture(
                new TextureDesc(TextureType.TYPE_2D, Format.R32_UINT, depthWidth, depthHeight,
                        1, 1, 1, SampleCount.COUNT_1,
                        Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.TRANSFER_SRC),
                        MemoryPreference.deviceLocal()),
                null));
    }

    /** 每视口拾取深度附件（拾取 pass 自有深度，与主深度解耦） */
    public Texture pickDepthFor(String cameraName) {
        return pickDepthTextures.computeIfAbsent(cameraName, n ->
                device.createTexture(TextureDesc.depth2D(depthFormat(), depthWidth, depthHeight,
                        Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT)), null));
    }

    // ═══ 每视口 G-Buffer 附件（HYBRID 档；Engine_RenderPipeline_Design §4.2/5.1） ═══

    /**
     * 每视口 G-Buffer 基色附件（R8G8B8A8_UNORM：`_Color × _MainTex`）。
     * 与 depthFor 同模式（视口独立、默认交换链尺寸、resize 换绑、惰性创建）。
     */
    public Texture gbufferAlbedoFor(String cameraName) {
        return gbufferAlbedoTextures.computeIfAbsent(cameraName, n -> createGbufferTexture(
                Format.R8G8B8A8_UNORM));
    }

    /** 每视口 G-Buffer 法线附件（R16G16B16A16_SFLOAT：世界空间法线，归一） */
    public Texture gbufferNormalFor(String cameraName) {
        return gbufferNormalTextures.computeIfAbsent(cameraName, n -> createGbufferTexture(
                Format.R16G16B16A16_SFLOAT));
    }

    /** 每视口 G-Buffer 世界位置附件（R32G32B32A32_SFLOAT：xyz=世界位置、**w=有效标志**） */
    public Texture gbufferWorldPosFor(String cameraName) {
        return gbufferWorldPosTextures.computeIfAbsent(cameraName, n -> createGbufferTexture(
                Format.R32G32B32A32_SFLOAT));
    }

    /** G-Buffer 采样器（LightingPass 全屏读，NEAREST/CLAMP） */
    public Sampler gbufferSampler() {
        return gbufferSampler;
    }

    private Texture createGbufferTexture(Format format) {
        return device.createTexture(new TextureDesc(TextureType.TYPE_2D, format,
                depthWidth, depthHeight, 1, 1, 1, SampleCount.COUNT_1,
                Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED),
                MemoryPreference.deviceLocal()), null);
    }

    // ═══ 每视口 HDR 场景颜色 + 后处理中间 RT（后处理链启用时才创建） ═══

    /**
     * 每视口 HDR 场景颜色缓冲（Engine_PostProcess_Design §3.3）：后处理链非空时，
     * 场景 Pass 的颜色终点由 backbuffer 改为本缓冲（RGBA16F），链尾 Tonemap 再写
     * backbuffer。与 depthFor/gbuffer*For 同模式（视口独立、交换链尺寸、resize 换绑、
     * 惰性创建——链空时永不创建，零新增 RT）。
     */
    public Texture lightingRtFor(String cameraName) {
        return lightingRtTextures.computeIfAbsent(cameraName, n -> createHdrViewTexture());
    }

    /**
     * 每视口后处理中间 RT（链内非首尾节点的输出，§3.4；index = 节点序）。
     * 与 lightingRtFor 同模式；链内节点 ≤1 时不会创建（链尾直出 colorTarget）。
     */
    public Texture postRtFor(String cameraName, int index) {
        return postRtTextures.computeIfAbsent(cameraName + "#" + index, n -> createHdrViewTexture());
    }

    /**
     * 每视口 Bloom 多级 RT（Engine_PostProcess_Design §3.7）：level 0..N-1 对应 1/2、1/4、
     * 1/8、1/16（尺寸 = 交换链尺寸 &gt;&gt; (level+1)）。与 lightingRtFor/postRtFor 同模式
     * （视口独立、resize 换绑、惰性创建、removeCamera 回收）；Bloom 关闭时不创建。
     */
    public Texture bloomRtFor(String cameraName, int level) {
        return bloomRtTextures.computeIfAbsent(cameraName + "#b" + level, n -> {
            int w = Math.max(1, depthWidth >> (level + 1));
            int h = Math.max(1, depthHeight >> (level + 1));
            return createHdrViewTexture(w, h);
        });
    }

    /** 每视口 HDR 颜色纹理（RGBA16F、交换链尺寸、1x；场景色/后处理中间 RT 同格式） */
    private Texture createHdrViewTexture() {
        return createHdrViewTexture(depthWidth, depthHeight);
    }

    /** 按尺寸创建 HDR 颜色纹理（RGBA16F、1x；Bloom 多级 RT 尺寸递减用） */
    private Texture createHdrViewTexture(int width, int height) {
        return device.createTexture(new TextureDesc(TextureType.TYPE_2D,
                HDR_SCENE_COLOR_FORMAT, width, height, 1, 1, 1, SampleCount.COUNT_1,
                Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED),
                MemoryPreference.deviceLocal()), null);
    }

    /**
     * 每视口 SSAO RT（Engine_SSAO_Design §3.4）：index 0 = AO 原始值、1 = 水平模糊中间、
     * 2 = 竖直模糊结果（可分离两遍，BUG-12，2026-11-21）。
     * 半分辨率（交换链尺寸 &gt;&gt; 1）、{@link #SSAO_AO_FORMAT}。与 lightingRtFor/bloomRtFor
     * 同模式（视口独立、resize 换绑、惰性创建、removeCamera 回收）；SSAO 关闭时不创建。
     */
    public Texture ssaoRtFor(String cameraName, int index) {
        return ssaoRtTextures.computeIfAbsent(cameraName + "#s" + index,
                n -> createSsaoViewTexture(Math.max(1, depthWidth >> 1), Math.max(1, depthHeight >> 1)));
    }

    /** 按尺寸创建 SSAO 单通道纹理（半分辨率 AO） */
    private Texture createSsaoViewTexture(int width, int height) {
        return device.createTexture(new TextureDesc(TextureType.TYPE_2D,
                SSAO_AO_FORMAT, width, height, 1, 1, 1, SampleCount.COUNT_1,
                Set.of(TextureUsage.COLOR_ATTACHMENT, TextureUsage.SAMPLED),
                MemoryPreference.deviceLocal()), null);
    }

    /** 退役某视口的全部后处理中间 RT（removeCamera 用；retire 两帧后销毁） */
    private void retirePostRts(String cameraName) {
        String prefix = cameraName + "#";
        Iterator<Map.Entry<String, Texture>> it = postRtTextures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Texture> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                retire(entry.getValue());
                it.remove();
            }
        }
    }

    /** 退役某视口的全部 Bloom RT（removeCamera 用；retire 两帧后销毁） */
    private void retireBloomRts(String cameraName) {
        String prefix = cameraName + "#b";
        Iterator<Map.Entry<String, Texture>> it = bloomRtTextures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Texture> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                retire(entry.getValue());
                it.remove();
            }
        }
    }

    /** 退役某视口的全部 SSAO RT（removeCamera 用；retire 两帧后销毁） */
    private void retireSsaoRts(String cameraName) {
        String prefix = cameraName + "#s";
        Iterator<Map.Entry<String, Texture>> it = ssaoRtTextures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Texture> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                retire(entry.getValue());
                it.remove();
            }
        }
    }

    // ═══ 每视口 DDGI 探针 atlas（Engine_DDGI_Design §3，P4-05 批 B1） ═══

    /**
     * 每视口 DDGI 两张 atlas（辐照度 {@code RGBA16F} / 深度矩 {@code RG16F}）。
     *
     * <p>版面（尺寸 / tile 数 / 探针数）全部由调用方给的 {@link DdgiProbeGrid} 决定：
     * 与现存一对同版面则原样复用，否则换代——旧两张图进两帧退役队列，新一对按新尺寸
     * 创建。与交换链尺寸无关（resize 不换绑；探针网格是相机居中滚动体，不受视口影响）。
     * 惰性创建：无症状挂账时（DDGI 关闭）永不开图。</p>
     */
    public DdgiAtlases ddgiAtlasesFor(String cameraName, DdgiProbeGrid grid) {
        if (cameraName == null || grid == null) {
            throw new IllegalArgumentException("cameraName / grid must not be null");
        }
        DdgiAtlases current = ddgiAtlasSets.get(cameraName);
        if (current != null && current.matches(grid)) {
            return current;
        }
        if (current != null) {
            log.info("DDGI atlas rebind[{}]: {} → {}", cameraName, current, grid);
            retire(current.irradiance());
            retire(current.depth());
            ddgiAtlasSets.remove(cameraName);
        }
        DdgiAtlases created = DdgiAtlases.create(device, grid);
        log.info("DDGI atlas created[{}]: {}（显存 {} KB）", cameraName, created,
                (grid.irradianceAtlasBytes() + grid.depthAtlasBytes()) / 1024);
        ddgiAtlasSets.put(cameraName, created);
        return created;
    }

    /** 退役某视口的 DDGI atlas（removeCamera 用；retire 两帧后销毁） */
    private void retireDdgiAtlases(String cameraName) {
        DdgiAtlases atlases = ddgiAtlasSets.remove(cameraName);
        if (atlases != null) {
            retire(atlases.irradiance());
            retire(atlases.depth());
        }
    }

    /**
     * 当前已绑定到该视口的 DDGI atlas（未建 = {@code null}）。
     *
     * <p>着色 Pass 的依赖声明用：辐照度/深度矩两张图既要被更新段 compute 以 STORAGE 写、
     * 又要被 Forward/Lighting 以 SAMPLED 读，图里必须两侧都声明，否则布局在
     * GENERAL / SHADER_READ_ONLY 之间无人负责转换与屏障（实测 VUID-09600 + 00344 刷屏、
     * 画面闪）。这里只读现成绑定，不触发建图（建图归 {@link #ddgiAtlasesFor}）。</p>
     */
    public DdgiAtlases ddgiAtlasesOf(String cameraName) {
        return cameraName == null ? null : ddgiAtlasSets.get(cameraName);
    }

    // ═══ TAA 历史色与状态面（TaaPass 消费） ═══

    /**
     * 每视口 TAA 历史色 RT（Engine_TAA_Design §3.4）：index 0/1 = ping-pong 两代。
     * 全帧尺寸、{@link #HDR_SCENE_COLOR_FORMAT}；与 lightingRtFor 同模式（resize 换绑、
     * 惰性创建、removeCamera 回收）；TAA 关闭时不创建。
     */
    public Texture taaRtFor(String cameraName, int index) {
        return taaRtTextures.computeIfAbsent(cameraName + "#t" + index,
                n -> createHdrViewTexture());
    }

    /** 退役某视口的全部 TAA 历史 RT（removeCamera 用；retire 两帧后销毁） */
    private void retireTaaRts(String cameraName) {
        String prefix = cameraName + "#t";
        Iterator<Map.Entry<String, Texture>> it = taaRtTextures.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Texture> entry = it.next();
            if (entry.getKey().startsWith(prefix)) {
                retire(entry.getValue());
                it.remove();
            }
        }
    }

    /**
     * 上一帧抖动后 viewProj（此视口）；相机未收集过返回 null。
     *
     * <p>只读视图——调用方不得修改，重投影参数由 TaaPass 拷进自己的参数块。</p>
     */
    public Matrix4f taaPrevViewProj(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history == null ? null : history.prevJitteredViewProj;
    }

    /** 历史可用判据：本视口上一帧恰好是 {@code frameIndex - 1}（中间断帧即视为失效） */
    public boolean taaHistoryValid(String cameraName, long frameIndex) {
        TaaHistory history = taaHistories.get(cameraName);
        // 相机本帧与上一帧都须启用 TAA（关闭期间历史 RT 不更新，帧号连续也不可用）；
        // 相机移动帧不混（thinTAA disableOnCameraMove：factor=1）；断帧亦失效
        boolean valid = history != null && history.taaEnabled && history.prevTaaEnabled
                && history.prevFrame == frameIndex - 1 && !history.cameraMoved;
        if (!valid && frameIndex > 1 && history != null && history.taaEnabled
                && !history.cameraMoved && !taaMissingValidLogged) {
            taaMissingValidLogged = true;
            log.warn("TAA 历史失效（该帧直出当前帧）: 相机[{}] frame={} prevFrame={}",
                    cameraName, frameIndex, history.prevFrame);
        }
        return valid;
    }

    /** 本视口本帧是否启用 TAA（按相机设置；TaaPass/SharpenPass 按视口判定） */
    public boolean taaViewEnabled(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history != null && history.taaEnabled;
    }

    /** 本视口 TAA 历史权重（内部常量 0.95；参数来自相机级设置之外的固定口径） */
    public float taaViewBlendWeight(String cameraName) {
        return TAA_BLEND_WEIGHT;
    }

    /** 本视口历史锐化（0 = 双线性 / 1 = 全 Catmull-Rom；相机级参数） */
    public float taaViewHistorySharpening(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history == null ? 1.0f : history.historySharpening;
    }

    /** 本视口 3×3 邻域钳制强度（0 = 关；相机「防闪烁」0~1 映射到 0~2） */
    public float taaViewClampScale(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history == null ? 0.0f : history.clampScale;
    }

    /** 本视口速度降权起始阈值（纹素；0 = 不抑制；相机「速度抑制」换算） */
    public float taaViewSpeedThreshold(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history == null ? 0.0f : history.speedThreshold;
    }

    /** 本视口锐化强度（相机级；相机未启用 TAA 时 0 = 直出） */
    public float sharpenStrengthFor(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        return history == null || !history.taaEnabled ? 0.0f : history.sharpenStrength;
    }

    /** 失效某视口历史（切场景等语义边界；下一帧从当前帧重新起累积） */
    public void invalidateTaa(String cameraName) {
        TaaHistory history = taaHistories.get(cameraName);
        if (history != null) {
            history.prevFrame = Long.MIN_VALUE;
            history.lastFrame = Long.MIN_VALUE;
        }
    }

    /** 失效全部视口历史（切场景调用；`prevTaaEnabled` 一并复位 → 重新启用首帧必直出） */
    public void invalidateTaaAll() {
        taaHistories.values().forEach(hist -> {
            hist.prevFrame = Long.MIN_VALUE;
            hist.lastFrame = Long.MIN_VALUE;
            hist.prevTaaEnabled = false;
        });
    }

    /** TAA 抖动开关（TAA 不在链上必须关——否则白白引入亚像素抖动与糊） */
    public void setTaaJitterEnabled(boolean enabled) {
        this.taaJitterEnabled = enabled;
    }

    /**
     * 本视口本帧**实际施加**的 TAA 抖动像素偏移（写入 out[2]）：TaaPass copy Pass 去抖消费。
     *
     * <p>必须是 writeCameraBlock 真正用过的值——关闭抖动、相机未启用 TAA 或相机移动帧
     *（thinTAA 口径停抖）时恒 0，否则 copy 会按相位值平移一张本来没抖的画面，凭空糊一层。
     * 按相机名取值：多相机时各视口的抖动量不同，不能共用一个全局暂存。</p>
     */
    public void taaJitterFor(String cameraName, float[] out) {
        TaaHistory history = taaHistories.get(cameraName);
        if (history == null) {
            out[0] = 0.0f;
            out[1] = 0.0f;
            return;
        }
        out[0] = history.appliedJitter[0];
        out[1] = history.appliedJitter[1];
    }

    /** 帧环形槽数（TaaPass 参数块按此分槽，与 UBO1 同奇偶） */
    public int frameSlots() {
        return frameSlots;
    }

    /**
     * 登记 shader Pass 名的 PSO 颜色附件格式列表（HYBRID 的 "GBuffer" = 三张 MRT）。
     * 未登记的 Pass 名走单张 {@link #colorAttachmentFormat()}（Forward 族现状）。
     */
    public void registerPassColorFormats(String passName, List<Format> colors) {
        if (passName != null && colors != null && !colors.isEmpty()) {
            passColorFormats.put(passName, List.copyOf(colors));
        }
    }

    /**
     * 设置场景色是否走 HDR 缓冲（帧构建每帧调用，Engine_PostProcess_Design §3.2）：
     * HDR 生效时场景 Pass（shader Pass 名 = "Forward"）的颜色附件为 RGBA16F，
     * {@code pipelineFor} 须按同一格式建 PSO，否则附件格式不匹配（VVL 报错/画面错）。
     * **格式切换即失效材质 PSO 缓存**（两帧退役旧管线，GPU in-flight 安全）——
     * overlay 制 Pass（"Gizmo" 等）写显示目标，走未登记回退的交换链格式，不受影响。
     */
    public void setHdrSceneColor(boolean hdr) {
        if (hdr == hdrSceneColor) {
            return;
        }
        hdrSceneColor = hdr;
        if (hdr) {
            registerPassColorFormats(RenderPass.FORWARD_PASS_NAME,
                    List.of(HDR_SCENE_COLOR_FORMAT));
        } else {
            passColorFormats.remove(RenderPass.FORWARD_PASS_NAME);
        }
        discardMaterialPipelines();
        log.info("场景颜色格式切换: {}（材质 PSO 缓存已失效重建）",
                hdr ? "RGBA16F（HDR 后处理链生效）" : "交换链格式（直通）");
    }

    /** 失效全部材质 PSO（格式/契约变化时调用；旧管线两帧退役） */
    private void discardMaterialPipelines() {
        if (pipelines.isEmpty()) {
            return;
        }
        pipelines.values().forEach(pipeline -> retireResource(pipeline::dispose));
        pipelines.clear();
        pipelineOwners.clear();
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
        // 光照 UBO 帧槽基址（LIGHTING_STRIDE × MAX_CAMERAS 一槽，与 UBO1 同奇偶）
        int lightSlotBase = (int) (frameIndex % frameSlots) * MAX_CAMERAS * LIGHTING_STRIDE;
        float tpf = Time.getDeltaTime();
        sortCamerasByDepth();

        List<RenderView> views = new ArrayList<>();
        int camIndex = 0;
        for (RenderCamera cam : sortedCameras) {
            cam.recalculate();
            computeShadow(cam);   // per-view 级联拟合（四期 CSM：依赖各相机视锥）
            int camOffset = slotBase + camIndex * CAMERA_STRIDE;
            writeCameraBlock(cam, camOffset, frameIndex);
            // 灯收集（每视口：方向光视空间方向 + lightList 各自正确——批次③ per-view
            // 三缓冲落位，批次②「首视口一份」挂账解除）
            lightCollector.collect(lights, cam.view(), cam.proj(),
                    cam.projectionType() == ProjectionType.ORTHOGRAPHIC,
                    cam.nearZ(), cam.farZ());
            int viewW = cam.viewportWidth();
            int viewH = cam.viewportHeight();
            int gridX = (viewW + ClusterBuilder.TILE_SIZE - 1) / ClusterBuilder.TILE_SIZE;
            int gridY = (viewH + ClusterBuilder.TILE_SIZE - 1) / ClusterBuilder.TILE_SIZE;
            ViewLighting vl = viewLightsFor(cam.name(), viewW, viewH, gridX, gridY);
            packLightingParams(lightSlotBase + camIndex * LIGHTING_STRIDE,
                    cam, gridX, gridY);
            packLightList(vl, camIndex == 0);
            buildClusters(vl, cam, gridX, gridY, viewW, viewH, camIndex == 0);
            warnOverflow();
            RenderList list = collectList(cam);
            if (!collectLogged) {
                collectLogged = true;
                log.info("首帧收集: backbuffer={}x{} 相机[{}] viewport={}x{} 不透明={} 透明={} 灯(点/方向)={}/{}",
                        w, h, cam.name(), cam.viewportWidth(), cam.viewportHeight(),
                        list.opaque().size(), list.transparent().size(),
                        lastPointLightCount, lastDirectionalCount);
            }
            views.add(new RenderView(cam.name(),
                    slotBase + camIndex * CAMERA_STRIDE,
                    lightSlotBase + camIndex * LIGHTING_STRIDE,
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
            // 单画，不再走"Opaque 先画 + 末尾重绘置顶"双画）。
            // LightingModel.UNLIT（施工序 ⑤）：不透明进 unlitOpaque 桶（UnlitForwardPass
            // 直出、不进 G-Buffer 桶）；透明照旧归透明桶（其 shader 未注入光照段，天然直出）
            Shader shader = ro.material().getShader();
            boolean unlit = shader.getLightingModel() == LightingModel.UNLIT;
            for (ShaderPass pass : shader.getPasses()) {
                if (RenderPass.FORWARD_PASS_NAME.equals(pass.getName())) {
                    if (ro.isTransparent()) {
                        ro.world().getTranslation(worldPos);
                        worldPos.mulPosition(cam.view());
                        viewZ.put(ro, worldPos.z);
                        list.addTransparent(ro);
                    } else if (unlit) {
                        list.addUnlitOpaque(ro);
                    } else {
                        list.addOpaque(ro);
                    }
                } else {
                    if (unlit && GBufferPass.SHADER_PASS_NAME.equals(pass.getName())) {
                        continue;   // 非光照不进 G-Buffer（即便 shader 残留 Pass GBuffer 也不入桶）
                    }
                    list.addPass(pass.getName(), ro);
                }
            }
            if (ro.isCastShadow()) {
                if (!ro.isTransparent()) {
                    // v0.x 透明物不投影（灯光档 §4.5「全部不透明 mesh renderer」）；
                    // 后置：材质/组件级投影开关
                    list.addShadowCaster(ro);
                }
            }
        }
        list.opaque().sort(Comparator.comparingLong(RenderObject::bucketKey));
        list.unlitOpaque().sort(Comparator.comparingLong(RenderObject::bucketKey));
        list.transparent().sort(Comparator.comparingDouble(o -> viewZ.getOrDefault(o, 0.0f)));
        return list;
    }

    // ═══ 灯收集打包（批次②；Engine_Lighting_Design §3.1/§3.3） ═══

    /**
     * 主光阴影帧收集（四期 CSM §4.4，**per-view**）：主光裁决（全局一致，与 LightCollector
     * 同口径——最强激活方向光，tie 取列表序首个）→ 逐视口按自己视锥拟合级联盒
     * （{@link ShadowMath#buildCascades}）→ 建/换 per-view shadowMap atlas。级联依赖各相机
     * 视锥，故 shadowMap 由「全局单张」改 per-view。级联数 1 时退化为「相机视锥拟合的单张」。
     *
     * <p>caster 集 = 全体 castShadow 不透明对象，但经本视口 layerMask 过滤（收集落在
     * {@link #collectList} 同一循环内，与旧「不按 cullingMask 过滤 = 超集保守」口径不同）。
     * 落视锥剔除（REND-01）时 caster 须独立按级联盒剔除，不可跟着相机视锥走——
     * 口径见 Engine_Shadow_Quality_Design §4.7-1。</p>
     * castShadow 标志仅在主光为真时生效；主光关影时矩阵置恒受光、ShadowPass 只清 atlas。
     */
    private void computeShadow(RenderCamera cam) {
        RenderLight sun = resolveSun();
        shadowSunActive = sun != null && sun.isCastShadow();
        ShadowSettings shadow = shadowSettings;
        int count = shadow.getCascadeCount();
        int resolution = sun != null ? sun.shadowResolution() : SHADOW_MAP_SIZE;
        // atlas：级联数≥2 用 2×2（2 级联只占前两象限，atlas 保持正方形——单纹素尺度 u/v 同值）
        int grid = count >= 2 ? 2 : 1;
        int atlasSize = resolution * grid;

        ViewShadow vs = viewShadows.get(cam.name());
        if (vs != null && (vs.resolution != resolution || vs.cascadeCount != count)) {
            viewShadows.remove(cam.name());
            retire(vs.map);   // 旧图两帧退役
            vs = null;
        }
        if (vs == null) {
            vs = new ViewShadow();
            vs.resolution = resolution;
            vs.cascadeCount = count;
            vs.grid = grid;
            vs.atlasSize = atlasSize;
            vs.map = device.createTexture(new TextureDesc(
                    TextureType.TYPE_2D, depthFormat(), atlasSize, atlasSize,
                    1, 1, 1, SampleCount.COUNT_1,
                    Set.of(TextureUsage.DEPTH_STENCIL_ATTACHMENT, TextureUsage.SAMPLED),
                    MemoryPreference.deviceLocal()), null);
            viewShadows.put(cam.name(), vs);
            // 已建视口 set1 binding4 重指新图（新建视口走 viewLightsFor 常规链，读的已是新图）
            ViewLighting vl = viewLights.get(cam.name());
            if (vl != null) {
                vl.set.update(DescriptorUpdate.sampledTexture(4, vs.map, shadowSampler));
                // binding11 = 同一张图的原始深度（PCSS 阻挡搜索），必须与 binding4 同步换指
                vl.set.update(DescriptorUpdate.sampledTexture(11, vs.map, shadowRawSampler));
            }
            log.info("视口阴影图创建: camera[{}] {}²×级联 {} → atlas {}²（grid {}）",
                    cam.name(), resolution, count, atlasSize, grid);
        }

        if (!shadowSunActive) {
            // 关影态：矩阵置恒受光（不消费），ShadowPass 只清 atlas
            for (int i = 0; i < 4; i++) {
                vs.matrices[i].identity();
                vs.splits[i] = cam.farZ();
                vs.invZ[i] = 1.0f;
            }
            return;
        }
        for (int i = 0; i < 4; i++) {
            vs.splits[i] = cam.farZ();   // 未用槽兜底（采样循环只走到 count）
        }
        // 级联拟合（相机基向量自建，避 viewProj 的 Y 翻转）
        ShadowMath.CascadeParams cp = cascadeParams;
        cp.position.set(cam.position());
        cp.forward.set(cam.target()).sub(cam.position()).normalize();
        cp.up.set(cam.up());
        cp.fovY = cam.fovY();
        cp.orthographicSize = cam.orthographicSize();
        cp.aspect = cam.viewportHeight() > 0
                ? (float) cam.viewportWidth() / cam.viewportHeight() : 1.0f;
        cp.nearZ = cam.nearZ();
        cp.farZ = Math.min(cam.farZ(), Math.max(shadow.getShadowDistance(), cam.nearZ() + 1e-3f));
        cp.orthographic = cam.projectionType() == ProjectionType.ORTHOGRAPHIC;
        ShadowMath.buildCascades(sun.worldDir(), cp, shadow.getCascadeLambda(), count, resolution,
                vs.matrices, vs.splits, shadowZRangeTmp);
        for (int i = 0; i < count; i++) {
            vs.invZ[i] = 1.0f / Math.max(shadowZRangeTmp[i], 1e-4f);
        }
    }

    /** 主光裁决：最强激活方向光（castShadow 位不参与——关阴影主光仍占槽 0，着色侧跳采样） */
    private RenderLight resolveSun() {
        RenderLight sun = null;
        float sunIntensity = Float.NEGATIVE_INFINITY;
        for (RenderLight light : lights) {
            if (light.type() == RenderLight.Type.DIRECTIONAL
                    && light.colorIntensity()[3] > sunIntensity) {
                sunIntensity = light.colorIntensity()[3];
                sun = light;
            }
        }
        return sun;
    }

    /** 主光阴影是否本帧生效（主光存在且 castShadow；ShadowPass 挂点判据） */
    public boolean shadowSunActive() {
        return shadowSunActive;
    }

    /**
     * 主光世界方向（**光的行进方向**，与 `lighting.directional` 打包同源；P4-06 B3 光追对照用）。
     * 无主光返回 null；调用方要「指向光源」的方向时取负。
     */
    public org.joml.Vector3f sunDirectionOrNull() {
        RenderLight sun = resolveSun();
        return sun == null ? null : new org.joml.Vector3f(sun.worldDir());
    }

    /** 每视口阴影资源（四期 CSM；私有——对外走下面基本访问器） */
    private ViewShadow shadowView(String cameraName) {
        ViewShadow vs = viewShadows.get(cameraName);
        if (vs == null) {
            throw new IllegalStateException("视口阴影资源缺失: camera[" + cameraName
                    + "]（帧收集必须先于图构建执行）");
        }
        return vs;
    }

    /** 本视口 shadowMap atlas（ShadowPass 深度输出 / Forward·Lighting Pass 图声明采样读） */
    public Texture shadowMapFor(String cameraName) {
        return shadowView(cameraName).map;
    }

    /**
     * 本视口 shadowMap atlas 的非抛出只读口（未建 = {@code null}）：供调用点无 ShadowPass
     * 前置约束的消费方（如 DDGI trace 段——主光未激活/关阴影时视口没有阴影资源，
     * 此时走占位图 + 级联数 0 的恒受光回落）。
     */
    public Texture shadowMapOrNull(String cameraName) {
        ViewShadow vs = cameraName == null ? null : viewShadows.get(cameraName);
        return vs == null ? null : vs.map;
    }

    /** 本视口级联数（1/2/4） */
    public int shadowCascadeCount(String cameraName) {
        return shadowView(cameraName).cascadeCount;
    }

    /** 本视口 atlas 每轴象限数（1 或 2；ShadowPass 象限定位用） */
    public int shadowAtlasGrid(String cameraName) {
        return shadowView(cameraName).grid;
    }

    /** 本视口第 cascade 级联的 世界→光裁剪 矩阵（ShadowPass push 消费） */
    public Matrix4f shadowCascadeMatrix(String cameraName, int cascade) {
        return shadowView(cameraName).matrices[cascade];
    }

    /**
     * 阴影参数（品质档/强度/PCF 半径/参考深度偏移/bias 两项）：启动期由
     * {@code JavelinEngine} 灌入设置、运行期由编辑器「项目设置」窗口改写。
     * 前三项与参考深度偏移每帧打包进 UBO 即时生效；bias 两项是 PSO 静态态，
     * 由 {@code ShadowPass} 检测变化后重建 PSO 缓存。
     */
    public ShadowSettings shadowSettings() {
        return shadowSettings;
    }

    /** 阴影参数设置（null 拒绝——保持恒有值，调用方不必判空） */
    public void setShadowSettings(ShadowSettings shadowSettings) {
        if (shadowSettings == null) {
            throw new IllegalArgumentException("shadowSettings must not be null");
        }
        this.shadowSettings = shadowSettings;
    }

    /**
     * DDGI 追踪段用的光照快照（Engine_DDGI_Design §4.1）：主光方向 + 颜色×强度 + 环境项。
     *
     * <p>打包成 12 个 float 供宿主塞进 UBO：{@code [0..2]} 主光方向（指向光源的归一向量）、
     * {@code [3..5]} 主光 rgb × 强度、{@code [6..8]} 环境 rgb、{@code [9]} 环境强度。
     * 主光缺失（场景无方向光）时前三与 3~5 位全零，着色端只剩环境项。</p>
     */
    public void packDdgiLighting(float[] out12) {
        if (out12 == null || out12.length < 12) {
            throw new IllegalArgumentException("out12 至少 12 个 float");
        }
        RenderLight sun = resolveSun();
        if (sun == null) {
            out12[0] = 0f;
            out12[1] = 0f;
            out12[2] = 0f;
            out12[3] = 0f;
            out12[4] = 0f;
            out12[5] = 0f;
        } else {
            Vector3f dir = sun.worldDir();
            out12[0] = dir.x;
            out12[1] = dir.y;
            out12[2] = dir.z;
            float[] color = sun.colorIntensity();
            out12[3] = color[0] * color[3];
            out12[4] = color[1] * color[3];
            out12[5] = color[2] * color[3];
        }
        out12[6] = ambientColor.r;
        out12[7] = ambientColor.g;
        out12[8] = ambientColor.b;
        out12[9] = ambientIntensity;
        out12[10] = 0f;
        out12[11] = 0f;
    }

    /**
     * DDGI 追踪段用的 CSM 阴影参数快照（Engine_DDGI_Design §4.1，直射项修正 2026-11-22）：
     * 主视口 ViewShadow 的矩阵/级联/atlas 段，直接打进 352B ByteBuffer（std140，与
     * {@code ddgi_trace.compute} binding7 的 {@code ShadowParams} 块逐字段对齐）。
     *
     * <p>布局：{@code cascadeHead}（x=级联数，yzw=0）+ {@code sunViewProj[4]}（未用槽 identity）
     * + {@code cascadeSplits[4]} + {@code shadowParams}（品质档/PCF 半径/强度/refDepthOffset）
     * + {@code shadowParams2}（atlas 象限数/atlas 边长/0/0）+ {@code cascadeInvZ[4]}。
     * 值与 {@code packLightingParams} 的 set1 CSM 段同源——探针直射项与主画面同一套影子。</p>
     *
     * <p>无主相机/无阴影资源时全零 + 级联数 0：着色端 {@code cascadeShadowFactor} 恒受光
     * （天然回落，不炸描述符）。{@code out} 须至少 352 字节、position 归零由调用方负责
     * （本方法只 put 不 flip）。</p>
     */
    public void packDdgiShadowParams(ByteBuffer out) {
        if (out == null || out.capacity() < 352) {
            throw new IllegalArgumentException("out 至少 352 字节（got: "
                    + (out == null ? "null" : out.capacity()) + "）");
        }
        RenderCamera primary = cameras.isEmpty() ? null : cameras.get(0);
        // 直查 viewShadows 表而非 shadowView(...)：后者在资源缺失时抛 IllegalStateException
        //（帧收集未建 = 无主光/关阴影的合法态，此时应回落全零而非炸）
        ViewShadow vs = primary == null ? null : viewShadows.get(primary.name());
        if (vs == null) {
            for (int i = 0; i < 88; i++) {
                out.putFloat(0f);
            }
            return;
        }
        // cascadeHead：x = 级联数
        out.putFloat(vs.cascadeCount).putFloat(0f).putFloat(0f).putFloat(0f);
        // sunViewProj[4]（未用槽 identity——compute 侧只走到 cascadeCount）
        for (int c = 0; c < 4; c++) {
            for (float v : vs.matrices[c].get(matTmp)) {
                out.putFloat(v);
            }
        }
        for (int c = 0; c < 4; c++) {
            out.putFloat(vs.splits[c]);
        }
        ShadowSettings shadow = shadowSettings;
        out.putFloat(shadow.getQuality().code())
                .putFloat(shadow.getPcfRadius())
                .putFloat(shadow.getStrength())
                .putFloat(shadow.getRefDepthOffset());
        out.putFloat(vs.grid)
                .putFloat(vs.atlasSize)
                .putFloat(0f)
                .putFloat(0f);
        for (int c = 0; c < 4; c++) {
            out.putFloat(vs.invZ[c]);
        }
    }

    /**
     * DDGI 参数（Engine_DDGI_Design §3/§4）：启动期由 {@code JavelinEngine} 灌入设置、
     * 运行期由编辑器「项目设置」窗口改写。{@code DdgiVoxelizer} 按它取体素场分辨率与
     * 开关，{@code DdgiAtlases} 按它取探针网格。
     */
    public DdgiSettings ddgiSettings() {
        return ddgiSettings;
    }

    /** DDGI 参数设置（null 拒绝——保持恒有值，调用方不必判空） */
    public void setDdgiSettings(DdgiSettings ddgiSettings) {
        if (ddgiSettings == null) {
            throw new IllegalArgumentException("ddgiSettings must not be null");
        }
        this.ddgiSettings = ddgiSettings;
    }

    /**
     * 非纹理资源的延迟销毁入口（stamp + 2 帧，GPU in-flight 安全）：供运行期重建
     * 类资源退役——{@code ShadowPass} 的 PSO 随阴影 bias 设置变化重建即此消费方。
     * 与纹理退役同队列（{@code drainRetired}）。
     */
    public void retireResource(Runnable disposeAction) {
        retiredResources.add(new RetiredResource(disposeAction, lastFrame));
    }

    /** 场景级天空盒 cubemap（批次⑤测试形态：RHI 纹理引用；null = 未挂） */
    public Texture skybox() {
        return skybox;
    }

    /** 设置天空盒（置 null 摘除）；纹理由调用方持有与释放，本系统只持引用 */
    public void setSkybox(Texture skybox) {
        this.skybox = skybox;
    }

    /**
     * 设置常量环境项（四期 P4-04 步骤一；桥每帧推送，不做身份门控——Color 就地编辑不换引用）。
     *
     * <p>rgb = 颜色、a = 强度；color 为 null 或强度 &lt;= 0 即纯黑（等同
     * {@code AmbientMode.OFF}，与三期基线一致）。下帧打包进 {@code LightingParamsBlock.ambient}，
     * FORWARD 与 HYBRID（LightingPass）同一份注入函数消费，两路自动一致。</p>
     */
    public void setAmbient(Color color, float intensity) {
        if (color == null || intensity <= 0.0f) {
            ambientColor.r = 0.0f;
            ambientColor.g = 0.0f;
            ambientColor.b = 0.0f;
            ambientIntensity = 0.0f;
            return;
        }
        ambientColor.r = color.r;
        ambientColor.g = color.g;
        ambientColor.b = color.b;
        ambientIntensity = intensity;
    }

    /**
     * 设置天空盒（资产路径，桥每帧按场景引用推送）：引用变化时懒上传六面
     * （textureCube 单命令整拷）、旧图两帧退役（GPU in-flight 安全；仅退役资产
     * 路径旧图——测试形态直灌纹理归调用方）；null 摘除。图句柄变化经 SkyboxPass
     * 的 addTextureRead 进 structureHash 自动失效图缓存。
     */
    public void setSkybox(CubemapImage asset) {
        if (asset == skyboxAsset) {
            return;
        }
        Texture old = skybox;
        boolean oldOwned = skyboxAsset != null;
        skyboxAsset = asset;
        skybox = asset != null ? TextureUpload.uploadCubemap(device, asset) : null;
        if (old != null && oldOwned) {
            retire(old);
        }
    }

    /**
     * LightingParamsBlock 打包（std140，与 ShaderInjector 注入声明同源）：
     * directional[8]×2vec4（槽 0=主光，视空间方向）/ ivec4 counts / ivec4 gridDim
     * （批次③：xy=维度 z=Z 切片数 w=tileSize）/ vec4 zParams（near/far/ortho 标志/级联数）/
     * vec4 viewport（帧缓冲左上原点+尺寸，gl_FragCoord 同源）/ vec4 ambient /
     * sunViewProj[4] + cascadeSplits + shadowParams + shadowParams2 + cascadeInvZ（四期 CSM §4.6）。
     * 字段详表=Engine_Uniform_Keys §4-1。
     */
    private void packLightingParams(int offset, RenderCamera cam, int gridX, int gridY) {
        lightingPack.clear();
        List<LightCollector.DirectionalItem> directionalItems = lightCollector.directionals();
        for (int i = 0; i < LightCollector.MAX_DIRECTIONAL; i++) {
            if (i < directionalItems.size()) {
                LightCollector.DirectionalItem d = directionalItems.get(i);
                lightingPack.putFloat(d.viewDir.x)
                        .putFloat(d.viewDir.y)
                        .putFloat(d.viewDir.z)
                        .putFloat(d.castShadow ? 1.0f : 0.0f)
                        .putFloat(d.colorIntensity[0])
                        .putFloat(d.colorIntensity[1])
                        .putFloat(d.colorIntensity[2])
                        .putFloat(d.colorIntensity[3]);
            } else {
                for (int j = 0; j < 8; j++) {
                    lightingPack.putFloat(0.0f);
                }
            }
        }
        lightingPack.putInt(directionalItems.size());
        lightingPack.putInt(lightCollector.pointItems().size());
        lightingPack.putInt(0).putInt(0);   // counts.zw 预留
        lightingPack.putInt(gridX).putInt(gridY)
                .putInt(ClusterBuilder.Z_SLICES).putInt(ClusterBuilder.TILE_SIZE);   // gridDim
        ViewShadow vs = shadowView(cam.name());
        lightingPack.putFloat(cam.nearZ()).putFloat(cam.farZ())
                .putFloat(cam.projectionType() == ProjectionType.ORTHOGRAPHIC ? 1.0f : 0.0f)
                .putFloat(vs.cascadeCount);   // zParams：正交=Z 线性切分；w=级联数（CSM §4.6）
        lightingPack.putFloat(cam.viewportX()).putFloat(cam.viewportY())
                .putFloat(cam.viewportWidth()).putFloat(cam.viewportHeight());   // viewport
        lightingPack.putFloat(ambientColor.r).putFloat(ambientColor.g)
                .putFloat(ambientColor.b).putFloat(ambientIntensity);
        // 级联矩阵段（四期 CSM §4.6，@336）：sunViewProj[4]（世界→光裁剪，逐级联；未用槽
        // 写 identity——采样循环只走到 cascadeCount）
        for (int c = 0; c < 4; c++) {
            for (float v : vs.matrices[c].get(matTmp)) {
                lightingPack.putFloat(v);
            }
        }
        // cascadeSplits（@592）：各级联视空间 z 远界（超出末级联恒受光）
        for (int c = 0; c < 4; c++) {
            lightingPack.putFloat(vs.splits[c]);
        }
        // shadowParams（@608）：x=品质档编码 / y=PCF 半径（纹素）/ z=阴影强度 /
        // w=参考深度偏移（**世界单位**，逐级联乘 cascadeInvZ 折算成裁剪 z 增量）
        ShadowSettings shadow = shadowSettings;
        lightingPack.putFloat(shadow.getQuality().code())
                .putFloat(shadow.getPcfRadius())
                .putFloat(shadow.getStrength())
                .putFloat(shadow.getRefDepthOffset());
        // shadowParams2（@624）：x=atlas 每轴象限数 / y=atlas 边长 / z=级联混合带比例 /
        // w=PCSS 软化系数（REND-21-C1；0 = 关 PCSS，走固定核 PCF）
        lightingPack.putFloat(vs.grid)
                .putFloat(vs.atlasSize)
                .putFloat(SHADOW_CASCADE_BLEND)
                .putFloat(shadow.getSoftness());
        // cascadeInvZ（@640）：逐级联 1/光空间 z 范围（参考深度偏移折算除数）
        for (int c = 0; c < 4; c++) {
            lightingPack.putFloat(vs.invZ[c]);
        }
        // contactParams（@656，REND-21-C2）：x=开关(0/1) / y=最大步进距离 tMax / z=强度 /
        // w=步数。仅 LightingPass（HYBRID）消费——Forward 路不声明该助手函数
        float contact = shadow.getContactShadows();
        lightingPack.putFloat(contact > 0.0f ? 1.0f : 0.0f)
                .putFloat(CONTACT_SHADOW_MAX_DISTANCE)
                .putFloat(contact)
                .putFloat(CONTACT_SHADOW_STEPS);
        lightingPack.flip();
        lightingBuffer.update(offset, lightingPack);
    }

    /** lightList SSBO 打包（视空间 16 float/灯，写给本视口缓冲；首视口顺带 F3 计数快照） */
    private void packLightList(ViewLighting vl, boolean captureStats) {
        lightListPack.clear();
        List<float[]> items = lightCollector.pointItems();
        for (float[] item : items) {
            for (float v : item) {
                lightListPack.putFloat(v);
            }
        }
        lightListPack.flip();
        vl.lightList.update(0, lightListPack);
        if (captureStats) {
            lastPointLightCount = items.size();
            lastDirectionalCount = lightCollector.directionals().size();
        }
    }

    /**
     * Cluster 构建（批次③ CPU 构建拍板 2026-11-01，Engine_Lighting_Design §6 修订版）：
     * 本视口 lightList → clusterGrid/clusterIndices 直写 hostVisible；构建耗时与
     * 池占用读数按首视口口径快照（F3 行 9）。proj 元素带符号消费（Y 翻转保号——
     * tile 定位非对称，与 LightCollector 取绝对值的剔除口径不同）。
     */
    private void buildClusters(ViewLighting vl, RenderCamera cam, int gridX, int gridY,
                               int viewW, int viewH, boolean captureStats) {
        ClusterBuilder.Params params = new ClusterBuilder.Params(gridX, gridY,
                ClusterBuilder.Z_SLICES, viewW, viewH, cam.nearZ(), cam.farZ(),
                cam.projectionType() == ProjectionType.ORTHOGRAPHIC,
                cam.proj().m00(), cam.proj().m11());
        long start = System.nanoTime();
        clusterBuilder.build(lightCollector.pointItems(), params);
        long nanos = System.nanoTime() - start;

        gridPack = ensureCapacity(gridPack, clusterBuilder.gridInts() * 4);
        gridPack.clear();
        clusterBuilder.writeGrid(gridPack);
        gridPack.flip();
        vl.clusterGrid.update(0, gridPack);
        if (clusterBuilder.poolUsed() > 0) {
            poolPack = ensureCapacity(poolPack, clusterBuilder.poolUsed() * 4);
            poolPack.clear();
            clusterBuilder.writePool(poolPack);
            poolPack.flip();
            vl.clusterIndices.update(0, poolPack);
        }
        if (captureStats) {
            clusterBuildNanos = nanos;
            clusterPoolUsed = clusterBuilder.poolUsed();
        }
    }

    /** 序列化暂存扩容（容量不足重分配；小端） */
    private static ByteBuffer ensureCapacity(ByteBuffer buffer, int bytes) {
        if (buffer != null && buffer.capacity() >= bytes) {
            return buffer;
        }
        return ByteBuffer.allocateDirect(bytes).order(ByteOrder.LITTLE_ENDIAN);
    }

    /**
     * 超容 WARN（§3.1/§3.3 显性化）：剔除后仍超 lightList 容量取强度前 4096、
     * 方向光超 8 截尾。状态位条件解除复位，持续超限不重复刷屏。
     */
    private void warnOverflow() {
        boolean pointOverflow = lightCollector.collectedPointCount() - lightCollector.culledCount()
                > LightCollector.MAX_POINT_LIGHTS;
        if (pointOverflow != pointOverflowActive) {
            pointOverflowActive = pointOverflow;
            if (pointOverflow) {
                log.warn("点/聚光超 lightList 容量 {}（在册 {}），已按强度截尾去最弱",
                        LightCollector.MAX_POINT_LIGHTS, lightCollector.collectedPointCount()
                                - lightCollector.culledCount());
            }
        }
        boolean directionalOverflow = lightCollector.directionals().size()
                >= LightCollector.MAX_DIRECTIONAL
                && countDirectional() > LightCollector.MAX_DIRECTIONAL;
        if (directionalOverflow != directionalOverflowActive) {
            directionalOverflowActive = directionalOverflow;
            if (directionalOverflow) {
                log.warn("方向光超 UBO 上限 {}（在册 {}），已截尾", LightCollector.MAX_DIRECTIONAL,
                        countDirectional());
            }
        }
        boolean clusterPoolOverflow = clusterBuilder.poolOverflow();
        if (clusterPoolOverflow != clusterPoolOverflowActive) {
            clusterPoolOverflowActive = clusterPoolOverflow;
            if (clusterPoolOverflow) {
                log.warn("cluster 索引池溢出（定容 {} uint），尾段截断——cell 计数已同步收缩",
                        ClusterBuilder.MAX_POOL_UINTS);
            }
        }
    }

    private int countDirectional() {
        int count = 0;
        for (RenderLight light : lights) {
            if (light.type() == RenderLight.Type.DIRECTIONAL) {
                count++;
            }
        }
        return count;
    }

    // ═══ 内部 ═══

    /** 材质首渲染：建 set 2 布局（按 Shader 缓存）+ UBO + 描述符集（贴图懒上传 + 占位解析） */
    private MaterialResources createMaterialResources(Material material) {
        Shader shader = material.getShader();
        // 反序列化实例补缺（read 旁路 setShader 默认值填充——坏 stub 时期落盘的空
        // values / 属性集少于现行 shader 的旧 .mat，pack 前按现行 shader 回填）
        material.fillMissingDefaults();
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

    /**
     * set0（UBO1 相机）布局契约：覆盖制引擎 pass（拾取/全屏光照等）自建 PSO 复用，与材质管线
     * set0 同构。阶段可见性=ALL_GRAPHICS 超集：LightingPass 的 fs 读 cam.view 重建视空间量
     * （VERTEX-only 会使片元读零，光照全黑——批次④-B 实锤）；只用 vs 的消费方超集合法。
     */
    public DescriptorSetLayoutDesc frameSetLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.ALL_GRAPHICS)));
    }

    private DescriptorSetLayoutDesc frameLayoutDesc() {
        return frameSetLayoutDesc();
    }

    /**
     * set1 帧级光照布局契约（材质 PSO 共用，Engine_Lighting_Design §3.4 终态槽位）：
     * binding0 = LightingParamsBlock UBO（DYNAMIC，每视口偏移，FRAGMENT）；
     * binding1 = lightList / binding2、3 = clusterGrid、clusterIndices SSBO
     * （批次③起 per-view 缓冲；通用注册通道已清退——零外部用户）；binding4 shadowMap
     * 归批次④；binding5/6/7 = G-Buffer albedo/normal/worldPos（批次④-B，HYBRID 档
     * LightingPass 消费；材质 shader 不声明即不用，超集合法）。
     * 声明 set1 的 shader 只须是自己所用 binding 的子集（布局超集合法）。
     */
    public DescriptorSetLayoutDesc frameStorageLayoutDesc() {
        return new DescriptorSetLayoutDesc(List.of(
                new DescriptorSetLayoutDesc.Binding(0, DescriptorType.UNIFORM_BUFFER_DYNAMIC, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(1, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(2, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(3, DescriptorType.STORAGE_BUFFER, 1,
                        ShaderStage.FRAGMENT),
                // binding4 = shadowMap 深度比较采样（批次④，主光单张硬影；compare 采样器
                // 引擎常驻，ShadowPass 每帧写图、Forward Pass addTextureRead 声明依赖）
                new DescriptorSetLayoutDesc.Binding(4, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                // binding5/6/7 = G-Buffer 三张（批次④-B，HYBRID 档 LightingPass 消费）
                new DescriptorSetLayoutDesc.Binding(5, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(6, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(7, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                // binding8/10 = DDGI 辐照度 / 深度矩 atlas（四期 P4-05 B3 消费面；P4-17 退役
                // 腾出的槽正好接上）；binding9 = DDGI 参数 UBO（网格 + 运行态 mode/强度）
                new DescriptorSetLayoutDesc.Binding(8, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(9, DescriptorType.UNIFORM_BUFFER, 1,
                        ShaderStage.FRAGMENT),
                new DescriptorSetLayoutDesc.Binding(10, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT),
                // binding11 = shadowMap 原始深度（REND-21-C1 PCSS 阻挡搜索：与 binding4 同一张
                // atlas，不同采样器——无 compare 才能读到真实深度）
                new DescriptorSetLayoutDesc.Binding(11, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                        ShaderStage.FRAGMENT)));
    }

    // ═══ set1 per-view 光照资源（批次③：lightList/clusterGrid/clusterIndices 按视口） ═══

    /**
     * 每视口灯光资源（惰性创建；视口像素尺寸变化重建——grid 规模随之变）：
     * binding1 lightList（4096×64B）/ binding2 clusterGrid（cells×8B）/
     * binding3 clusterIndices（定容 1M uint）+ set1 描述符集（binding0 恒指引擎
     * 常驻 lightingBuffer 动态偏移）。旧资源两帧退役（GPU in-flight 安全）。
     */
    private ViewLighting viewLightsFor(String cameraName, int viewW, int viewH,
                                       int gridX, int gridY) {
        ViewLighting existing = viewLights.get(cameraName);
        if (existing != null && existing.viewW == viewW && existing.viewH == viewH
                && existing.gridX == gridX && existing.gridY == gridY) {
            return existing;
        }
        if (existing != null) {
            log.info("视口灯光资源重建: camera[{}] viewport {}x{} → {}x{} grid {}x{} → {}x{}",
                    cameraName, existing.viewW, existing.viewH, viewW, viewH,
                    existing.gridX, existing.gridY, gridX, gridY);
            viewLights.remove(cameraName);
            retireResource(existing::dispose);
        }
        Buffer lightList = device.createBuffer(new BufferDesc(
                (long) LightCollector.MAX_POINT_LIGHTS * LightCollector.LIGHT_ITEM_FLOATS * 4,
                Set.of(BufferUsage.STORAGE), MemoryPreference.hostVisibleCoherent()), null);
        Buffer clusterGrid = device.createBuffer(new BufferDesc(
                (long) gridX * gridY * ClusterBuilder.Z_SLICES * 8,
                Set.of(BufferUsage.STORAGE), MemoryPreference.hostVisibleCoherent()), null);
        Buffer clusterIndices = device.createBuffer(new BufferDesc(
                (long) ClusterBuilder.MAX_POOL_UINTS * 4,
                Set.of(BufferUsage.STORAGE), MemoryPreference.hostVisibleCoherent()), null);
        Buffer ddgiParams = device.createBuffer(new BufferDesc(
                (long) DDGI_PARAM_BYTES, Set.of(BufferUsage.UNIFORM),
                MemoryPreference.hostVisibleCoherent()), null);
        DescriptorSet set = device.createDescriptorSet(frameStorageSetLayout);
        // G-Buffer 三张（批次④-B）：材质 shader 不用、HYBRID 档 LightingPass 消费——
        // 同 set1 布局须给全 binding 有效描述符（Vulkan 绑定完整性），恒指向本视口附件
        Texture gbufferAlbedo = gbufferAlbedoFor(cameraName);
        Texture gbufferNormal = gbufferNormalFor(cameraName);
        Texture gbufferWorldPos = gbufferWorldPosFor(cameraName);
        ViewLighting created = new ViewLighting(cameraName, lightList, clusterGrid, clusterIndices,
                ddgiParams, gbufferAlbedo, gbufferNormal, gbufferWorldPos,
                set, viewW, viewH, gridX, gridY);
        // binding8/10 先指占位 1×1（探针 atlas 就绪后由 syncDdgi 重指）；
        // 参数 UBO 恒存在，mode=0 时着色端直接回落常量环境项
        updateLightingSet(created);
        viewLights.put(cameraName, created);
        log.info("视口灯光资源创建: camera[{}] viewport {}x{} grid {}x{}x{} ({}B/{}B)",
                cameraName, viewW, viewH, gridX, gridY, ClusterBuilder.Z_SLICES,
                gridX * gridY * ClusterBuilder.Z_SLICES * 8, ClusterBuilder.MAX_POOL_UINTS * 4);
        return created;
    }

    /**
     * set1 全量描述符写入（创建与 DDGI atlas 换绑共用；Vulkan 要求布局内每个 binding 都有
     * 有效描述符——材质 shader 不声明 binding5..10 也照填）。
     */
    private void updateLightingSet(ViewLighting vl) {
        Texture irr = vl.ddgiIrradiance != null ? vl.ddgiIrradiance : ddgiPlaceholderTexture();
        Texture dep = vl.ddgiDepth != null ? vl.ddgiDepth : ddgiPlaceholderTexture();
        Texture shadowMap = shadowMapFor(vl.cameraName);
        vl.set.update(new DescriptorUpdate[]{
                DescriptorUpdate.dynamicUniformBuffer(0, lightingBuffer, 0, LIGHTING_STRIDE),
                DescriptorUpdate.storageBuffer(1, vl.lightList),
                DescriptorUpdate.storageBuffer(2, vl.clusterGrid),
                DescriptorUpdate.storageBuffer(3, vl.clusterIndices),
                DescriptorUpdate.sampledTexture(4, shadowMap, shadowSampler),
                DescriptorUpdate.sampledTexture(5, vl.gbufferAlbedo, gbufferSampler),
                DescriptorUpdate.sampledTexture(6, vl.gbufferNormal, gbufferSampler),
                DescriptorUpdate.sampledTexture(7, vl.gbufferWorldPos, gbufferSampler),
                DescriptorUpdate.sampledTexture(8, irr, ddgiSampler),
                DescriptorUpdate.uniformBuffer(9, vl.ddgiParams, 0, DDGI_PARAM_BYTES),
                DescriptorUpdate.sampledTexture(10, dep, ddgiSampler),
                DescriptorUpdate.sampledTexture(11, shadowMap, shadowRawSampler)});
    }

    /** DDGI 占位图（1×1）：atlas 未就绪/DDGI 关闭时 binding8/10 的描述符占位（着色端不采样） */
    private Texture ddgiPlaceholderTexture() {
        if (ddgiPlaceholder == null) {
            ddgiPlaceholder = device.createTexture(TextureDesc.texture2D(
                    HDR_SCENE_COLOR_FORMAT, 1, 1, Set.of(TextureUsage.SAMPLED)), null);
        }
        return ddgiPlaceholder;
    }

    /** 环境光模式（桥每帧推送；{@code DDGI} 档由探针辐照度接管环境项） */
    public void setAmbientMode(AmbientMode mode) {
        this.ambientMode = mode != null ? mode : AmbientMode.OFF;
    }

    /** 当前环境光模式（DdgiProbePass 判定是否推送 DDGI 数据面用） */
    public AmbientMode ambientMode() {
        return ambientMode;
    }

    /**
     * DDGI 数据面绑定（P4-05 B3；{@code DdgiProbePass} 在 atlas 就绪后每帧调用）。
     *
     * <p>两张 atlas 句柄变 → 重建本视口 set1（旧 set 两帧退役，不原地 update，绕开 03047
     * 在途约束）；参数 UBO 每帧重写——网格原点是相机居中滚动的，不刷就是上一帧的旧原点。</p>
     */
    public void syncDdgi(String cameraName, DdgiAtlases atlases, DdgiProbeGrid grid,
                         DdgiSettings settings) {
        checkOpen();
        ViewLighting vl = viewLights.get(cameraName);
        if (vl == null || atlases == null || grid == null || settings == null) {
            return;
        }
        boolean rebound = vl.ddgiIrradiance != atlases.irradiance()
                || vl.ddgiDepth != atlases.depth();
        vl.ddgiIrradiance = atlases.irradiance();
        vl.ddgiDepth = atlases.depth();
        vl.ddgiGrid = grid;
        if (rebound) {
            rebuildLightingSet(vl);
        }
        writeDdgiParams(vl, grid, settings);
    }

    /** 本视口 set1 换代（DDGI atlas 换绑：新建 + 旧 set 两帧退役） */
    private void rebuildLightingSet(ViewLighting vl) {
        DescriptorSet fresh = device.createDescriptorSet(frameStorageSetLayout);
        DescriptorSet old = vl.set;
        vl.set = fresh;
        updateLightingSet(vl);
        retireResource(old::dispose);
    }

    /**
     * DDGI 参数 UBO 打包（std140，与 ShaderInjector 的 {@code DdgiParamsBlock} 同源）：
     * gridOriginSpacing（xyz 原点 / w 间距）、counts（xyz 三向数量 / w tilesPerRow）、
     * tileParams（x 辐照度 tile 边长 / y 辐照度内区 / z 深度 tile 边长 / w 深度内区）、
     * ddgiParams（x mode 0/1 / y 强度 / z 法线偏置（世界单位）/ w 预留）。
     */
    private void writeDdgiParams(ViewLighting vl, DdgiProbeGrid grid, DdgiSettings settings) {
        boolean live = ambientMode == AmbientMode.DDGI && settings.isEnabled();
        // 消费面接管状态只报变化（首跑排障用：数据面在跑但画面没变化，八成是这里 mode=0）
        String state = live + "|" + ambientMode + "|" + settings.isEnabled() + "|" + grid;
        if (!state.equals(ddgiStateLog)) {
            ddgiStateLog = state;
            if (live) {
                log.info("DDGI 消费面接管：camera[{}] {}，强度 {}、法线偏置 {}（环境项由探针辐照度接管）",
                        vl.cameraName, grid, settings.getIntensity(),
                        settings.getNormalBiasScale() * grid.spacing());
            } else if (settings.isEnabled()) {
                log.warn("DDGI 数据面在跑但消费面没接管：camera[{}] 环境光模式 = {}（要选 DDGI / 「探针 GI（DDGI）」"
                        + "才会由探针辐照度接管环境项，现在仍走常量项）", vl.cameraName, ambientMode);
            } else {
                log.info("DDGI 消费面关闭：camera[{}] 环境光模式 = {}、启用探针 GI = false",
                        vl.cameraName, ambientMode);
            }
        }
        ddgiPack.clear();
        ddgiPack.putFloat(grid.originX()).putFloat(grid.originY()).putFloat(grid.originZ())
                .putFloat(grid.spacing());
        ddgiPack.putInt(grid.countX()).putInt(grid.countY()).putInt(grid.countZ())
                .putInt(grid.tilesPerRow());
        ddgiPack.putFloat(DdgiProbeGrid.IRRADIANCE_TILE)
                .putFloat(DdgiProbeGrid.IRRADIANCE_INNER)
                .putFloat(DdgiProbeGrid.DEPTH_TILE)
                .putFloat(DdgiProbeGrid.DEPTH_INNER);
        ddgiPack.putFloat(live ? 1.0f : 0.0f)
                .putFloat(settings.getIntensity())
                .putFloat(settings.getNormalBiasScale() * grid.spacing())
                .putFloat(0.0f);
        ddgiPack.flip();
        vl.ddgiParams.update(0, ddgiPack);
    }

    /** set1 描述符集（本视口光照套件；collectFrame 先行建资源）。绑定带 1 个动态偏移（{@link RenderView#lightingOffset()}） */
    public DescriptorSet lightingSet(String cameraName) {
        checkOpen();
        ViewLighting vl = viewLights.get(cameraName);
        if (vl == null) {
            throw new IllegalStateException("视口灯光资源缺失: camera[" + cameraName
                    + "]（帧收集必须先于图构建执行）");
        }
        return vl.set;
    }

    /** CSM 对照参数块字节数：sunViewProj[4] 256 + shadowParams 16 + shadowParams2 16 + cascadeInvZ 16 */
    public static final int CSM_COMPARE_BYTES = 304;

    /**
     * CSM 对照参数快照（P4-06 B3 双通道对照用）：把本视口级联矩阵与阴影参数按
     * {@code sunViewProj[4]（未用槽 identity）→ shadowParams → shadowParams2 → cascadeInvZ}
     * 直通写出（与注入段 {@code lighting} UBO 同名同序，故对照 Pass 与实渲染同源）。
     *
     * <p>调用方负责 {@code out} 容量 ≥ {@link #CSM_COMPARE_BYTES}；本方法不 flip。</p>
     */
    public void packCsmCompareParams(String cameraName, ByteBuffer out) {
        checkOpen();
        ViewShadow vs = shadowView(cameraName);
        for (int c = 0; c < 4; c++) {
            for (float v : vs.matrices[c].get(matTmp)) {
                out.putFloat(v);
            }
        }
        ShadowSettings shadow = shadowSettings;
        out.putFloat(shadow.getQuality().code())
                .putFloat(shadow.getPcfRadius())
                .putFloat(shadow.getStrength())
                .putFloat(shadow.getRefDepthOffset());
        out.putFloat(vs.grid)
                .putFloat(vs.atlasSize)
                .putFloat(SHADOW_CASCADE_BLEND)
                .putFloat(shadow.getSoftness());
        for (int c = 0; c < 4; c++) {
            out.putFloat(vs.invZ[c]);
        }
    }

    /** 本视口 set1 storage 三缓冲（Forward Pass 图声明 addBufferRead：显式依赖 + 句柄进 structureHash） */
    public List<Buffer> viewStorages(String cameraName) {
        ViewLighting vl = viewLights.get(cameraName);
        if (vl == null) {
            throw new IllegalStateException("视口灯光资源缺失: camera[" + cameraName + "]");
        }
        return List.of(vl.lightList, vl.clusterGrid, vl.clusterIndices);
    }

    /** 每视口灯光资源（binding1/2/3 三缓冲 + set1 描述符集；渲染域私有） */
    private static final class ViewLighting {
        /** 视口名（set1 换绑时重指 shadowMap 等 per-view 资源用） */
        final String cameraName;
        final Buffer lightList;
        final Buffer clusterGrid;
        final Buffer clusterIndices;
        /** DDGI 参数 UBO（binding9；网格 origin/spacing/counts + 运行态 mode/强度/normalBias） */
        final Buffer ddgiParams;
        /** 本视口 G-Buffer 三张（set1 binding5/6/7 指向；纹理归 RenderingSystem 每视口
         *  表持有，此处仅留引用供调试/读数） */
        final Texture gbufferAlbedo;
        final Texture gbufferNormal;
        final Texture gbufferWorldPos;
        /** 当前指进 set1 binding8/10 的 DDGI atlas（null = 仍是占位图）；atlas 归
         *  {@link RenderingSystem} 的 ddgiAtlasSets 持有，此处只留引用做换绑判据 */
        Texture ddgiIrradiance;
        Texture ddgiDepth;
        /** 当前 atlas 对应网格（参数 UBO 的来源） */
        DdgiProbeGrid ddgiGrid;
        /** 本视口 set1（视口尺寸变化时整套换新、旧 set retire——不原地 update，
         *  绕开 03047 在途约束；dispose 只管当前这套，旧套在 retire 队列各自走完） */
        DescriptorSet set;
        final int viewW;
        final int viewH;
        final int gridX;
        final int gridY;

        ViewLighting(String cameraName, Buffer lightList, Buffer clusterGrid, Buffer clusterIndices,
                     Buffer ddgiParams,
                     Texture gbufferAlbedo, Texture gbufferNormal, Texture gbufferWorldPos,
                     DescriptorSet set, int viewW, int viewH, int gridX, int gridY) {
            this.cameraName = cameraName;
            this.lightList = lightList;
            this.clusterGrid = clusterGrid;
            this.clusterIndices = clusterIndices;
            this.ddgiParams = ddgiParams;
            this.gbufferAlbedo = gbufferAlbedo;
            this.gbufferNormal = gbufferNormal;
            this.gbufferWorldPos = gbufferWorldPos;
            this.set = set;
            this.viewW = viewW;
            this.viewH = viewH;
            this.gridX = gridX;
            this.gridY = gridY;
        }

        void dispose() {
            set.dispose();
            lightList.dispose();
            clusterGrid.dispose();
            clusterIndices.dispose();
            ddgiParams.dispose();
        }
    }

    // ═══ TAA（Engine_TAA_Design §3.2/§3.4） ═══

    /**
     * 写本视口 CameraBlock（viewProj + view）：TAA 开启时 viewProj 按 Halton 相位做亚像素
     * NDC 平移（T × P × V），并登记进历史表供下一帧重投影；关闭时写原矩阵。
     *
     * <p>抖动只影响本 UBO——{@code computeShadow} / 灯收集 / 簇构建均已在本调用之前用
     * 未抖动矩阵完成（顺序由 {@code collectFrame} 保证）。</p>
     */
    private void writeCameraBlock(RenderCamera cam, int cameraOffset, long frameIndex) {
        Matrix4f raw = cam.viewProj();
        TaaHistory history = taaHistories.computeIfAbsent(cam.name(), n -> new TaaHistory());
        // 位姿判定先行：移动帧不抖动（thinTAA: disableOnCameraMove —— 移动中抖动只会被直出放大）
        boolean moved = isTaaCameraMoved(history, raw);
        history.cameraMoved = moved;
        history.curRawViewProj.set(raw);
        // 本视口抗锯齿生效参数快照（TaaPass / SharpenPass 在 buildGraph 期按视口读）
        history.taaEnabled = cam.taaEnabled();
        history.sharpenStrength = cam.sharpenStrength();
        history.historySharpening = cam.historySharpening();
        // 防闪烁 0~1 → 3×3 AABB 钳制强度 0~2（0 = 关，thinTAA clampHistory 口径）
        history.clampScale = 2.0f * cam.antiFlickering();
        // 速度抑制 0~1 → 降权起始阈值（纹素；1 = 0.5 纹素起降权＝已验收口径，0 = 不抑制）
        history.speedThreshold = cam.speedRejection() <= 0.0f
                ? 0.0f
                : Math.max(0.25f, 2.5f - 2.0f * cam.speedRejection());
        Matrix4f effective = raw;
        // 抖动按相机：本视口启用 TAA 且未移动才抖（相机关闭时整幅不抖，链路照常直出）
        if (taaJitterEnabled && history.taaEnabled && !moved) {
            taaJitterPixels(frameIndex, TAA_JITTER_SCALE, jitterPixel);
            applyTaaJitter(raw, jitterPixel[0], jitterPixel[1],
                    cam.viewportWidth(), cam.viewportHeight(), jitterMatrix);
            effective = jitterMatrix;
            history.appliedJitter[0] = jitterPixel[0];
            history.appliedJitter[1] = jitterPixel[1];
        } else {
            history.appliedJitter[0] = 0.0f;
            history.appliedJitter[1] = 0.0f;
        }
        effective.get(jitteredViewProjTmp);
        uboBuffer.writeAligned(cameraOffset, FloatBuffer.wrap(jitteredViewProjTmp));
        // CameraBlock.view（批次③）：vs 传视空间位置/法线插值（Lit 类受光着色器）
        uboBuffer.writeAligned(cameraOffset + 64,
                FloatBuffer.wrap(cam.view().get(new float[16])));
        updateTaaHistory(cam.name(), history, effective, frameIndex);
        if (history.taaEnabled && !taaJitterLogged) {
            taaJitterLogged = true;
            log.info("TAA 抖动注入首帧: 相机[{}] frame={} moved={} 相位={} 抖动像素=({}, {}) 视口={}x{} "
                            + "历史帧号 prev/last={}/{}",
                    cam.name(), frameIndex, moved, (int) (frameIndex % TAA_JITTER_X.length),
                    String.format("%.3f", history.appliedJitter[0]),
                    String.format("%.3f", history.appliedJitter[1]),
                    cam.viewportWidth(), cam.viewportHeight(),
                    history.prevFrame, history.lastFrame);
        }
    }

    /** 相机位姿是否变化（未抖动 viewProj 逐元素比较；首帧视为移动 → 直出当前帧） */
    private boolean isTaaCameraMoved(TaaHistory history, Matrix4f raw) {
        if (history.lastFrame == Long.MIN_VALUE) {
            return true;
        }
        raw.get(taaRawA);
        history.curRawViewProj.get(taaRawB);
        for (int i = 0; i < taaRawA.length; i++) {
            if (Math.abs(taaRawA[i] - taaRawB[i]) > TAA_MOVE_EPS) {
                return true;
            }
        }
        return false;
    }

    /** Halton(2,3) 相位取点：帧号取相 × 幅度缩放 → 像素偏移（写入 out，避免分配） */
    static void taaJitterPixels(long frameIndex, float scale, float[] out) {
        int phase = (int) (frameIndex % TAA_JITTER_X.length);
        out[0] = TAA_JITTER_X[phase] * scale;
        out[1] = TAA_JITTER_Y[phase] * scale;
    }

    /**
     * NDC 亚像素平移后的 viewProj（T × P × V）：单像素 NDC 跨度 = 2/W、2/H。
     * 偏移为 0 时结果与原矩阵逐元素相等（单测锁定 JOML 方向与乘法顺序）。
     */
    static Matrix4f applyTaaJitter(Matrix4f viewProj, float pixelX, float pixelY,
                                   int viewWidth, int viewHeight, Matrix4f out) {
        return out.identity()
                .translate(2.0f * pixelX / Math.max(1, viewWidth),
                        2.0f * pixelY / Math.max(1, viewHeight), 0.0f)
                .mul(viewProj);
    }

    /** 历史登记：本帧矩阵移入 prev，帧号错一帧（TaaPass 按 prevFrame == frameIndex-1 判可用） */
    private void updateTaaHistory(String cameraName, TaaHistory history, Matrix4f effectiveViewProj,
                                  long frameIndex) {
        history.prevJitteredViewProj.set(history.curJitteredViewProj);
        history.curJitteredViewProj.set(effectiveViewProj);
        history.prevFrame = history.lastFrame;
        history.lastFrame = frameIndex;
        // 本帧启用状态滚入「上一帧」——关闭期间历史 RT 不更新，重新启用首帧必须判失效
        history.prevTaaEnabled = history.taaEnabled;
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
            disposeAll(pickIdTextures);
            disposeAll(pickDepthTextures);
            disposeAll(gbufferAlbedoTextures);
            disposeAll(gbufferNormalTextures);
            disposeAll(gbufferWorldPosTextures);
            disposeAll(lightingRtTextures);
            disposeAll(postRtTextures);
            disposeAll(bloomRtTextures);
            disposeAll(ssaoRtTextures);
            disposeAll(taaRtTextures);
            // 历史纹理换代：帧号一并作废（否则下一帧把新空纹理当历史采样）
            taaHistories.values().forEach(history -> {
                history.prevFrame = Long.MIN_VALUE;
                history.lastFrame = Long.MIN_VALUE;
            });
            // set1 per-view 描述符集引用 G-Buffer 纹理（binding5/6/7）——纹理换绑即重建
            // 描述符集（视口像素尺寸可能未变，不能只靠 viewLightsFor 的尺寸判据）
            if (!viewLights.isEmpty()) {
                viewLights.values().forEach(vl -> retireResource(vl::dispose));
                viewLights.clear();
            }
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

    private void checkOpen() {        if (closed) {
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
        if (rayTracingScene != null) {
            rayTracingScene.dispose();
            rayTracingScene = null;
        }
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
        disposeAll(pickIdTextures);
        disposeAll(pickDepthTextures);
        disposeAll(gbufferAlbedoTextures);
        disposeAll(gbufferNormalTextures);
        disposeAll(gbufferWorldPosTextures);
        disposeAll(lightingRtTextures);
        disposeAll(postRtTextures);
        disposeAll(bloomRtTextures);
        disposeAll(ssaoRtTextures);
        disposeAll(taaRtTextures);
        // DDGI atlas（每视口两张；表里只剩当前代——旧代都已在换代时进 retiredTextures）
        ddgiAtlasSets.values().forEach(a -> {
            a.irradiance().dispose();
            a.depth().dispose();
        });
        ddgiAtlasSets.clear();
        retiredTextures.forEach(r -> r.texture.dispose());
        retiredTextures.clear();
        // 天空盒：仅资产路径产物归本系统释放（Demo 测试形态 Texture 由调用方持有）
        if (skybox != null && skyboxAsset != null) {
            skybox.dispose();
            skybox = null;
        }
        retiredResources.forEach(r -> r.disposeAction.run());
        retiredResources.clear();
        frameSet.dispose();
        frameSetLayout.dispose();
        frameStorageSetLayout.dispose();
        viewLights.values().forEach(ViewLighting::dispose);
        viewLights.clear();
        lightingBuffer.dispose();
        viewShadows.values().forEach(ViewShadow::dispose);
        viewShadows.clear();
        shadowSampler.dispose();
        shadowRawSampler.dispose();
        gbufferSampler.dispose();
        ddgiSampler.dispose();
        if (ddgiPlaceholder != null) {
            ddgiPlaceholder.dispose();
            ddgiPlaceholder = null;
        }
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

    /** 每视口 TAA 历史（抖动后 viewProj 两代 + 帧号 + 本帧生效的抗锯齿参数快照；渲染域私有） */
    private static final class TaaHistory {
        /** 上一帧抖动后 viewProj（TaaPass 重投影基准） */
        final Matrix4f prevJitteredViewProj = new Matrix4f();
        /** 本帧抖动后 viewProj（下一帧滚入 prev） */
        final Matrix4f curJitteredViewProj = new Matrix4f();
        /** 未抖动 viewProj（上一帧）——相机位姿判据（thinTAA: 移动即丢历史、停抖） */
        final Matrix4f curRawViewProj = new Matrix4f();
        /** 本帧相机是否在移动（块混合 = 直出当前帧） */
        boolean cameraMoved = true;
        /** 上一帧本视口的帧号（可用判据 = frameIndex - 1） */
        long prevFrame = Long.MIN_VALUE;
        /** 本帧（最近一次收集）帧号 */
        long lastFrame = Long.MIN_VALUE;
        /** 本帧是否启用 TAA（相机级设置；关 = 不抖、不累积、不锐化） */
        boolean taaEnabled;
        /** 上一帧是否启用 TAA（关闭期间历史 RT 不更新，重新启用首帧须判失效） */
        boolean prevTaaEnabled;
        /** 本帧实际施加的抖动像素偏移（启用且未移动才非 0；copy 去抖按它平移） */
        final float[] appliedJitter = new float[2];
        /** 本帧生效的抗锯齿参数快照（相机设置） */
        float sharpenStrength;
        float historySharpening = 1.0f;
        float clampScale;
        float speedThreshold;
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
