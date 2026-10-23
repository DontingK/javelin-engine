package com.javelin.rhi;

import com.javelin.rhi.desc.BufferDesc;
import com.javelin.rhi.desc.MemoryPreference;
import com.javelin.rhi.types.BufferUsage;
import com.javelin.rhi.types.TextureLayout;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * 纹理读回环（GPU→CPU 小区域拷贝，多槽环形）。
 * 每槽 command buffer + fence + staging buffer 池化复用；submit 非阻塞投递
 * （槽忙 = GPU 落后，返回 false 由调用方跳帧）、poll 非阻塞收割（FIFO，
 * 最旧完成槽先出）。屏障与 copyImageToBuffer 收口在本类内（22.4 迁移纪律，
 * uploadTexture 的读回方向延伸）。
 *
 * <p>线程模型：引擎主线程串行调用（submit/poll 同线程），无内部并发。
 * 调用方负责 GPU→CPU 可见性语义：poll 返回 true 后结果已可读
 * （fence signaled 即 GPU 写入完成，hostVisibleCoherent 内存直接可见）。
 *
 * <p>生命周期：close 须在 device waitIdle 后（GPU 侧命令全部完成后）调用。
 */
public final class TextureReadbackRing implements AutoCloseable {

    /** 单槽资源：staging buffer + 命令缓冲 + fence，随环复用 */
    private static final class Slot {
        final Buffer staging;
        final CommandBuffer cmd;
        final Fence fence;
        boolean inFlight;
        long tag;
        int bytes;

        Slot(Buffer staging, CommandBuffer cmd, Fence fence) {
            this.staging = staging;
            this.cmd = cmd;
            this.fence = fence;
        }
    }

    private final Device device;
    private final List<Slot> slots;
    private final Deque<Integer> pending = new ArrayDeque<>();
    private final ByteBuffer result;
    private final int capacityBytes;
    private int nextSlot;
    private long resultTag;
    private boolean hasResult;
    private boolean closed;

    /**
     * @param device        RHI 设备（graphicsQueue 提交）
     * @param slots         槽数（与 maxFramesInFlight 匹配，拾取环 = 2）
     * @param capacityBytes 单次区域的最大字节数（w*h*bytesPerPixel；staging 按此容量建）
     */
    public TextureReadbackRing(Device device, int slots, int capacityBytes) {
        if (device == null) {
            throw new IllegalArgumentException("device must not be null");
        }
        if (slots < 1 || capacityBytes < 1) {
            throw new IllegalArgumentException("slots/capacityBytes must be >= 1, got: "
                    + slots + "/" + capacityBytes);
        }
        this.device = device;
        this.capacityBytes = capacityBytes;
        this.result = ByteBuffer.allocateDirect(capacityBytes).order(ByteOrder.nativeOrder());
        this.slots = new ArrayList<>(slots);
        for (int i = 0; i < slots; i++) {
            Buffer staging = device.createBuffer(new BufferDesc(capacityBytes,
                    java.util.Set.of(BufferUsage.TRANSFER_DST),
                    MemoryPreference.hostVisibleCoherent()), null);
            CommandBuffer cmd = device.allocateCommandBuffer(false);
            Fence fence = device.createFence(true);   // 初始 signaled：首 submits 可直接用
            this.slots.add(new Slot(staging, cmd, fence));
        }
    }

    /**
     * 投递一次读回：屏障 oldLayout→TRANSFER_SRC → copyImageToBuffer → 提交。
     *
     * @param tex       源纹理（调用方提供当前真实布局，如附件终态 COLOR_ATTACHMENT）
     * @param oldLayout 纹理当前布局
     * @param x         区域左上（纹理像素坐标）
     * @param y         区域上
     * @param width     区域宽（像素）
     * @param height    区域高（像素）
     * @param tag       调用方关联标记（如帧号，poll 时原样奉还）
     * @return false = 下一槽还在飞行（GPU 落后），本次未投递，调用方跳帧或下帧重试
     */
    public boolean submit(Texture tex, TextureLayout oldLayout,
                          int x, int y, int width, int height, long tag) {
        checkOpen();
        if (tex == null) {
            throw new IllegalArgumentException("texture must not be null");
        }
        if (width < 1 || height < 1) {
            throw new IllegalArgumentException("region size must be >= 1x1, got: " + width + "x" + height);
        }
        long bytes = (long) width * height * tex.desc().format().bytesPerPixel();
        if (bytes > capacityBytes) {
            throw new IllegalArgumentException("region bytes " + bytes + " exceed ring capacity " + capacityBytes);
        }
        Slot slot = slots.get(nextSlot);
        nextSlot = (nextSlot + 1) % slots.size();
        if (slot.inFlight && !slot.fence.isSignaled()) {
            return false;
        }
        slot.fence.reset();
        slot.cmd.begin();
        slot.cmd.pipelineBarrier(tex, oldLayout, TextureLayout.TRANSFER_SRC);
        slot.cmd.copyImageToBuffer(tex, slot.staging,
                new TextureRegion(x, y, 0, width, height, 1, 0, 0));
        slot.cmd.end();
        device.graphicsQueue().submit(slot.cmd, SubmitInfo.of(slot.fence));
        slot.inFlight = true;
        slot.tag = tag;
        slot.bytes = (int) bytes;
        pending.addLast(indexOf(slot));
        return true;
    }

    /**
     * 非阻塞收割：最旧的飞行中槽若已完成 → staging 拷入结果缓冲，返回 true
     * （{@link #resultTag()} / {@link #readResult(ByteBuffer)} 可读，直到下次 poll）。
     * FIFO 语义保证逐槽送达（点击结果不会被更新的 hover 结果跳过）。
     */
    public boolean poll() {
        checkOpen();
        Integer idx = pending.peekFirst();
        if (idx == null) {
            return false;
        }
        Slot slot = slots.get(idx);
        if (!slot.fence.isSignaled()) {
            return false;
        }
        pending.pollFirst();
        slot.inFlight = false;
        int bytes = slot.bytes;
        result.clear();
        result.limit(bytes);
        slot.staging.read(0, bytes, result);
        result.rewind();
        resultTag = slot.tag;
        hasResult = true;
        return true;
    }

    /** 最近一次 poll 收割结果关联的 tag（submit 原样奉还） */
    public long resultTag() {
        return resultTag;
    }

    /** 拷出最近一次 poll 收割的结果（out 容量须足够） */
    public void readResult(ByteBuffer out) {
        if (!hasResult) {
            throw new IllegalStateException("No polled result available");
        }
        out.put(result.duplicate().rewind());
    }

    /** 最近收割结果的字节数 */
    public int resultBytes() {
        if (!hasResult) {
            throw new IllegalStateException("No polled result available");
        }
        return result.remaining();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        for (Slot slot : slots) {
            slot.cmd.dispose();
            slot.fence.dispose();
            slot.staging.dispose();
        }
        slots.clear();
        pending.clear();
    }

    private int indexOf(Slot slot) {
        for (int i = 0; i < slots.size(); i++) {
            if (slots.get(i) == slot) {
                return i;
            }
        }
        throw new IllegalStateException("Slot not found");
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("TextureReadbackRing is closed");
        }
    }
}
