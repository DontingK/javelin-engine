package com.javelin.editor.jcef.bridge;

import com.javelin.editor.event.BridgeEvent;
import com.javelin.editor.event.EditorEvent;
import com.javelin.editor.event.EditorEventBus;
import com.javelin.editor.event.JsEvent;
import com.javelin.editor.event.PongEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 事件桥转发器：{@link EditorEventBus} → 前端。
 *
 * <p>前端经 /event/subscribe 记录订阅类型（幂等）；总线全局订阅（EditorEvent.class
 * 层级匹配收到所有事件）后，实现 {@link BridgeEvent} 且类型已订阅的事件被推送到前端
 * （消息类型 = bridgeType，数据 = bridgePayload，经 window.__javelinBridge.onMessage
 * 分发）。</p>
 *
 * <p><b>2026-10-17 起（Editor_Swing_Design M1）主窗口已非 CEF，无 JS 推送对象，
 * onEvent 推送通道停用</b>；订阅簿记与 ping/pong 自检保留（CEF 辅窗口仍走桥协议），
 * 类整体随 M6（CEF 窗口全迁/依赖移除）拆除。</p>
 *
 * <p>浏览器未就绪（启动早期 MainFrame/getCefBrowser 为 null）时跳过推送：
 * 前端订阅发生在页面挂载后，引擎启动期发布的事件不会被推送——跨桥事件只做边沿通知，
 * 状态同步消费方须 pull-on-mount 兜底。</p>
 *
 * <p>内置 ping/pong 自检：前端 publishEvent("bridge.ping") → 回
 * {@link PongEvent}（bridge.pong），兼作桥链路永久诊断。总线全局订阅不退订，
 * 与总线同生命周期。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EventBridgeForwarder {

    private static final Logger LOGGER = LoggerFactory.getLogger(EventBridgeForwarder.class);

    /** 前端已订阅的事件类型（幂等 add/remove；空类型入口已挡，null 键不受 ConcurrentHashMap 支持） */
    private static final Set<String> SUBSCRIBED_TYPES = ConcurrentHashMap.newKeySet();

    /** 总线订阅懒建标志（synchronized 守护） */
    private static boolean subscribed;

    private EventBridgeForwarder() {
        // 静态门面禁止实例化
    }

    /**
     * 前端订阅事件类型（/event/subscribe，桥线程调用）。
     *
     * @param type 事件类型字符串
     */
    public static void subscribe(String type) {
        if (invalidType(type)) {
            return;
        }
        ensureBusSubscription();
        SUBSCRIBED_TYPES.add(type);
    }

    /**
     * 前端退订事件类型（/event/unsubscribe，桥线程调用）。
     *
     * @param type 事件类型字符串
     */
    public static void unsubscribe(String type) {
        if (invalidType(type)) {
            return;
        }
        SUBSCRIBED_TYPES.remove(type);
    }

    /**
     * 前端发布事件（/event/publish，桥线程调用）：包装为 {@link JsEvent} 进入总线。
     *
     * @param type 事件类型字符串
     * @param data 事件数据（可为 null）
     */
    public static void publishFromFrontend(String type, Map<String, Object> data) {
        if (invalidType(type)) {
            return;
        }
        ensureBusSubscription();
        EditorEventBus.publish(new JsEvent(type, data));
    }

    /** 懒建全局订阅（synchronized：桥线程池并发首调竞争） */
    private static synchronized void ensureBusSubscription() {
        if (subscribed) {
            return;
        }
        subscribed = true;
        EditorEventBus.subscribe(EditorEvent.class, EventBridgeForwarder::onEvent);
        EditorEventBus.subscribe(JsEvent.class, EventBridgeForwarder::onJsEvent);
    }

    /** 总线事件回调（泵线程）：BridgeEvent 且类型已订阅 → EDT 推送前端 */
    private static void onEvent(EditorEvent event) {
        // 主窗口已迁 Swing（Editor_Swing_Design M1）：无 JS 推送对象——停用；
        // 原 EDT executeJavaScript 推送链路见 M1 前版本。M6 随 CEF 退役拆除。
    }

    /** 前端事件回调（泵线程）：ping 自检回 pong */
    private static void onJsEvent(JsEvent event) {
        if ("bridge.ping".equals(event.getType())) {
            LOGGER.info("收到前端事件 bridge.ping，回 pong");
            EditorEventBus.publish(new PongEvent());
        }
    }

    private static boolean invalidType(String type) {
        if (type == null || type.isBlank()) {
            LOGGER.warn("事件类型为空，忽略");
            return true;
        }
        return false;
    }
}
