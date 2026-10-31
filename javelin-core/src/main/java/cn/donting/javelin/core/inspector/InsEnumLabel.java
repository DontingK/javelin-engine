package cn.donting.javelin.core.inspector;

/**
 * 枚举实现此接口，为 Inspector 下拉项提供显示名 i18n 键（editor.core
 * 语言包局部键，编辑器侧解析；缺键显示 !key! 兜底显性可见）。
 */
public interface InsEnumLabel {
    String getLabel();
}
