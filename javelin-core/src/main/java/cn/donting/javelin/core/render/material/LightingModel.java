package cn.donting.javelin.core.render.material;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 材质光照模型（`.shader` 顶层 {@code LightingModel} 声明，四期施工序 ⑤）——**单一封闭轴**：
 * 每个值 = 表面最终色怎么算，互斥且穷尽（不存在「公式 + 不受光」这类无效组合）。
 *
 * <p>后置扩值（`PBR` / `Toon`）在同轴追加：仍走受光通路（`opaque` 桶 + G-Buffer + LightingPass），
 * 只在注入层换公式块。缺省 {@link #LAMBERT}。</p>
 */
public enum LightingModel implements InsEnumLabel {
    /**
     * 受光（缺省）：Lambert + 无方向环境项 + 阴影
     */
    LAMBERT("ins.enum.lightingmodel.lambert"),
    /**
     * 非光照：直接输出 albedo（自发光/遮罩/调试几何/错误兜底材质）
     */
    UNLIT("ins.enum.lightingmodel.unlit");

    private final String label;

    LightingModel(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
