package cn.donting.javelin.core.render;

import org.joml.Vector3f;

/**
 * 引擎侧灯（Engine_Lighting_Design §4；场景 Light 组件族的渲染侧翻译镜像，
 * SceneRenderBridge pull 填充——RenderingSystem 不感知 scene 包，与 RenderCamera 同构）。
 *
 * <p>灯型为引擎侧枚举 {@link Type}（自持，不依赖 scene 组件子类身份——
 * render 包只关心三分支行为，不关心组件类层次）。方向光：worldDir 世界前向
 * （节点 +Z 世界像）。点/聚光：worldPos 世界位置 + range 包围球（视锥剔除用）。
 * 视空间打包归 {@link LightCollector}。</p>
 */
public class RenderLight {

    /** 灯型（引擎侧三分支；POINT/SPOT 进 lightList、DIRECTIONAL 进 UBO 数组） */
    public enum Type {
        DIRECTIONAL, POINT, SPOT
    }

    private final Type type;
    /** 线性 HDR rgb + intensity（小端 16B，collectFrame 原样喂 LightItem.colorIntensity） */
    private final float[] colorIntensity = new float[4];
    private final Vector3f worldPos = new Vector3f();
    /** 方向光/聚光世界前向（节点局部 +Z 世界像，normalize 后写入） */
    private final Vector3f worldDir = new Vector3f(0.0f, 0.0f, -1.0f);
    private boolean castShadow;
    private float range;
    private float cosInner;
    private float cosOuter;

    public RenderLight(Type type, boolean castShadow, float range,
                       float cosInner, float cosOuter) {
        this.type = type;
        this.castShadow = castShadow;
        this.range = range;
        this.cosInner = cosInner;
        this.cosOuter = cosOuter;
    }

    /** 参数每帧同步入口（SceneRenderBridge 逐字段写；castShadow/range/锥角 Inspector
     *  运行期编辑即时生效。type 由构造钉死不收——组件类型不切换，拍板 2026-10-31） */
    public void setParams(boolean castShadow, float range, float cosInner, float cosOuter) {
        this.castShadow = castShadow;
        this.range = range;
        this.cosInner = cosInner;
        this.cosOuter = cosOuter;
    }

    public Type type() {
        return type;
    }

    /** 线性 HDR rgb + intensity 四元组（原值引用，收集/打包共读直通） */
    public float[] colorIntensity() {
        return colorIntensity;
    }

    public Vector3f worldPos() {
        return worldPos;
    }

    public Vector3f worldDir() {
        return worldDir;
    }

    public boolean isCastShadow() {
        return castShadow;
    }

    /** POINT/SPOT 衰减半径（世界单位） */
    public float range() {
        return range;
    }

    /** SPOT 锥 cos(inner)（非 SPOT 恒 1） */
    public float cosInner() {
        return cosInner;
    }

    /** SPOT 锥 cos(outer)（非 SPOT 恒 0） */
    public float cosOuter() {
        return cosOuter;
    }
}
