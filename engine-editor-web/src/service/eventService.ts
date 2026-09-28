import { callJava } from '../bridge/jcefBridge.ts'
import javaScriptBridge from '../bridge/javaScriptBridge.ts'

/**
 * 事件处理器。
 */
type EventHandler<T> = (data: T) => void

/** 本地各类型已注册的处理器（引用计数，归零才退订 Java 侧） */
const handlers = new Map<string, Set<EventHandler<unknown>>>()

/**
 * 订阅事件：本地注册处理器并通知 Java 侧订阅该类型。
 *
 * <p>处理器收到的是 Java 侧 BridgeEvent 的 bridgePayload（纯数据；引用对象以
 * id 值传递，Java 侧消费方经 ObjectCache 反查）。引擎启动期发布的事件不会被推送
 * （订阅发生在页面挂载后），状态同步须另行拉取兜底。</p>
 *
 * @param type 事件类型（= Java 侧 bridgeType）
 * @param handler 处理器
 */
export async function subscribeEvent<T = unknown>(type: string, handler: EventHandler<T>): Promise<void> {
  javaScriptBridge.addMsgListener(type, handler as EventHandler<unknown>)
  let set = handlers.get(type)
  if (set == null) {
    set = new Set()
    handlers.set(type, set)
  }
  set.add(handler as EventHandler<unknown>)
  await callJava('/event/subscribe', {type})
}

/**
 * 退订事件：移除本地处理器；该类型本地监听归零时才退订 Java 侧
 * （多组件共用同一类型时防止误退订）。
 *
 * @param type 事件类型
 * @param handler 处理器
 */
export async function unsubscribeEvent<T = unknown>(type: string, handler: EventHandler<T>): Promise<void> {
  const set = handlers.get(type)
  if (set == null) {
    return
  }
  javaScriptBridge.removeMsgListener(type, handler as EventHandler<unknown>)
  set.delete(handler as EventHandler<unknown>)
  if (set.size === 0) {
    handlers.delete(type)
    await callJava('/event/unsubscribe', {type})
  }
}

/**
 * 发布事件到 Java 侧事件总线（包装为 JsEvent 进入总线）。
 *
 * <p>引用对象只传 id 值，Java 侧消费方可经 ObjectCache 反查。</p>
 *
 * @param type 事件类型
 * @param data 事件数据
 */
export async function publishEvent(type: string, data?: Record<string, unknown>): Promise<void> {
  await callJava('/event/publish', {type, data: data ?? {}})
}
