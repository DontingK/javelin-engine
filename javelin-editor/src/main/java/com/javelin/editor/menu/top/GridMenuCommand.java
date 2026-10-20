package com.javelin.editor.menu.top;

import com.javelin.core.appstate.AppStateManager;
import com.javelin.editor.JavelinEditor;
import com.javelin.editor.appstate.EditGridAppState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网格显示命令（工具域，checked 勾选）：切换 EditGridAppState 挂/卸——
 * onAttach 建网格节点、onDetach 摘除，重挂安全。注册于插件装载期（早于
 * 场景装配），gridState/stateManager 经 JavelinEditor 懒取——场景未就绪
 * 时 null 安全 no-op。
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class GridMenuCommand extends TopMenuCommand {

    private static final Logger LOGGER = LoggerFactory.getLogger(GridMenuCommand.class);

    /**
     * 网格可见性镜像：checked 求值发生在 EDT/桥线程，AppStateManager.states 是
     * 非并发 ArrayList（跨线程 getState 有迭代竞争），故命令自持镜像；
     * CAS 翻转保证并发 toggle 各自生效一次。
     */
    private final AtomicBoolean gridVisible = new AtomicBoolean(true);

    @Override
    public String id() {
        return "tools.grid";
    }

    @Override
    public Boolean checked() {
        return grid() != null && gridVisible.get();
    }

    @Override
    public void execute() {
        EditGridAppState gridState = grid();
        if (gridState == null) {
            LOGGER.warn("编辑器场景未就绪，网格开关不可用");
            return;
        }
        AppStateManager stateManager = JavelinEditor.getInstance().getStateManager();
        if (gridVisible.compareAndSet(false, true)) {
            stateManager.attach(gridState);
        } else if (gridVisible.compareAndSet(true, false)) {
            stateManager.detach(gridState);
        }
    }

    /** 网格状态懒取（编辑器实例未建/场景未就绪返回 null） */
    private EditGridAppState grid() {
        JavelinEditor editor = JavelinEditor.getInstance();
        return editor == null ? null : editor.getGridState();
    }
}
