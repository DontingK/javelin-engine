package com.javelin.editor.swing.inspector.widget;

import javax.swing.JLabel;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * label 拖动调值（Unity scrub 口径）：按住左键左右拖，每像素=目标控件步长
 * （滚轮同一语义），拖动期间实时提交。悬停/拖动显示水平调整光标。
 *
 * @author javelin
 * @create 2026/10/22
 */
public final class ScrubDragger {

    private ScrubDragger() {
    }

    /** 把 label 变成拖动调值手柄（目标=同一行的 {@link JeNumberInput}） */
    public static void attach(JLabel label, JeNumberInput target) {
        MouseAdapter adapter = new MouseAdapter() {
            private boolean dragging;
            private int startX;
            private float startValue;

            @Override
            public void mouseEntered(MouseEvent e) {
                label.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                if (!dragging) {
                    label.setCursor(Cursor.getDefaultCursor());
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                Float current = target.currentValue();
                if (current == null) {
                    return;
                }
                dragging = true;
                startX = e.getXOnScreen();
                startValue = current;
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (!dragging) {
                    return;
                }
                target.scrubTo(startValue + (e.getXOnScreen() - startX) * target.getScrubStep());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                dragging = false;
            }
        };
        label.addMouseListener(adapter);
        label.addMouseMotionListener(adapter);
    }
}
