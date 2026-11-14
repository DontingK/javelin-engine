package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 后处理设置（Engine_PostProcess_Design §3.7/§9.8）：**三个效果开关**（Tonemap/Bloom/SSAO）
 * + Bloom 阈值/软膝/强度 + Tonemap 肩部 + SSAO 参数。
 *
 * <p><b>为何独立成对象</b>：{@link InputCapsule} 无「键是否存在」判定、浮点缺键恒 0，
 * 无法在 {@link JavelinSetting} 里直接「缺键保默认」——故用嵌套 Serializable 对象，经
 * {@code readSerializable} 的 null 判定保默认（照 backgroundColor 的 Color 先例）。
 * 参数经 {@code PostProcessSetup} 下发到 BloomPass/TonemapPass（push 常量），运行期可调。</p>
 *
 * <p><b>开关收拢（2026-11-14）</b>：Tonemap/Bloom/SSAO 三开关原散在 {@link JavelinSetting}
 * 顶层，现收进本类（与各自的参数同域）；旧设置文件的顶层键由 {@code JavelinSetting.read}
 * 一次性迁移。</p>
 */
@Reflective
public class PostProcessSettings implements Serializable {

    /** Tonemap（后处理主开关）默认关：关=链空直通；开=链尾挂 Tonemap、HDR 生效 */
    private boolean tonemapEnabled = false;
    /** Bloom 开关（独立于 Tonemap）默认关 */
    private boolean bloomEnabled = false;
    /** SSAO 开关（独立；仅 HYBRID 档生效）默认关 */
    private boolean ssaoEnabled = false;
    /** Bloom 亮度阈值（≥ 该值的像素才起辉；与 Tonemap 肩部同口径起步值） */
    private float bloomThreshold = 0.8f;
    /** Bloom 阈值软过渡半宽（阈值 ± knee 间平滑登场） */
    private float bloomKnee = 0.4f;
    /** Bloom 合成强度（场景色 + bloom × intensity） */
    private float bloomIntensity = 0.5f;
    /** Tonemap 高光肩部起点（≤ 该值逐像素不变、> 该值指数压向 1.0） */
    private float tonemapKnee = 0.8f;
    /** SSAO 参数（半径/偏移/强度；嵌套对象同为由「缺键保默认」——见 SsaoSettings 类注释） */
    private SsaoSettings ssao = new SsaoSettings();

    /** Tonemap 开关（原 JavelinSetting.postProcessing） */
    public boolean isTonemapEnabled() {
        return tonemapEnabled;
    }

    public void setTonemapEnabled(boolean tonemapEnabled) {
        this.tonemapEnabled = tonemapEnabled;
    }

    /** Bloom 开关（原 JavelinSetting.bloom） */
    public boolean isBloomEnabled() {
        return bloomEnabled;
    }

    public void setBloomEnabled(boolean bloomEnabled) {
        this.bloomEnabled = bloomEnabled;
    }

    /** SSAO 开关（原 JavelinSetting.ssao） */
    public boolean isSsaoEnabled() {
        return ssaoEnabled;
    }

    public void setSsaoEnabled(boolean ssaoEnabled) {
        this.ssaoEnabled = ssaoEnabled;
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

    public SsaoSettings getSsao() {
        return ssao;
    }

    public void setSsao(SsaoSettings ssao) {
        if (ssao == null) {
            throw new IllegalArgumentException("ssao must not be null");
        }
        this.ssao = ssao;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("tonemapEnabled", tonemapEnabled);
        output.write("bloomEnabled", bloomEnabled);
        output.write("ssaoEnabled", ssaoEnabled);
        output.write("bloomThreshold", bloomThreshold);
        output.write("bloomKnee", bloomKnee);
        output.write("bloomIntensity", bloomIntensity);
        output.write("tonemapKnee", tonemapKnee);
        output.write("ssao", ssao);
    }

    @Override
    public void read(InputCapsule input) {
        // 本对象首次落地即含全部字段；后续新增字段时旧文件读 0（需另加「键存在」判定，见类注释）
        // 三开关为 2026-11-14 从 JavelinSetting 顶层迁入：旧文件本键缺 → false = 默认语义（关）；
        // 旧文件的顶层键由 JavelinSetting.read 一次性迁移进来
        tonemapEnabled = input.readBoolean("tonemapEnabled");
        bloomEnabled = input.readBoolean("bloomEnabled");
        ssaoEnabled = input.readBoolean("ssaoEnabled");
        bloomThreshold = input.readFloat("bloomThreshold");
        bloomKnee = input.readFloat("bloomKnee");
        bloomIntensity = input.readFloat("bloomIntensity");
        tonemapKnee = input.readFloat("tonemapKnee");
        // SSAO 参数是后补字段：旧文件无此键 → readSerializable 返 null → 保默认（嵌套对象先例）
        SsaoSettings loaded = input.readSerializable("ssao", SsaoSettings.class);
        if (loaded != null) {
            ssao = loaded;
        }
    }
}
