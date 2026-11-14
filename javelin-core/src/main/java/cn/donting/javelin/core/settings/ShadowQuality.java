package cn.donting.javelin.core.settings;

/**
 * 阴影采样品质档（Engine_Shadow_Quality_Design §3.1，对标 Unity Soft Shadows 开关）：
 * 采样次数即软影半影宽度——{@code HARD} 单次比较采样、{@code SOFT_LOW} 4 tap 复现
 * 3×3、{@code SOFT_HIGH} 9 tap 复现 5×5（MJP 亚纹素权重，注入段按 {@link #code()}
 * 分支；枚举序即编码，改序须同步注入段）。
 */
public enum ShadowQuality {

    /** 单次比较采样（硬件 2×2 比较双线性仍在；无邻域核） */
    HARD(0),
    /** 4 tap 复现 3×3 高斯（中档，缺省） */
    SOFT_LOW(1),
    /** 9 tap 复现 5×5 高斯（高档） */
    SOFT_HIGH(2);

    private final int code;

    ShadowQuality(int code) {
        this.code = code;
    }

    /** UBO {@code shadowParams.x} 编码（注入段消费；与枚举序一致） */
    public int code() {
        return code;
    }
}
