package cn.donting.javelin.editor.event;

/**
 * 编辑器事件标记接口。
 *
 * <p>所有编辑器事件类实现本接口，事件类即 payload（字段 + getter），随消费方任务逐个定义。
 * 事件对象经 {@link EditorEventBus#publish(EditorEvent)} 发布后视为<strong>不可变</strong>：
 * 异步分发存在时间差，发布方不得再修改事件字段。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public interface EditorEvent {
}
