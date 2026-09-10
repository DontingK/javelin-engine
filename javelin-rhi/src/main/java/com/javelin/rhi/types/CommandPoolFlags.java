package com.javelin.rhi.types;

/**
 * 命令池标志。
 */
public enum CommandPoolFlags {
    TRANSIENT(0x00000001),
    RESET_COMMAND_BUFFER(0x00000002),
    PROTECTED(0x00000004);

    private final int value;

    CommandPoolFlags(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    public static int combine(CommandPoolFlags... flags) {
        int result = 0;
        for (CommandPoolFlags flag : flags) {
            result |= flag.value;
        }
        return result;
    }
}
