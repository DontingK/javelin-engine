package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.pipeline.pass.*;
import cn.donting.javelin.rhi.Device;

/**
 * 纯前向渲染管线（Engine_RenderPipeline_Design §3.3）：不透明直写颜色终点 → 天空盒 →
 * 透明 LOAD 叠加 → 后处理链。
 *
 * <p>与 {@link DefaultHybridRenderPipeline} 并列、**无档位切换**（2026-11-13 拆分）：编辑器
 * 固定用混合管线，本类供打包运行时按 {@code JavelinSetting.renderPipeline} 启动期选用，
 * 亦为 demo/测试留一份独立的前向管线。编排与后处理机制共用 {@link AbstractRenderPipeline}。</p>
 *
 * @deprecated 2026-11-27 起标废弃：混合管线是唯一目标线（G-Buffer 常驻，光追 GI/光追阴影/
 *     光追反射、SSAO、TAA 都建立在它之上）。纯前向档没有 G-Buffer，光追系列 Pass 一律挂不上，
 *     与「混合唯一」的迁移动向直接冲突，不再投入维护。
 *
 *     <p><b>为什么只标废弃、暂不删除</b>：{@code GraphSetting.renderPipeline} 仍要能读旧项目的
 *     序列化值，{@link PipelineProfile#FORWARD}、{@code JavelinEngine} 的 forward 分支、编辑器
 *     两处 HYBRID 判定与 {@code JavelinSettingTest} 的枚举遍历都还引用它。真正移除时一次清：
 *     枚举值 + {@code DefaultHybridRenderPipeline} 之外的实现类 + 设置项字段与 i18n +
 *     {@code JavelinEngine} 的两处 {@code boolean forward} 分支 + 编辑器判定 + 测试遍历。</p>
 *
 *     <p>新代码请直接用 {@link DefaultHybridRenderPipeline}。</p>
 */
@Deprecated
public class ForwardRenderPipeline extends AbstractRenderPipeline {

    public ForwardRenderPipeline(Device device, AssetManager assetManager) {
        super(device, assetManager);
        OpaqueForwardPass opaque = new OpaqueForwardPass();
        addScenePass(new ShadowPass(device, assetManager));
        addScenePass(opaque);
        addScenePass(new SkyboxPass(device, assetManager));
        // 非光照不透明：与 HYBRID 同相对位置（天空盒之后、透明之前）
        addScenePass(new UnlitForwardPass());
        addScenePass(new TransparentForwardPass());
        addClearColorSink(opaque::setClearColor);
    }

    @Override
    public PipelineProfile profile() {
        return PipelineProfile.FORWARD;
    }
}
