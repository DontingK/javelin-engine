package com.javelin.editor.jcef.bridge;


import com.javelin.editor.exception.*;
import com.javelin.editor.util.JSONUtil;
import org.cef.callback.CefQueryCallback;
import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;

import java.lang.reflect.Method;

/**
 * 已注册的 JS -> Java 桥接方法封装。
 *
 * <p>保存方法、目标对象、URL 及参数类型，负责将前端 JSON 请求反序列化后反射调用，
 * 成功时直接序列化返回结果，失败时通过 {@link CefQueryCallback#failure(int, String)} 返回错误。</p>
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
    private final Class<?> methodParameterType;

    /**
     * 构造桥接方法。
     *
     * @param method              目标方法
     * @param object              目标对象实例
     * @param url                 桥接 URL
     * @param methodParameterType 方法参数类型，无参时为 {@code null}
     */
    public JavaBridgeMethod(Method method, Object object, String url, Class<?> methodParameterType) {
        this.method = method;
        this.object = object;
        this.url = url;
        this.methodParameterType = methodParameterType;
    }

    /**
     * 处理前端请求。
     *
     * @param callback JCEF 查询回调
     * @param parSrt  请求字符串，无参时可能为 {@code null} 或空 JSON 对象
     */
    public void handle(CefQueryCallback callback, String parSrt) {
        try {
            Object result;
            if (methodParameterType != null) {
                Object par = JSONUtil.toObject(parSrt, methodParameterType);
                result = method.invoke(object, par);
            } else {
                result = method.invoke(object);
            }
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

