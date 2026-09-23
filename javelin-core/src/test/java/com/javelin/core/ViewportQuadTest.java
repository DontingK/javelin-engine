package com.javelin.core;

import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.data.RHIScissor;

/**
 * 阶段⑥分区域清屏验证：4 个象限各自不同清屏颜色（多相机/编辑器视口清屏的底层机制）。
 *
 * <p>结构对照设计文档第 8 节多相机分屏：首写 pass 全屏 CLEAR（第一相机语义），
 * 后续 4 个 pass 各声明自己象限的区域清屏（addColorOutput 四参重载，
 * 底层 vkCmdClearAttachments——loadOp CLEAR 无视 scissor，区域清屏只能走此命令）。
 * 全程无 PSO、无顶点缓冲、无绘制调用。
 *
 * <p>坐标系：framebuffer 坐标，原点在左上（Vulkan 约定，RHI 直接透传）。
 *
 * <p>运行：gradlew :javelin-core:runTest -DmainClass=com.javelin.core.ViewportQuadTest
 * 验收：屏幕四象限分别为红/绿/蓝/黄，象限间无底色缝隙，ESC 退出，拖拽 resize 画面跟随且不崩溃。
 */
public final class ViewportQuadTest extends RhiTestBase {

    /** 象限顺序：左上、右上、左下、右下 */
    private static final String[] QUAD_NAMES = {"TL", "TR", "BL", "BR"};

    private static final RHIClearColor[] QUAD_COLORS = {
            new RHIClearColor(0.85f, 0.30f, 0.30f, 1.0f),  // 左上：红
            new RHIClearColor(0.30f, 0.80f, 0.35f, 1.0f),  // 右上：绿
            new RHIClearColor(0.30f, 0.45f, 0.90f, 1.0f),  // 左下：蓝
            new RHIClearColor(0.90f, 0.80f, 0.25f, 1.0f),  // 右下：黄
    };

    public static void main(String[] args) {
        new ViewportQuadTest().run();
    }

    @Override
    protected String testName() {
        return "ViewportQuadTest";
    }

    @Override
    protected RHIClearColor clearColor() {
        // 底色 pass 用：若区域清屏有缝隙会露出此色，验收时肉眼可查
        return new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f);
    }

    @Override
    protected void buildPasses(RenderGraphBuilder graph, Texture backbuffer) {
        // 底色 pass：全屏 CLEAR（第一相机语义，第 8 节）
        graph.addGraphicsPass("Base", pass ->
                pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, clearColor()));

        // 4 个象限 pass：各自区域清屏（后续相机语义，LOAD 叠加 + clearRegion）
        int w = window.width();
        int h = window.height();
        int hw = w / 2;
        int hh = h / 2;
        for (int i = 0; i < 4; i++) {
            int x = (i & 1) * hw;
            int y = (i >> 1) * hh;
            int rw = (i & 1) == 0 ? hw : w - hw;
            int rh = (i >> 1) == 0 ? hh : h - hh;
            RHIClearColor color = QUAD_COLORS[i];
            graph.addGraphicsPass("Quad-" + QUAD_NAMES[i], pass ->
                    pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, color,
                            new RHIScissor(x, y, rw, rh)));
        }
    }
}
