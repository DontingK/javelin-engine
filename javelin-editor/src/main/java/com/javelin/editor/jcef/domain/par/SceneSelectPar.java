package com.javelin.editor.jcef.domain.par;


/**
 * /scene/select 请求参数（场景树选中上报；null = 清除——点击场景根/取消选中）。
 *
 * @author javelin
 * @create 2026/9/30
 **/
public class SceneSelectPar {
    /** 选中 Node id；null 清除 */
    public Integer nodeId;
}
