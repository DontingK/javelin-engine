package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 后处理参数（Engine_PostProcess_Design §3.7/§9.8）：Bloom 阈值/肩部/强度 + Tonemap 肩部。
 *
 * <p><b>为何独立成对象</b>：{@link InputCapsule} 无「键是否存在」判定、浮点缺键恒 0，
 * 无法在 {@link JavelinSetting} 里直接「缺键保默认」——故用嵌套 Serializable 对象，经
 * {@code readSerializable} 的 null 判定保默认（照 backgroundColor 的 Color 先例）。
 * 参数经 {@code PostProcessSetup} 下发到 BloomPass/TonemapPass（push 常量），运行期可调。</p>
 */
@Reflective
public class PostProcessSettings implements Serializable {

    /** Bloom 亮度阈值（≥ 该值的像素才起辉；与 Tonemap 肩部同口径起步值） */
    private float bloomThreshold = 0.8f;
    /** Bloom 阈值软过渡半宽（阈值 ± knee 间平滑登场） */
    private float bloomKnee = 0.4f;
    /** Bloom 合成强度（场景色 + bloom × intensity） */
    private float bloomIntensity = 0.5f;
    /** Tonemap 高光肩部起点（≤ 该值逐像素不变、> 该值指数压向 1.0） */
    private float tonemapKnee = 0.8f;

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
        output.write("bloomThreshold", bloomThreshold);
        output.write("bloomKnee", bloomKnee);
        output.write("bloomIntensity", bloomIntensity);
        output.write("tonemapKnee", tonemapKnee);
    }

    @Override
    public void read(InputCapsule input) {
        // 本对象首次落地即含全部字段；后续新增字段时旧文件读 0（需另加「键存在」判定，见类注释）
        bloomThreshold = input.readFloat("bloomThreshold");
        bloomKnee = input.readFloat("bloomKnee");
        bloomIntensity = input.readFloat("bloomIntensity");
        tonemapKnee = input.readFloat("tonemapKnee");
    }
}
