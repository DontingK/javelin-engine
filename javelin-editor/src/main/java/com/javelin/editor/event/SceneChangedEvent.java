package com.javelin.editor.event;

import java.util.Map;

/**
 * 场景结构变更事件（scene.changed）：编辑命令（创建/删除节点等）在引擎线程改完
 * GameScene 后发布，前端层级面板订阅后重新拉取 /scene/tree（边沿通知，状态以
 * 拉取为准——跨桥事件时序契约见 {@link BridgeEvent}）。
 *
 * @author javelin
 * @create 2026/9/30
 */
public final class SceneChangedEvent implements BridgeEvent {

    private final boolean dirty;

    /**
     * @param dirty 变更后的场景脏标记
     */
    public SceneChangedEvent(boolean dirty) {
        this.dirty = dirty;
    }

    @Override
    public String bridgeType() {
        return "scene.changed";
    }

    @Override
    public Object bridgePayload() {
        return Map.of("dirty", dirty);
    }
}
