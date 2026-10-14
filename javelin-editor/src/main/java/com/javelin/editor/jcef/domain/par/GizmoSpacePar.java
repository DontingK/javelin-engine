package com.javelin.editor.jcef.domain.par;


/**
 * /gizmo/setSpace 请求参数（Gizmo 坐标空间切换）。
 *
 * @author javelin
 * @create 2026/10/14
 **/
public class GizmoSpacePar {
    /** 坐标空间："world"=世界轴（默认，现状语义）、"local"=跟随目标局部轴；仅影响 Gizmo 朝向与拖拽轴，Inspector 恒 local */
    public String space;
}
