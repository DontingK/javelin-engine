package cn.donting.javelin.editor.inspector;

import cn.donting.javelin.core.scene.Component;

/**
 * 「添加组件」候选 VO：组件类型 + 分组头显示信息 + 可添加判据。
 *
 * <p>{@link AddComponentCatalog} 在引擎线程产出、AddComponentDialog（EDT）消费。
 * 不可添加项照常列出（灰显 + {@link #reason}），不隐藏——隐藏会让用户以为组件
 * 不存在（Unity Add Component 灰显口径）。</p>
 *
 * @author javelin
 * @create 2026/11/8
 */
public class AddComponentVo {

    /** 组件类型（候选身份；提交侧按此类型 addComponent） */
    public Class<? extends Component> type;
    /** 显示名（@InsHeader.label 经 EditorLang，兜底类简单名——与检查器分组头同源） */
    public String name;
    /** 分组头图标名（@InsHeader.icon，classpath /icon/{name}.svg；空串无图标） */
    public String icon;
    /** 可添加（false=唯一性冲突，列出但不可选） */
    public boolean addable;
    /** 不可添加原因（null = 可添加） */
    public String reason;
}
