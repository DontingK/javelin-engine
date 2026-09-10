package com.javelin.rhi;

import com.javelin.rhi.api.*;
import com.javelin.rhi.desc.*;
import com.javelin.rhi.types.*;
import com.javelin.rhi.vulkan.VulkanDevice;
import com.javelin.rhi.vulkan.VulkanSwapChain;
import org.lwjgl.vulkan.VK;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 简化版渲染器，管理每帧资源和呈现流程。
 * <p>
 * 使用模式：
 * <pre>
 *   renderer.beginFrame();
 *   // 录制命令到 renderer.commandBuffer()
 *   renderer.endFrame();
 * </pre>
 */
public class Renderer {

    private static final Logger log = LoggerFactory.getLogger(Renderer.class);

    private final Device device;
    private final SwapChain swapChain;
    private final CommandPool commandPool;
    private final CommandBuffer[] commandBuffers;
    private final Fence[] inFlightFences;
    private final Semaphore[] imageAvailableSemaphores;
    private final Semaphore[] renderFinishedSemaphores;
    private final int maxFramesInFlight;
    private final DelayedResourceDisposer resourceDisposer;

    private int currentFrame = 0;
    private int imageIndex = -1;
    private boolean frameStarted = false;

    public Renderer(Device device, SwapChain swapChain, int maxFramesInFlight) {
        this.device = device;
        this.swapChain = swapChain;
        this.maxFramesInFlight = maxFramesInFlight;

        // 创建延迟释放管理器（延迟 maxFramesInFlight 帧），并注册为全局 GPU 资源释放器
        this.resourceDisposer = new DelayedResourceDisposer(maxFramesInFlight);
        GPUResource.setDisposer(resourceDisposer);

        // 创建命令池
        this.commandPool = device.createCommandPool(device.graphicsQueue(), CommandPoolFlags.RESET_COMMAND_BUFFER);

        // 每帧资源
        this.commandBuffers = new CommandBuffer[maxFramesInFlight];
        this.inFlightFences = new Fence[maxFramesInFlight];
        this.imageAvailableSemaphores = new Semaphore[maxFramesInFlight];
        this.renderFinishedSemaphores = new Semaphore[maxFramesInFlight];

        for (int i = 0; i < maxFramesInFlight; i++) {
            commandBuffers[i] = device.createCommandBuffer(commandPool, true);
            inFlightFences[i] = device.createFence(true);
            imageAvailableSemaphores[i] = device.createSemaphore();
            renderFinishedSemaphores[i] = device.createSemaphore();
        }

        log.info("Renderer created (framesInFlight={})", maxFramesInFlight);
    }

    /**
     * 开始一帧渲染。
     * @return 是否成功获取交换链图像
     */
    public boolean beginFrame() {
        if (frameStarted) {
            throw new IllegalStateException("Frame already started, call endFrame() first");
        }

        // 1. 等待上一帧完成
        device.waitForFences(new Fence[]{inFlightFences[currentFrame]}, true, Long.MAX_VALUE);

        // 2. 获取交换链图像（先 acquire，确认成功后再 reset fence）
        imageIndex = swapChain.acquireNextImage(Long.MAX_VALUE, imageAvailableSemaphores[currentFrame], null);
        if (imageIndex < 0) {
            // 交换链需要重建，但 fence 仍保持 signaled 状态（因为还没 reset）
            return false;
        }

        // 3. acquire 成功后才 reset fence，这样如果 acquire 失败，fence 仍是 signaled
        device.resetFences(new Fence[]{inFlightFences[currentFrame]});

        // 4. 推进延迟释放队列（上一帧的 fence 已等待完成，到期的资源可安全释放）
        resourceDisposer.beginFrame();

        // 5. 重置并开始录制命令缓冲区
        CommandBuffer cmd = commandBuffers[currentFrame];
        cmd.reset();
        cmd.begin();

        frameStarted = true;
        return true;
    }

    /**
     * 结束一帧渲染并呈现。
     */
    public void endFrame() {
        if (!frameStarted) {
            throw new IllegalStateException("No frame started, call beginFrame() first");
        }

        CommandBuffer cmd = commandBuffers[currentFrame];
        cmd.end();

        // 提交
        Queue.SubmitInfo submitInfo = new Queue.SubmitInfo(
            new CommandBuffer[]{cmd},
            new Semaphore[]{imageAvailableSemaphores[currentFrame]},
            new int[]{PipelineStage.COLOR_ATTACHMENT_OUTPUT.value()},
            new Semaphore[]{renderFinishedSemaphores[currentFrame]}
        );
        device.graphicsQueue().submit(submitInfo, inFlightFences[currentFrame]);

        // 呈现
        Queue.PresentInfo presentInfo = new Queue.PresentInfo(
            swapChain, imageIndex,
            new Semaphore[]{renderFinishedSemaphores[currentFrame]}
        );
        device.graphicsQueue().present(presentInfo);

        currentFrame = (currentFrame + 1) % maxFramesInFlight;
        frameStarted = false;
        imageIndex = -1;
    }

    /**
     * 获取当前帧的命令缓冲区（用于录制命令）。
     */
    public CommandBuffer commandBuffer() {
        if (!frameStarted) {
            throw new IllegalStateException("No frame started, call beginFrame() first");
        }
        return commandBuffers[currentFrame];
    }

    /**
     * 获取当前帧的交换链图像索引。
     */
    public int imageIndex() {
        return imageIndex;
    }

    /**
     * 获取当前帧的交换链图像。
     */
    public Texture currentImage() {
        return swapChain.getImage(imageIndex);
    }

    /**
     * 等待所有帧完成。
     */
    public void waitIdle() {
        device.waitIdle();
    }

    /**
     * 销毁渲染器资源。
     */
    public void dispose() {
        device.waitIdle();

        // 请求释放帧资源（fence / 信号量 / 命令池）
        for (int i = 0; i < maxFramesInFlight; i++) {
            inFlightFences[i].dispose();
            imageAvailableSemaphores[i].dispose();
            renderFinishedSemaphores[i].dispose();
        }
        commandPool.dispose();

        // 强制释放所有待延迟资源（GPU 已空闲，可安全释放）
        resourceDisposer.flush();
    }

    /**
     * 获取 GPU 资源延迟释放管理器。
     */
    public DelayedResourceDisposer resourceDisposer() {
        return resourceDisposer;
    }

    public Device device() {
        return device;
    }

    public SwapChain swapChain() {
        return swapChain;
    }
}
