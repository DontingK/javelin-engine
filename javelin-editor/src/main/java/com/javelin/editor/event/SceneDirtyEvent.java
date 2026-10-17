package com.javelin.editor.event;

/**
 * 场景脏标记翻转信号（scene.dirty）：{@code EditorScene.markDirty} 由净变脏的
 * 翻转沿发布（用户拍板 2026-10-11「修改任何属性都应加 *」——Inspector 属性回写/
 * 变换检出/命令置脏都必经 markDirty，翻转即星标事件）。
 *
 * <p>与 {@link SceneChangedEvent} 分工：scene.changed = 树内容变更须重拉整树；
 * scene.dirty 仅指示消费方<b>本地镜像置星标</b>，不重拉树（树内容未变，仅为一个
 * {@code *} 重建整树不值）。已脏重复置脏不发布（每脏周期至多一响，滑条连续回写
 * 无事件风暴）。熄灭归保存路径 clearDirty + scene.changed(false)（dirty 单一真源），
 * 本事件恒 dirty=true 无熄灭形态。</p>
 *
 * <p>边沿通知无载荷；发布方在引擎线程，Swing 消费方自行 EDT hop
 * （Editor_Swing_Design §7.3）。</p>
 *
 * @author javelin
 * @create 2026/10/11
 */
public final class SceneDirtyEvent implements EditorEvent {
}
