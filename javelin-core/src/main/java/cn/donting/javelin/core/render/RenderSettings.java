package cn.donting.javelin.core.render;

import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.render.pipeline.PostProcessSetup;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.PostProcessSettings;
import cn.donting.javelin.core.settings.ShadowQuality;
import cn.donting.javelin.core.settings.ShadowSettings;
import cn.donting.javelin.core.settings.SsaoSettings;
import cn.donting.javelin.rhi.data.RHIClearColor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 渲染设置门面 —— 游戏/编辑器代码读写渲染设置的**唯一入口**（背景色 / 后处理链 / 阴影）。
 *
 * <p>形态对齐项目 {@code Input} / {@code Time} 静态门面：引擎初始化时 {@link #bind 绑定}
 * 子系统引用、shutdown 时 {@link #unbind 解绑}。统一的意义 = 把「改哪个对象、调哪个方法」
 * 的差异（阴影改 live 实例即生效、后处理必须经组合根下发、背景色要下发两处）全部内聚到
 * 本类，外部只调门面，无需知道底层路由。</p>
 *
 * <pre>
 * RenderSettings.setBloomEnabled(true);
 * RenderSettings.setBloom(1.1f, 0.4f, 0.6f);      // 阈值/软膝/强度
 * RenderSettings.setSsao(0.7f, 0.02f, 1.2f);      // 半径/深度偏移/强度
 * RenderSettings.setShadowCascadeCount(4);
 * RenderSettings.setShadowStrength(0.6f);
 * RenderSettings.setBackgroundColor(new Color(0.1f, 0.1f, 0.12f, 1f));
 * </pre>
 *
 * <p><b>真值源</b> = {@link JavelinSetting}（持久化单点）：门面写入即同步 setting 字段
 * （供写盘 / 查询），再下发对应子系统。写盘仍走项目设置保存链——门面不落盘。</p>
 *
 * <p><b>阴影兜底</b>：{@link #shadow()} 返回 live {@link ShadowSettings}（引擎持有的同一
 * 实例，改即生效），供批量 / 高级改（链式）；扁平 setter 是常用路径。</p>
 *
 * <p><b>范围</b>：渲染域（背景 / 后处理 / 阴影）；窗口域（vsync / maxFps / 分辨率）不在本门面。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public final class RenderSettings {

    private static final Logger log = LoggerFactory.getLogger(RenderSettings.class);

    private static JavelinSetting setting;
    private static PostProcessSetup postProcessSetup;
    private static RenderPipeline pipeline;

    private RenderSettings() {
    }

    /**
     * 绑定子系统引用（引擎初始化后调用；此后门面可用）。阴影走 {@link JavelinSetting}
     * 的 live {@link ShadowSettings} 实例（引擎启动期已把同一实例交给渲染系统），故无需另持
     * 渲染系统引用。
     *
     * @param setting          设置真值源（持久化单点）
     * @param postProcessSetup 后处理组合根（节点参数 / 开关下发）
     * @param pipeline         渲染管线（链 / 清屏 / 档位）
     */
    public static void bind(JavelinSetting setting, PostProcessSetup postProcessSetup,
                            RenderPipeline pipeline) {
        RenderSettings.setting = setting;
        RenderSettings.postProcessSetup = postProcessSetup;
        RenderSettings.pipeline = pipeline;
    }

    /** 解绑（引擎 shutdown 调用；解绑后调用门面报错） */
    public static void unbind() {
        setting = null;
        postProcessSetup = null;
        pipeline = null;
    }

    private static void requireBound() {
        if (setting == null) {
            throw new IllegalStateException(
                    "RenderSettings 未绑定——引擎尚未初始化（bind 由 JavelinEngine 调用）");
        }
    }

    // ═══ 背景色 ═══

    /** 当前背景清屏色（setting 实例） */
    public static Color background() {
        requireBound();
        return setting.getBackgroundColor();
    }

    /** 设背景清屏色：同步 setting + 下发管线清屏 sink 与后处理节点（原「两处都要」内聚于此） */
    public static void setBackgroundColor(Color color) {
        requireBound();
        if (color == null) {
            throw new IllegalArgumentException("color must not be null");
        }
        setting.setBackgroundColor(color);
        RHIClearColor clear = new RHIClearColor(color.r, color.g, color.b, color.a);
        if (pipeline != null) {
            pipeline.setClearColor(clear);
        }
        if (postProcessSetup != null) {
            postProcessSetup.setClearColor(clear);
        }
    }

    /** 设背景清屏色（分量重载） */
    public static void setBackgroundColor(float r, float g, float b, float a) {
        setBackgroundColor(new Color(r, g, b, a));
    }

    // ═══ 后处理开关（挂/摘链） ═══

    public static boolean isPostProcessingEnabled() {
        requireBound();
        return isNodeEnabled(PostProcessSetup.TONEMAP);
    }

    public static void setPostProcessingEnabled(boolean enabled) {
        requireBound();
        setNodeEnabled(PostProcessSetup.TONEMAP, enabled);
        setting.getPostProcessSettings().setTonemapEnabled(enabled);
    }

    public static boolean isBloomEnabled() {
        requireBound();
        return isNodeEnabled(PostProcessSetup.BLOOM);
    }

    public static void setBloomEnabled(boolean enabled) {
        requireBound();
        setNodeEnabled(PostProcessSetup.BLOOM, enabled);
        setting.getPostProcessSettings().setBloomEnabled(enabled);
    }

    public static boolean isSsaoEnabled() {
        requireBound();
        return isNodeEnabled(PostProcessSetup.SSAO);
    }

    /** 开 SSAO（仅 HYBRID 档；FORWARD 档忽略并 warn，与启动期口径一致） */
    public static void setSsaoEnabled(boolean enabled) {
        requireBound();
        if (enabled && pipeline != null && pipeline.profile() == PipelineProfile.FORWARD) {
            log.warn("SSAO 需要 G-Buffer，FORWARD 档不支持——忽略本次启用（HYBRID 档生效）");
            setting.getPostProcessSettings().setSsaoEnabled(true);
            return;
        }
        setNodeEnabled(PostProcessSetup.SSAO, enabled);
        setting.getPostProcessSettings().setSsaoEnabled(enabled);
    }

    private static boolean isNodeEnabled(String id) {
        return postProcessSetup != null && pipeline != null
                && postProcessSetup.isEnabled(pipeline.postProcess(), id);
    }

    private static void setNodeEnabled(String id, boolean enabled) {
        if (postProcessSetup != null && pipeline != null) {
            postProcessSetup.setEnabled(pipeline.postProcess(), id, enabled);
        }
    }

    // ═══ 后处理参数 ═══

    public static float getBloomThreshold() {
        requireBound();
        return setting.getPostProcessSettings().getBloomThreshold();
    }

    public static float getBloomKnee() {
        requireBound();
        return setting.getPostProcessSettings().getBloomKnee();
    }

    public static float getBloomIntensity() {
        requireBound();
        return setting.getPostProcessSettings().getBloomIntensity();
    }

    /** 设 Bloom 三参数（阈值/软膝/强度）：同步 setting + 下发组合根 */
    public static void setBloom(float threshold, float knee, float intensity) {
        requireBound();
        PostProcessSettings pps = setting.getPostProcessSettings();
        pps.setBloomThreshold(threshold);
        pps.setBloomKnee(knee);
        pps.setBloomIntensity(intensity);
        if (postProcessSetup != null) {
            postProcessSetup.setBloomParams(threshold, knee, intensity);
        }
    }

    public static void setBloomThreshold(float threshold) {
        setBloom(threshold, getBloomKnee(), getBloomIntensity());
    }

    public static void setBloomKnee(float knee) {
        setBloom(getBloomThreshold(), knee, getBloomIntensity());
    }

    public static void setBloomIntensity(float intensity) {
        setBloom(getBloomThreshold(), getBloomKnee(), intensity);
    }

    public static float getTonemapKnee() {
        requireBound();
        return setting.getPostProcessSettings().getTonemapKnee();
    }

    /** 设 Tonemap 高光肩部：同步 setting + 下发组合根 */
    public static void setTonemapKnee(float knee) {
        requireBound();
        setting.getPostProcessSettings().setTonemapKnee(knee);
        if (postProcessSetup != null) {
            postProcessSetup.setTonemapKnee(knee);
        }
    }

    public static SsaoSettings ssao() {
        requireBound();
        return setting.getPostProcessSettings().getSsao();
    }

    /** 设 SSAO 三参数（半径/深度偏移/强度）：同步 setting + 下发组合根 */
    public static void setSsao(float radius, float bias, float intensity) {
        requireBound();
        SsaoSettings s = setting.getPostProcessSettings().getSsao();
        s.setRadius(radius);
        s.setBias(bias);
        s.setIntensity(intensity);
        if (postProcessSetup != null) {
            postProcessSetup.setSsaoParams(radius, bias, intensity);
        }
    }

    public static void setSsaoRadius(float radius) {
        SsaoSettings s = ssao();
        setSsao(radius, s.getBias(), s.getIntensity());
    }

    public static void setSsaoBias(float bias) {
        SsaoSettings s = ssao();
        setSsao(s.getRadius(), bias, s.getIntensity());
    }

    public static void setSsaoIntensity(float intensity) {
        SsaoSettings s = ssao();
        setSsao(s.getRadius(), s.getBias(), intensity);
    }

    // ═══ 阴影（扁平 setter + live 实例兜底） ═══

    /** live ShadowSettings 实例（引擎持有的同一对象，改即生效）——批量/高级改的链式入口 */
    public static ShadowSettings shadow() {
        requireBound();
        return setting.getShadowSettings();
    }

    public static ShadowQuality getShadowQuality() {
        return shadow().getQuality();
    }

    public static void setShadowQuality(ShadowQuality quality) {
        shadow().setQuality(quality);
    }

    public static float getShadowStrength() {
        return shadow().getStrength();
    }

    public static void setShadowStrength(float strength) {
        shadow().setStrength(strength);
    }

    public static float getShadowPcfRadius() {
        return shadow().getPcfRadius();
    }

    public static void setShadowPcfRadius(float radius) {
        shadow().setPcfRadius(radius);
    }

    public static float getShadowRefDepthOffset() {
        return shadow().getRefDepthOffset();
    }

    public static void setShadowRefDepthOffset(float offset) {
        shadow().setRefDepthOffset(offset);
    }

    public static float getShadowBiasConstant() {
        return shadow().getBiasConstant();
    }

    public static void setShadowBiasConstant(float biasConstant) {
        shadow().setBiasConstant(biasConstant);
    }

    public static float getShadowBiasSlope() {
        return shadow().getBiasSlope();
    }

    public static void setShadowBiasSlope(float biasSlope) {
        shadow().setBiasSlope(biasSlope);
    }

    public static int getShadowCascadeCount() {
        return shadow().getCascadeCount();
    }

    /** 设级联数（仅 1/2/4 生效——其余忽略，同 ShadowSettings 口径） */
    public static void setShadowCascadeCount(int cascadeCount) {
        shadow().setCascadeCount(cascadeCount);
    }

    public static float getShadowDistance() {
        return shadow().getShadowDistance();
    }

    public static void setShadowDistance(float distance) {
        shadow().setShadowDistance(distance);
    }

    public static float getShadowCascadeLambda() {
        return shadow().getCascadeLambda();
    }

    public static void setShadowCascadeLambda(float lambda) {
        shadow().setCascadeLambda(lambda);
    }

    // ═══ 只读 ═══

    /** 当前渲染档位（启动期选定、运行期不可切） */
    public static PipelineProfile pipelineProfile() {
        requireBound();
        return pipeline != null ? pipeline.profile() : setting.getRenderPipeline();
    }
}
