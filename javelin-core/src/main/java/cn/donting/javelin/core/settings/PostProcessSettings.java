package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsFloatRange;
import cn.donting.javelin.core.inspector.InsSlider;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 后处理设置（Engine_PostProcess_Design §3.7/§9.8）：**Tonemap / Bloom 两个开关** +
 * Bloom 阈值/软膝/强度 + Tonemap 肩部。
 *
 * <p><b>为何独立成对象</b>：后处理参数域内聚——面板按它展开成「后处理」分组，参数经
 * {@code PostProcessSetup} 下发到 BloomPass/TonemapPass（push 常量），运行期可调。字段级
 * 缺键回退由 {@link InputCapsule} 的默认值重载负责（默认值 = 字段声明初值）。</p>
 *
 * <p><b>SSAO 不在此域</b>：开关与参数归 {@link SsaoSettings}（独立成组，挂在
 * {@link GraphSetting#getSsaoSettings()}）。</p>
 */
@Reflective
public class PostProcessSettings implements Serializable {

    /** Tonemap（后处理主开关）默认关：关=链空直通；开=链尾挂 Tonemap、HDR 生效 */
    @InsField(label = "projectSettings.postProcessing")
    private boolean tonemapEnabled = false;
    /** Bloom 开关（独立于 Tonemap）默认关 */
    @InsField(label = "projectSettings.bloom")
    private boolean bloomEnabled = false;
    /** Bloom 亮度阈值（≥ 该值的像素才起辉；与 Tonemap 肩部同口径起步值） */
    @InsField(label = "projectSettings.bloomThreshold")
    @InsFloatRange(min = 0f, max = 10f, step = 0.05f)
    @InsSlider
    private float bloomThreshold = 0.8f;
    /** Bloom 阈值软过渡半宽（阈值 ± knee 间平滑登场） */
    @InsField(label = "projectSettings.bloomKnee")
    @InsFloatRange(min = 0f, max = 2f, step = 0.05f)
    @InsSlider
    private float bloomKnee = 0.4f;
    /** Bloom 合成强度（场景色 + bloom × intensity） */
    @InsField(label = "projectSettings.bloomIntensity")
    @InsFloatRange(min = 0f, max = 5f, step = 0.05f)
    @InsSlider
    private float bloomIntensity = 0.5f;
    /** Tonemap 高光肩部起点（≤ 该值逐像素不变、> 该值指数压向 1.0） */
    @InsField(label = "projectSettings.tonemapKnee")
    @InsFloatRange(min = 0f, max = 1f, step = 0.05f)
    @InsSlider
    private float tonemapKnee = 0.8f;

    public boolean isTonemapEnabled() {
        return tonemapEnabled;
    }

    public void setTonemapEnabled(boolean tonemapEnabled) {
        this.tonemapEnabled = tonemapEnabled;
    }

    public boolean isBloomEnabled() {
        return bloomEnabled;
    }

    public void setBloomEnabled(boolean bloomEnabled) {
        this.bloomEnabled = bloomEnabled;
    }

    public float getBloomThreshold() {
        return bloomThreshold;
    }

    public void setBloomThreshold(float bloomThreshold) {
        this.bloomThreshold = bloomThreshold;
    }

    public float getBloomKnee() {
        return bloomKnee;
    }

    public void setBloomKnee(float bloomKnee) {
        this.bloomKnee = bloomKnee;
    }

    public float getBloomIntensity() {
        return bloomIntensity;
    }

    public void setBloomIntensity(float bloomIntensity) {
        this.bloomIntensity = bloomIntensity;
    }

    public float getTonemapKnee() {
        return tonemapKnee;
    }

    public void setTonemapKnee(float tonemapKnee) {
        this.tonemapKnee = tonemapKnee;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("tonemapEnabled", tonemapEnabled);
        output.write("bloomEnabled", bloomEnabled);
        output.write("bloomThreshold", bloomThreshold);
        output.write("bloomKnee", bloomKnee);
        output.write("bloomIntensity", bloomIntensity);
        output.write("tonemapKnee", tonemapKnee);
    }

    @Override
    public void read(InputCapsule input) {
        // 默认值 = 字段声明初值：缺键即字段默认
        tonemapEnabled = input.readBoolean("tonemapEnabled", false);
        bloomEnabled = input.readBoolean("bloomEnabled", false);
        bloomThreshold = input.readFloat("bloomThreshold", 0.8f);
        bloomKnee = input.readFloat("bloomKnee", 0.4f);
        bloomIntensity = input.readFloat("bloomIntensity", 0.5f);
        tonemapKnee = input.readFloat("tonemapKnee", 0.8f);
    }
}
