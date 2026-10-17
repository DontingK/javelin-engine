package com.javelin.editor.jcef.domain.vo;


import java.util.List;

/**
 * /project/tree 项目文件树 VO。
 *
 * <p>ready=false = 编辑器实例未就绪（启动竞态：页面挂载可能早于引擎装配），
 * 其余字段无效——前端节流重试，对齐 SceneTreeVo.notReady 口径。</p>
 *
 * @author javelin
 * @create 2026/10/17
 **/
public class ProjectTreeVo {
    /** 就绪标记 */
    public boolean ready;
    /** 根节点（Assets、src；项目结构不完整的根静默缺席） */
    public List<ProjectTreeNodeVo> roots;

    /** 未就绪哨兵（编辑器实例 null） */
    public static ProjectTreeVo notReady() {
        ProjectTreeVo vo = new ProjectTreeVo();
        vo.ready = false;
        return vo;
    }
}
