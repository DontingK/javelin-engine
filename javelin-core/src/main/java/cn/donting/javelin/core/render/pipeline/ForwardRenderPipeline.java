package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.pipeline.pass.OpaqueForwardPass;
import cn.donting.javelin.core.render.pipeline.pass.ShadowPass;
import cn.donting.javelin.core.render.pipeline.pass.SkyboxPass;
import cn.donting.javelin.core.render.pipeline.pass.TransparentForwardPass;
import cn.donting.javelin.rhi.Device;

/**
 * 纯前向渲染管线（Engine_RenderPipeline_Design §3.3）：不透明直写颜色终点 → 天空盒 →
 * 透明 LOAD 叠加 → 后处理链。
 *
 * <p>与 {@link DefaultHybridRenderPipeline} 并列、**无档位切换**（2026-11-13 拆分）：编辑器
 * 固定用混合管线，本类供打包运行时按 {@code JavelinSetting.renderPipeline} 启动期选用，
 * 亦为 demo/测试留一份独立的前向管线。编排与后处理机制共用 {@link AbstractRenderPipeline}。</p>
 */
public class ForwardRenderPipeline extends AbstractRenderPipeline {

    public ForwardRenderPipeline(Device device, AssetManager assetManager) {
        super(device, assetManager);
        OpaqueForwardPass opaque = new OpaqueForwardPass();
        addScenePass(new ShadowPass(device, assetManager));
        addScenePass(opaque);
        addScenePass(new SkyboxPass(device, assetManager));
        addScenePass(new TransparentForwardPass());
        addClearColorSink(opaque::setClearColor);
    }

    @Override
    public PipelineProfile profile() {
        return PipelineProfile.FORWARD;
    }
}
