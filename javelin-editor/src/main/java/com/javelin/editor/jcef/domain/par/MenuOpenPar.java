package com.javelin.editor.jcef.domain.par;


/**
 * /menu/open 请求参数。
 *
 * @author javelin
 * @create 2026/9/28
 **/
public class MenuOpenPar {
    /** 菜单域 id（顶栏菜单 id 或右键域如 ctx.hierarchy） */
    public String menuId;
    /** web 坐标 x（顶栏=标签左缘、右键=鼠标 x；web 坐标 = contentPane 坐标，同 rectChange 口径） */
    public int x;
    /** web 坐标 y（顶栏=顶栏下缘、右键=鼠标 y） */
    public int y;
}
