package com.javelin.rhi.types;

/**
 * 纹理用途标志。
 */
public enum TextureUsage {
    TRANSFER_SRC(0x00000001),
    TRANSFER_DST(0x00000002),
    SAMPLED(0x00000004),
    STORAGE(0x00000008),
    COLOR_ATTACHMENT(0x00000010),
    DEPTH_STENCIL_ATTACHMENT(0x00000020),
    TRANSIENT_ATTACHMENT(0x00000040),
    INPUT_ATTACHMENT(0x00000080);

    private final int value;

    TextureUsage(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(TextureUsage... usages) {
        int result = 0;
        for (TextureUsage usage : usages) {
            result |= usage.value;
        }
        return result;
    }
}
