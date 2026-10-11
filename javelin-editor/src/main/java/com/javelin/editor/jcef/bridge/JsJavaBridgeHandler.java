package com.javelin.editor.jcef.bridge;


import com.javelin.editor.util.ClassUtil;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.callback.CefQueryCallback;
import org.cef.handler.CefMessageRouterHandlerAdapter;
import org.slf4j.Logger;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JS -> Java 桥接消息路由器。
 *
 * <p>扫描 {@code com.javelin.editor.jecf} 包下所有带 {@link BridgeMethod} 注解的类与方法，
 * 构建 URL -> {@link JavaBridgeMethod} 映射。前端通过 {@code url::json} 格式发起调用，
 * 后端在线程池中执行并将结果返回。</p>
 *
 * @author: javelin
 * @create: 2026/7/4
 * @Version 1.0
 **/
public class JsJavaBridgeHandler extends CefMessageRouterHandlerAdapter {
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(JsJavaBridgeHandler.class);


    private final Map<String, JavaBridgeMethod> javaBridgeMethodMap = new HashMap<>();
    private final ThreadPoolExecutor threadPoolExecutor;

    public JsJavaBridgeHandler() {
        init();
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger threadNum = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "js-java-bridge-thread-" + threadNum.getAndIncrement());
                thread.setDaemon(true);
                thread.setPriority(Thread.NORM_PRIORITY);
                return thread;
            }
        };
        threadPoolExecutor = new ThreadPoolExecutor(
                8,
                16,
                60L,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(2000),
                threadFactory,
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    /**
     * 扫描并注册所有桥接方法。
     */
    private void init() {
        List<Class<?>> classes = ClassUtil.scanClasses("");
        for (Class<?> clazz : classes) {
            if (Modifier.isAbstract(clazz.getModifiers())) {
                continue;
            }
            BridgeMethod classAnnotation = clazz.getAnnotation(BridgeMethod.class);
            if(classAnnotation==null){
                continue;
            }
            String batchUrl = classAnnotation != null ? classAnnotation.value() : "";
            Object instance;
            try {
                instance = clazz.getDeclaredConstructor().newInstance();
            } catch (Exception e) {
                LOGGER.error("实例化桥接类失败: {}", clazz.getName(), e);
                continue;
            }
            Method[] methods = clazz.getMethods();
            for (Method method : methods) {
                BridgeMethod annotation = method.getAnnotation(BridgeMethod.class);
                if (annotation == null) {
                    continue;
                }
                String url = buildUrl(batchUrl, annotation.value(), method.getName());
                JavaBridgeMethod javaBridgeMethod = new JavaBridgeMethod(method, instance, url);
                if (javaBridgeMethodMap.containsKey(url)) {
                    throw new RuntimeException("Duplicate url: " + url);
                }
                LOGGER.info("注册JS->Java 方法: {} -> {}", url, javaBridgeMethod);
                javaBridgeMethodMap.put(url, javaBridgeMethod);
            }
        }
    }

    /**
     * 根据类前缀、方法注解值构建完整 URL。
     *
     * @param batchUrl   类前缀
     * @param methodUrl  方法注解值
     * @param methodName 方法名（注解值为空时的默认 URL）
     * @return 完整 URL
     */
    private String buildUrl(String batchUrl, String methodUrl, String methodName) {
        if (batchUrl == null) {
            batchUrl = "";
        }
        batchUrl = batchUrl.trim();
        if (batchUrl.endsWith("/")) {
            batchUrl = batchUrl.substring(0, batchUrl.length() - 1);
        }
        if (methodUrl == null || methodUrl.isBlank()) {
            methodUrl = methodName;
        }
        methodUrl = methodUrl.trim();
        if (!methodUrl.startsWith("/")) {
            methodUrl = "/" + methodUrl;
        }
        return batchUrl + methodUrl;
    }

    @Override
    public boolean onQuery(CefBrowser browser, CefFrame frame, long queryId, String par, boolean persistent, CefQueryCallback callback) {
        int i = par.indexOf("::");
        if (i < 0) {
            callback.failure(400, "Invalid request format, expected url::json");
            return true;
        }
        String url = par.substring(0, i);
        String paramJson = par.substring(i + 2);
        JavaBridgeMethod javaBridgeMethod = javaBridgeMethodMap.get(url);
        if (javaBridgeMethod != null) {
            threadPoolExecutor.execute(() -> javaBridgeMethod.handle(callback, paramJson, browser));
        } else {
            callback.failure(404, "Method not found: " + url);
        }
        return true;
    }

    @Override
    public void onQueryCanceled(CefBrowser browser, CefFrame frame, long queryId) {
        // 查询取消时无需特殊处理
    }
}
