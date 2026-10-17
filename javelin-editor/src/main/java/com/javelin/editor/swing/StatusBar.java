package com.javelin.editor.swing;

import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import java.awt.BorderLayout;

/**
 * 主窗口底部状态栏（M1 简版——单条只读文本，Editor_Swing_Design §9 里程碑 M1）。
 *
 * <p>内容通道（选中对象/资产统计/构建状态等）随面板实装批次接入；间距守
 * 常驻布局 ≤2 规范。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class StatusBar extends JComponent {

    private final JLabel label = new JLabel("就绪");

    public StatusBar() {
        setLayout(new BorderLayout(0, 0));
        label.setBorder(BorderFactory.createEmptyBorder(1, 2, 1, 2));
        add(label, BorderLayout.WEST);
    }

    /**
     * 设置状态文本。
     *
     * @param text 文本（null 回退「就绪」）
     */
    public void setText(String text) {
        label.setText(text == null || text.isBlank() ? "就绪" : text);
    }
}
