package com.javelin.editor.jcef.domain.vo;

import java.util.List;

/**
 * /scene/tree 树节点 VO（对齐前端 TreeNode {id, name, children}，extra 字段渐进消费）。
 *
 * @author javelin
 * @create 2026/9/30
 **/
public class SceneNodeVo {
    /** Node id（ObjectId 全局唯一，前端选中上报/ObjectCache 反查的锚点） */
    public int id;
    /** 节点名（Unity GameObject 名） */
    public String name;
    /** 自身激活态（层级生效见 Node.isActiveInHierarchy；inactive 灰显留前端渐进） */
    public boolean active;
    /** 子节点 */
    public List<SceneNodeVo> children;
}
