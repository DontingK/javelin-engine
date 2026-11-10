package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.pipeline.pass.GBufferPass;
import cn.donting.javelin.core.render.pipeline.pass.LightingPass;
import cn.donting.javelin.core.render.pipeline.pass.OpaqueForwardPass;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.pipeline.pass.ShadowPass;
import cn.donting.javelin.core.render.pipeline.pass.SkyboxPass;
import cn.donting.javelin.core.render.pipeline.pass.TransparentForwardPass;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.data.RHIClearColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认渲染管线（Engine_RenderPipeline_Design.md 全文；RHI 14 节引擎侧兑现）。
 *
 * <p><b>两档形态</b>（{@link PipelineProfile}，同一实例按档分派，运行期可切）：
 * <pre>
 * FORWARD：Shadow → OpaqueForward（直写终点，CLEAR） → Skybox → Transparent（LOAD 叠加）
 * HYBRID ：Shadow → GBuffer（三张 MRT + depth 写） → Lighting（全屏，读 G-Buffer） →
 *          Skybox → Transparent（LOAD 叠加）
 * </pre>
 * 两档共用同一套 set1 cluster/阴影数据与同一 {@code javelinLighting} 光照段函数
 * ——光照一致性由构造保证（{@link LightingPass}）。后处理恒空链（直通），
 * 单相机全屏时终点即最后一个 Pass，无 Composite；多相机由下一视口的区域清屏/LOAD 叠加衔接。</p>
 *
 * <p>编排 = 有序 {@link RenderPass} 列表（设计文档 6.2）：buildGraph 按当前档取列表逐 Pass
 * 调用 addToGraph；扩展 = 向 {@link #addPass} 增删/插入自定义 Pass（RHI 14.3 方式 1，
 * 列表组成变化 = 图缓存 structureHash 正常失效，RHI 18.5）。切档同属列表组成变化。</p>
 */
public class DefaultHybridRenderPipeline implements RenderPipeline {

    private static final Logger log = LoggerFactory.getLogger(DefaultHybridRenderPipeline.class);

    private final PostProcessChain postProcess = new PostProcessChain();
    private final List<RenderPass> forwardPasses = new ArrayList<>();
    private final List<RenderPass> hybridPasses = new ArrayList<>();
    /** 当前档位（运行期可切；volatile——buildGraph 在渲染线程读，切换多来自编辑器线程） */
    private volatile PipelineProfile activeProfile;
    /** FORWARD 档颜色清屏入口（直写终点） */
    private final OpaqueForwardPass opaque;
    /** HYBRID 档颜色清屏入口（全屏光照，Opaque 的清屏职责移交至此） */
    private final LightingPass lighting;

    public DefaultHybridRenderPipeline(Device device, AssetManager assetManager) {
        this(device, assetManager, PipelineProfile.HYBRID);
    }

    /**
     * @param profile 启动档位（null = FORWARD）；运行期可经 {@link #setProfile} 切换
     */
    public DefaultHybridRenderPipeline(Device device, AssetManager assetManager,
                                       PipelineProfile profile) {
        // 全局 Pass 两档共用实例（ShadowPass 帧号幂等挂载；Skybox/Transparent 资源复用）：
        // 同一帧只会跑其中一份列表
        ShadowPass shadow = new ShadowPass(device, assetManager);
        SkyboxPass skybox = new SkyboxPass(device, assetManager);
        TransparentForwardPass transparent = new TransparentForwardPass();

        // FORWARD 档（v0.1 形态）：直写终点
        opaque = new OpaqueForwardPass();
        forwardPasses.add(shadow);
        forwardPasses.add(opaque);
        forwardPasses.add(skybox);
        forwardPasses.add(transparent);

        // HYBRID 档（批次④-B）：G-Buffer + 全屏光照，不透明几何走 GBuffer/Forward 材质 Pass
        lighting = new LightingPass(device, assetManager);
        hybridPasses.add(shadow);
        hybridPasses.add(new GBufferPass());
        hybridPasses.add(lighting);
        hybridPasses.add(skybox);
        hybridPasses.add(transparent);

        this.activeProfile = profile == null ? PipelineProfile.FORWARD : profile;
    }

    @Override
    public PipelineProfile profile() {
        return activeProfile;
    }

    /**
     * 运行期切换档位（列表组成变化 → 图缓存下次构建正常失效，RHI 18.5）。
     * HYBRID 需引擎侧 AA 已关闭（MSAA-GBuffer 归四期，见 PipelineProfile javadoc）。
     */
    public void setProfile(PipelineProfile profile) {
        if (profile != null && profile != activeProfile) {
            log.info("渲染管线档位切换: {} → {}", activeProfile, profile);
            this.activeProfile = profile;
        }
    }

    /** 后处理链（恒空链 = 直通，节点接口落地后处理时定义，4.6） */
    public PostProcessChain postProcess() {
        return postProcess;
    }

    /** 清屏色（两档各自的颜色清屏入口同步：FORWARD=Opaque、HYBRID=Lighting） */
    public void setClearColor(RHIClearColor clearColor) {
        opaque.setClearColor(clearColor);
        lighting.setClearColor(clearColor);
    }

    @Override
    public void buildGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        for (RenderPass pass : passes()) {
            pass.addToGraph(graph, ctx);
        }
    }

    /** 当前档位的 Pass 列表（有序，只读消费用；扩展走 addPass/removePass） */
    public List<RenderPass> passes() {
        return activeProfile == PipelineProfile.HYBRID ? hybridPasses : forwardPasses;
    }

    /**
     * 尾部追加自定义 Pass（两档同步加入——编辑器 Gizmo/拾取/调试线等覆盖制 Pass
     * 与档位无关，切档后仍在）。同一实例被两份列表共享，同帧只会跑其中一份。
     */
    @Override
    public void addPass(RenderPass pass) {
        forwardPasses.add(pass);
        hybridPasses.add(pass);
    }

    /** 插于命名 Pass 之后（两档各自定位；某档无该名则跳过，末尾置顶同口径） */
    @Override
    public void addPass(RenderPass pass, String afterPass) {
        insertAfter(forwardPasses, pass, afterPass);
        insertAfter(hybridPasses, pass, afterPass);
    }

    private static void insertAfter(List<RenderPass> list, RenderPass pass, String afterPass) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name().equalsIgnoreCase(afterPass)) {
                list.add(i + 1, pass);
                return;
            }
        }
    }

    /** 摘除自定义 Pass（两档同步；未在两档中出现时静默无操作，既有语义） */
    @Override
    public void removePass(RenderPass pass) {
        forwardPasses.remove(pass);
        hybridPasses.remove(pass);
    }
}
