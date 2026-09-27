package com.javelin.core.debug;

import com.javelin.core.Time;
import com.javelin.core.appstate.AbstractAppState;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.rhi.Device;
import com.javelin.rhi.GPUInfo;
import com.javelin.rhi.RenderStats;

/**
 * 渲染统计 overlay（doc/Engine_RenderStats_Design.md §3.2）：每帧覆写 DebugText
 * 固定行 0~7，展示 GPU 信息 / FPS / draw call 统计 / 存活资源计数。
 *
 * <p><b>固定占用 DebugText 行 0~7</b>（行号 {@link #LAST_ROW}），其他 DebugText
 * 客户端请避开该行区；每帧单行覆写惯用法 {@code clear(row) + printf(row, ...)}，
 * 与持续文本语义同行屏共存。{@code F3} 边沿开关，<b>默认显示</b>；关闭时逐行清空。</p>
 *
 * <p>读到的是<b>上一帧</b>快照：overlay 装配先于录制，
 * {@link Device#renderStats()} 公开 getter 即 lastFrame 快照（§3.1 时序）。
 * 由 JavelinEngine 默认挂载（{@code registerDefaultAppStates} 钩子）；
 * DebugTextDemo 因行区冲突覆盖关闭。</p>
 */
public class RenderStatsAppState extends AbstractAppState {

    /** overlay 固定占用行 0 ~ LAST_ROW */
    private static final int LAST_ROW = 7;

    /** 文本刷新节流间隔（秒）：写行 0~7 每秒一轮，数字不逐帧跳动（F3 开关不受节流） */
    private static final float REFRESH_INTERVAL = 1.0f;

    /** F3 切换；默认显示 */
    private boolean visible = true;

    /** 距上次刷新累计时长；初值=间隔 → 首帧立即刷一次 */
    private float refreshTimer = REFRESH_INTERVAL;
    /** 本刷新窗内主循环迭代数（FPS 分子） */
    private int framesSinceRefresh;
    /** 首帧无满秒窗口，FPS 以 Time EMA 兜底显示，其后为本窗实测 */
    private boolean firstRefresh = true;

    @Override
    public void update(float deltaTime) {
        if (Input.wasPressed(KeyCode.F3)) {
            visible = !visible;
            if (!visible) {
                closeRows();
            }
        }
        if (!visible) {
            return;
        }

        // 1Hz 节流：数字每秒刷新一轮；计时用未缩放 dt（timeScale 暂停时统计照常推进）
        refreshTimer += Time.getUnscaledDeltaTime();
        framesSinceRefresh++;
        if (refreshTimer < REFRESH_INTERVAL) {
            return;
        }
        float fps = firstRefresh
                ? Time.getFps()
                : framesSinceRefresh / refreshTimer;
        firstRefresh = false;
        refreshTimer = 0f;
        framesSinceRefresh = 0;

        Device device = engine.device();
        RenderStats stats = device.renderStats();
        GPUInfo gpu = device.gpuInfo();

        // 行 0：标题（黄）+ 开关提示（灰）
        DebugText.clear(0);
        DebugText.printf(0, "Javelin 统计",
                DebugTextColor.attr(DebugTextColor.YELLOW, DebugTextColor.BLACK));
        DebugText.printf(0, " [F3 隐藏]", DebugTextColor.GRAY);

        // 行 1~2：GPU 名与版本（选卡一次性信息）
        DebugText.clear(1);
        DebugText.printf(1, gpu.deviceName(), DebugTextColor.WHITE);
        DebugText.clear(2);
        DebugText.printf(2, "API " + gpu.apiVersionString()
                + "  驱动 " + gpu.driverVersionString(), DebugTextColor.GRAY);

        // 行 3：FPS（本刷新窗实测；只有首帧用 Time EMA 兜底）+ 帧计数
        DebugText.clear(3);
        DebugText.printf(3, "FPS " + String.format("%.1f", fps)
                + "  帧 " + Time.getFrameCount(), DebugTextColor.LIGHT_GREEN);

        // 行 4：上一帧 draw 统计（dispatch 非零才显示）
        DebugText.clear(4);
        String dispatchPart = stats.dispatches() > 0
                ? "  Dispatch " + stats.dispatches() : "";
        DebugText.printf(4, "DrawCalls " + stats.drawCalls()
                + "  三角形 " + stats.triangles()
                + "  实例 " + stats.instances() + dispatchPart,
                DebugTextColor.LIGHT_CYAN);

        // 行 5~7：存活资源计数（创建 − 释放）
        DebugText.clear(5);
        DebugText.printf(5, "纹理 " + stats.textureCount()
                + "  缓冲 " + stats.bufferCount()
                + "  管线 " + stats.graphicsPipelineCount()
                + "/" + stats.computePipelineCount(), DebugTextColor.LIGHT_CYAN);
        DebugText.clear(6);
        DebugText.printf(6, "采样器 " + stats.samplerCount()
                + "  描述符集 " + stats.descriptorSetCount()
                + "  描述符布局 " + stats.descriptorSetLayoutCount(),
                DebugTextColor.LIGHT_CYAN);
        DebugText.clear(7);
        DebugText.printf(7, "着色器 " + stats.shaderModuleCount()
                + "  管线布局 " + stats.pipelineLayoutCount()
                + "  栅栏 " + stats.fenceCount()
                + "  信号量 " + stats.semaphoreCount(), DebugTextColor.LIGHT_CYAN);
    }

    /** 关闭/隐藏：把占用行清干净 */
    private void closeRows() {
        for (int row = 0; row <= LAST_ROW; row++) {
            DebugText.clear(row);
        }
    }
}
