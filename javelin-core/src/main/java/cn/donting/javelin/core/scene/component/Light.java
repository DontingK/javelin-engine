package cn.donting.javelin.core.scene.component;

import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.inspector.InsShadowResolution;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.scene.Component;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;

/**
 * 灯光组件抽象基类（Engine_Lighting_Design §4，2026-10-31 拍板子类化——
 * Collider 族同构：每型参数集不相交，不摆无生效语义的死字段；**不支持切换
 * 类型**，换型 = 删组件重加）。
 *
 * <p>子类各自贴 {@code @InsHeader}（组件面板头，用户拍板显式贴法——基类注解
 * 不经 @Inherited 传递）。子类：{@link DirectionalLight}（零附加字段，方向=节点
 * 朝向）、{@link PointLight}（+range）、{@link SpotLight}（+range+锥角）。
 * 公共面：颜色（线性 HDR）/强度/投射阴影。主光无标志——引擎自动裁决最强
 * 激活方向光（对齐 Unity，{@code Scene.getSun}）。点/聚光按 range 包围球
 * 视锥剔除后进 lightList SSBO。SPOT 着色分支归批次③后半。</p>
 *
 * @author Javelin Engine Team
 * @since 1.0.0
 */
public abstract class Light extends Component {

    /** 阴影图分辨率下限（显存护栏） */
    public static final int MIN_RESOLUTION = 256;
    /** 阴影图分辨率上限（显存护栏，4096 起高成本档） */
    public static final int MAX_RESOLUTION = 8192;
    /** 缺省分辨率（旧档场景无此字段时的默认值） */
    public static final int DEFAULT_RESOLUTION = 2048;

    /** 颜色（线性 HDR；render 侧 Material 线性口径，无 sRGB 转换） */
    @InsField(label = "ins.light.color")
    private Color color = new Color(1.0f, 1.0f, 1.0f, 1.0f);
    @InsField(label = "ins.light.intensity")
    private float intensity = 1.0f;
    /** v0.x 仅主光消费（ShadowPass）；per-object 投射物开关后置 */
    @InsField(label = "ins.light.castShadow")
    private boolean castShadow;
    /**
     * 阴影图分辨率（预设档；v0.x 仅主光消费——点/聚光阴影归后置，值不生效；
     * 基类占位拍板 2026-11-01：后续灯型阴影落地直接消费零迁移。与「参数集
     * 不相交」口径的例外：这是全体灯的预留位，非方向光专属参数）
     */
    @InsField(label = "ins.light.shadowResolution")
    @InsShadowResolution
    private int shadowResolution = 2048;

    /** 灯型（子类身份；引擎侧分流与主光裁决用） */
    public abstract LightType getType();

    public Color getColor() {
        return color;
    }

    public void setColor(Color color) {
        this.color.r = color.r;
        this.color.g = color.g;
        this.color.b = color.b;
        this.color.a = color.a;
    }

    public float getIntensity() {
        return intensity;
    }

    public void setIntensity(float intensity) {
        this.intensity = intensity;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    public void setCastShadow(boolean castShadow) {
        this.castShadow = castShadow;
    }

    /** 阴影图分辨率（像素，预设档 256~4096；写入钳 [256,8192]，非预设值显示最近档） */
    public int getShadowResolution() {
        return shadowResolution;
    }

    public void setShadowResolution(int shadowResolution) {
        this.shadowResolution = Math.max(MIN_RESOLUTION,
                Math.min(MAX_RESOLUTION, shadowResolution));
    }

    /**
     * 序列化数据面：公共字段直传本体（type 不落盘——子类身份即类型，反序列化
     * 按子类走；红线 10；enabled/node 在基类）。
     */
    @Override
    public void write(OutputCapsule output) {
        super.write(output);
        output.write("color", color);
        output.write("intensity", intensity);
        output.write("castShadow", castShadow);
        output.write("shadowResolution", shadowResolution);
    }

    @Override
    public void read(InputCapsule input) {
        super.read(input);
        Color c = input.readSerializable("color", Color.class);
        if (c != null) {
            color = c;
        }
        intensity = input.readFloat("intensity");
        castShadow = input.readBoolean("castShadow");
        int resolution = input.readInt("shadowResolution");
        setShadowResolution(resolution > 0 ? resolution : DEFAULT_RESOLUTION);
    }

    /** 灯型枚举（引擎侧分流用；Inspector 不显——组件类型即答案） */
    public enum LightType {
        DIRECTIONAL, POINT, SPOT
    }
}
