/**
 * Java -> JS 推送消息类型定义。
 *
 * <p>对应后端 {@code JavaScriptBridge.send(type, data)} 发送的消息。</p>
 */
export interface CallbackMessageDataMap {
  /** 初始化完成。 */
  'init': null
  /** 动态事件类型（/event 协议）：键 = Java 侧 BridgeEvent 的 bridgeType()，数据 = bridgePayload。 */
  [eventType: string]: unknown
}
