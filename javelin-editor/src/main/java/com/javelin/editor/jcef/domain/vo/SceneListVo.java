package com.javelin.editor.jcef.domain.vo;

import java.util.List;

/**
 * 场景列表视图（资产索引枚举）。
 *
 * @author javelin
 * @version 1.0
 * @create 2026/10/11
 **/
public class SceneListVo {
    /** 项目场景相对路径列表（资产索引 .scene 枚举，字典序） */
    public List<String> scenes;
    /** 项目默认场景（ProjectSettings.defaultOpenScene，可空） */
    public String defaultScene;

    public SceneListVo(List<String> scenes, String defaultScene) {
        this.scenes = scenes;
        this.defaultScene = defaultScene;
    }
}
