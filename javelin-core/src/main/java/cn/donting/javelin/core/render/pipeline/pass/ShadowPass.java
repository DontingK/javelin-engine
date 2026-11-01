package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.mesh.MeshDraw;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.data.RHIScissor;
import cn.donting.javelin.rhi.data.RHIViewport;
import cn.donting.javelin.rhi.desc.GraphicsPipelineDesc;
import cn.donting.javelin.rhi.desc.ShaderDesc;
import cn.donting.javelin.rhi.types.BlendFactor;
import cn.donting.javelin.rhi.types.BlendOp;
import cn.donting.javelin.rhi.types.CompareOp;
import cn.donting.javelin.rhi.types.CullMode;
import cn.donting.javelin.rhi.types.DynamicState;
import cn.donting.javelin.rhi.types.Format;
import cn.donting.javelin.rhi.types.FrontFace;
import cn.donting.javelin.rhi.types.PolygonMode;
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
 * 主光阴影深度 Pass（Engine_Lighting_Design §4.5，批次④：单张硬影）。
 *
 * <p><b>覆盖制</b>（PickPass 同构先例）：不消费材质 shader 的 Pass 声明——用户
 * shader 零阴影声明负担，default.shader 兜底不受牵连。着色器装载走资产导入链
 * （2026-11-01 拍板）：内置 {@code builtin-asset-core/shadow/shadow.vert/.frag}
 * 源码经 SpvShaderImporter 编译 SPIR-V 落 Library，本 Pass 经
 * {@link AssetManager#loadAsset} 按路径加载（EditGridAppState 内置资产按路径
 * 加载先例；tool 内嵌 spv 路线同日推翻）。全局单张 shadowMap（2048² D32，
 * 非视口归属）CLEAR 后绘制全部 shadowCasters，depth-only 无颜色附件。</p>
 *
 * <p><b>全局 Pass、per-view 幂等挂载</b>（拍板 2026-11-01）：buildGraph 按视口循环调用，
 * 本 Pass 以帧号门控——本帧首个视口 addToGraph 时挂载一次，后续视口跳过
 * （PickSystem.beginFrame 同款幂等先例）。恒清空 shadowMap：主光不投影
 * （无/关阴影/无 caster）时只清不画——全图深度 1.0 采样恒受光，图结构不随光照态抖动。</p>
 *
 * <p><b>PSO</b>：无描述符集（vs 只消费 push），push 布局 128B 专属（lightViewProj@0 +
 * model@64，非引擎材质 80B 布局）；depthBias 静态保守值（漏影/彼得潘宁实测再调，
 * 灯光档 §4.5 口径）。透明物不投影（v0.x）。</p>
 */
public class ShadowPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(ShadowPass.class);

    /** 内置阴影着色器 loadAsset 路径（builtin-asset-core 释放到 .javelin/assets 后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/shadow/shadow.vert";
    private static final String FRAG_SHADER_PATH = "core/shadow/shadow.frag";
    /** push 布局：lightViewProj@0(64B) + model@64(64B) = 128B（ShadowPass 专属） */
    static final int SHADOW_PUSH_SIZE = 128;

    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    /** 覆盖制 PSO 缓存：key = layoutIdentity:topology（与材质无关，PSO 不每帧新建） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 每帧复用的 push constants 打包缓冲（引擎线程录制串行，非线程共享） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(SHADOW_PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] lightViewProjTmp = new float[16];
    private final float[] worldTmp = new float[16];
    /** 幂等挂载门控（本帧已挂载的帧号；buildGraph per-view 循环重复调用） */
    private long lastAddedFrame = -1;
    private boolean shadowLogged;

    /**
     * @param device       RHI 设备（PSO 创建）
     * @param assetManager 资产管理器（装载内置 shadow.vert/.frag 的 SpvShader 产物）
     */
    public ShadowPass(Device device, AssetManager assetManager) {
        this.device = device;
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
        long frameIndex = ctx.frame().frameIndex();
        // 全局 Pass 幂等：本帧首视口挂载一次（多视口共享同一张 shadowMap）
        if (frameIndex == lastAddedFrame) {
            return;
        }
        if (frameIndex < lastAddedFrame) {
            // 帧号回退（理论上不发生）：重挂避免整帧无影
            log.warn("ShadowPass 帧号回退: {} < {}，重新挂载", frameIndex, lastAddedFrame);
        }
        lastAddedFrame = frameIndex;

        RenderingSystem rs = ctx.renderingSystem();
        // caster 集取首视口列表（收集序覆盖全部 castShadow 不透明对象——cullingMask
        // 差异致盒欠盖的风险由收集域超集口径兜底，RenderingSystem.computeShadow 注释）
        RenderView view = ctx.view();
        List<RenderObject> casters = view.list().shadowCasters();
        // 视口随 shadowMap 实际尺寸（分辨率随主光组件参数可变，按需重建）
        Texture shadowMap = rs.shadowMap();
        int shadowMapSize = shadowMap.desc().width();
        graph.addGraphicsPass("Shadow", pass -> {
            pass.addDepthOutput(shadowMap, RHILoadOp.CLEAR,
                    new RHIClearDepthStencil(1.0f, 0));
            pass.setViewport(new RHIViewport(0.0f, 0.0f, shadowMapSize, shadowMapSize, 0.0f, 1.0f));
            pass.setScissor(new RHIScissor(0, 0, shadowMapSize, shadowMapSize));
            pass.setRenderCallback(cmd -> {
                // 恒挂载（保 shadowMap 写者存在 + 图结构稳定）：阴影关/无 caster 只清不画
                // ——全图深度 1.0 采样恒受光；着色侧 castShadow 标志同步门控采样分支
                if (!rs.shadowSunActive() || casters.isEmpty()) {
                    return;
                }
                if (!shadowLogged) {
                    shadowLogged = true;
                    log.info("首帧 Shadow 回调执行: casters={}", casters.size());
                }
                float[] lightViewProj = rs.sunViewProj().get(lightViewProjTmp);
                for (RenderObject ro : casters) {
                    drawCaster(cmd, ro, lightViewProj);
                }
            });
        });
    }

    /** 释放 PSO 缓存（须 device waitIdle 后） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
    }

    private void drawCaster(CommandBuffer cmd, RenderObject ro, float[] lightViewProj) {
        cmd.bindPipeline(pipelineFor(ro));
        MeshDraw.bindBuffers(cmd, ro.mesh());
        pushData.clear();
        for (float v : lightViewProj) {
            pushData.putFloat(v);
        }
        for (float v : ro.world().get(worldTmp)) {
            pushData.putFloat(v);
        }
        pushData.position(0);
        cmd.pushConstants(0, pushData);
        MeshDraw.drawIndexed(cmd, ro.mesh(), ro.submesh());
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
                null,
                List.of(), Format.D32_SFLOAT,   // depth-only：无颜色附件（RHI 允许空列表）
                List.of(),                      // 无描述符集（vs 只消费 push）
                SHADOW_PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                SampleCount.COUNT_1,
                // depthBias 静态保守值（2048² 单张全场景正交；实测漏影/彼得潘宁再调）
                true, 2.0f, 1.5f);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("shadow PSO created: layout={} topology={}",
                ro.mesh().layout(), ro.mesh().getTopology());
        return created;
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
