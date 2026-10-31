package cn.donting.javelin.editor.event;

import cn.donting.javelin.editor.plugin.EditorLang;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.function.Consumer;

/**
 * 编辑器事件总线（Java 侧）——异步单线程泵分发。
 *
 * <p>使用契约：</p>
 * <ol>
 *     <li><strong>发布线程无关</strong>：publish 任意线程可调、非阻塞（入队即返回）；
 *     发布方可能是引擎线程、桥线程池、资产导入池或 EDT。</li>
 *     <li><strong>监听器单线程串行</strong>：所有监听器恒在唯一的总线泵线程上按事件到达顺序执行，
 *     监听器之间无需加锁；要动 Swing 自行跳 EDT，要改引擎场景自行走引擎帧队列；
 *     监听器须快速返回（慢监听器会推迟其后所有事件）。</li>
 *     <li><strong>事件不可变</strong>：事件对象发布后不得再修改字段（异步分发存在时间差）。</li>
 * </ol>
 *
 * <p>分发按事件实际类型匹配：沿父类链 + 传递实现的接口收集注册键——订阅具体类只收具体类，
 * 订阅父类型/接口（含 {@link EditorEvent}.class 全量订阅）收其子类。监听器内再 publish
 * 属正常用法：新事件追加队尾、下一轮分发，无递归风险。监听器抛出的异常被隔离
 * （记日志后继续其余监听器），不打断发布方。</p>
 *
 * <p>泵线程为 daemon、首次 publish 时懒启动；{@link #shutdown()} 供编辑器显式收尾。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EditorEventBus {

    private static final Logger LOGGER = LoggerFactory.getLogger(EditorEventBus.class);

    /** 注册表：事件类型 -> 监听器列表（订阅/退订来自任意线程，CoW 迭代安全） */
    private static final Map<Class<?>, CopyOnWriteArrayList<Consumer<EditorEvent>>> LISTENERS =
            new ConcurrentHashMap<>();

    /** 事件队列（无界）：publish 入队，泵线程 take 阻塞等待；所有事件类型共用，跨类型严格 FIFO */
    private static final LinkedBlockingQueue<EditorEvent> QUEUE = new LinkedBlockingQueue<>();

    /** 泵线程（懒启动；volatile：isDispatchThread 无锁读） */
    private static volatile Thread pumpThread;

    /** 关停标志：置位后泵线程退出、publish 拒绝新事件（总线不预期重启） */
    private static volatile boolean shutdown;

    private EditorEventBus() {
        // 静态门面禁止实例化
    }

    /**
     * 发布事件：入队即返回，监听器稍后在泵线程上被回调。
     *
     * @param event 事件对象（发布后不可再修改）
     */
    public static void publish(EditorEvent event) {
        if (event == null) {
            throw new IllegalArgumentException(EditorLang.text("eventBus.nullEvent"));
        }
        if (shutdown) {
            LOGGER.warn("总线已关停，丢弃事件: {}", event.getClass().getSimpleName());
            return;
        }
        ensurePumpStarted();
        QUEUE.offer(event);
    }

    /**
     * 订阅事件：按类型注册监听器，返回退订句柄。
     *
     * <p>监听器在总线泵线程上串行执行（契约见类注释）。同一次事件分发中，
     * 同一监听器注册到多个匹配类型（如具体类 + {@link EditorEvent}.class）会被调用多次。</p>
     *
     * @param type     订阅的事件类型（注册键；可为父类型/接口）
     * @param listener 监听器
     * @return 退订句柄
     */
    public static <E extends EditorEvent> Subscription subscribe(Class<E> type, Consumer<E> listener) {
        if (type == null || listener == null) {
            throw new IllegalArgumentException(EditorLang.text("eventBus.nullTypeListener"));
        }
        @SuppressWarnings("unchecked")
        Consumer<EditorEvent> cast = (Consumer<EditorEvent>) listener;
        CopyOnWriteArrayList<Consumer<EditorEvent>> list =
                LISTENERS.computeIfAbsent(type, k -> new CopyOnWriteArrayList<>());
        list.add(cast);
        LOGGER.debug("订阅事件: {}（当前监听器数: {}）", type.getSimpleName(), list.size());
        return new Subscription(type, cast);
    }

    /**
     * 关停总线：中断泵线程，队列中未分发的事件被丢弃。
     *
     * <p>泵线程为 daemon，进程退出本不受阻；本方法供编辑器显式收尾调用，幂等。</p>
     */
    public static synchronized void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;
        if (pumpThread != null) {
            pumpThread.interrupt();
        }
        LOGGER.info("编辑器事件总线已关停");
    }

    /**
     * 当前线程是否为总线泵线程（监听器内调试/断言辅助）。
     */
    public static boolean isDispatchThread() {
        return Thread.currentThread() == pumpThread;
    }

    /** 退订（Subscription.dispose 调用）：移除监听器，类型无剩余订阅者时清键 */
    static void removeSubscription(Subscription subscription) {
        CopyOnWriteArrayList<Consumer<EditorEvent>> list = LISTENERS.get(subscription.eventType);
        if (list == null) {
            return;
        }
        list.remove(subscription.listener);
        if (list.isEmpty()) {
            // 两参 remove：仅当仍映射到同一 list 才清键，避免与并发 subscribe 竞争误删
            LISTENERS.remove(subscription.eventType, list);
        }
        LOGGER.debug("退订事件: {}（剩余监听器数: {}）", subscription.eventType.getSimpleName(), list.size());
    }

    /** 懒启动泵线程：首次 publish 时创建（只有订阅没有发布不起线程；意外死亡后自愈重启） */
    private static synchronized void ensurePumpStarted() {
        if (shutdown || (pumpThread != null && pumpThread.isAlive())) {
            return;
        }
        pumpThread = new Thread(EditorEventBus::pump, "editor-event-bus");
        pumpThread.setDaemon(true);
        pumpThread.setPriority(Thread.NORM_PRIORITY);
        pumpThread.start();
        LOGGER.info("编辑器事件总线泵线程已启动");
    }

    /** 泵线程主体：取事件 → 分发；shutdown 中断退出 */
    private static void pump() {
        while (!shutdown) {
            EditorEvent event;
            try {
                event = QUEUE.take();
            } catch (InterruptedException e) {
                break;
            }
            dispatch(event);
        }
        LOGGER.info("编辑器事件总线泵线程已退出");
    }

    /** 分发单条事件：收集类型可达的注册键，逐监听器隔离异常回调 */
    private static void dispatch(EditorEvent event) {
        Set<Class<?>> types = new LinkedHashSet<>();
        collectTypes(event.getClass(), types);
        int invoked = 0;
        for (Class<?> type : types) {
            List<Consumer<EditorEvent>> list = LISTENERS.get(type);
            if (list == null || list.isEmpty()) {
                continue;
            }
            for (Consumer<EditorEvent> listener : list) {
                try {
                    listener.accept(event);
                    invoked++;
                } catch (Exception e) {
                    LOGGER.error("事件监听器执行出错 [{}] {}", type.getName(), listener, e);
                }
            }
        }
        LOGGER.debug("分发事件: {}（回调 {} 次）", event.getClass().getSimpleName(), invoked);
    }

    /** 收集事件类型可达的注册键：父类链每一级 + 其传递实现的接口 */
    private static void collectTypes(Class<?> type, Set<Class<?>> out) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            if (!out.add(c)) {
                continue;
            }
            collectInterfaces(c, out);
        }
    }

    /** 传递收集类直接实现的接口 */
    private static void collectInterfaces(Class<?> type, Set<Class<?>> out) {
        for (Class<?> iface : type.getInterfaces()) {
            if (!out.add(iface)) {
                continue;
            }
            collectInterfaces(iface, out);
        }
    }
}
