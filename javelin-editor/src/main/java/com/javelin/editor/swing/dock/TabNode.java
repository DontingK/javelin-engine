package com.javelin.editor.swing.dock;

import com.javelin.editor.plugin.EditorLang;

import java.util.ArrayList;
import java.util.List;

/**
 * Dock 标签组节点：面板 id 有序列表 + 活动下标（Editor_Swing_Design §5.1）。
 *
 * <p>树只存面板 id——同一面板 id 同一时刻至多出现在一个标签组（关闭=摘除，
 * 恢复入口 M5「窗口」域菜单）。活动项被摘时停原位（右邻顶上），摘的是活动项
 * 之前的项时下标左移。</p>
 *
 * @author javelin
 * @create 2026/10/17
 */
public final class TabNode extends DockNode {

    private final List<String> panelIds;
    private int activeIndex;

    public TabNode(List<String> panelIds) {
        if (panelIds == null || panelIds.isEmpty()) {
            throw new IllegalArgumentException(EditorLang.text("dock.tabNodePanelsRequired"));
        }
        this.panelIds = new ArrayList<>(panelIds);
        this.activeIndex = 0;
    }

    public static TabNode of(String... panelIds) {
        return new TabNode(List.of(panelIds));
    }

    public List<String> getPanelIds() {
        return panelIds;
    }

    public int getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(int index) {
        if (index < 0 || index >= panelIds.size()) {
            throw new IndexOutOfBoundsException("activeIndex: " + index);
        }
        this.activeIndex = index;
    }

    /** 活动面板 id（组已空返回 null） */
    public String getActivePanelId() {
        return activeIndex < panelIds.size() ? panelIds.get(activeIndex) : null;
    }

    /** 摘除面板并修正活动下标（不在组内为 no-op） */
    public void removePanel(String panelId) {
        int index = panelIds.indexOf(panelId);
        if (index < 0) {
            return;
        }
        panelIds.remove(index);
        if (activeIndex >= panelIds.size()) {
            activeIndex = Math.max(0, panelIds.size() - 1);
        } else if (index < activeIndex) {
            activeIndex--;
        }
        // index == activeIndex：活动项被摘——停原位，右邻顶上
    }

    public boolean isEmpty() {
        return panelIds.isEmpty();
    }
}
