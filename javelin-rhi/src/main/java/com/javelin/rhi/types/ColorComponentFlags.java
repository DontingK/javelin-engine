package com.javelin.rhi.types;

/**
 * 颜色通道掩码。
 */
public enum ColorComponentFlags {
    R(0x00000001),
    G(0x00000002),
    B(0x00000004),
    A(0x00000008);

    private final int value;

    ColorComponentFlags(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(ColorComponentFlags... flags) {
        int result = 0;
        for (ColorComponentFlags flag : flags) {
            result |= flag.value;
        }
        return result;
    }

    public static int ALL = R.value | G.value | B.value | A.value;
}
