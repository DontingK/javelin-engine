package cn.donting.javelin.rhi;

/**
 * 队列接口。
 * 对照设计文档第 24.4 节 / 第 26.4 节。
 */
public interface Queue {

    /**
     * 提交命令缓冲。
     *
     * @param cmd  命令缓冲
     * @param info 提交信息（wait/signal semaphore + fence）
     */
    void submit(CommandBuffer cmd, SubmitInfo info);

    /** 等待队列空闲 */
    void waitIdle();
}
