package cn.donting.javelin.core.render.pipeline;

import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.render.pipeline.pass.*;
import cn.donting.javelin.rhi.Device;

/**
 * 混合（Deferred 复用）渲染管线（Engine_RenderPipeline_Design §4.2/§4.3）：不透明几何写
 * G-Buffer 三张 MRT → 全屏光照 → 天空盒 → 透明 LOAD 叠加 → 后处理链。
 *
 * <p>与 {@link ForwardRenderPipeline} 并列、**无档位切换**（2026-11-13 拍板：管线不再承载
 * 运行期切档）——启动期由 {@code JavelinSetting.renderPipeline} 选实现类。编排与后处理机制
 * 共用 {@link AbstractRenderPipeline}。</p>
 */
public class DefaultHybridRenderPipeline extends AbstractRenderPipeline {

    public DefaultHybridRenderPipeline(Device device, AssetManager assetManager) {
        super(device, assetManager);
        // 全局阴影（帧号幂等挂载）→ G-Buffer → 全屏光照（本实现颜色清屏入口）→ 天空盒
        // → 非光照不透明（覆盖制，晚于 Lighting 否则被整屏盖掉）→ 透明叠加
        addScenePass(new ShadowPass(device, assetManager));
        addScenePass(new GBufferPass());
        LightingPass lighting = new LightingPass(device, assetManager);
        addScenePass(lighting);
        addScenePass(new SkyboxPass(device, assetManager));
        addScenePass(new UnlitForwardPass());
        addScenePass(new TransparentForwardPass());
        addClearColorSink(lighting::setClearColor);
    }

    @Override
    public PipelineProfile profile() {
        return PipelineProfile.HYBRID;
    }
}
