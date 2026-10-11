package com.javelin.editor.jcef.bridge;


import com.javelin.editor.exception.*;
import com.javelin.editor.util.JSONUtil;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefQueryCallback;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 已注册的 JS -> Java 桥接方法封装。
 *
 * <p>保存方法、目标对象、URL 及参数布局，负责将前端 JSON 请求反序列化后反射调用，
 * 成功时直接序列化返回结果，失败时通过 {@link CefQueryCallback#failure(int, String)} 返回错误。</p>
 *
 * <p>参数约定（2026-10-11）：方法可声明两类参数——JSON 请求参数（前端
 * {@code url::json} 反序列化，至多一个）与 {@link CefBrowser} 参数（注入
 * <b>当前调用者的浏览器实例</b>，至多一个，位置任意），后者供桥方法经
 * {@code JavaScriptBridge.send(browser, type, data)} 向调用页实时推送。</p>
 *
 * @author: javelin
 * @create: 2026/7/4
 * @Version 1.0
 **/
public class JavaBridgeMethod {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(JavaBridgeMethod.class);

    private final Method method;
    private final Object object;
    private final String url;
    private final Class<?>[] parameterTypes;
    /** CefBrowser 参数下标（注入调用者浏览器），无则 -1；其余参数（至多一个）走 JSON 反序列化 */
    private final int browserIndex;

    /**
     * 构造桥接方法。
     *
     * @param method 目标方法
     * @param object 目标对象实例
     * @param url    桥接 URL
     * @throws IllegalStateException 参数布局非法（CefBrowser/JSON 参数超过一个）
     */
    public JavaBridgeMethod(Method method, Object object, String url) {
        this.method = method;
        this.object = object;
        this.url = url;
        this.parameterTypes = method.getParameterTypes();
        int browser = -1;
        int par = -1;
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i] == CefBrowser.class) {
                if (browser >= 0) {
                    throw new IllegalStateException("桥方法最多声明一个 CefBrowser 参数: " + method);
                }
                browser = i;
            } else {
                if (par >= 0) {
                    throw new IllegalStateException("桥方法最多声明一个 JSON 请求参数: " + method);
                }
                par = i;
            }
        }
        this.browserIndex = browser;
    }

    /**
     * 处理前端请求。
     *
     * @param callback JCEF 查询回调
     * @param parSrt  请求 JSON 字符串（无 JSON 参数的方法不消费）
     * @param browser 当前调用者的浏览器实例（方法声明 CefBrowser 参数时注入）
     */
    public void handle(CefQueryCallback callback, String parSrt, CefBrowser browser) {
        try {
            Object[] args = new Object[parameterTypes.length];
            for (int i = 0; i < parameterTypes.length; i++) {
                if (i == browserIndex) {
                    args[i] = browser;
                } else {
                    args[i] = JSONUtil.toObject(parSrt, parameterTypes[i]);
                }
            }
            Object result = method.invoke(object, args);
            callback.success(JSONUtil.toJSON(result));
        } catch (TipException | ValidationException e) {
            LOGGER.warn("业务提示异常 [{}]: {}", url, e.getMessage());
            callback.failure(400, e.getMessage());
            TinyFileDialogs.tinyfd_messageBox("错误", e.getMessage(),
                    "ok", "warning", 1);
        } catch (ProjectException | AssetException | SceneException | SettingsException e) {
            LOGGER.warn("领域操作异常 [{}]: {}", url, e.getMessage());
            callback.failure(422, e.getMessage());
            TinyFileDialogs.tinyfd_messageBox("错误", e.getMessage(),
                    "ok", "error", 1);
        } catch (BuildException e) {
            LOGGER.error("构建异常 [{}]: {}", url, e.getMessage());
            callback.failure(502, e.getMessage());
            TinyFileDialogs.tinyfd_messageBox("错误", e.getMessage(),
                    "ok", "error", 1);
        } catch (Exception e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            LOGGER.error("调用桥接方法出错 [{}]", url, cause);
            String message = cause instanceof TipException || cause instanceof ValidationException
                    || cause instanceof ProjectException || cause instanceof AssetException
                    || cause instanceof SceneException || cause instanceof SettingsException
                    || cause instanceof BuildException
                    ? cause.getMessage() : "调用失败: " + cause.getMessage();
            callback.failure(500, message);
            TinyFileDialogs.tinyfd_messageBox("错误", cause.getMessage(),
                    "ok", "error", 1);
        }
    }

    @Override
    public String toString() {
        return object.getClass().getName() + "#" + method.getName();
    }
}
