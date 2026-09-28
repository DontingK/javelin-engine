package com.javelin.editor.event;

import com.javelin.core.obj.ObjectCache;
import com.javelin.core.obj.ObjectId;

import java.util.Map;

/**
 * 前端发布事件（JS → Java 方向）的桥包装。
 *
 * <p>前端经 /event/subscribe 的姊妹方法 /event/publish {type, data} 发布，
 * 包装为本事件进入 {@link EditorEventBus}。Java 消费方
 * {@code EditorEventBus.subscribe(JsEvent.class, e -> ...)} 按 {@link #getType()}
 * 字符串过滤。</p>
 *
 * <p>data 为 Jackson 反序列化的 Map（数字可能是 Integer/Double，经 {@link #getInt}
 * 归一）；引用对象前端只传 id 值，{@link #getObject(String)} 经 ObjectCache 反查
 * （弱引用，对象已不可达或 id 无效返回 null，消费方自行判空）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class JsEvent implements EditorEvent {

    private final String type;
    private final Map<String, Object> data;

    public JsEvent(String type, Map<String, Object> data) {
        this.type = type;
        this.data = data;
    }

    /** 事件类型字符串（前端语义，消费方按此过滤） */
    public String getType() {
        return type;
    }

    /** 事件数据（前端未传 data 时为 null） */
    public Map<String, Object> getData() {
        return data;
    }

    /**
     * 取 int 字段（Jackson 数字会是 Integer/Double，按 Number 归一）。
     *
     * @return 值缺失或非数字时 null
     */
    public Integer getInt(String key) {
        Object value = value(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return null;
    }

    /**
     * 取 String 字段。
     *
     * @return 值缺失或非字符串时 null
     */
    public String getString(String key) {
        Object value = value(key);
        return value instanceof String ? (String) value : null;
    }

    /**
     * 取对象字段：data 里的 id 经 {@link ObjectCache} 反查对象本体。
     *
     * @return id 缺失/非数字/对象已被回收时 null
     */
    public <T extends ObjectId> T getObject(String key) {
        Integer id = getInt(key);
        return id == null ? null : ObjectCache.get(id);
    }

    private Object value(String key) {
        return data == null ? null : data.get(key);
    }
}
