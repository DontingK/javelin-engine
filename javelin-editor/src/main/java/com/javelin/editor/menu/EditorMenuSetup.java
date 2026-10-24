package com.javelin.editor.menu;

import com.javelin.editor.menu.hierarchy.CreateBoxMenuCommand;
import com.javelin.editor.menu.hierarchy.CreateCameraMenuCommand;
import com.javelin.editor.menu.hierarchy.CreateEmptyMenuCommand;
import com.javelin.editor.menu.hierarchy.CreatePlaneMenuCommand;
import com.javelin.editor.menu.hierarchy.DeleteMenuCommand;
import com.javelin.editor.menu.hierarchy.UnimplementedMenuCommand;
import com.javelin.editor.menu.project.CopyPathMenuCommand;
import com.javelin.editor.menu.project.CreateMaterialMenuCommand;
import com.javelin.editor.menu.project.OpenFileMenuCommand;
import com.javelin.editor.menu.project.ReimportMenuCommand;
import com.javelin.editor.menu.project.ShowInExplorerMenuCommand;
import com.javelin.editor.menu.top.CompileMenuCommand;
import com.javelin.editor.menu.top.SaveSceneMenuCommand;
import com.javelin.editor.menu.top.GridMenuCommand;
import com.javelin.editor.menu.top.PackMenuCommand;
import com.javelin.editor.plugin.EditorLang;
import com.javelin.editor.plugin.PluginContext;

/**
 * 菜单/命令启动期装配（EditorCorePlugin.setup 调用——插件装载期早于建窗，
 * 设计档 Editor_Menu_Design §8）。
 *
 * <p>命令以类形态注册（实现 MenuCommand，域分包：menu.top / menu.hierarchy /
 * menu.project，域基类与该域命令同包）；集中显式注册，不做注解扫描——菜单结构顺序
 * 仍由 MenuRegistry 单源声明。「工具」域首期：tools.grid（checked 切换网格开关）；
 * tools.devtools/tools.refresh 随主窗口去 Web 化退役（2026-10-17，Editor_Swing_Design）。
 * 「场景树右键」域 Unity 壳对齐（结构
 * 2026-10-11 重排）：已实现 hierarchy.delete / hierarchy.create.empty（顶级
 * 「空对象」）/ hierarchy.create.plane、hierarchy.create.cube（「3D 对象」
 * 子菜单，cube 2026-10-24 落地）/ hierarchy.create.camera
 * （「摄像机」，2026-10-11 落地）；剪切/复制/粘贴、3D 对象其余项、灯光/粒子以
 * {@link UnimplementedMenuCommand} 占位注册置灰，功能落地时逐个替换为实类注册行
 * （rename 随树内编辑 UI 后置，未入菜单）。
 * 「项目面板右键」域首期落地（2026-10-17）：showInExplorer / copyPath / reimport
 * 实做；open 2026-10-18 文件注册器批次实做（FileTypeRegistry 分发，脏场景
 * 三键确认）；创建族/粘贴/重命名/删除以 project 域占位类置灰（选中上下文经
 * /project/select 上报 EditorSelection——右键前 await 落上报，菜单 enabled 求值
 * 读新选中态）。顶栏菜单域按 Unity 组织，其余域随功能落地逐域补。</p>
 *
 * @author javelin
 * @create 2026/9/28
 */
public final class EditorMenuSetup {

    private EditorMenuSetup() {
        // 静态装配禁止实例化
    }

    /**
     * 装配菜单与命令（经插件上下文收口）。网格命令的 gridState 经
     * JavelinEditor 懒取，场景未就绪时安全 no-op。
     *
     * @param context 插件上下文
     */
    public static void setup(PluginContext context) {
        context.registerCommand(new SaveSceneMenuCommand());
        context.registerCommand(new GridMenuCommand());
        context.registerCommand(new CompileMenuCommand());
        context.registerCommand(new PackMenuCommand());
        context.registerCommand(new CreateEmptyMenuCommand());
        context.registerCommand(new CreatePlaneMenuCommand());
        context.registerCommand(new CreateBoxMenuCommand());
        context.registerCommand(new CreateCameraMenuCommand());
        context.registerCommand(new DeleteMenuCommand());
        // Unity 壳占位（未实现置灰，落地时替换为实类注册行）：剪贴板三项阻塞于
        // .scene 序列化（剪贴板格式）；3D 对象其余项/灯光/粒子阻塞于对应组件
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.cut", "Ctrl+X"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.copy", "Ctrl+C", "copy"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.paste", "Ctrl+V", "paste"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.sphere", null));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.pyramid", null));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.light.directional", null));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.light.point", null));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.particle", null));
        context.registerCommand(new ShowInExplorerMenuCommand());
        context.registerCommand(new CopyPathMenuCommand());
        context.registerCommand(new ReimportMenuCommand());
        // project 域 Unity 壳占位（未实现置灰，落地时替换为实类注册行）：
        // 创建族/粘贴阻塞于文件写管线；重命名/删除阻塞于
        // guid/meta/引用联动拍板
        context.registerCommand(new OpenFileMenuCommand());
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.create.folder", null));
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.create.scene", null));
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.create.script", null));
        context.registerCommand(new com.javelin.editor.menu.project.CreateMaterialMenuCommand(
                "project.create.material", null));
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.paste", "Ctrl+V"));
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.rename", "F2"));
        context.registerCommand(new com.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.delete", "Delete"));

        // 文件域首期仅「保存」（2026-10-01 拍板：另存为/打开/新建场景随下期落地）
        MenuRegistry.menu("file", EditorLang.key("file"))
                .item("file.save");

        MenuRegistry.menu("tools", EditorLang.key("tools"))
                .item("tools.grid");

        // 构建域（2026-10-11）：编译先禁用（入口拍板，引擎侧 compile 已备）、
        // 打包弹 CEF 对话框（独立前端页 pack.html，纯 UI 先行不接桥）
        MenuRegistry.menu("build", EditorLang.key("build"))
                .item("build.compile")
                .item("build.pack");

        MenuRegistry.menu("ctx.hierarchy", EditorLang.key("ctx.hierarchy"))
                .item("hierarchy.cut")
                .item("hierarchy.copy")
                .item("hierarchy.paste")
                .separator()
                .item("hierarchy.delete")
                .separator()
                .item("hierarchy.create.empty")
                .submenu(EditorLang.key("hierarchy.3dobjects"), create -> create
                        .item("hierarchy.create.plane")
                        .item("hierarchy.create.cube")
                        .item("hierarchy.create.sphere")
                        .item("hierarchy.create.pyramid"))
                .submenu(EditorLang.key("hierarchy.lights"), light -> light
                        .item("hierarchy.create.light.directional")
                        .item("hierarchy.create.light.point"))
                .item("hierarchy.create.particle")
                .item("hierarchy.create.camera");

        MenuRegistry.menu("ctx.project", EditorLang.key("ctx.project"))
                .item("project.open")
                .separator()
                .submenu(EditorLang.key("project.create"), create -> create
                        .item("project.create.folder")
                        .item("project.create.material")
                        .item("project.create.scene")
                        .item("project.create.script"))
                .item("project.paste")
                .separator()
                .item("project.showInExplorer")
                .item("project.copyPath")
                .item("project.reimport")
                .separator()
                .item("project.rename")
                .item("project.delete");
    }
}
