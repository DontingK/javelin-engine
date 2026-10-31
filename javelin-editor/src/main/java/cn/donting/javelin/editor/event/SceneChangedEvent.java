package cn.donting.javelin.editor.event;

/**
 * 场景变更重拉信号（scene.changed）：编辑命令（创建/删除节点等）在引擎线程改完
 * GameScene 后发布；SceneWatchAppState 结构/图层标签 diff 检出与保存完成亦发
 * （dirty=false）。层级面板订阅后重拉场景树快照（边沿通知，状态以拉取为准）。
 * 仅翻转星标不重拉树的专用信号见 {@link SceneDirtyEvent}（2026-10-11 分工：
 * scene.changed=树内容变更须重拉；scene.dirty=脏翻转仅置星）。
 *
 * <p>发布方在引擎线程，Swing 消费方自行 EDT hop（Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/9/30
 */
public final class SceneChangedEvent implements EditorEvent {

    private final boolean dirty;

    /**
     * @param dirty 变更后的场景脏标记
     */
    public SceneChangedEvent(boolean dirty) {
        this.dirty = dirty;
    }

    /** 变更后的场景脏标记 */
    public boolean isDirty() {
        return dirty;
    }
}
