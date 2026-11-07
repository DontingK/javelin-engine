package cn.donting.javelin.core.render.pipeline.pass;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.assets.shadow.SpvShader;
import cn.donting.javelin.core.render.RenderObject;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.mesh.MeshDraw;
import cn.donting.javelin.core.render.picking.PickSystem;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.rhi.CommandBuffer;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.GraphicsPipeline;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.data.RHIClearDepthStencil;
import cn.donting.javelin.rhi.data.RHIClearUintColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
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
 * GPU ID-buffer 拾取 Pass（Engine_Picking_Design.md，2026-10-23 拍板落地）。
 *
 * <p><b>覆盖制</b>（拍板）：不消费材质 shader 的 Pass 声明——「看得见即
 * 拾得」，用户 shader 零声明负担。着色器装载走资产导入链（ShadowPass/SkyboxPass
 * 同款）：内置 {@code builtin-asset-core/pick/pick.vert/.frag} 源码经
 * SpvShaderImporter 编译 SPIR-V 落 Library，本 Pass 经
 * {@link AssetManager#loadAsset} 按路径加载（2026-11-07 改向——原
 * 「GLSL 放 javelin-tool、spv 内嵌 core resources」路线作废）。绘制全部不透明
 * 物体 + "Gizmo" 桶。每个可拾取物体以帧内分配的 pick ID
 * （{@link PickSystem}，0 = 背景）写进 R32_UINT 附件，附件经 TextureReadbackRing
 * 点击/悬停读回。由 {@link PickSystem} 挂/摘管线（{@link RenderPipeline#addPass}），
 * 运行时游戏与编辑器同为消费方。</p>
 *
 * <p>深度语义：不透明+透明物体深度测试照常（近者胜，Unity 口径透明物可选中——
 * 拾取输出常量 ID 无 blend 语义；编辑器透明面如 Grid 命中后由消费方按节点归属
 * 裁决）；gizmo 轴深度关（置顶，轴压物体——与 GizmoPass 两层语义一致）。恒 1x
 * （与主画面 MSAA 档解耦，附件像素与屏幕像素一一对应）。</p>
 */
public class PickPass implements RenderPass {

    private static final Logger log = LoggerFactory.getLogger(PickPass.class);

    /** 内置拾取着色器 loadAsset 路径（builtin-asset-core 释放到 .javelin/assets 后的相对路径） */
    private static final String VERT_SHADER_PATH = "core/pick/pick.vert";
    private static final String FRAG_SHADER_PATH = "core/pick/pick.frag";
    /** push 布局：model@0(64B) + pickId@64（tpf 槽位让渡——拾取无时间语义） */
    private static final int PICK_ID_OFFSET = RenderingSystem.PUSH_TPF_OFFSET;

    private final PickSystem picking;
    private final Device device;
    private final byte[] vertSpv;
    private final byte[] fragSpv;
    /** 覆盖制 PSO 缓存：key = layoutIdentity:topology:depthOn（与材质无关，PSO 不每帧新建） */
    private final Map<String, GraphicsPipeline> pipelines = new HashMap<>();
    /** 每帧复用的 push constants 打包缓冲（引擎线程录制串行，非线程共享） */
    private final ByteBuffer pushData = ByteBuffer.allocateDirect(RenderingSystem.PUSH_SIZE)
            .order(ByteOrder.LITTLE_ENDIAN);
    private final float[] worldTmp = new float[16];
    private boolean pickLogged;

    /**
     * @param picking      拾取系统（帧表/pick ID 分配）
     * @param device       RHI 设备（PSO 创建）
     * @param assetManager 资产管理器（装载内置 pick.vert/.frag 的 SpvShader 产物）
     */
    public PickPass(PickSystem picking, Device device, AssetManager assetManager) {
        this.picking = picking;
        this.device = device;
        this.vertSpv = loadSpv(assetManager, VERT_SHADER_PATH, "pick.vert");
        this.fragSpv = loadSpv(assetManager, FRAG_SHADER_PATH, "pick.frag");
    }

    @Override
    public String name() {
        return "Pick";
    }

    /** 身份标识（覆盖制不查材质 shader 的 Pass；声明了 "Pick" Pass 的用户物体会进同名桶，本 Pass 不消费） */
    @Override
    public String shaderPassName() {
        return "Pick";
    }

    @Override
    public void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        RenderView view = ctx.view();
        RenderingSystem rs = ctx.renderingSystem();
        long frameIndex = ctx.frame().frameIndex();
        // 帧表重建（多视口 addToGraph 同帧多次调用，PickSystem 内部按帧号幂等）
        picking.beginFrame(frameIndex);
        graph.addGraphicsPass(view.name() + "_Pick", pass -> {
            pass.addColorOutput(rs.pickIdFor(view.name()), RHILoadOp.CLEAR,
                    new RHIClearUintColor(0, 0, 0, 0));
            pass.addDepthOutput(rs.pickDepthFor(view.name()), RHILoadOp.CLEAR,
                    new RHIClearDepthStencil(1.0f, 0));
            pass.setViewport(view.viewport());
            pass.setScissor(view.scissor());
            int uboOffset = view.uboOffset();
            pass.setRenderCallback(cmd -> {
                List<RenderObject> opaque = view.list().opaque();
                List<RenderObject> transparent = view.list().transparent();
                List<RenderObject> gizmo = view.list().passObjects("Gizmo");
                if (opaque.isEmpty() && transparent.isEmpty() && gizmo.isEmpty()) {
                    return;
                }
                if (!pickLogged) {
                    pickLogged = true;
                    log.info("首帧 Pick 回调执行: 视口[{}] 不透明={} 透明={} gizmo={}",
                            view.name(), opaque.size(), transparent.size(), gizmo.size());
                }
                // set0 绑定需要已绑 PSO 提供布局：先用首个物体 PSO 建立
                // （全部拾取 PSO 的 set0 同为帧布局，后续切 PSO 不影响已绑描述符）
                RenderObject first = !opaque.isEmpty() ? opaque.get(0)
                        : !transparent.isEmpty() ? transparent.get(0) : gizmo.get(0);
                cmd.bindPipeline(pipelineFor(rs, first, true));
                cmd.bindDescriptorSet(0, rs.frameSet(), 1, new int[]{uboOffset});
                for (RenderObject ro : opaque) {
                    drawPick(cmd, rs, ro, true);
                }
                // 透明桶同深度语义（Unity 口径：透明物可选中）——拾取输出常量 ID 无 blend，
                // 近者胜规则成立；网格等编辑器透明面命中后由消费方按节点归属裁决（点空清空）
                for (RenderObject ro : transparent) {
                    drawPick(cmd, rs, ro, true);
                }
                // gizmo 轴置顶：深度关，轴压物体（与 GizmoPass 对外语义一致）
                for (RenderObject ro : gizmo) {
                    drawPick(cmd, rs, ro, false);
                }
            });
        });
    }

    /** 释放 PSO 缓存（PickSystem.close 时调用；须 device waitIdle 后） */
    public void close() {
        pipelines.values().forEach(GraphicsPipeline::dispose);
        pipelines.clear();
    }

    private void drawPick(CommandBuffer cmd, RenderingSystem rs, RenderObject ro, boolean depthOn) {
        int pickId = picking.assignPickId(ro);
        ro.pickId(pickId);
        cmd.bindPipeline(pipelineFor(rs, ro, depthOn));
        MeshDraw.bindBuffers(cmd, ro.mesh());
        pushData.clear();
        for (float v : ro.world().get(worldTmp)) {
            pushData.putFloat(v);
        }
        pushData.putInt(PICK_ID_OFFSET, pickId);
        pushData.limit(PICK_ID_OFFSET + Integer.BYTES);
        pushData.position(0);
        cmd.pushConstants(0, pushData);
        MeshDraw.drawIndexed(cmd, ro.mesh(), ro.submesh());
    }

    private GraphicsPipeline pipelineFor(RenderingSystem rs, RenderObject ro, boolean depthOn) {
        String key = System.identityHashCode(ro.mesh().layout()) + ":"
                + ro.mesh().getTopology() + ":" + depthOn;
        GraphicsPipeline cached = pipelines.get(key);
        if (cached != null) {
            return cached;
        }
        GraphicsPipelineDesc desc = new GraphicsPipelineDesc(
                new ShaderDesc(ShaderStage.VERTEX, vertSpv),
                new ShaderDesc(ShaderStage.FRAGMENT, fragSpv),
                ro.mesh().layout(),
                ro.mesh().getTopology(),
                CullMode.NONE,               // 看见即拾得：背面也拾
                FrontFace.COUNTER_CLOCKWISE,
                PolygonMode.FILL,
                1.0f,
                depthOn, depthOn, CompareOp.LESS_OR_EQUAL,
                false, BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                BlendFactor.ONE, BlendFactor.ZERO, BlendOp.ADD,
                null,
                List.of(Format.R32_UINT), Format.D32_SFLOAT,
                List.of(rs.frameSetLayoutDesc()),   // 仅 set0，无材质 set2（覆盖制）
                RenderingSystem.PUSH_SIZE,
                Set.of(DynamicState.VIEWPORT, DynamicState.SCISSOR),
                SampleCount.COUNT_1);
        GraphicsPipeline created = device.createGraphicsPipeline(desc);
        pipelines.put(key, created);
        log.info("pick PSO created: layout={} topology={} depthOn={}",
                ro.mesh().layout(), ro.mesh().getTopology(), depthOn);
        return created;
    }

    /**
     * 装载内置 SPIR-V 着色器资产（资产导入链产物；红线 15——缺失/坏产物显性报错
     * 不静默兜底：导入链未跑（内置资产未释放/未导入）或编译失败 stub 都在此爆出）。
     */
    private static byte[] loadSpv(AssetManager assetManager, String path, String label) {
        SpvShader spv = assetManager.loadAsset(path, SpvShader.class);
        if (spv == null) {
            throw new IllegalStateException("拾取着色器资产未找到: " + path
                    + "（内置资产应随引擎启动释放进 .javelin/assets 并经导入链编译——"
                    + "检查 builtin-asset-core/pick/" + label + " 与伴生 .meta）");
        }
        if (!spv.isValid()) {
            throw new IllegalStateException("拾取着色器为坏产物（编译失败 stub）: " + path
                    + "——请 reimport 修复");
        }
        return spv.getSpvData();
    }
}
