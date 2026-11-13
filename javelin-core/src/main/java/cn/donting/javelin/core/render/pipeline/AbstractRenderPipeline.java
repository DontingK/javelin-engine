package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.core.render.pipeline.pass.TonemapPass;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * 管线共用骨架（Engine_RenderPipeline_Design §6.2，2026-11-13 两管线拆分）：
 * 单条有序 Pass 列表 + 场景 Pass 身份集 + 后处理链（链非空 = HDR 生效）+ 清屏色。
 *
 * <p>子类只在构造器里 {@link #addScenePass} 装配自己的场景 Pass 序列（顺序即录制序）
 * 并给出 {@link #profile()} 身份常量；overlay 制 Pass（Gizmo/调试线/拾取/调试视图）由
 * 编辑器经 {@link #addPass} 增删。buildGraph 模板：场景 Pass 逐个录制 → 在最后一个场景
 * Pass 之后展开后处理链（链尾 Tonemap 写显示目标）→ overlay 尾随（故链不会被它们覆盖）。</p>
 */
public abstract class AbstractRenderPipeline implements RenderPipeline {

    protected final Device device;
    protected final AssetManager assetManager;

    /** 有序 Pass 列表（场景 Pass 前缀 + overlay 制 Pass 尾随） */
    private final List<RenderPass> passes = new ArrayList<>();
    /** 场景 Pass 身份集（构造期登记；后处理链展开点判定用） */
    private final Set<RenderPass> scenePasses = Collections.newSetFromMap(new IdentityHashMap<>());
    /** 清屏色接收者（各实现把颜色清屏 Pass 登记进来；Tonemap 由本类直下） */
    private final List<Consumer<RHIClearColor>> clearColorSinks = new ArrayList<>();
    private final PostProcessChain postProcess = new PostProcessChain();
    /** 后处理启用时惰性创建（装载内置 Tonemap 着色器产物） */
    private TonemapPass tonemap;
    private RHIClearColor clearColor = new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);

    protected AbstractRenderPipeline(Device device, AssetManager assetManager) {
        this.device = device;
        this.assetManager = assetManager;
    }

    /** 场景 Pass 装配（列表尾追加 + 登记身份集；构造期调用，须早于任何 overlay 制 Pass） */
    protected final void addScenePass(RenderPass pass) {
        passes.add(pass);
        scenePasses.add(pass);
    }

    /** 登记清屏色接收者（各实现自己的颜色清屏 Pass：FORWARD=Opaque、HYBRID=Lighting） */
    protected final void addClearColorSink(Consumer<RHIClearColor> sink) {
        clearColorSinks.add(sink);
        sink.accept(clearColor);
    }

    // ═══ 后处理（Engine_PostProcess_Design；链非空 = HDR 生效） ═══

    /** 后处理链（§3.4；空链 = 直通，非空 = HDR 生效） */
    public final PostProcessChain postProcess() {
        return postProcess;
    }

    /**
     * 后处理开关（启动期按设置填充 + 工具菜单运行期切换）：开 = 链尾挂 Tonemap（HDR 生效
     * ——场景 Pass 写 lightingRT，Tonemap 写显示终点）；关 = 清空链（直通，逐像素不变）。
     */
    @Override
    public final void setPostProcessingEnabled(boolean enabled) {
        if (enabled) {
            if (tonemap == null) {
                tonemap = new TonemapPass(device, assetManager);
                tonemap.setClearColor(clearColor);
            }
            if (!postProcess.nodes().contains(tonemap)) {
                postProcess.addLast(tonemap);
            }
        } else {
            postProcess.clear();
        }
    }

    @Override
    public final boolean postProcessingEnabled() {
        return !postProcess.isEmpty();
    }

    /** HDR 生效 = 后处理链非空（帧构建据此换场景颜色终点，见 PipelineContext） */
    @Override
    public final boolean usesHdrSceneTarget() {
        return !postProcess.isEmpty();
    }

    // ═══ 清屏色 ═══

    /** 清屏色（下发各实现登记的清屏入口 + 链尾 Tonemap） */
    @Override
    public final void setClearColor(RHIClearColor clearColor) {
        this.clearColor = clearColor;
        clearColorSinks.forEach(sink -> sink.accept(clearColor));
        if (tonemap != null) {
            tonemap.setClearColor(clearColor);
        }
    }

    // ═══ 编排 ═══

    @Override
    public final void buildGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        // 链展开点 = 最后一个场景 Pass 的下标（场景 Pass 是列表前缀，overlay 制 Pass 尾随）
        int sceneEnd = -1;
        for (int i = 0; i < passes.size(); i++) {
            if (scenePasses.contains(passes.get(i))) {
                sceneEnd = i;
            }
        }
        for (int i = 0; i < passes.size(); i++) {
            passes.get(i).addToGraph(graph, ctx);
            if (i == sceneEnd) {
                expandPostProcess(graph, ctx);
            }
        }
        if (sceneEnd < 0) {
            // 场景 Pass 全被移除（异常态，非正常路径）：链仍展开，避免静默丢失后处理
            expandPostProcess(graph, ctx);
        }
    }

    /**
     * 后处理链展开（Engine_PostProcess_Design §3.4）：场景 Pass 之后、overlay 制 Pass 之前
     * 逐节点录制，链空则零展开（HDR 未生效）。链首输入 = 本视口 lightingRT，链尾输出 =
     * 显示终点，中间节点输出 = 每视口中间 RT。不进 Pass 列表——链与管线实现无关，共用一套。
     */
    private void expandPostProcess(RenderGraphBuilder graph, PipelineContext ctx) {
        List<PostProcessNode> nodes = orderedChain();
        if (nodes.isEmpty()) {
            return;
        }
        RenderingSystem rs = ctx.renderingSystem();
        Texture input = ctx.sceneColorTarget();
        for (int i = 0; i < nodes.size(); i++) {
            PostProcessNode node = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            Texture output = last ? ctx.colorTarget() : rs.postRtFor(ctx.view().name(), i);
            node.addToGraph(graph, ctx, input, output);
            input = output;
        }
    }

    /** 链序：Tonemap 恒置链尾（Bloom 须读未 tonemap 的 HDR 缓冲，§3.5——不依赖注册序） */
    private List<PostProcessNode> orderedChain() {
        List<PostProcessNode> nodes = postProcess.nodes();
        List<PostProcessNode> ordered = new ArrayList<>(nodes.size());
        for (PostProcessNode node : nodes) {
            if (!(node instanceof TonemapPass)) {
                ordered.add(node);
            }
        }
        for (PostProcessNode node : nodes) {
            if (node instanceof TonemapPass) {
                ordered.add(node);
            }
        }
        return ordered;
    }

    /** 当前 Pass 列表（有序；扩展走 addPass/removePass） */
    public final List<RenderPass> passes() {
        return passes;
    }

    /** 尾部追加 overlay 制 Pass（编辑器 Gizmo/拾取/调试线/调试视图） */
    @Override
    public final void addPass(RenderPass pass) {
        passes.add(pass);
    }

    /** 插于命名 Pass 之后（未找到该名则无操作，末尾置顶同口径） */
    @Override
    public final void addPass(RenderPass pass, String afterPass) {
        for (int i = 0; i < passes.size(); i++) {
            if (passes.get(i).name().equalsIgnoreCase(afterPass)) {
                passes.add(i + 1, pass);
                return;
            }
        }
    }

    /** 摘除 Pass（未在列表中时静默无操作，既有语义） */
    @Override
    public final void removePass(RenderPass pass) {
        passes.remove(pass);
    }
}
