package com.javelin.core.input;

/**
 * 布尔动作绑定源标记接口 —— {@link KeyCode} / {@link MouseButton} 实现
 * （用户拍板 2026-09-26 补全：动作须能绑鼠标键）。
 *
 * <p>{@link Input#bind} 按本类型收混参：键与鼠标键（将来手柄按钮）
 * 同表混绑，任一命中即动作命中。查询侧按实际类型分发到对应的
 * {@link Input} 通道，增量新增实现不改动 {@code Binding}。</p>
 */
public interface InputCode {
}
