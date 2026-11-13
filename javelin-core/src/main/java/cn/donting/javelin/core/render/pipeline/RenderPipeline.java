package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.data.RHIClearColor;

/**
 * 引擎侧渲染管线（Engine_RenderPipeline_Design.md 6.1；模式 B 接管点，RHI 14.1）。
 *
 * <p>每视口调用一次 buildGraph；实现只准做 Pass 声明 + 回调录制，不碰屏障/布局
 * （RHI 22.4 迁移纪律）。实现类 = {@link DefaultHybridRenderPipeline}（混合）与
 * {@link ForwardRenderPipeline}（纯前向），启动期按设置选定、运行期不再切换。</p>
 */
public interface RenderPipeline {

    /**
     * 管线身份常量（实现类固定返回，非可变状态）：FORWARD = 纯前向、HYBRID = 混合。
     * 消费方据此判定能力差异（如 G-Buffer 可视化仅 HYBRID 可用）。
     */
    PipelineProfile profile();

    /** 为本视口向图追加 Pass 链 */
    void buildGraph(RenderGraphBuilder graph, PipelineContext ctx);

    /**
     * 场景颜色是否走 HDR 缓冲（Engine_PostProcess_Design §3.1）：帧构建据此决定是否
     * 创建 lightingRT 并交给 {@link PipelineContext#sceneColorTarget()}。默认 false
     * （直通，自定义管线零影响）。
     */
    default boolean usesHdrSceneTarget() {
        return false;
    }

    /** 清屏色（管线全局默认色；相机自带 clearColor 优先） */
    void setClearColor(RHIClearColor clearColor);

    /**
     * 后处理链（Engine_PostProcess_Design §3.4）：管线承载链与展开点，**不认识具体后处理类**。
     * 链的组成/开关由组合根（{@code PostProcessSetup}）经 {@link PostProcessChain#addLast} /
     * {@link PostProcessChain#remove} 维护；链非空即 HDR 生效。
     */
    PostProcessChain postProcess();

    void addPass(RenderPass pass);

    void addPass(RenderPass pass, String afterPass);

    /**
     * 移除 Pass（设计 6.2「增删/插入」之删；列表组成变化 = 图缓存 structureHash
     * 正常失效，同 addPass）。
     */
    void removePass(RenderPass pass);
}
