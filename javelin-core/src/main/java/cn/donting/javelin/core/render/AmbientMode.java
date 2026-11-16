package cn.donting.javelin.core.render;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 场景环境光模式（四期 P4-04 步骤一）。
 *
 * <p>在 {@link cn.donting.javelin.core.scene.component.Environment} 上选择：
 * {@code OFF} = 环境项纯黑（与三期基线一致），{@code CONSTANT} = 一组颜色 + 强度构成的
 * 无方向常量环境项（{@code ins.environment.ambientSetting} 条件子组内联展开）。</p>
 */
public enum AmbientMode implements InsEnumLabel {
    /**
     * 关闭：环境项纯黑，场景亮度完全由灯决定
     */
    OFF("ins.enum.ambientmode.off"),
    /**
     * 直接环境光：无方向的常量颜色 + 强度（天空盒球谐环境光为后续步骤）
     */
    CONSTANT("ins.enum.ambientmode.constant");

    private final String label;

    AmbientMode(String label) {
        this.label = label;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
