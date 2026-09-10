package com.javelin.rhi.types;

/**
 * 访问标志（用于内存屏障）。
 */
public enum AccessFlags {
    NONE(0),
    INDIRECT_COMMAND_READ(0x00000001),
    INDEX_READ(0x00000002),
    VERTEX_ATTRIBUTE_READ(0x00000004),
    UNIFORM_READ(0x00000008),
    INPUT_ATTACHMENT_READ(0x00000010),
    SHADER_READ(0x00000020),
    SHADER_WRITE(0x00000040),
    COLOR_ATTACHMENT_READ(0x00000080),
    COLOR_ATTACHMENT_WRITE(0x00000100),
    DEPTH_STENCIL_ATTACHMENT_READ(0x00000200),
    DEPTH_STENCIL_ATTACHMENT_WRITE(0x00000400),
    TRANSFER_READ(0x00000800),
    TRANSFER_WRITE(0x00001000),
    HOST_READ(0x00002000),
    HOST_WRITE(0x00004000),
    MEMORY_READ(0x00008000),
    MEMORY_WRITE(0x00010000);

    private final int value;

    AccessFlags(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(AccessFlags... flags) {
        int result = 0;
        for (AccessFlags flag : flags) {
            result |= flag.value;
        }
        return result;
    }
}
