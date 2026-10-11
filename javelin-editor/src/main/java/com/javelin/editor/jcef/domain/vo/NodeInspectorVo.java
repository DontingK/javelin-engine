package com.javelin.editor.jcef.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * /inspector/info 响应根 VO：选中节点的 Inspector 快照（节点头信息 + 组件字段分组）。
 *
 * <p>ready=false = 编辑器未就绪/节点不存在或已被 GC/拉取超时，其余字段无效——
 * 「未就绪」走字段不走异常（同 {@link SceneTreeVo} 口径，前端重试或清面板兜底）。</p>
 *
 * @author javelin
 * @create 2026/10/9
 **/
public class NodeInspectorVo {
    /** 就绪标记（false 时其余字段无效，前端重试或清面板） */
    public boolean ready;
    /** 节点 id（ObjectCache 反查锚点） */
    public int objId;
    /** 节点名 */
    public String nodeName;
    /** 节点 Layer 索引（0~30 = Layer.getValue()；Unity 口径单层，勿按位掩码 getMask() 语义取值） */
    public int layer;
    /** Layer 名列表（SettingManager 直读投影，索引=层号；空串=未命名槽位，下拉只显示非空） */
    public List<String> layerNames;
    /** 节点 tags 名列表（Node.tags 投影，头部 Tag 下拉当前值；空列表显示内建默认 "Untagged"） */
    public List<String> tags;
    /** Tag 注册表名列表（Tag 下拉选项；含 Unity 内建兜底 "Untagged"，随 info 快照下发） */
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
