package com.javelin.rhi.types;

/**
 * 缓冲区用途标志（可组合）。
 */
public enum BufferUsage {
    TRANSFER_SRC(0x00000001),
    TRANSFER_DST(0x00000002),
    UNIFORM_BUFFER(0x00000010),
    STORAGE_BUFFER(0x00000020),
    INDEX_BUFFER(0x00000040),
    VERTEX_BUFFER(0x00000080),
    INDIRECT_BUFFER(0x00000100),
    SHADER_DEVICE_ADDRESS(0x00020000);

    private final int value;

    BufferUsage(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(BufferUsage... usages) {
        int result = 0;
        for (BufferUsage usage : usages) {
            result |= usage.value;
        }
        return result;
    }
}
