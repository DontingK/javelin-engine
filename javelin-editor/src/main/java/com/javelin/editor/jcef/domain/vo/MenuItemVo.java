package com.javelin.editor.jcef.domain.vo;

import java.util.List;

/**
 * /menu/list 菜单结构 VO（设计档 Editor_Menu_Design §6）。
 *
 * <p>顶栏拉取时仅填 id/label；右键域拉取时全量即时求值。
 * separator=true 表示分隔符（其余字段无效）。children 为子菜单（结构项：
 * 仅 label + children 递归，无命令 id；null = 叶命令项）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 **/
public class MenuItemVo {
    /** 菜单 id（顶栏拉取）或命令 id（域拉取） */
    public String id;
    /** 显示文本 */
    public String label;
    /** 快捷键文本（可空，第一期仅显示） */
    public String shortcut;
    /** 分隔符项（true 时其余字段无效） */
    public boolean separator;
    /** 是否勾选命令（渲染形态：勾选框） */
    public boolean checkable;
    /** 当前勾选态（仅 checkable 有意义） */
    public boolean checked;
    /** 置灰（即时求值） */
    public boolean enabled;
    /** 子菜单（结构项：仅 label + children 递归，无命令 id；null = 叶命令项） */
    public List<MenuItemVo> children;
}
