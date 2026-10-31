package cn.donting.javelin.editor.event;

/**
 * 项目盘面变更重拉信号（project.changed）：改盘命令（创建资产等）在落盘后发布；
 * 项目面板订阅后重拉文件树快照（边沿通知，状态以拉取为准——与 scene.changed
 * 同一分工口径，面板刷新链随首个改盘命令落地接线，Editor_Design §10 遗留第二批）。
 *
 * <p>发布方在 EDT，Swing 消费方自行 EDT hop（Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/19
 */
public final class ProjectChangedEvent implements EditorEvent {
}
