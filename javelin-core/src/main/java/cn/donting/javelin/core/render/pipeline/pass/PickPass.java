package cn.donting.javelin.core.render.pipeline.pass;

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

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GPU ID-buffer 拾取 Pass（Engine_Picking_Design.md，2026-10-23 拍板落地）。
 *
 * <p><b>覆盖制</b>（拍板）：不消费材质 shader 的 Pass 声明，用引擎内嵌 pick.spv
 * （javelin-tool PickAssets 产物）绘制全部不透明物体 + "Gizmo" 桶——「看得见即
 * 拾得」，用户 shader 零声明负担。每个可拾取物体以帧内分配的 pick ID
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

    private static final String VERT_SPV_RESOURCE = "/pick/pick.vert.spv";
    private static final String FRAG_SPV_RESOURCE = "/pick/pick.frag.spv";
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

    public PickPass(PickSystem picking, Device device) {
        this.picking = picking;
        this.device = device;
        this.vertSpv = readSpv(VERT_SPV_RESOURCE);
        this.fragSpv = readSpv(FRAG_SPV_RESOURCE);
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

    /** 读 classpath .spv 二进制资源（DebugDrawText 同法） */
    private static byte[] readSpv(String resource) {
        try (InputStream stream = PickPass.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IOException("SPIR-V 资源不存在: " + resource + "（先跑 gradlew :javelin-tool:generatePickSpv 生成）");
            }
            try (InputStream in = new BufferedInputStream(stream)) {
                return in.readAllBytes();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Pick SPIR-V 加载失败: " + e.getMessage(), e);
        }
    }
}
