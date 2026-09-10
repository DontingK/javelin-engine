package com.javelin.core;

import com.javelin.rhi.api.CommandBuffer;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JavelinEngine 集成测试：验证窗口创建、RHI 初始化和主循环是否正常。
 * <p>
 * 运行方式：
 * <pre>
 *   ./gradlew :javelin-core:runTest -DmainClass=com.javelin.core.JavelinEngineTest
 * </pre>
 * 或直接运行 main 方法。
 */
public class JavelinEngineTest extends JavelinEngine {

    private static final Logger log = LoggerFactory.getLogger(JavelinEngineTest.class);

    private int frameCount = 0;
    private final int maxFrames;

    public JavelinEngineTest() {
        super("Javelin Engine Test", 800, 600, true, 2);
        this.maxFrames = 300; // 运行约 5 秒（60fps）后自动退出
    }

    @Override
    protected void initialize() {
        log.info("Test initialize() called");
        log.info("Window size: {}x{}", window().width(), window().height());
        log.info("Device: {}", device().physicalDevice().deviceName());
        log.info("SwapChain: {}x{} images={}", swapChain().width(), swapChain().height(), swapChain().imageCount());
    }

    @Override
    protected void update() {
//        frameCount++;
//        if (frameCount >= maxFrames) {
//            log.info("Reached max frames ({}), stopping engine", maxFrames);
//            stop();
//        }
    }

    @Override
    protected void render() {
        CommandBuffer cmd = renderer().commandBuffer();

        // 简单的清屏渲染：暗蓝色背景
        // 注意：这里只做最基本的命令缓冲区验证
        // 实际渲染需要设置 RenderPass、Framebuffer 等，由后续测试扩展
    }

    // ========== 入口 ==========

    public static void main(String[] args) {
        log.info("Starting JavelinEngineTest");
        var test = new JavelinEngineTest();
        test.start();
        log.info("Test completed, total frames: {}", test.frameCount);
    }

    @Test
    void testEngineLifecycle() {
        // JUnit 测试：运行引擎并验证生命周期
        // 注意：此测试需要 Vulkan 兼容 GPU 和显示环境
        main(new String[0]);
    }
}
