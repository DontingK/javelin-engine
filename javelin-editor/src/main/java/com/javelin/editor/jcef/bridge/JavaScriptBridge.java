package com.javelin.editor.jcef.bridge;


import com.javelin.editor.util.JSONUtil;
import org.cef.browser.CefBrowser;
import org.slf4j.Logger;

/**
 * Java 到 JavaScript 的消息推送桥接。
 *
 * <p>持有当前 {@link CefBrowser} 实例，通过执行 JS 函数 {@code window.__javelinBridge.onMessage}
 * 将后端事件主动推送给前端，替代原有的 WebSocket 服务端推送。</p>
 *
 * @author javelin
 * @version 1.0
 * @create 2026/7/5
 */
public class JavaScriptBridge {

    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(JavaScriptBridge.class);


    private JavaScriptBridge() {
        // 工具类禁止实例化
    }



    /**
     * 向前端推送带数据的消息。
     *
     * @param type 消息类型
     * @param data 消息数据对象
     */
    public static void send(CefBrowser cefBrowser,String type, Object data) {
        String script = buildScript(type, data);
        try {
            cefBrowser.executeJavaScript(script, "", 0);
        } catch (Exception e) {
            LOGGER.error("向前端推送消息失败 [{}]", type, e);
        }
    }
    public static void sendMainFrame(String type) {
//        send(MainFrame.getInstance().getCefBrowser(), type, null);
    }
    public static void sendMainFrame(String type,Object msg) {
//        send(MainFrame.getInstance().getCefBrowser(), type, msg);
    }
    /**
     * 向前端推送无数据消息。
     *
     * @param type 消息类型
     */
    public static void send(CefBrowser cefBrowser,String type) {
        send(cefBrowser,type, null);
    }

    /**
     * 构建调用前端桥接函数的 JS 脚本。
     *
     * @param type 消息类型
     * @param data 消息数据对象
     * @return JS 脚本字符串
     */
    private static String buildScript(String type, Object data) {
        StringBuilder script = new StringBuilder();
        script.append("if(window.__javelinBridge&&window.__javelinBridge.onMessage){");
        script.append("window.__javelinBridge.onMessage('").append(escapeJsString(type)).append("'");
        if (data != null) {
            script.append(",").append(JSONUtil.toJSON(data));
        }
        script.append(")}");
        return script.toString();
    }

    /**
     * 转义 JavaScript 字符串中的特殊字符。
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    private static String escapeJsString(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\")
                .replace("'", "\\'")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}

