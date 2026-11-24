package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.physics.collision.Aabb;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.Material;
import cn.donting.javelin.core.render.material.Shader;
import cn.donting.javelin.core.render.mesh.MeshDraw;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.DescriptorSet;
import cn.donting.javelin.rhi.DescriptorSetLayout;
import cn.donting.javelin.rhi.DescriptorUpdate;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.VertexLayout;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.desc.DescriptorSetLayoutDesc;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DescriptorType;
import cn.donting.javelin.rhi.types.DynamicState;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
import cn.donting.javelin.rhi.types.SampleCount;
import cn.donting.javelin.rhi.types.ShaderStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 主光阴影深度 Pass（Engine_Lighting_Design §4.5 批次④ → 四期 CSM）。
 *
 * <p><b>覆盖制</b>（PickPass 同构先例）：不消费材质 shader 的 Pass 声明——用户
 * shader 零阴影声明负担，default.shader 兜底不受牵连。着色器装载走资产导入链
 * （2026-11-01 拍板）：内置 {@code builtin-asset-core/shadow/shadow.vert/.frag}
 * 源码经 SpvShaderImporter 编译 SPIR-V 落 Library，本 Pass 经
 * {@link AssetManager#loadAsset} 按路径加载。depth-only 无颜色附件。</p>
 *
 * <p><b>per-view、逐级联</b>（四期 CSM §4.7）：每视口挂一 Pass（Pass 名 = 视口名 + ":Shadow"），
 * 写该视口自己的 shadowMap atlas（cascadeCount≥2 时 2×2、每象限一级联）；atlas 一次 CLEAR 后
 * 回调内逐级联 {@code setViewport/setScissor} 切象限绘制（VIEWPORT/SCISSOR 动态态，同一 PSO
 * 复用）。恒清空：主光不投影（无/关阴影/无 caster）时只清不画——全图深度 1.0 采样恒受光，
 * 图结构不随光照态抖动。</p>
 *
 * <p><b>PSO</b>：set0 为阴影专有（binding0 = 材质 {@code _MainTex} 采样器，REND-21-A alpha
 * 裁剪），push 布局 144B 专属（lightViewProj@0 + model@64 + cutoff@128 + hasAlbedoTex@132，
 * 非引擎材质 80B 布局）；depthBias 静态两项取阴影设置（改设置即整批重建，
 * 旧管线两帧退役）。透明物不投影（v0.x）；声明 {@code _AlphaCutoff} 的材质按 alpha 裁剪
 * （Engine_Shadow_Cutout_Design）。</p>
 */
public class ShadowPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(ShadowPass.class);

    /** 内置阴影着色器 loadAsset 路径（builtin-asset-core 释放到 .javelin/assets 后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/shadow/shadow.vert";
    private static final String FRAG_SHADER_PATH = "core/shadow/shadow.frag";
    /**
     * push 布局：lightViewProj@0(64B) + model@64(64B) + cutoff@128(4B) + hasAlbedoTex@132(4B)
     * = 144B（ShadowPass 专属；GLSL 块按 16B 对齐收官，故 vs/fs 两侧同声明同一结构）。
     */
    static final int SHADOW_PUSH_SIZE = 144;

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    /** 覆盖制 PSO 缓存：key = layoutIdentity:topology（与材质无关，PSO 不每帧新建） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 阴影专 set0 布局（binding0 = albedo 采样器；REND-21-A alpha 裁剪） */
    private final DescriptorSetLayoutDesc shadowSetDesc = new DescriptorSetLayoutDesc(List.of(
            new DescriptorSetLayoutDesc.Binding(0, DescriptorType.COMBINED_IMAGE_SAMPLER, 1,
                    ShaderStage.FRAGMENT)));
    private DescriptorSetLayout shadowSetLayout;
    /** 每材质 alpha 集（贴图版本/材质 shader 变即重建，旧集两帧退役） */
    private final Map<Material, AlphaBinding> alphaBindings = new IdentityHashMap<>();
    /** 不裁剪 caster 共用的白图集（懒建一次） */
    private DescriptorSet whiteAlphaSet;
    /** 「声明裁剪但网格无 UV」告警只打一次（避免每帧刷屏） */
    private boolean warnedMissingUv;
    /** 每帧复用的 push constants 打包缓冲（引擎线程录制串行，非线程共享） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(SHADOW_PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] lightViewProjTmp = new float[16];
    private final float[] worldTmp = new float[16];
    private boolean shadowLogged;
    /** 逐级联剔除（REND-21-B）复用缓冲：光裁剪矩阵 + 世界盒 + 剪辑盒（引擎线程串行录制，非共享） */
    private final Matrix4f cascadeMat = new Matrix4f();
    private final Aabb worldBox = new Aabb();
    private final Aabb clipBox = new Aabb();
    /** 首帧剔除统计只打一次（级联 × 存活数，便于观察收益） */
    private boolean cullLogged;
    /** 当前 PSO 批次建库时用的 depth bias 两项（设置变化即整批重建；NaN = 尚未建库） */
    private float builtBiasConstant = Float.NaN;
    private float builtBiasSlope = Float.NaN;

    /**
     * @param device       RHI 设备（PSO 创建）
     * @param assetManager 资产管理器（装载内置 shadow.vert/.frag 的 SpvShader 产物）
     */
    public ShadowPass(Device device, AssetManager assetManager) {
        this.device = device;
        // 红线 15：push 144B 超出设备上限（规范保底 128）时显性报错，不静默降级
        if (SHADOW_PUSH_SIZE > device.maxPushConstantsSize()) {
            throw new IllegalStateException("阴影 Pass push 需 " + SHADOW_PUSH_SIZE
                    + "B（alpha 裁剪），设备上限仅 " + device.maxPushConstantsSize()
                    + "B——alpha 裁剪需改走 UBO 传输（Engine_Shadow_Cutout_Design §4 P2 备选）");
        }
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "shadow.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "shadow.frag");
    }

    @Override
    public String name() {
        return "Shadow";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；与用户 shader 自定义 Pass 名无消费关系） */
    @Override
    public String shaderPassName() {
        return "Shadow";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderingSystem rs = ctx.renderingSystem();
        RenderView view = ctx.view();
        List<RenderObject> casters = view.list().shadowCasters();
        int cascadeCount = rs.shadowCascadeCount(view.name());
        int grid = rs.shadowAtlasGrid(view.name());
        Texture atlas = rs.shadowMapFor(view.name());
        int atlasSize = atlas.desc().width();
        int quadSize = atlasSize / grid;
        // 逐视口一 Pass（CSM §4.7）：atlas 一次 CLEAR，回调内逐级联切 viewport/scissor 绘制
        // 到各象限（VIEWPORT/SCISSOR 是动态态，同一 PSO 复用）。Pass 名含视口名保全局唯一。
        graph.addGraphicsPass(view.name() + ":Shadow", pass -> {
            pass.addDepthOutput(atlas, RHILoadOp.CLEAR,
                    new RHIClearDepthStencil(1.0f, 0));
            pass.setViewport(new RHIViewport(0.0f, 0.0f, atlasSize, atlasSize, 0.0f, 1.0f));
            pass.setScissor(new RHIScissor(0, 0, atlasSize, atlasSize));
            pass.setRenderCallback(cmd -> {
                // 恒挂载（保 atlas 写者存在 + 图结构稳定）：阴影关/无 caster 只清不画
                // ——全图深度 1.0 采样恒受光；着色侧 castShadow 标志同步门控采样分支
                ensurePipelineBias(rs);
                if (!shadowLogged) {
                    shadowLogged = true;
                    log.info("首帧 Shadow 回调: 视口[{}] sunActive={} casters={} 级联={} atlas={}² grid={}",
                            view.name(), rs.shadowSunActive(), casters.size(),
                            cascadeCount, atlasSize, grid);
                }
                if (!rs.shadowSunActive() || casters.isEmpty()) {
                    return;
                }
                for (int c = 0; c < cascadeCount; c++) {
                    int col = c % grid;
                    int row = c / grid;
                    int qx = col * quadSize;
                    int qy = row * quadSize;
                    cmd.setViewport(new RHIViewport(qx, qy, quadSize, quadSize, 0.0f, 1.0f));
                    cmd.setScissor(new RHIScissor(qx, qy, quadSize, quadSize));
                    float[] lightViewProj = rs.shadowCascadeMatrix(view.name(), c).get(lightViewProjTmp);
                    // 逐级联剔除（REND-21-B）：caster 的世界盒放进本级联的光裁剪空间，
                    // 完全落在 x/y 之外或整体越过远平面（min.z > 1）则跳过——
                    // 正交投影下横向/纵深都在盒外就投不进本级联。近平面不裁（光与级联之间的
                    // 遮挡体仍会把影子投进来）；盒为 null（无 POSITION 且 CPU 副本已释放）恒画
                    cascadeMat.set(lightViewProj);
                    int kept = 0;
                    int total = 0;
                    for (RenderObject ro : casters) {
                        total++;
                        if (!visibleInCascade(ro, cascadeMat)) {
                            continue;
                        }
                        kept++;
                        drawCaster(cmd, ro, lightViewProj, rs);
                    }
                    if (!cullLogged && c == cascadeCount - 1) {
                        cullLogged = true;
                        log.info("逐级联剔除: 视口[{}] 级联{} 末级存活 {}/{}（REND-21-B）",
                                view.name(), c, kept, total);
                    }
                }
            });
        });
    }

    /** 释放 PSO 缓存与 alpha 描述符集（须 device waitIdle 后） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
        alphaBindings.values().forEach(binding -> binding.set.dispose());
        alphaBindings.clear();
        if (whiteAlphaSet != null) {
            whiteAlphaSet.dispose();
            whiteAlphaSet = null;
        }
        if (shadowSetLayout != null) {
            shadowSetLayout.dispose();
            shadowSetLayout = null;
        }
    }

    /**
     * 单 caster 是否落进某级联（REND-21-B）：网格本地盒 → 世界盒 → 光裁剪盒（正交、z ∈ [0,1]），
     * 判 x/y 区间重叠且未整体越过远平面。
     *
     * <p>近平面侧**不裁**：光与级联之间的遮挡体虽然 z &lt; 0，影子仍会投进本级联，裁了会漏光。
     * 无包围盒（无 POSITION 且 CPU 副本已释放）一律按可见处理，宁可多画不丢影。</p>
     */
    private boolean visibleInCascade(RenderObject ro, Matrix4fc lightViewProj) {
        Aabb local = ro.mesh().getBounds();
        if (local == null) {
            return true;
        }
        local.transform(ro.world(), worldBox).transform(lightViewProj, clipBox);
        float margin = 0.02f;
        return clipBox.getMax().x >= -1.0f - margin
                && clipBox.getMin().x <= 1.0f + margin
                && clipBox.getMax().y >= -1.0f - margin
                && clipBox.getMin().y <= 1.0f + margin
                && clipBox.getMin().z <= 1.0f + margin;
    }

    private void drawCaster(CommandBuffer cmd, RenderObject ro, float[] lightViewProj,
                            RenderingSystem rs) {
        Material material = ro.material();
        float cutoff = rs.alphaCutoff(material);
        boolean hasUv = hasUv(ro.mesh().layout());
        if (cutoff > 0.0f && !hasUv && !warnedMissingUv) {
            warnedMissingUv = true;
            log.warn("材质声明 alpha 裁剪（cutoff={}）但网格无 UV0 属性——该 caster 按不裁剪绘制:"
                    + "（着色器属性 {} 已开但无法取 alpha）", cutoff,
                    RenderingSystem.ALPHA_CUTOFF_PROPERTY);
        }
        float hasAlbedoTex = cutoff > 0.0f && hasUv ? 1.0f : 0.0f;
        cmd.bindPipeline(pipelineFor(ro));
        // set0 恒绑（管线布局含该集）：不裁剪时白图 alpha 恒 1，等价现状
        cmd.bindDescriptorSet(0, hasAlbedoTex > 0.0f
                ? alphaBindingFor(material, rs) : whiteAlphaSet(rs), 0, null);
        MeshDraw.bindBuffers(cmd, ro.mesh());
        pushData.clear();
        for (float v : lightViewProj) {
            pushData.putFloat(v);
        }
        for (float v : ro.world().get(worldTmp)) {
            pushData.putFloat(v);
        }
        pushData.putFloat(cutoff);
        pushData.putFloat(hasAlbedoTex);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
        MeshDraw.drawIndexed(cmd, ro.mesh(), ro.submesh());
    }

    /** 网格是否有 UV0（规范序 location 3；无 UV 取不到 alpha，按不裁剪绘制） */
    private static boolean hasUv(VertexLayout layout) {
        for (VertexLayout.Attribute attribute : layout.attributes()) {
            if (attribute.location() == 3) {
                return true;
            }
        }
        return false;
    }

    /**
     * 每材质 alpha 集（set0 binding0 = 材质 {@code _MainTex} 的 GPU 纹理）。
     * 贴图版本（{@code getTextureRevision}）或 shader 变即重建；旧集走
     * {@code retireResource} 两帧退役（GPU in-flight 安全）。
     */
    private DescriptorSet alphaBindingFor(Material material, RenderingSystem rs) {
        int revision = material == null ? -1 : material.getTextureRevision();
        Shader shader = material == null ? null : material.getShader();
        AlphaBinding existing = material == null ? null : alphaBindings.get(material);
        if (existing != null && existing.revision == revision && existing.shader == shader) {
            return existing.set;
        }
        if (shadowSetLayout == null) {
            shadowSetLayout = device.createDescriptorSetLayout(shadowSetDesc);
        }
        if (existing != null) {
            DescriptorSet retiredSet = existing.set;
            rs.retireResource(retiredSet::dispose);
        }
        DescriptorSet set = device.createDescriptorSet(shadowSetLayout);
        set.update(DescriptorUpdate.sampledTexture(0,
                rs.shadowAlbedoTexture(material), rs.shadowAlbedoSampler(material)));
        if (material != null) {
            alphaBindings.put(material, new AlphaBinding(set, revision, shader));
        }
        return set;
    }

    /** 不裁剪 caster 共用集（1×1 白图，alpha 恒 1） */
    private DescriptorSet whiteAlphaSet(RenderingSystem rs) {
        if (whiteAlphaSet != null) {
            return whiteAlphaSet;
        }
        if (shadowSetLayout == null) {
            shadowSetLayout = device.createDescriptorSetLayout(shadowSetDesc);
        }
        whiteAlphaSet = device.createDescriptorSet(shadowSetLayout);
        whiteAlphaSet.update(DescriptorUpdate.sampledTexture(0,
                rs.shadowAlbedoTexture(null), rs.shadowAlbedoSampler(null)));
        return whiteAlphaSet;
    }

    /** 材质 alpha 集缓存项（贴图版本 / shader 任一变化即重建） */
    private static final class AlphaBinding {
        final DescriptorSet set;
        final int revision;
        final Shader shader;

        AlphaBinding(DescriptorSet set, int revision, Shader shader) {
            this.set = set;
            this.revision = revision;
            this.shader = shader;
        }
    }

    private GraphicsPipeline pipelineFor(RenderObject ro) {
        String key = System.identityHashCode(ro.mesh().layout()) + ":"
                + ro.mesh().getTopology();
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                ro.mesh().layout(),
                ro.mesh().getTopology(),
                CullMode.BACK,
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                true, true, CompareOp.LESS,
                false, BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,                           // 无颜色组件掩码（depth-only）
                List.of(), Format.D32_SFLOAT,   // depth-only：无颜色附件（RHI 允许空列表）
                List.of(shadowSetDesc),         // 阴影专 set0：binding0 = alpha 裁剪 albedo
                SHADOW_PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                SampleCount.COUNT_1,
                // depthBias 静态两项来自阴影设置（Engine_Shadow_Quality_Design §3.5）；
                // 设置变化由 ensurePipelineBias 整批重建 PSO
                true, builtBiasConstant, builtBiasSlope);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("shadow PSO created: layout={} topology={}",
                ro.mesh().layout(), ro.mesh().getTopology());
        return created;
    }

    /**
     * 对齐 PSO 静态 depth bias 与当前设置（Engine_Shadow_Quality_Design §3.5）：
     * 两项是管线创建态（RHI 无 vkCmdSetDepthBias 口），变化即整批退役重建——旧管线走
     * {@code RenderingSystem.retireResource} 两帧延迟销毁（GPU in-flight 安全）。
     * 重建发生在录制回调内、下一帧起用新管线绘制。
     */
    private void ensurePipelineBias(RenderingSystem rs) {
        float constant = rs.shadowSettings().getBiasConstant();
        float slope = rs.shadowSettings().getBiasSlope();
        if (constant == builtBiasConstant && slope == builtBiasSlope) {
            return;
        }
        boolean hadPipelines = !pipelines.isEmpty();
        pipelines.values().forEach(pipeline -> rs.retireResource(pipeline::dispose));
        pipelines.clear();
        builtBiasConstant = constant;
        builtBiasSlope = slope;
        if (hadPipelines) {
            log.info("shadow PSO 重建：depthBias constant={} slope={}（旧管线两帧退役）", constant, slope);
        }
    }

    /**
     * 装载内置 SPIR-V 着色器资产（资产导入链产物；红线 15——缺失/坏产物显性报错
     * 不静默兜底：导入链未跑（内置资产未释放/未导入）或编译失败 stub 都在此爆出）。
     */
    private static byte[] loadSpv(AssetManager assetManager, String path, String label) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException("阴影着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/shadow/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("阴影着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }
}
