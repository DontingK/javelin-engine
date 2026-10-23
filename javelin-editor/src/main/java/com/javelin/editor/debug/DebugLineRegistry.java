package com.javelin.editor.debug;

import com.javelin.core.scene.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * 组件调试线绘制器注册中心（Editor_DebugLine_Design §4）：
 * {@code Class<T> → ComponentDebugLine<T>}，选中节点时逐组件查表分派。
 *
 * <p><b>精确类型匹配</b>（{@code get(component.getClass())}，不做继承链爬升）——
 * 复用走实现类内共享方法，查表隐式回退会让「哪个类画了这条线」不可见
 * （Inspector 注册器同口径）。注册/查询均在引擎线程（编辑器装配与 update 段）。</p>
 *
 * @author javelin
 * @create 2026/10/23
 */
public final class DebugLineRegistry {

    private static final Logger log = LoggerFactory.getLogger(DebugLineRegistry.class);

    private static final Map<Class<?>, ComponentDebugLine<?>> drawers = new HashMap<>();

    private DebugLineRegistry() {
    }

    /**
     * 注册绘制器（重复注册覆盖 + warn——防装配期双注册静默吞）。
     *
     * @param type   绘制的组件类型
     * @param drawer 绘制器
     */
    public static <T extends Component> void register(Class<T> type, ComponentDebugLine<T> drawer) {
        if (type == null || drawer == null) {
            throw new IllegalArgumentException("type / drawer must not be null");
        }
        ComponentDebugLine<?> old = drawers.put(type, drawer);
        if (old != null) {
            log.warn("DebugLine 绘制器覆盖注册: {}", type.getSimpleName());
        }
    }

    /** 查组件的绘制器（精确类型；null = 无绘制器，跳过——常态，不打日志） */
    public static ComponentDebugLine<?> drawerFor(Component component) {
        if (component == null) {
            return null;
        }
        return drawers.get(component.getClass());
    }

    /** 清空注册（DebugLineAppState onDetach 对称退场） */
    public static void clear() {
        drawers.clear();
    }
}
