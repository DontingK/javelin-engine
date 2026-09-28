package com.javelin.editor.jcef.domain.par;


import java.util.Map;

/**
 * /event/publish 请求参数。
 *
 * @author javelin
 * @create 2026/9/28
 **/
public class EventPublishPar {
    /** 事件类型字符串（前端语义） */
    public String type;
    /** 事件数据（引用对象只传 id 值；可为 null） */
    public Map<String, Object> data;
}
