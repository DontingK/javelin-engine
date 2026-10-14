package com.javelin.editor.jcef.domain.par;


/**
 * /gizmo/setTool 请求参数（变换工具切换）。
 *
 * @author javelin
 * @create 2026/10/14
 **/
public class GizmoToolPar {
    /** 目标工具名（TransformTools.getName 口径，线上惯例小写；当前仅 "move"——旋转/缩放工具未落地） */
    public String tool;
}
