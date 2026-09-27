package com.javelin.core;

import com.javelin.core.appstate.AppStateManager;
import com.javelin.core.debug.DebugTextColor;
import com.javelin.core.debug.DebugText;
import com.javelin.core.input.Input;
import com.javelin.core.input.KeyCode;
import com.javelin.core.settings.JavelinSetting;

/**
 * DebugText 验收样例（doc/Engine_DebugText_Design.md §7）：
 * <b>无相机纯清屏场景</b>——overlay 独立于相机路径（ClearOnly 兜底分支外照常出字）。
 *
 * <p>API 为 2026-09-27 用户重定义版：固定行追加 + 自动叠行 + 单行/全部 clear，无 \n\r 转义。</p>
 * <p>验收点：</p>
 * <ul>
 *   <li>无相机也出字（挂点验证）；</li>
 *   <li><b>行 2 每帧单行刷新</b>（clear(row)+printf(row,...)），其余行写一次持续不动——
 *       单行刷新与持续语义同屏验收；</li>
 *   <li>行 0/1 无参 printf 自动叠行；行 4 两段 printf 同行追加；</li>
 *   <li>全 16 色对照（每行"背景色块 + 前景色样"两次追加，attr 两个半字节）；</li>
 *   <li>SPACE 全清：其他行全部消失（"持续到 clear"验收）；F 重写静态块；</li>
 *   <li>窗口 resize：网格重建清空；[perf] 有字/无字两态成本应不可见。</li>
 * </ul>
 *
 * <p>运行：{@code gradlew :javelin-core:runTest -DmainClass=com.javelin.core.DebugTextDemo}</p>
 */
public class DebugTextDemo extends JavelinEngineTestBase {

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        setting.setWidth(1280);
        setting.setHeight(720);
        new DebugTextDemo(setting).start();
    }

    public DebugTextDemo(JavelinSetting setting) {
        super(setting);
        perfLogEnabled = true;
    }

    @Override
    protected void onInitialize() {
        writeStaticBlock();
    }

    @Override
    protected void registerDefaultAppStates(AppStateManager stateManager) {
        // 关闭默认统计 overlay：RenderStatsAppState 固定占 DebugText 行 0~7，
        // 与本样例的行区验收样例（自动行 0/1、色表行区）叠写冲突
    }

    @Override
    protected void update() {
        super.update();     // [perf] 观测窗

        // 单行刷新惯用法：每帧 clear(row) + printf(row,...)——同屏其余行持续不动
        DebugText.clear(2);
        DebugText.printf(2, "FPS:" + Time.getFps() + "  帧:" + Time.getFrameCount()
                + "  时间:" + String.format("%.1fs", Time.getRealtimeSinceStartup()),
                DebugTextColor.WHITE);

        if (Input.wasPressed(KeyCode.SPACE)) {
            DebugText.clear();          // 全清：其他行一并消失——"持续到 clear"验收
        }
        if (Input.wasPressed(KeyCode.F)) {
            writeStaticBlock();         // 重写静态块（全清后游标已归 0，叠行回到行 0/1）
        }
    }

    /** 静态块：写一次持续到 clear（SPACE 被按下前与行 2 的单行刷新互不干扰） */
    private void writeStaticBlock() {
        DebugText.printf("① 自动叠行：无参 printf 写完换下一行（自动行 0）");
        DebugText.printf("② 写一次持续到 clear——SPACE 全清 / F 重写本块（自动行 1）");

        DebugText.printf(4, "你好世界 Hello DebugText!", DebugTextColor.LIGHT_GREEN);
        DebugText.printf(4, " ← 同行追加段", DebugTextColor.GRAY);   // 同行追加验收

        // 全 16 色对照：每行先背景色块（全角空格 + bg=色）再前景色样
        for (int c = 0; c < 16; c++) {
            DebugText.printf(6 + c, "　", DebugTextColor.attr(DebugTextColor.BLACK, c));
            DebugText.printf(6 + c, " 汉A", DebugTextColor.attr(c, DebugTextColor.BLACK));
        }
    }
}
