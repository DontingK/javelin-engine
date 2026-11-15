package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 图形设置（项目设置「图形」页根）：管线档位 / 背景清屏色 / 后处理 / 环境光遮蔽 / 阴影。
 *
 * <p>分组即字段：管线档位与背景清屏色两个标量各自开组，后处理 / 环境光遮蔽 / 阴影三个
 * 参数对象各成一组（面板按 {@link SettingGroup} 标题展开，声明序即面板序）。</p>
 */
@Reflective
@Setting(label = "projectSettings.category.graphics")
public class GraphSetting implements Serializable {

    /** 渲染管线启动期选择（FORWARD / HYBRID）：运行期不可切，改后重启生效 */
    @SettingGroup(label = "projectSettings.section.pipeline")
    @InsField(label = "projectSettings.pipeline")
    @SettingRestart
    @SettingHint("projectSettings.pipeline.restartHint")
    private PipelineProfile renderPipeline = PipelineProfile.HYBRID;

    /** 默认背景颜色（引擎三处清屏的统一来源：无渲染系统清屏 / 无相机兜底 / Opaque Pass 清屏） */
    @SettingGroup(label = "projectSettings.section.background")
    @InsField(label = "projectSettings.background")
    private Color backgroundColor = new Color(0.05f, 0.05f, 0.08f, 1f);

    /** 后处理（Tonemap / Bloom 开关与参数，经 PostProcessSetup 下发为 push 常量） */
    @SettingGroup(label = "projectSettings.section.postProcessing")
    private PostProcessSettings postProcessSettings = new PostProcessSettings();

    /** 环境光遮蔽（仅 HYBRID 档生效） */
    @SettingGroup(label = "projectSettings.section.ssao")
    private SsaoSettings ssaoSettings = new SsaoSettings();

    /** 阴影（品质档 / 强度 / PCF / bias / CSM） */
    @SettingGroup(label = "projectSettings.section.shadow")
    private ShadowSettings shadowSettings = new ShadowSettings();

    public PipelineProfile getRenderPipeline() {
        return renderPipeline;
    }

    public void setRenderPipeline(PipelineProfile renderPipeline) {
        if (renderPipeline == null) {
            throw new IllegalArgumentException("renderPipeline must not be null");
        }
        this.renderPipeline = renderPipeline;
    }

    public Color getBackgroundColor() {
        return backgroundColor;
    }

    public void setBackgroundColor(Color backgroundColor) {
        if (backgroundColor == null) {
            throw new IllegalArgumentException("backgroundColor must not be null");
        }
        this.backgroundColor = backgroundColor;
    }

    public PostProcessSettings getPostProcessSettings() {
        return postProcessSettings;
    }

    public void setPostProcessSettings(PostProcessSettings postProcessSettings) {
        if (postProcessSettings == null) {
            throw new IllegalArgumentException("postProcessSettings must not be null");
        }
        this.postProcessSettings = postProcessSettings;
    }

    public SsaoSettings getSsaoSettings() {
        return ssaoSettings;
    }

    public void setSsaoSettings(SsaoSettings ssaoSettings) {
        if (ssaoSettings == null) {
            throw new IllegalArgumentException("ssaoSettings must not be null");
        }
        this.ssaoSettings = ssaoSettings;
    }

    public ShadowSettings getShadowSettings() {
        return shadowSettings;
    }

    public void setShadowSettings(ShadowSettings shadowSettings) {
        if (shadowSettings == null) {
            throw new IllegalArgumentException("shadowSettings must not be null");
        }
        this.shadowSettings = shadowSettings;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("renderPipeline", renderPipeline);
        output.write("backgroundColor", backgroundColor);
        output.write("postProcessSettings", postProcessSettings);
        output.write("ssaoSettings", ssaoSettings);
        output.write("shadowSettings", shadowSettings);
    }

    @Override
    public void read(InputCapsule input) {
        // 标量默认值 = 字段声明初值；嵌套对象缺键走 readSerializable 的 null 判定保默认
        renderPipeline = input.readEnum("renderPipeline", PipelineProfile.class,
                PipelineProfile.HYBRID);
        Color bg = input.readSerializable("backgroundColor", Color.class);
        if (bg != null) {
            backgroundColor = bg;
        }
        PostProcessSettings pps = input.readSerializable("postProcessSettings", PostProcessSettings.class);
        if (pps != null) {
            postProcessSettings = pps;
        }
        ShadowSettings shadow = input.readSerializable("shadowSettings", ShadowSettings.class);
        if (shadow != null) {
            shadowSettings = shadow;
        }
        SsaoSettings ssao = input.readSerializable("ssaoSettings", SsaoSettings.class);
        if (ssao != null) {
            ssaoSettings = ssao;
        }
    }
}
