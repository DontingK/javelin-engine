package cn.donting.javelin.editor.menu;

import cn.donting.javelin.core.scene.component.DirectionalLight;
import cn.donting.javelin.core.scene.component.PointLight;
import cn.donting.javelin.core.scene.component.SpotLight;
import cn.donting.javelin.editor.menu.hierarchy.CreateBoxMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateCapsuleMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateCameraMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateCylinderMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateEmptyMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateEnvironmentMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateLightMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreatePlaneMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.CreateSphereMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.DeleteMenuCommand;
import cn.donting.javelin.editor.menu.hierarchy.UnimplementedMenuCommand;
import cn.donting.javelin.editor.menu.project.CopyPathMenuCommand;
import cn.donting.javelin.editor.menu.project.CreateMaterialMenuCommand;
import cn.donting.javelin.editor.menu.project.CreateSceneMenuCommand;
import cn.donting.javelin.editor.menu.project.CreateShaderMenuCommand;
import cn.donting.javelin.editor.menu.project.CreateTextureMenuCommand;
import cn.donting.javelin.editor.menu.project.OpenFileMenuCommand;
import cn.donting.javelin.editor.menu.project.ReimportMenuCommand;
import cn.donting.javelin.editor.menu.project.ShowInExplorerMenuCommand;
import cn.donting.javelin.editor.menu.top.CompileMenuCommand;
import cn.donting.javelin.editor.menu.top.SaveSceneMenuCommand;
import cn.donting.javelin.editor.menu.top.GridMenuCommand;
import cn.donting.javelin.editor.debug.GBufferDebugAppState;
import cn.donting.javelin.editor.menu.top.PostProcessingMenuCommand;
import cn.donting.javelin.editor.menu.top.BloomMenuCommand;
import cn.donting.javelin.editor.menu.top.GBufferViewMenuCommand;
import cn.donting.javelin.editor.menu.top.PackMenuCommand;
import cn.donting.javelin.editor.menu.top.PreferencesMenuCommand;
import cn.donting.javelin.editor.menu.top.ProjectSettingsMenuCommand;
import cn.donting.javelin.editor.plugin.EditorLang;
import cn.donting.javelin.editor.plugin.PluginContext;

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
 * 「空对象」）/ hierarchy.create.plane、hierarchy.create.cube、
 * hierarchy.create.sphere、hierarchy.create.capsule、hierarchy.create.cylinder
 * （「3D 对象」子菜单，原始体 2026-10-25 批次落地）/ hierarchy.create.camera
 * （「摄像机」，2026-10-11 落地）/ hierarchy.create.light.directional+point
 * （「灯光」子菜单三项，三期批次② 2026-10-31 落地——灯族子类化后 spot 一并解锁）；
 * 剪切/复制/粘贴、3D 对象其余项、粒子以
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
        // 后处理开关（直通 ↔ HDR Tonemap，四期第 1 项步骤一 2026-11-13）：运行期切换 + 写盘
        context.registerCommand(new PostProcessingMenuCommand());
        context.registerCommand(new BloomMenuCommand());
        // G-Buffer 可视化视图（dbg-2，2026-11-12）：工具菜单四项单选（关闭/基色/世界法线/世界位置）
        context.registerCommand(new GBufferViewMenuCommand("tools.gbufferView.off",
                GBufferDebugAppState.MODE_OFF));
        context.registerCommand(new GBufferViewMenuCommand("tools.gbufferView.albedo",
                GBufferDebugAppState.MODE_ALBEDO));
        context.registerCommand(new GBufferViewMenuCommand("tools.gbufferView.normal",
                GBufferDebugAppState.MODE_NORMAL));
        context.registerCommand(new GBufferViewMenuCommand("tools.gbufferView.worldPos",
                GBufferDebugAppState.MODE_WORLD_POS));
        context.registerCommand(new CompileMenuCommand());
        context.registerCommand(new PackMenuCommand());
        context.registerCommand(new PreferencesMenuCommand());
        // 项目设置窗口（编辑域，2026-11-14）：非模态「图形 | 后处理」设置页，对标 Unity
        // Edit → Project Settings
        context.registerCommand(new ProjectSettingsMenuCommand());
        context.registerCommand(new CreateEmptyMenuCommand());
        context.registerCommand(new CreatePlaneMenuCommand());
        context.registerCommand(new CreateBoxMenuCommand());
        context.registerCommand(new CreateSphereMenuCommand());
        context.registerCommand(new CreateCapsuleMenuCommand());
        context.registerCommand(new CreateCylinderMenuCommand());
        context.registerCommand(new CreateCameraMenuCommand());
        // 环境设置 = 普通节点 + Environment 组件（场景唯一；菜单恒可用，已存在弹警告，2026-11-08）
        context.registerCommand(new CreateEnvironmentMenuCommand());
        context.registerCommand(new DeleteMenuCommand());
        // Unity 壳占位（未实现置灰，落地时替换为实类注册行）：剪贴板三项阻塞于
        // .scene 序列化（剪贴板格式）；3D 对象其余项/粒子阻塞于对应组件
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.cut", "Ctrl+X"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.copy", "Ctrl+C", "copy"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.paste", "Ctrl+V", "paste"));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.pyramid", null));
        // 灯光三项随三期批次②解锁（灯族子类化拍板 2026-10-31：组件身份即类型，
        // 不支持切换——三类各一命令）
        context.registerCommand(new CreateLightMenuCommand("hierarchy.create.light.directional",
                CreateLightMenuCommand.DIRECTIONAL_NAME, "light-directional", DirectionalLight.class));
        context.registerCommand(new CreateLightMenuCommand("hierarchy.create.light.point",
                CreateLightMenuCommand.POINT_NAME, "light-point", PointLight.class));
        context.registerCommand(new CreateLightMenuCommand("hierarchy.create.light.spot",
                CreateLightMenuCommand.SPOT_NAME, "light-spot", SpotLight.class));
        context.registerCommand(new UnimplementedMenuCommand("hierarchy.create.particle", null));
        context.registerCommand(new ShowInExplorerMenuCommand());
        context.registerCommand(new CopyPathMenuCommand());
        context.registerCommand(new ReimportMenuCommand());
        // project 域 Unity 壳占位（未实现置灰，落地时替换为实类注册行）：
        // 创建族剩余项/粘贴阻塞于文件写管线；重命名/删除阻塞于
        // guid/meta/引用联动拍板；创建场景已实装（2026-11-01，创建即切换）
        context.registerCommand(new OpenFileMenuCommand());
        context.registerCommand(new cn.donting.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.create.folder", null));
        context.registerCommand(new CreateSceneMenuCommand(
                "project.create.scene", null));
        context.registerCommand(new cn.donting.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.create.script", null));
        context.registerCommand(new CreateMaterialMenuCommand(
                "project.create.material", null));
        context.registerCommand(new CreateShaderMenuCommand(
                "project.create.shader", null));
        context.registerCommand(new CreateTextureMenuCommand(
                "project.create.texture", null));
        context.registerCommand(new cn.donting.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.paste", "Ctrl+V"));
        context.registerCommand(new cn.donting.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.rename", "F2"));
        context.registerCommand(new cn.donting.javelin.editor.menu.project.UnimplementedMenuCommand(
                "project.delete", "Delete"));

        // 文件域首期仅「保存」（2026-10-01 拍板：另存为/打开/新建场景随下期落地）；
        // 「首选项」2026-10-29 落地（语言设置首批，编辑器首选项对话框）
        MenuRegistry.menu("file", EditorLang.key("file"))
                .item("file.save")
                .separator()
                .item("file.preferences");

        // 编辑域（2026-11-14，Unity 对齐）：项目设置窗口（Edit → Project Settings）
        MenuRegistry.menu("edit", EditorLang.key("edit"))
                .item("edit.projectSettings");

        MenuRegistry.menu("tools", EditorLang.key("tools"))
                .item("tools.grid")
                .item("tools.postProcessing")
                .item("tools.bloom")
                .submenu(EditorLang.key("tools.gbufferView"), gbuffer -> gbuffer
                        .item("tools.gbufferView.off")
                        .item("tools.gbufferView.albedo")
                        .item("tools.gbufferView.normal")
                        .item("tools.gbufferView.worldPos"));

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
                        .item("hierarchy.create.capsule")
                        .item("hierarchy.create.cylinder")
                        .item("hierarchy.create.pyramid"))
                .submenu(EditorLang.key("hierarchy.lights"), light -> light
                        .item("hierarchy.create.light.directional")
                        .item("hierarchy.create.light.point")
                        .item("hierarchy.create.light.spot"))
                .item("hierarchy.create.particle")
                .item("hierarchy.create.camera")
                .item("hierarchy.create.environment");

        MenuRegistry.menu("ctx.project", EditorLang.key("ctx.project"))
                .item("project.open")
                .separator()
                .submenu(EditorLang.key("project.create"), create -> create
                        .item("project.create.folder")
                        .item("project.create.material")
                        .item("project.create.shader")
                        .item("project.create.texture")
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
