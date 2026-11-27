package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 渲染管线形态（Engine_RenderPipeline_Design.md 6.3）：启动期选哪个管线实现类
 * ——纯前向（{@link ForwardRenderPipeline}）还是混合（{@link DefaultHybridRenderPipeline}，
 * Deferred 不透明 + Forward 透明）；两实现共用同一套光照/cluster 数据
 * （Engine_Lighting_Design §9 批次④-B）。运行期不可切（2026-11-13 拆分）。
 *
 * <p>显示名走 {@link InsEnumLabel}（i18n 键在 editor.core 语言包）——设置面板只读行复用。</p>
 */
public enum PipelineProfile implements InsEnumLabel {
    /**
     * 纯前向：不透明与透明都走 Forward（无 G-Buffer）
     *
     * <p><b>2026-11-27 起目标线废弃</b>：实现类 {@link ForwardRenderPipeline} 已标
     * {@code @Deprecated}——混合管线是唯一目标线（G-Buffer 常驻，光追三件套/SSAO/TAA 都挂在它上面）。
     * 常量本身暂不标 {@code @Deprecated}：{@code GraphSetting} 读旧项目的序列化值、编辑器判档
     * 与测试遍历都要用它；真正移除时随实现类一起清。</p>
     */
    FORWARD("projectSettings.pipeline.forward"),
    /** 混合：不透明走 G-Buffer 三张 MRT + 全屏 LightingPass，透明仍走 Forward */
    HYBRID("projectSettings.pipeline.hybrid");

    private final String label;

    PipelineProfile(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
