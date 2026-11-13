package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.render.pipeline.pass.RenderPass;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;

/**
 * 后处理链节点（Engine_PostProcess_Design §3.4）：读「链输入」写「链输出」，构建期声明 + 回调录制。
 *
 * <p>链由 {@link AbstractRenderPipeline#buildGraph} 在场景 Pass 之后展开：链首输入 =
 * 本视口 lightingRT，链尾输出 = 显示终点（colorTarget），中间节点输出 = 每视口中间 RT。
 * 节点不进管线 Pass 列表——{@link RenderPass#addToGraph} 由链展开驱动。</p>
 */
public interface PostProcessNode extends RenderPass {

    /**
     * 链内声明：input = 上一节点输出（链首 = lightingRT），output = 本节点输出
     * （链尾 = colorTarget，中间 = 中间 RT）。
     */
    void addToGraph(RenderGraphBuilder graph, PipelineContext ctx, Texture input, Texture output);

    /** 后处理节点必经链展开提供 input/output，直接调两参版本属误用（显性报错不自吞） */
    @Override
    default void addToGraph(RenderGraphBuilder graph, PipelineContext ctx) {
        throw new UnsupportedOperationException(
                "后处理节点须经链展开（input/output 由 AbstractRenderPipeline 提供）: " + name());
    }
}
