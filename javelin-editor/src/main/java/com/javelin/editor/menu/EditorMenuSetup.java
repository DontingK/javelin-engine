package com.javelin.editor.menu;

import com.javelin.core.appstate.AppStateManager;
import com.javelin.editor.appstate.EditGridAppState;
import com.javelin.editor.menu.top.DevToolsMenuCommand;
import com.javelin.editor.menu.top.GridMenuCommand;

/**
 * 菜单/命令启动期装配（JavelinEditor.onInitialize 调用，设计档 Editor_Menu_Design §8）。
 *
 * <p>命令以类形态注册（实现 MenuCommand，域分包：menu.top / menu.hierarchy /
 * menu.project，域基类与该域命令同包）；集中显式注册，不做注解扫描——菜单结构顺序
 * 仍由 MenuRegistry 单源声明。首批「工具」域两命令：tools.devtools /
 * tools.grid（checked 切换网格开关）。顶栏菜单域按 Unity 组织，其余域
 * 随功能落地逐域补。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EditorMenuSetup {

    private EditorMenuSetup() {
        // 静态装配禁止实例化
    }

    /**
     * 装配菜单与命令。
     *
     * @param stateManager 状态管理器（网格开关 attach/detach，挂卸线程安全走帧边界）
     * @param gridState    已挂载的网格状态；场景未就绪时传 null——网格命令退化为
     *                    安全 no-op（checked 恒 false，execute 记 warn）
     */
    public static void setup(AppStateManager stateManager, EditGridAppState gridState) {
        CommandService.register(new DevToolsMenuCommand());
        CommandService.register(new GridMenuCommand(stateManager, gridState));

        MenuRegistry.menu("tools", "工具")
                .item("tools.grid")
                .separator()
                .item("tools.devtools");
    }
}
