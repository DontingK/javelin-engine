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
import com.javelin.editor.menu.top.RefreshPageMenuCommand;

/**
 * 菜单/命令启动期装配（JavelinEditor.onInitialize 调用，设计档 Editor_Menu_Design §8）。
 *
 * <p>命令以类形态注册（实现 MenuCommand，域分包：menu.top / menu.hierarchy /
 * menu.project，域基类与该域命令同包）；集中显式注册，不做注解扫描——菜单结构顺序
 * 仍由 MenuRegistry 单源声明。首批「工具」域三命令：tools.devtools /
 * tools.grid（checked 切换网格开关）/ tools.refresh（刷新 web 页）；「场景树右键」域 Unity 壳对齐（结构
 * 2026-10-11 重排）：已实现 hierarchy.delete / hierarchy.create.empty（顶级
 * 「空对象」）/ hierarchy.create.plane（「3D 对象」子菜单）；剪切/复制/粘贴、
 * 3D 对象其余项、灯光/粒子/摄像机以 {@link UnimplementedMenuCommand} 占位注册
 * 置灰，功能落地时逐个替换为实类注册行（rename 随树内编辑 UI 后置，未入菜单）。
 * 顶栏菜单域按 Unity 组织，其余域随功能落地逐域补。</p>
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
        CommandService.register(new RefreshPageMenuCommand());
        CommandService.register(new CreateEmptyMenuCommand());
        CommandService.register(new CreatePlaneMenuCommand());
        CommandService.register(new DeleteMenuCommand());
        // Unity 壳占位（未实现置灰，落地时替换为实类注册行）：剪贴板三项阻塞于
        // .scene 序列化（剪贴板格式）；3D 对象其余项/灯光/粒子/摄像机阻塞于对应组件
        CommandService.register(new UnimplementedMenuCommand("hierarchy.cut", "剪切", "Ctrl+X"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.copy", "复制", "Ctrl+C", "copy"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.paste", "粘贴", "Ctrl+V", "paste"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.cube", "立方体", null, "box"));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.sphere", "球", null));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.pyramid", "三角锥", null));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.light.directional", "定向光", null));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.light.point", "点光源", null));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.particle", "粒子", null));
        CommandService.register(new UnimplementedMenuCommand("hierarchy.create.camera", "摄像机", null, "camera"));

        // 文件域首期仅「保存」（2026-10-01 拍板：另存为/打开/新建场景随下期落地）
        MenuRegistry.menu("file", "文件")
                .item("file.save");

        MenuRegistry.menu("tools", "工具")
                .item("tools.grid")
                .separator()
                .item("tools.refresh")
                .item("tools.devtools");

        MenuRegistry.menu("ctx.hierarchy", "场景树右键")
                .item("hierarchy.cut")
                .item("hierarchy.copy")
                .item("hierarchy.paste")
                .separator()
                .item("hierarchy.delete")
                .separator()
                .item("hierarchy.create.empty")
                .submenu("3D 对象", create -> create
                        .item("hierarchy.create.plane")
                        .item("hierarchy.create.cube")
                        .item("hierarchy.create.sphere")
                        .item("hierarchy.create.pyramid"))
                .submenu("灯光", light -> light
                        .item("hierarchy.create.light.directional")
                        .item("hierarchy.create.light.point"))
                .item("hierarchy.create.particle")
                .item("hierarchy.create.camera");
    }
}
