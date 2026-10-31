package cn.donting.javelin.editor.menu.hierarchy;

import cn.donting.javelin.core.scene.Node;
import cn.donting.javelin.editor.i18n.I18nKey;
import cn.donting.javelin.editor.menu.MenuCommand;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.selection.EditorSelection;

/**
 * 场景树右键域命令基类（设计档 Editor_Menu_Design §3.2）。域分包：场景树域
 * 基类与场景树命令同包（menu.hierarchy），命令随场景树任务落地。
 *
 * <p>提供当前选中 Node 上下文——来源是 Java 侧全局选中状态单源
 * {@link EditorSelection}（场景选中存 Node 引用，2026-10-18 全链引用化拍板；
 * 写入方为层级树点击上报/创建命令自动选中）。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public abstract class HierarchyMenuCommand implements MenuCommand {

    /**
     * 当前选中 Node。
     *
     * @return 无选中或对象已被回收时 null
     */
    protected Node getSelectedNode() {
        return EditorSelection.getSelectedNode();
    }

    /** label 默认（editor.core 约定 label key = 命令 id） */
    @Override
    public I18nKey label() {
        return EditorLang.key(id());
    }
}
