import type { CallbackMessageDataMap } from './messageTypes.ts'

/**
 * Java -> JS 消息推送桥接。
 *
 * <p>后端通过 {@code JavaScriptBridge.send(type, data)} 执行前端
 * {@code window.__javelinBridge.onMessage(type, data)}，将事件主动推送给前端。</p>
 *
 * <p>本模块提供与原有 {@code webSocketLink} 兼容的监听 API，方便组件替换消息来源。</p>
 */

/**
 * JS 桥接消息处理器。
 */
type BridgeMessageHandler = (type: string, data: unknown) => void

declare global {
  interface Window {
    /**
     * Java -> JS 消息桥接对象。
     *
     * <p>本模块负责创建并设置 {@code onMessage}，后端 {@code JavaScriptBridge}
     * 通过执行 JS 调用 {@code window.__javelinBridge.onMessage(type, data)}。</p>
     */
    __javelinBridge?: {
      /** 消息接收函数，由本模块实现并注册到 window。 */
      onMessage?: BridgeMessageHandler
    }
  }
}

const listeners = new Map<string, Set<(data: unknown) => void>>()
const connectionListeners = new Set<(connected: boolean) => void>()

/**
 * 通知连接状态变化。
 *
 * @param connected 是否已连接
 */
function notifyConnectionChanged(connected: boolean): void {
  connectionListeners.forEach((callback) => callback(connected))
}

/**
 * 初始化 JS 桥接消息接收器。
 *
 * <p>将 {@code window.__javelinBridge.onMessage} 设置为本模块的消息分发函数。</p>
 */
function initBridge(): void {
  if (typeof window === 'undefined') {
    return
  }
  if (!window.__javelinBridge) {
    window.__javelinBridge = {}
  }
  window.__javelinBridge.onMessage = (type: string, data: unknown) => {
    console.log(`[JavaScriptBridge] Received message of type ${type}:`, data)
    const typeListeners = listeners.get(type)
    if (typeListeners) {
      typeListeners.forEach((callback) => callback(data))
    }
  }
  // JCEF 桥接在页面加载后即视为可用
  notifyConnectionChanged(true)
}

/**
 * 注册指定类型的消息监听器。
 *
 * @param type 消息类型
 * @param callback 回调函数
 */
function addMsgListener<T extends keyof CallbackMessageDataMap>(
  type: T,
  callback: (data: CallbackMessageDataMap[T]) => void
): void {
  if (!listeners.has(type)) {
    listeners.set(type, new Set())
  }
  listeners.get(type)!.add(callback as (data: unknown) => void)
}

/**
 * 移除指定类型的消息监听器。
 *
 * @param type 消息类型
 * @param callback 回调函数
 */
function removeMsgListener<T extends keyof CallbackMessageDataMap>(
  type: T,
  callback: (data: CallbackMessageDataMap[T]) => void
): void {
  const typeListeners = listeners.get(type)
  if (!typeListeners) {
    return
  }
  typeListeners.delete(callback as (data: unknown) => void)
  if (typeListeners.size === 0) {
    listeners.delete(type)
  }
}

/**
 * 注册桥接连接状态变化监听器。
 *
 * <p>JCEF 桥接没有 WebSocket 的断开概念，初始化完成后即视为已连接。</p>
 *
 * @param callback 连接状态变化回调
 * @returns 取消监听的函数
 */
function onConnectionChange(callback: (connected: boolean) => void): () => void {
  connectionListeners.add(callback)
  callback(true)
  return () => {
    connectionListeners.delete(callback)
  }
}

initBridge()

export default {
  addMsgListener,
  removeMsgListener,
  onConnectionChange
}
