package com.javelin.editor.inspector;

import java.util.ArrayList;
import java.util.List;

/**
 * Inspector 快照根 VO：选中节点的检视数据（节点头信息 + 组件字段分组）。
 *
 * <p>{@link InspectorReflector} 产出、M4 Swing 属性面板消费（原为 /inspector/info
 * 桥响应，桥已随 2026-10-17 去 Web 化整包退役）。ready=false = 编辑器未就绪/节点不
 * 存在或已被 GC/拉取超时，其余字段无效——「未就绪」走字段不走异常（对齐场景树
 * 快照通道口径，面板重试或清空兜底）。</p>
 *
 * @author javelin
 * @create 2026/10/9
 **/
public class NodeInspectorVo {
    /** 就绪标记（false 时其余字段无效，面板重试或清空） */
    public boolean ready;
    /** 节点 id（M4 实装时随引用化改造为直接持引用——2026-10-18 全链引用化拍板，暂无消费方） */
    public int objId;
    /** 节点名 */
    public String nodeName;
    /** 节点 Layer 索引（0~30 = Layer.getValue()；Unity 口径单层，勿按位掩码 getMask() 语义取值） */
    public int layer;
    /** Layer 名列表（SettingManager 直读投影，索引=层号；空串=未命名槽位，下拉只显示非空） */
    public List<String> layerNames;
    /** 节点 tags 名列表（Node.tags 投影，头部 Tag 下拉当前值；空列表显示内建默认 "Untagged"） */
    public List<String> tags;
    /** Tag 注册表名列表（Tag 下拉选项；含 Unity 内建兜底 "Untagged"，随快照下发） */
    public List<String> tagNames;
    /** 自身激活态（层级生效见 Node.isActiveInHierarchy） */
    public boolean active;
    /** 组件字段分组列表 */
    public List<ComponentInspectorVo> components;

    /**
     * 未就绪响应（编辑器未就绪/节点不存在或已被 GC/拉取超时）。
     */
    public static NodeInspectorVo notReady() {
        NodeInspectorVo vo = new NodeInspectorVo();
        vo.components = new ArrayList<>();
        return vo;
    }
}
