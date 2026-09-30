package com.javelin.editor.jcef.domain.vo;

import java.util.ArrayList;
import java.util.List;

/**
 * /scene/tree 场景树快照 VO（引擎线程组装，含选中态对齐）。
 *
 * <p>ready=false = 场景未就绪（loadScene 失败或拉取超时），其余字段无效——
 * 「未就绪」走字段不走异常，避桥层原生弹窗（启动竞态下前端拉取重试兜底）。</p>
 *
 * @author javelin
 * @create 2026/9/30
 **/
public class SceneTreeVo {
    /** 场景就绪标记（false 时其余字段无效，前端重试） */
    public boolean ready;
    /** 场景 id（GameScene ObjectId） */
    public int id;
    /** 场景名（场景根显示名：GameScene.name，空则场景文件名去扩展名） */
    public String name;
    /** 场景脏标记（前端标题 {@code *} 显示源） */
    public boolean dirty;
    /** 当前选中 Node id（EditorSelection 快照，前端拉取后对齐选中态；无选中 null） */
    public Integer selectedNodeId;
    /** 根级节点（层级经 Transform 父子，组装时递归展开） */
    public List<SceneNodeVo> entities;

    /**
     * 未就绪响应（场景缺失/拉取超时）。
     */
    public static SceneTreeVo notReady() {
        SceneTreeVo vo = new SceneTreeVo();
        vo.entities = new ArrayList<>();
        return vo;
    }
}
