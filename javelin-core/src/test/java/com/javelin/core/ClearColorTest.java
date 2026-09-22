package com.javelin.core;

import com.javelin.core.render.FrameLoop;
import com.javelin.core.window.GLFWWindow;
import com.javelin.core.window.Window;
import com.javelin.rhi.Device;
import com.javelin.rhi.Instance;
import com.javelin.rhi.RHI;
import com.javelin.rhi.RHIDevicePrefs;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 阶段⑥首画面验证：窗口呈现 + 交换链 + 帧循环 + 纯色清屏。
 *
 * 运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.ClearColorTest
 * 验收：窗口显示纯色，ESC 退出，拖拽 resize 画面跟随且不崩溃。
 */
public final class ClearColorTest {

    private static final Logger log = LoggerFactory.getLogger(ClearColorTest.class);

    private static final RHIClearColor CLEAR_COLOR = new RHIClearColor(0.18f, 0.35f, 0.55f, 1.0f);

    public static void main(String[] args) {
        Window window = new GLFWWindow(1280, 720, "Javelin RHI - ClearColor");
        Instance instance = null;
        Device device = null;
        FrameLoop frameLoop = null;
        try {
            window.create();
            window.show();

            RenderSurface surface = window.renderSurface();
            instance = RHI.createInstance(
                    new RHI.RHIInstanceConfig("ClearColorTest", 1, true),
                    surface.platform());
            device = instance.createDevice(RHIDevicePrefs.defaults(), surface);

            frameLoop = new FrameLoop(device, surface,
                    SwapChainDesc.defaults(window.width(), window.height()), 2);

            while (!window.closeRequested()) {
                window.pollEvents();
                frameLoop.renderFrame(backbuffer -> {
                    RenderGraphBuilder graph = RHI.beginRenderGraph();
                    graph.addGraphicsPass("Clear", pass ->
                            pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, CLEAR_COLOR));
                    return graph;
                });
            }
        } finally {
            if (frameLoop != null) {
                frameLoop.close();
            }
            if (device != null) {
                device.dispose();
            }
            if (instance != null) {
                instance.dispose();
            }
            window.close();
        }
        log.info("ClearColorTest finished, frames rendered: {}",
                frameLoop != null ? frameLoop.frameCounter() : 0);
    }

    private ClearColorTest() {}
}
