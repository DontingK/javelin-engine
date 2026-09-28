package com.javelin.editor.event;

import java.util.Map;

/**
 * 桥自检回声事件（bridge.pong）：前端 publishEvent("bridge.ping") 触发，
 * 事件桥转发器回发本事件，验证 Java→JS 推送链路；兼作桥链路永久诊断。
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class PongEvent implements BridgeEvent {

    @Override
    public String bridgeType() {
        return "bridge.pong";
    }

    @Override
    public Object bridgePayload() {
        return Map.of("ok", true);
    }
}
