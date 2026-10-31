package cn.donting.javelin.core;

import cn.donting.javelin.rhi.data.RHIClearColor;

/**
 * 阶段⑥首画面验证：窗口呈现 + 交换链 + 帧循环 + 纯色清屏。
 *
 * 运行：gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.ClearColorTest
 * 验收：窗口显示纯色，ESC 退出，拖拽 resize 画面跟随且不崩溃。
 */
public final class ClearColorTest extends RhiTestBase {

    public static void main(String[] args) {
        new ClearColorTest().run();
    }

    @Override
    protected String testName() {
        return "ClearColorTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        return new RHIClearColor(0.18f, 0.35f, 0.55f, 1.0f);
    }
}
