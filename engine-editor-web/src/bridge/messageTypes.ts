/**
 * Java -> JS 推送消息类型定义。
 *
 * <p>对应后端 {@code JavaScriptBridge.send(type, data)} 发送的消息。</p>
 */
export interface CallbackMessageDataMap {
  /** 初始化完成。 */
  'init': null
}
