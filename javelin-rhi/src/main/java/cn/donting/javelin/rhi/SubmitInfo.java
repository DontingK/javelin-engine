package cn.donting.javelin.rhi;

import java.util.List;

/**
 * 提交信息。
 * 对照 VkSubmitInfo。
 */
public final class SubmitInfo {

    private final List<Semaphore> waitSemaphores;
    private final List<Semaphore> signalSemaphores;
    private final Fence fence;

    public SubmitInfo(List<Semaphore> waitSemaphores,
                      List<Semaphore> signalSemaphores,
                      Fence fence) {
        this.waitSemaphores = waitSemaphores != null ? List.copyOf(waitSemaphores) : List.of();
        this.signalSemaphores = signalSemaphores != null ? List.copyOf(signalSemaphores) : List.of();
        this.fence = fence;
    }

    public List<Semaphore> waitSemaphores() { return waitSemaphores; }
    public List<Semaphore> signalSemaphores() { return signalSemaphores; }
    public Fence fence() { return fence; }

    public static SubmitInfo of(Fence fence) {
        return new SubmitInfo(null, null, fence);
    }
}
