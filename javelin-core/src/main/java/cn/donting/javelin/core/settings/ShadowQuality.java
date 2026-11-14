package cn.donting.javelin.core.settings;

import cn.donting.javelin.core.inspector.InsEnumLabel;

/**
 * 阴影采样品质档（Engine_Shadow_Quality_Design §3.1，对标 Unity Soft Shadows 开关）：
 * 采样次数即软影半影宽度——{@code HARD} 单次比较采样、{@code SOFT_LOW} 4 tap 复现
 * 3×3、{@code SOFT_HIGH} 9 tap 复现 5×5（MJP 亚纹素权重，注入段按 {@link #code()}
 * 分支；枚举序即编码，改序须同步注入段）。
 *
 * <p>显示名走 {@link InsEnumLabel}（i18n 键在 editor.core 语言包）——设置面板下拉直接复用。</p>
 */
public enum ShadowQuality implements InsEnumLabel {

    /** 单次比较采样（硬件 2×2 比较双线性仍在；无邻域核） */
    HARD(0, "projectSettings.shadowQuality.hard"),
    /** 4 tap 复现 3×3 高斯（中档，缺省） */
    SOFT_LOW(1, "projectSettings.shadowQuality.softLow"),
    /** 9 tap 复现 5×5 高斯（高档） */
    SOFT_HIGH(2, "projectSettings.shadowQuality.softHigh");

    private final int code;
    private final String label;

    ShadowQuality(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** UBO {@code shadowParams.x} 编码（注入段消费；与枚举序一致） */
    public int code() {
        return code;
    }

    @Override
    public String getLabel() {
        return label;
    }
}
