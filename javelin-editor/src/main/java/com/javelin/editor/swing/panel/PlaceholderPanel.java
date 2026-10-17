package com.javelin.editor.swing.panel;

import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BorderLayout;

/**
 * 占位面板（M1 骨架期——五面板注册未实装，Editor_Swing_Design §9 里程碑）。
 *
 * <p>各面板随 M2~M4 实装替换 factory；标题与实装里程碑由注册处给定。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public class PlaceholderPanel extends JComponent implements EditorPanel {

    public PlaceholderPanel(String title, String milestone) {
        setLayout(new BorderLayout());
        JLabel label = new JLabel(title + "（" + milestone + " 实装）", SwingConstants.CENTER);
        java.awt.Color dim = UIManager.getColor("Label.disabledForeground");
        if (dim != null) {
            label.setForeground(dim);
        }
        add(label, BorderLayout.CENTER);
    }
}
