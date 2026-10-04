package com.javelin.editor.menu;

import com.javelin.core.appstate.AppStateManager;
import com.javelin.editor.appstate.EditGridAppState;
import com.javelin.editor.menu.hierarchy.CreateEmptyMenuCommand;
import com.javelin.editor.menu.hierarchy.CreatePlaneMenuCommand;
import com.javelin.editor.menu.hierarchy.DeleteMenuCommand;
import com.javelin.editor.menu.hierarchy.UnimplementedMenuCommand;
import com.javelin.editor.menu.top.SaveSceneMenuCommand;
import com.javelin.editor.menu.top.DevToolsMenuCommand;
import com.javelin.editor.menu.top.GridMenuCommand;

/**
 * 菜单/命令启动期装配（JavelinEditor.onInitialize 调用，设计档 Editor_Menu_Design §8）。
 *
 * <p>命令以类形态注册（实现 MenuCommand，域分包：menu.top / menu.hierarchy /
 * menu.project，域基类与该域命令同包）；集中显式注册，不做注解扫描——菜单结构顺序
 * 仍由 MenuRegistry 单源声明。首批「工具」域两命令：tools.devtools /
 * tools.grid（checked 切换网格开关）；「场景树右键」域 Unity 壳对齐（v0.5）：
 * 已实现 hierarchy.create.empty / hierarchy.create.plane（入「创建」子菜单，Unity
 * 3D Object → Plane 口径）/ hierarchy.delete，未实现项
 * hierarchy.copy / hierarchy.paste / hierarchy.rename 以 {@link UnimplementedMenuCommand}
 * 占位注册置灰，功能落地时逐个替换为实类注册行。顶栏菜单域按 Unity 组织，其余域
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
        CommandService.register(new SaveSceneMenuCommand());
        CommandService.register(new DevToolsMenuCommand());
        CommandService.register(new GridMenuCommand(stateManager, gridState));
        CommandService.register(new CreateEmptyMenuCommand());
        CommandService.register(new CreatePlaneMenuCommand());
        CommandService.register(new DeleteMenuCommand());
        // Unity 壳占位（未实现置灰）：copy/paste 阻塞于 .scene 序列化（剪贴板格式），
        // rename 待树内编辑 UI——落地时替换为实类注册行
        CommandService.register(new UnimplementedMenuCommand("hierarchy.copy", "复制", "Ctrl+C"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.paste", "粘贴", "Ctrl+V"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.rename", "重命名", "F2"));

        // 文件域首期仅「保存」（2026-10-01 拍板：另存为/打开/新建场景随下期落地）
        MenuRegistry.menu("file", "文件")
                .item("file.save");

        MenuRegistry.menu("tools", "工具")
                .item("tools.grid")
                .separator()
                .item("tools.devtools");

        MenuRegistry.menu("ctx.hierarchy", "场景树右键")
                .submenu("创建", create -> create
                        .item("hierarchy.create.empty")
                        .item("hierarchy.create.plane"))
                .separator()
                .item("hierarchy.copy")
                .item("hierarchy.paste")
                .separator()
                .item("hierarchy.rename")
                .separator()
                .item("hierarchy.delete");
    }
}
