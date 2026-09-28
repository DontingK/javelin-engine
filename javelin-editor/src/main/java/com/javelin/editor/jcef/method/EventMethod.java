package com.javelin.editor.jcef.method;


import com.javelin.editor.jcef.bridge.BridgeMethod;
import com.javelin.editor.jcef.bridge.EventBridgeForwarder;
import com.javelin.editor.jcef.domain.par.EventPublishPar;
import com.javelin.editor.jcef.domain.par.EventTypePar;

/**
 * 事件桥方法：前端订阅/退订/发布事件（EditorEventBus 跨桥延伸）。
 *
 * @author javelin
 * @create 2026/9/28
 **/
@BridgeMethod("/event")
public class EventMethod {

    @BridgeMethod("/subscribe")
    public void subscribe(EventTypePar par) {
        EventBridgeForwarder.subscribe(par.type);
    }

    @BridgeMethod("/unsubscribe")
    public void unsubscribe(EventTypePar par) {
        EventBridgeForwarder.unsubscribe(par.type);
    }

    @BridgeMethod("/publish")
    public void publish(EventPublishPar par) {
        EventBridgeForwarder.publishFromFrontend(par.type, par.data);
    }
}
