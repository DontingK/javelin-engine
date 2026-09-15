package com.javelin.rhi;

/**
 * RHI 常量定义，包含推送常量、帧内存池、描述符池等默认大小限制。
 */
public final class RHIConstants {

    private RHIConstants() {
    }

    /** 推送常量的最大字节数。 */
    public static final int MAX_PUSH_CONSTANT_SIZE = 128;
    /** 每帧临时内存池的默认大小（字节）。 */
    public static final int DEFAULT_FRAME_ARENA_SIZE = 64 * 1024 * 1024;
    /** 帧内存池的环形缓冲数量。 */
    public static final int FRAME_ARENA_RING_COUNT = 3;
    /** 描述符池的默认容量。 */
    public static final int DEFAULT_DESCRIPTOR_POOL_SIZE = 512;
}
