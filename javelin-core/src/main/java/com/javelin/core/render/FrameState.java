package com.javelin.core.render;

import com.javelin.rhi.CommandBuffer;
import com.javelin.rhi.Device;
import com.javelin.rhi.Fence;
import com.javelin.rhi.FrameArena;
import com.javelin.rhi.Semaphore;

/**
 * 帧状态（设计文档第 24.1 节）：一个 in-flight 帧的全部同步原语。
 *
 * 组成：fence（帧闸门，创建时置位使首帧直接通过）+ imageAvailable（acquire 信号）
 * + renderFinished（图完成信号）+ 命令缓冲 + 帧内存池（17.3）。
 * 环形槽位由 {@link FrameLoop} 管理；槽位复用前必须 fence 闸门通过。
 */
public final class FrameState {

    private final Fence fence;
    private final Semaphore imageAvailable;
    private final Semaphore renderFinished;
    private final CommandBuffer cmd;
    private final FrameArena arena;

    public FrameState(Device device) {
        if (device == null) {
            throw new IllegalArgumentException("Device must not be null");
        }
        this.fence = device.createFence(true);
        this.imageAvailable = device.createSemaphore();
        this.renderFinished = device.createSemaphore();
        this.cmd = device.allocateCommandBuffer(false);
        // frameArena() 每次调用新建一块，必须长期持有（每帧复用、fence 闸门后 reset）
        this.arena = device.frameArena();
    }

    /** 帧闸门：提交时由 GPU 置位，复用本槽位前 await */
    public Fence fence() {
        return fence;
    }

    /** acquire 完成信号（等待于提交的 COLOR_ATTACHMENT_OUTPUT 阶段） */
    public Semaphore imageAvailable() {
        return imageAvailable;
    }

    /** 渲染完成信号（present 等待） */
    public Semaphore renderFinished() {
        return renderFinished;
    }

    /** 本槽位的命令缓冲（每帧重新录制，ONE_TIME_SUBMIT） */
    public CommandBuffer cmd() {
        return cmd;
    }

    /** 本槽位的帧内存池（fence 闸门后 reset） */
    public FrameArena arena() {
        return arena;
    }

    /** 显式释放全部原语（调用方须保证 device 已 waitIdle） */
    void close() {
        fence.dispose();
        imageAvailable.dispose();
        renderFinished.dispose();
        cmd.dispose();
    }
}
