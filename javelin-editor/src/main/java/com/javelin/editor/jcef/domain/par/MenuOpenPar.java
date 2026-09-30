package com.javelin.editor.jcef.domain.par;


/**
 * /menu/open 请求参数。
 *
 * @author javelin
 * @create 2026/9/28
 **/
public class MenuOpenPar {
    /** 菜单域 id */
    public String menuId;
    /** web 坐标 x（标签左缘；web 坐标 = contentPane 坐标，同 rectChange 口径） */
    public int x;
    /** web 坐标 y（顶栏下缘） */
    public int y;
}
