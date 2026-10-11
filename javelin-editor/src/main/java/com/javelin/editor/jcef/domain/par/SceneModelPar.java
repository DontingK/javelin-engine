package com.javelin.editor.jcef.domain.par;


/**
 * /scene/setModel 请求参数（Scene/Game 视图模式切换）。
 *
 * @author javelin
 * @create 2026/10/11
 **/
public class SceneModelPar {
    /** 目标模式（{@link com.javelin.editor.scene.SceneModel} 枚举名："SCENE"/"GAME"） */
    public String model;
}
