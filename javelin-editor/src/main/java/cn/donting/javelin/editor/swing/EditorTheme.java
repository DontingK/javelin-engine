package cn.donting.javelin.editor.swing;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;

/**
 * 编辑器外观基座（设计档 Editor_Swing_Design §3，拍板 2026-10-17：FlatLaf 亮色——
 * 当日复拍板由 Dark 改亮色，深色后置随主题切换能力再做）。
 *
 * <p>必须在任何 Swing 组件创建之前调用（{@code main} 首行）——LAF 安装只影响
 * 之后创建的组件，CEF 窗口的 Swing 壳同吃此主题（浏览内容不受影响）。观感微调
 * （accent/边框/间距）统一走 {@code UIManager} 属性覆盖单点收口，禁散落各组件
 * 硬编码色值。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class EditorTheme {

    private EditorTheme() {
        // 静态门面禁止实例化
    }

    /**
     * 安装 FlatLaf 亮色（幂等——重复调用只是重装同款 LAF）。
     */
    public static void setup() {
        FlatLaf.setup(new FlatLightLaf());
    }
}
