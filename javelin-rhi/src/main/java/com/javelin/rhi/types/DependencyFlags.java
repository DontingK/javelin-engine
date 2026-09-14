package com.javelin.rhi.types;

/**
 * 依赖标志。
 */
public enum DependencyFlags {
    NONE(0),
    BY_REGION(0x00000001),
    DEVICE_GROUP(0x00000004),
    VIEW_LOCAL(0x00000002);

    private final int value;

    DependencyFlags(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(DependencyFlags... flags) {
        int result = 0;
        for (DependencyFlags flag : flags) {
            result |= flag.value;
        }
        return result;
    }
}
