package cn.donting.javelin.editor.event;

/**
 * 编辑器主窗口重获焦点信号：焦点扫描触发器（Unity Auto Refresh 口径，热重载
 * 批次②）——MainFrame windowGainedFocus 发布，AssetReloadService 订阅后扫描
 * Assets/ 变更自动导入。发布点在 EDT，消费方自行线程安排（IO 走后台）。
 *
 * @author javelin
 * @create 2026-10-27
 */
public final class EditorFocusGainedEvent implements EditorEvent {
}
