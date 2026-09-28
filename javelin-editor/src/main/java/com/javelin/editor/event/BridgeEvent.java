package com.javelin.editor.event;

/**
 * 跨桥事件接口（Java → JS 方向）。
 *
 * <p>实现本接口的事件具备跨 JCEF 桥推送资格：前端经 /event/subscribe 按
 * {@link #bridgeType()} 订阅后，事件桥转发器以 bridgeType 为消息类型、
 * {@link #bridgePayload()} 为数据推送到前端（window.__javelinBridge.onMessage 分发）。
 * 未实现本接口的事件留在 Java 侧，不外溢。</p>
 *
 * <p>payload 纯数据契约：基本类型/String/Map/List；引用对象只放 {@code obj.getId()}
 * 的 int 值，不放对象本体（Jackson 序列化引擎对象会失败或泄漏大对象图）。前端回传
 * id 后，Java 侧消费方可经 ObjectCache 反查。</p>
 *
 * <p>时序契约：前端订阅发生在页面挂载后，引擎启动期发布的事件不会被推送——
 * 跨桥事件只做边沿通知，状态同步消费方须 pull-on-mount 兜底。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public interface BridgeEvent extends EditorEvent {

    /** 跨桥推送的事件类型字符串（前端订阅键 = JS 消息类型） */
    String bridgeType();

    /** 跨桥推送的 payload（纯数据契约，见接口注释） */
    Object bridgePayload();
}
