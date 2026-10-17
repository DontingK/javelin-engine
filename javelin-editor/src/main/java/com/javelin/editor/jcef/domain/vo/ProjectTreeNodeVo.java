package com.javelin.editor.jcef.domain.vo;


import java.util.List;

/**
 * /project/tree 树节点 VO（对齐前端 TreeNode {id, name, children}——path 充当 id，
 * 兼作前端图标映射的锚点）。
 *
 * @author javelin
 * @create 2026/10/17
 **/
public class ProjectTreeNodeVo {
    /** 相对项目根路径（/ 分隔） */
    public String path;
    /** 文件名 */
    public String name;
    /** 是否目录 */
    public boolean directory;
    /** 子节点（文件为空列表） */
    public List<ProjectTreeNodeVo> children;
}
