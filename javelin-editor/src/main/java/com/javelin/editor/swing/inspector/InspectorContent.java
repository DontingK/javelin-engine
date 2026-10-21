package com.javelin.editor.swing.inspector;

import javax.swing.JComponent;

/**
 * 检查器内容件：检查器外壳（{@link InspectorPanel}）按选中分发挂载的一类目标
 * 展示内容（Unity 多态 Inspector 口径）。
 *
 * <p>生命周期契约（外壳调序恒定，全 EDT）：create 后 onAttach → onSelection
 * （同类目标间切换再调）→ 换内容/清空前 onDetach。内容件自管线程（帧队列/
 * 后台 IO 先例）；事件订阅 onAttach 订、onDetach 退（Subscription.dispose）。
 * 目标为空不达 onSelection——空态归外壳。</p>
 *
 * @author javelin
 * @create 2026/10/21
 */
public interface InspectorContent {

    /** 内容根组件（外壳挂进面板 CENTER；含自身滚动结构） */
    JComponent component();

    /** 挂载（订阅事件等） */
    default void onAttach() {
    }

    /** 摘除（退订；外壳换内容/清空前调） */
    default void onDetach() {
    }

    /** 选中目标变更（同 provider 内切换：node→node、.mat→.mat） */
    default void onSelection(InspectorSelection selection) {
    }
}
