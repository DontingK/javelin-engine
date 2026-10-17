# 编辑器菜单与命令系统设计（v0.11）

> 定位：`javelin-editor` 的菜单/命令子系统——命令层（Command）+ 菜单结构注册（MenuRegistry）
> + 菜单渲染统一 Swing 弹窗（Web 浮层菜单会被视口 Canvas 盖）。
> （2026-10-18 去 Web 化收尾：/menu、/command 桥入口随 jcef 清退删除，命令执行全量
> Swing 直调 CommandService；本文桥协议相关章节转为历史沿革，命令/注册/渲染本体不变。）
> 定稿：2026-09-28。讨论沉淀路径：菜单遮挡约束 → 渲染形态 → engine2 体系对照 → 融合方案；
> v0.2：菜单域收敛为**三类**（顶栏 / 场景树 / project），右键两域设**上下文域基类**；
> v0.3：命令定义形态改为**类实现 MenuCommand 接口（三域基类）**，lambda 保留为快捷通道；
> v0.4：**渲染判定规则作废、菜单统一 Swing**——2026-09-30 实测层级面板右键 Web 菜单
> （已做 boundary 夹紧）仍被视口 Canvas 遮挡，「按浮层位置判定渲染方式」不可靠，
> `DropdownMenu.vue` Web 右键路径删除、右键域并入 `/menu/open` Swing 链路。
> v0.5（2026-10-01）：**子菜单支持**——MenuRegistry 结构项（label + children 任意嵌套）、
> Swing JMenu 递归渲染（每层打开瞬间求值 enabled/checked）、MenuItemVo.children 填充
> （/menu/list 协议面）；层级右键域按 Unity Create 口径组「创建」子菜单；弹窗最小宽度
> 垫宽（MIN_WIDTH=140，项少不局促）；**层级右键域 Unity 壳对齐**（未实现命令占位
> 注册置灰，§4；pasteAsChild/duplicate 暂不注册，随功能落地再加）+ 菜单项快捷键
> accelerator 右对齐显示（仅弹窗期间生效，§5）。
> v0.6（2026-10-01）：**「文件」顶栏域首个命令 file.save（保存场景 Ctrl+S）**——
> 随 .scene 序列化落地（Engine_Scene_Design §9）：TextExporter 文本协议写回
> EditorScene.gameSceneFile，保存走 submitTask 帧边界，成功 clearDirty +
> 发布 scene.changed（前端树根星号消失）；另存为/打开/新建场景随下期落地（拍板
> 2026-10-01：首期仅保存）。
> v0.7（2026-10-11）：**菜单图标 + 层级右键 Unity 结构重排 + tools.refresh**——
> SvgIcon（JSVG）classpath /icon/ 加载 SVG 渲染 Swing Icon、MenuCommand.icon()
> override 即配（勾选命令不配，§5.3）；层级右键按用户清单重排为 Unity 结构
> （剪切/复制/粘贴—删除—空对象—3D 对象/灯光/粒子/摄像机，rename 撤出、
> 未实现项占位置灰）；工具域补 tools.refresh 刷新 web 页（§8）。
> v0.8（2026-10-11）：**hierarchy.create.camera 摄像机命令落地**（替换占位注册行）——
> Unity Camera 口径：节点名 Main Camera / Main Camera (n)、位姿 Unity 新建默认
> （原点无旋转）、相机参数全取组件默认（main 标志不预设——引擎侧 main 消费未落地）；
> 入用户 game scene，SCENE 模式编辑器视口不受影响（EditorScene 相机按 SceneModel
> 双模式分流：SCENE 渲染编辑器相机、GAME 渲染用户相机），Camera write/read 已通
> 保存重载可恢复。
> v0.9（2026-10-11）：**「构建」顶栏域落地（非 Unity 菜单结构，用户指定域）**——
> build.compile 编译（实而先禁：引擎侧 `GradleProject.compile()` 已备，菜单入口
> 按拍板恒置灰，启用去掉 enabled() 覆写即接通）、build.pack 打包（弹 CEF 模态
> 对话框：独立前端页 page/pack.html，宿主=JDialog 版 CEF 容器
> `window/cef/dialog/Dialog`（URL 传参、模态挂 MainFrame、DISPOSE_ON_CLOSE 连
> 浏览器销毁）；「开始打包」同日接入真构建（用户落地 GradleProject.jarBuild：
> /build/jar 桥同步执行 dumpRuntimeJars→build→**jlink 裁剪运行时**→产物组装
> （运行时=jdeps 算模块清单+jlink 只装所需模块的 JRE 级，替代整 JDK 镜像——
> 2026-10-11 拍板「游戏运行时只需 JRE，不带 javac/jmods 工具链」）；**日志实时推送**——
> ConsoleOut 行经 JavaScriptBridge 定向推调用者浏览器（build.out.info/warn/error，
> console.* 前缀保留给控制台面板，
> 依赖桥方法 CefBrowser 参数注入新机制，见 Editor_Design §4.1），对话框日志区
> 流式回显+自动滚底、响应到达即完成（返回值为结束标记），构建期按钮
> loading/取消置灰；**「启动场景」下拉已接真数据**（onMounted 拉取 /build/scenes
> ——AssetManager.listAssetPaths(".scene") 从资产索引枚举、每次先重载索引拾取
> 运行期新导入，默认选中项目默认场景；core AssetManager 接口为此补了
> listAssetPaths 查询方法，见 Editor_Design §6.4）；**表单参数随构建持久化进项目
> Game/**（2026-10-11 拍板「写进 Game 再原封复制」：窗口元信息→
> javelinSettings.settings、启动场景→game.settings 的 defaultSceneGuid 经伴生 .meta
> 解 guid；空值/非法值跳过不覆写、构建模式仍不消费），组装阶段 **Game/ 与 Library/
> 原封复制进产物**（GameMain 运行时从 runDir/Game 读设置、场景走 Library guid 产物）；
> 关窗仍走 X）。
> v0.10（2026-10-13）：**打包对话框「构建形态」JAR/AOT 双链落地**（四拍板：AOT
> 全链落地 / 产物目录采纳 **Builds/**（jar+aot 一级子目录抗 gradle clean，jar 形态
> 产物自 build/jar 迁移，根与 project-template 的 .gitignore 补 Builds）/ 构建模式
> 本批消费 / 桥改名 **/build/pack**——BuildPackPar+packBuild 按形态分流，引擎侧
> jarBuild/aotBuild 链名不动）。AOT 链 = nativeCompile（GraalVM native-image，模板
> graalvmNative 配置已备本批接通）+ 产物组装 Builds/aot：exe + **外置 lib/*.dll**
>（模板补 extractLwjglNatives 任务镜像 javelin-core；新增 **AotGameMain** 主类设
> org.lwjgl.librarypath=./lib——DLL 不进 exe 防体积膨胀，2026-10-13 拍板）+ Game/
> + Library/ + bat（同 jar 形态口径）。构建模式仅 AOT 形态显示并消费：Debug→
> quickBuild 快速构建 / Release→全优化（runTask withArguments -PbuildMode 下发，
> JAR 形态下该行隐藏——JVM 无优化差异）。首跑风险在档：GraalVM 25 toolchain +
> MSVC 环境前置、三方反射（logback）可达性配置、exe 体积验证（DLL 外置应显著
> 更小）。
> 实测验收（2026-10-13 深夜）：AOT/JAR/编辑器三端 DebugText/FPS overlay 一致——
> 三条资源路径（classpath / jlink JAR / native-image include）全验证，双链关账。
> v0.10 修复（2026-10-17）：jar 组装 lib 目录路径错位——`createOutputJarApp` 拼成
> `rootDir/build/jar/lib`（构建工作区）而非产物目录 `Builds/jar/lib`，依赖 jar 全部
> 落错位、启动脚本 `-cp %~dp0lib\*` 落空致主类不可寻；改 `new File(file, "lib")` 与
> AOT 形态（`createOutputAotApp`）同口径（GradleProject.createOutputJarApp）。
> v0.11（2026-10-17）：**project 右键域首期落地（待验收）**——
> EditorSelection project 写入方接线：`/project/select`（ProjectMethod，相对路径
> 解析 + 边界闸门防逃逸项目根，null 清除；右键路径前端 await 落上报再 openMenu，
> 菜单 enabled 求值读新选中态）；三实做命令（menu.project：showInExplorer/
> copyPath/reimport——Desktop.browseFileDirectory 或 open / 剪贴板 /
> AssetImportPipeline.submitImport 目录递归）；Unity 壳占位（menu.project 域
> UnimplementedMenuCommand 恒置灰：open/创建族/paste/rename/delete）；ctx.project
> 菜单结构一次对齐 Unity（创建子菜单+两道分割线）。面板 UI 侧见 Editor_Design §10
> 项目面板条目。

## 1. 背景与约束

**设计基准**：编辑器整体完全参考 Unity（`Editor_Design.md` §1，拍板 2026-09-28）——
菜单组织、菜单项语义、快捷键习惯均以 Unity Editor 为默认答案。

### 1.1 渲染遮挡约束（第一约束）

主窗口是「挖洞式视口」结构（见 `Editor_Design.md` §3）：CEF 浏览器组件铺满整窗，
AWT `Canvas`（Vulkan 呈现面）作为独立组件**浮在 CEF 之上**、只占视口矩形（rectChange 驱动）。

推论：**Web 页面内画的任何浮层（下拉菜单、居中弹窗）延伸进视口矩形，就会被上层的
Canvas 盖住**。

由此得到本系统的渲染规则（v0.4 收敛）：

**菜单浮层一律 Swing 原生弹窗**（heavyweight popup 天然浮于 Canvas 之上，不按位置判定）。
v0.1~v0.3 曾按「浮层位置」判定（不进视口矩形 → Web 自渲染、必盖视口 → Swing），
2026-09-30 实测推翻：层级面板右键 Web 菜单已做 boundary 夹紧（菜单浮层限制在面板
矩形内）仍被视口 Canvas 盖住——面板与视口相邻，按位置判定在实践中兜不住，菜单一律
Swing、Web 浮层菜单全面禁用。

同类潜在坑备案：Web 居中模态弹窗（现有 `ConfirmDialog`/`PromptDialog`）将来若覆盖
视口区域会撞同一堵墙（届时同走 Swing 弹窗口径处理）。

### 1.2 多入口统一

动作的触发入口（现有与规划）：

1. 顶栏菜单（Swing 弹窗）
2. 面板右键菜单（Swing 弹窗）
3. 快捷键（预留，见 §9）
4. 面板工具按钮（预留）

四个入口必须指向**同一命令集**，因此需要稳定的命令 id 作为桥协议与各入口的公共锚点。
这是命令层存在的根本理由。

## 2. engine2 对照与取舍

engine2 有成熟的 Swing 面板右键菜单体系（`engine-editor/.../menu`：`PanelMenu<T>` 四件套
+ `@MenuItem` 注解 + 包扫描 + panel 侧硬编码组装）。逐项对照：

| engine2 做法 | 取舍 | engine5 替代 | 理由 |
|---|---|---|---|
| 动作与菜单结构分离（注解声明 group/name） | ✅ 继承思想 | Command + MenuRegistry | 分离方向正确，实现方式换 |
| `disabled()` 显示前动态求值 | ✅ 继承 | enabled/checked Supplier 弹出时求值 | 菜单是瞬态 UI，显示瞬间求值永远新鲜 |
| 每面板一个菜单域（Hierarchy/Project） | ✅ 继承 | MenuRegistry 菜单域 | 场景树右键、项目面板右键各自成域 |
| `setPanel(this)` 注入上下文 | ❌ 弃 | 域基类读全局选中状态（§3.2） | 动作强绑 panel 则顶栏/web/快捷键进不来；上下文全局化后四入口同一命令 |
| 注解声明 + `addGroup` 硬编码顺序（两处咬合） | ❌ 弃 | 声明式结构单源 | engine2 加一个菜单项要动三处（建类/标注解/改组装）；单源后一处 |
| 一动作一个类（30+ 文件） | ⚠️ 简化 | lambda 集中注册 | 模板代码占比高、注解 value 与 `name()` 双份真相、包扫描是已知性能坑；命令量大后再考虑类化，注册协议不变 |
| 面板右键 = JPopupMenu | ✅ 回归 | 面板右键同样 Swing JPopupMenu | v0.4：Web 右键实测仍被 Canvas 盖（§1.1），v0.2「换轨走 Web」结论推翻 |
| 无命令 id / 无 checked / 无 shortcut | ❌ 补 | Command 字段补齐 | Web 触发（桥协议）、勾选命令（网格开关）、快捷键显示都需要 |

## 3. 命令层（Command / CommandService）

### 3.1 命令定义形态与数据内核

命令以**类实现 `MenuCommand` 接口**为主形态（可选项全 default，简单命令只实现
id/label/execute）。**域分包**：`menu.top` / `menu.hierarchy` / `menu.project`
三个域包，域基类与该域具体命令同包；`menu` 根包只放框架件。`Command` 数据类是内部内核
（Supplier 模型）——桥/MenuRegistry/Swing 层只认内核，定义形态不影响下游。
两注册通道并存：

```java
public interface MenuCommand {
    String id();                                  // 如 "tools.devtools"（桥协议与四入口锚点）
    String label();                               // "开发者工具"
    default String shortcut() { return null; }    // "Ctrl+Shift+I"，第一期仅显示
    default void execute() { }
    default Boolean enabled() { return null; }    // null = 恒可用
    default Boolean checked() { return null; }    // null = 非勾选命令；有值 = 勾选菜单项
}

CommandService.register(new XxxMenuCommand(...));                            // 类形态（主）
CommandService.register(Command.of("id", "标签").action(...).build());       // lambda 快捷通道
```

集中显式注册于 `EditorMenuSetup`，**不做注解 + classpath 扫描**：菜单结构顺序/分割线
仍由 MenuRegistry 单源声明，扫描省不掉结构注册，反而回到 engine2「扫描 + 硬编码」
两处咬合的老路并引入全量扫描性能坑。

### 3.2 上下文契约：三类菜单域 + 域基类

菜单域收敛为**三类**（现在只有这三类），两个右键域各设抽象基类提供类型化上下文：

| 菜单域 | 基类 | 上下文 |
|---|---|---|
| 顶栏（如 `tools`） | `TopMenuCommand`（marker，暂无成员） | 无上下文，全局命令 |
| 场景树右键（`ctx.hierarchy`） | `HierarchyMenuCommand` | `getSelectedNode()` 当前选中 Node，无选中 null |
| 项目面板右键（`ctx.project`） | `ProjectMenuCommand` | `getSelectedFile()` / `getSelectedDirectory()` 当前选中文件/目录，无选中 null |

三域基类是**抽象类 implements MenuCommand**——一个命令 extends 一个域基类（抽象类
强制单一域归属，接口则可同时实现多域无意义）；类型即域标注（找某域全部命令 =
找基类子类）。上下文来源是 **Java 侧全局选中状态单源 `EditorSelection`**（已落地
最小实现：场景选中存 Node id 取用时经 ObjectCache 反查，project 选中存路径；
写入方=前端经桥上报，随场景树 / project 任务接线，当前读取恒为无选中）。

这是对 engine2 `PanelMenu.setPanel` 注入的替代：engine2 的 panel 在 Swing 侧、上下文从
panel 实例取；engine5 的 panel 在 web 侧，上下文从全局选中状态取——**基类只读不持有**，
单一数据源，四个触发入口（顶栏/面板右键/快捷键/按钮）行为一致。命令仍不持有 panel /
Swing 组件 / web 上下文引用。

project 选中写入方已于 2026-10-17 接线（v0.11）：前端经 `/project/select` 上报
（ProjectPanel 单击 fire-and-forget / 右键 await 后弹菜单），Java 侧相对路径解析
+ 边界闸门（越出项目根记 warn 忽略）；场景选中与 project 选中两通道均活跃。

### 3.3 线程契约

命令可能在 EDT（Swing `ActionListener`）或桥线程（`/command/execute`）被调。
**命令内部自行跳线程**：

- 动场景结构 → 引擎帧队列（场景树任务建立的任务通道）
- 动 CEF / Swing → EDT
- 纯逻辑（计数、文件路径计算等）→ 直跑

第一期命令均 EDT 安全，先约定后机制；将来需要时再给 Command 加调度标记，注册协议不变。

### 3.4 CommandService

静态门面：`register(Command)` / `unregister(id)` / `execute(String id)` / `get(id)`。

`execute` 统一包裹异常：记 error 日志 + tinyfd `messageBox` 错误弹窗
（对齐桥接层 `JavaBridgeMethod` 的异常处理惯例）；执行未知 id 记 warn 返回。

## 4. 菜单结构（MenuRegistry）

声明式单源——顺序、分割线、子菜单归属**一处说清**：

```java
MenuRegistry.menu("tools", "工具")
        .item("tools.grid")
        .separator()
        .item("tools.devtools");
MenuRegistry.menu("ctx.hierarchy", "场景树右键")   // 右键域：无顶层标签，右键拉取用
        .submenu("创建", create -> create          // 子菜单：consumer 降层，层级任意嵌套
                .item("hierarchy.create.empty"))
        .separator()
        .item("hierarchy.copy")
        .item("hierarchy.paste")
        .separator()
        .item("hierarchy.rename")
        .separator()
        .item("hierarchy.delete");
MenuRegistry.menu("ctx.project", "项目面板右键")
        .item("project.new.folder")
        ...
```

- 菜单项只存命令 id（不存在则组装时记 warn 跳过——命令未注册不算错误，容忍分批落地）；
- 子菜单为**结构项**（仅 label + 子项列表，无命令 id）：`submenu(label, consumer)` 降层
  组装、层级任意嵌套（Unity Create 口径）；子项全未注册的空壳子菜单组装/拉取时跳过不显示；
- **未实现命令占位注册**（v0.5，Unity 壳对齐）：菜单结构一次对齐 Unity——未实现命令以
  域包占位类 `UnimplementedMenuCommand`（enabled 恒 false）注册，菜单项置灰显示；
  功能落地时以同 id 实类替换 EditorMenuSetup 注册行，菜单结构不动。「未注册即 warn
  跳过」机制保留，用于真实漏注册场景，不再承担"暂未实现"语义；
- 菜单域**三类**（现在只有这三类，见 §3.2）：**顶栏菜单**（有顶层标签，供 `/menu/list`
  顶栏渲染）与两个**右键域**（`ctx.hierarchy` 场景树、`ctx.project` 项目面板，无顶层标签，
  供面板右键拉取）；
- 启动期一次性构建（`EditorMenuSetup`），不做动态增删（§9）。

## 5. 渲染（统一 Swing）

v0.4 起所有菜单浮层一律 Swing JPopupMenu（§1.1）；Web 侧只做触发转发（顶栏标签渲染、
右键坐标上报），不渲染菜单本体。顶栏与面板右键共用同一条 `/menu/open` →
`MenuPopupFactory` 链路，仅坐标语义不同。
子菜单 = `JMenu` 递归嵌套（JMenu extends JMenuItem，直接 add 进 JPopupMenu；子菜单
浮层同由 PopupFactory 决定重量级，浮于视口 Canvas 无虞）——每层挂各自的
`popupMenuWillBecomeVisible`，子菜单项在该层**打开瞬间**求值 enabled/checked。
菜单项 shortcut 文本转 KeyStroke 设为 accelerator（右对齐显示；绑定在菜单项自身
WHEN_IN_FOCUSED_WINDOW InputMap——BasicMenuItemUI.updateAcceleratorBinding，仅弹窗
显示期间生效、收起即无宿主，无全局触发，符合「shortcut 第一期仅显示」；解析失败
记 warn 跳过显示）。

### 5.1 顶栏菜单：Web 标签 + Swing 弹窗

1. Web 顶栏（`MenuBar.vue`）挂载时 `/menu/list`（无 menuId）拉顶栏菜单
   `{id, label}` 列表渲染标签——观感与 Web 主题一致；
2. 点击标签 → `/menu/open {menuId, x, y}`（x = 标签左缘、y = 顶栏下缘，Web 坐标）；
3. Java：web 坐标即 contentPane 坐标（CEF 组件铺满 contentPane，同 rectChange 口径），
   EDT 上直接 `JPopupMenu.show(contentPane, x, y)`；
4. `popupMenuWillBecomeVisible` 时逐项求值 enabled/checked 刷新置灰/勾选；
5. 菜单项 `ActionListener` → `CommandService.execute(id)`。

样式：JPopupMenu 对齐 web 浅色主题（style.css `--editor-*` 色板：白底 `--editor-surface` /
深字 `--editor-text` / 浅灰边 `--editor-border`）；hover 高亮留 LAF 默认（JMenuItem 无
selection setter，UIManager 键属 LAF 私有不瞎写），不深入 LAF 定制（§9）。
弹窗最小宽度 140px（`MenuPopupFactory.MIN_WIDTH`，顶层与子菜单同口径）——Swing 弹窗
按 preferred 尺寸开窗，`setMinimumSize` 不参与开窗尺寸计算，故 preferred 宽不足时
直接改写 preferred 宽、高度保持自然值。

### 5.2 面板右键菜单：Swing 定点弹窗（v0.4）

1. 面板右键 → 先选中上报（如 `/scene/select`——EditorSelection volatile 直写，返回即
   对 EDT 可见；enabled 求值读 Java 侧选中，须先落上报防弹窗求值读到旧态）；
2. `/menu/open {menuId: "ctx.xxx", x: clientX, y: clientY}`（右键 = 鼠标点；
   web 坐标 = contentPane 坐标，同顶栏口径）；
3. Java：EDT 弹 JPopupMenu 于鼠标位置，显示前（`popupMenuWillBecomeVisible`）
   逐项求值 enabled/checked——瞬态状态显示瞬间算一次，天然新鲜；
4. 菜单项 `ActionListener` → `CommandService.execute(id)`（Java 侧直执行，不回 Web）。

（v0.2~v0.3 本节为「Web 自渲染 + `DropdownMenu.vue` boundary 夹紧」，2026-09-30 实测
夹紧后仍被视口 Canvas 盖，路径已删除——见 §1.1。）

### 5.3 菜单图标（SvgIcon / JSVG，v0.7）

**挂点：`MenuCommand.icon()`（default null，override 返回图标名即配）**——资源约定
classpath `/icon/{name}.svg`（`javelin-editor/src/main/resources/icon/`，IntelliJ 系
图标库导出）；渲染 `SvgIcon.of(name, 16)` 实现 Swing `Icon`。渲染约定：

- **勾选命令不配图标**——勾选标记与图标在 JMenuItem 同一图标槽，互挤（Unity 口径
  勾选项也无图标）；
- **资源缺失不设置**（不占空位，也避免缺资源时逐帧 paint 警告）；
- `SvgIcon`（`editor.swing` 包）内部缓存 `SVGDocument`（ConcurrentHashMap，同图
  多处引用不重复解析），首次 paint 时渲染 BufferedImage；
- 弹窗配色三常量随色板微调同步（`--editor-text`/`--editor-border` 新档），深色档
  落地时须改读主题（Swing 不读 CSS，见 §5.1 样式注）。

## 6. 桥协议

| 方法 | 参数 | 返回/行为 |
|---|---|---|
| `/menu/list` | `{menuId?}`（省略 = 顶栏） | 菜单结构数组 `MenuItemVo[]`（menuId 分支 v0.4 后无 Web 消费方，保留作协议面） |
| `/menu/open` | `{menuId, x, y}` | Swing 弹窗（顶栏/右键域共用，无返回数据） |
| `/command/execute` | `{id}` | 命令执行（异常走桥层错误协议；Swing 菜单项 Java 侧直执行不经此，本方法留作 Web 直触发入口） |

命令上下文不进桥参数——`/command/execute` 只传命令 id，上下文由命令经域基类读
Java 侧选中状态（§3.2）；前端的选中上报随场景树 / project 对接任务落地。

```java
public class MenuItemVo {        // 顶层与子项同构
    public String id;            // 菜单 id（顶栏拉取）或命令 id（域拉取）
    public String label;
    public String shortcut;      // 可空，第一期仅显示
    public boolean separator;    // 分隔符项（true 时其余字段无效）
    public boolean checkable;    // 是否勾选命令（渲染形态：勾选框）
    public boolean checked;      // 当前勾选态（仅 checkable 有意义，即时求值）
    public boolean enabled;      // 置灰（即时求值）
    public List<MenuItemVo> children;   // 子菜单（结构项：仅 label + children 递归，无命令 id）
}
```

**与事件总线的分工**：命令走 callJava 请求/响应（动作需要结果反馈）；
事件桥（`EditorEventBus` 跨桥延伸）只负责**持久状态外溢**——场景 dirty、模式切换等
`BridgeEvent` 推送，前端订阅刷新顶栏标签等常驻 UI。两协议各管各的，不混用。

## 7. 状态模型

| 状态类型 | 机制 | 说明 |
|---|---|---|
| 瞬态（enabled / checked） | Swing 弹窗显示前求值 | 菜单瞬态显示，显示瞬间算一次永远新鲜，零同步成本 |
| 持久（场景 dirty、编辑模式） | 事件桥推送 | 影响常驻 UI（标题 `*` 等），走 BridgeEvent |

## 8. 首批命令与菜单

| id | label | 菜单 | 语义 |
|---|---|---|---|
| `file.save` | 保存 | 文件 | TextExporter 写回 gameSceneFile（.scene 文本协议），成功 clearDirty + scene.changed 推送（2026-10-01 落地，随 Engine_Scene_Design §9 .scene 序列化） |
| `tools.devtools` | 开发者工具 | 工具 | `CefFrame.openDevTools()`——同时是事件桥 ping/pong 验收的 DevTools 入口 |
| `tools.grid` | 网格显示 | 工具 | checked 勾选命令，切换 `EditGridAppState` 网格开关——验证 checked 求值链路 |
| `tools.refresh` | 刷新页面 | 工具 | 重载 CEF 内编辑器 web 页（`CefBrowser.reload`，EDT）——引擎/场景状态不动，面板 pull-on-mount 重新拉取；开发期 web 界面状态异常恢复入口（2026-10-11 落地） |
| `hierarchy.create.empty` | 创建空物体 | 场景树右键 → 创建 | 选中节点下创建空节点（无选中=根级），重名去重 GameObject (n)，创建后选中新节点（2026-09-30 落地，v0.5 入「创建」子菜单） |
| `hierarchy.create.plane` | 创建 Plane | 场景树右键 → 创建 | 选中节点下创建 10×10 XZ 平面物体（无选中=根级），重名去重 Plane (n)，创建后选中新节点；mesh=内置 plane.smesh 共享加载（带 guid 落盘可恢复）、材质=default.shader 运行期实例（Material 序列化 2026-10-04 落地，内嵌数据面重载可恢复；.mat 资产格式仍待）；资产未就绪记 error 不建节点（2026-10-04 落地，Unity 3D Object → Plane 口径） |
| `hierarchy.create.camera` | 摄像机 | 场景树右键 → 摄像机 | 选中节点下创建相机（无选中=根级），重名去重 Main Camera (n)，创建后选中新节点；Unity 口径=节点名 Main Camera、原点无旋转、参数全取 Camera 组件默认（fov 60°/near 0.1/far 1000/depth 0/SOLID_COLOR/全屏），main 标志不预设（引擎 main 消费未落地）；相机入用户 game scene——SCENE 模式编辑器视口不受影响（EditorScene 相机 SceneModel 双模式分流）、GAME 模式渲染用户相机，Camera 序列化已通保存可恢复（2026-10-11 落地，替换占位注册） |
| `hierarchy.delete` | 删除 | 场景树右键 | 删除选中子树，无选中置灰，删后清空选中（2026-09-30 落地） |
| `hierarchy.copy` | 复制 | 场景树右键 | 占位置灰——序列化数据面已通（Engine_Scene_Design §9），剩剪贴板子树导出机制（导出须置根 parent=null），随命令落地 |
| `hierarchy.paste` | 粘贴 | 场景树右键 | 占位置灰——同上 |
| `hierarchy.rename` | 重命名 | 场景树右键 | 占位置灰——待树内编辑 UI（scene.nodeRenamed 定点通知已就绪） |
| `build.compile` | 编译 | 构建 | 实而先禁（2026-10-11 拍板）：引擎侧 `GradleProject.compile()`（gradle compileJava）已备，菜单入口恒置灰（enabled 覆写），启用即删覆写接通；execute 记 warn 仅防桥直触发 |
| `build.pack` | 打包 | 构建 | 弹 CEF 模态打包对话框（独立前端页 page/pack.html：构建形态/启动场景/构建模式/窗口元信息表单 + 构建日志区）；「开始打包」接 `/build/pack` 桥（BuildMethod 声明 CefBrowser 参数注入调用者浏览器，buildForm 分流：JAR→`GradleProject.jarBuild`（dumpRuntimeJars→build→jlink 裁剪 JRE 运行时→产物组装到 Builds/jar/），AOT→`GradleProject.aotBuild`（nativeCompile（GraalVM native-image，-PbuildMode 下发构建模式：debug→quickBuild/release→全优化）→产物组装到 Builds/aot/：exe + 外置 lib/*.dll + Game/ + Library/ + bat，AotGameMain 设 org.lwjgl.librarypath=./lib——DLL 不进 exe 防体积膨胀）；ConsoleOut 行经 JavaScriptBridge EDT hop 定向实时推送 build.out.*、前端流式回显自动滚底、响应到达即完成；桥线程池阻塞执行、失败走桥错误协议）；宿主=`window/cef/dialog/Dialog` 通用容器（EDT invokeLater 弹出）——启动场景下拉已接 /build/scenes 真数据；构建模式仅 AOT 形态显示并消费（JAR 形态隐藏——JVM 无优化差异）（v0.9 2026-10-11 落地；v0.10 2026-10-13 构建形态双链 + /build/pack 改名 + Builds/ 产物目录，已实测验收 2026-10-13：AOT/JAR/编辑器三端表现一致） |

顶栏菜单域按 Unity 组织（文件 / 编辑 / 资产 / 游戏对象 / 组件 / 工具 / 窗口 / 帮助），
首期只落有命令的域、空域不渲染——已落「文件」（v0.6，仅保存）、「工具」、
「构建」（v0.9）三域。注：「构建」非 Unity 菜单结构（Unity 的 Build Settings 在
File 菜单下），是用户指定的独立顶级域。
注：Unity 无「视图」顶级菜单，
Scene 视图类开关（如网格）在 Unity 属视图内 Gizmos 工具栏——我们暂以「工具」域承载，
待将来场景视图工具栏落地后再对位迁移（届时注意 §1.1 遮挡约束：工具栏浮进视口矩形
不能走 Web）。

场景树右键域首批命令已随场景树对接落地（2026-09-30）；v0.7（2026-10-11）结构按
用户清单重排为 Unity 顺序：**剪切/复制/粘贴—删除—空对象—3D 对象（Plane 已实现、
立方体/球/三角锥占位）/灯光（定向光/点光源占位）/粒子（占位）/摄像机（v0.8 落地）**，
rename
撤出（随树内编辑 UI 后置）、未实现项占位置灰（§4；pasteAsChild/duplicate 暂不注册）。
project 右键域的
命令、域基类与前端选中上报，随 project 对接任务落地（域基类设计已就绪）。

## 9. 预留（明确不做）

- **快捷键全局触发——Web 层通道已落地（2026-10-02，Ctrl+S 验收驱动；同日拍板
  改为前端直接绑定）**：`shortcutService.ts` 前端静态绑定表（不走菜单结构拉取
  ——零竞态零解析；新命令快捷键加一行，命令本体/菜单显示仍归 Java 侧单源），
  window keydown（capture）匹配 → preventDefault（阻断 CEF 默认行为如保存网页
  对话框）→ `/command/execute` 直触发。文本输入目标与 e.repeat 不拦截；置灰
  命令触发为安全 no-op。**覆盖边界：焦点在 Web 面板内**；焦点在视口 Canvas 时
  键盘进引擎输入系统不经 Web——引擎侧全局接入（Input 边沿 → 命令桥）为开放项，
  随「编辑器输入统一」任务设计；
- 菜单/命令动态增删、命令权限过滤、最近文件列表；
- Swing LAF 深度定制；
- 「一动作一类」组织形式（命令量上来后再评估，注册协议不变）。

## 10. 落地清单

**Java（`com.javelin.editor.menu` + 桥）**

| 件 | 职责 |
|---|---|
| `MenuCommand` + 三域包（`menu.top` / `menu.hierarchy` / `menu.project`，域基类与域命令同包） | §3.1/§3.2 命令类形态契约；`menu` 根包 = 框架件 |
| `Command` / `CommandService` | §3.1 数据内核与注册执行（register(MenuCommand) 适配 + Command.of lambda 通道） |
| `EditorSelection`（`selection` 包） | §3.2 全局选中状态单源（写入方随场景树 / project 任务接线） |
| `MenuRegistry` | §4 声明式结构（含子菜单嵌套） |
| `UnimplementedMenuCommand`（menu.hierarchy） | §4 未实现命令占位注册（enabled 恒 false 置灰，功能落地时替换注册行） |
| `UnimplementedMenuCommand`（menu.project） | 同上，project 域占位（open/创建族/paste/rename/delete，v0.11） |
| `ProjectMenuCommand` 域命令三实做（menu.project） | v0.11：showInExplorer / copyPath / reimport |
| `EditorMenuSetup` | 启动期组装（`JavelinEditor.onInitialize` 调用）：注册命令类 + 搭菜单 |
| `MenuPopupFactory` | §5 JPopupMenu/JMenu 递归构建 + 分层状态求值 + 坐标换算 |
| `SvgIcon`（`editor.swing`）+ `resources/icon/` | §5.3 SVG 图标渲染（JSVG，Document 缓存）与图标资源 |
| `MenuMethod`（`jcef/method`）+ `MenuItemVo`（`domain/vo`） | §6 桥协议 |

**前端（engine-editor-web）**

| 件 | 职责 |
|---|---|
| `MenuBar.vue` 改造 | 拉取顶栏标签 + 点击转发 `/menu/open` |
| `DropdownMenu.vue` | ~~新建（v0.1 落地，v0.4 删除）~~ Web 右键渲染路径随统一 Swing 作废 |
| `shortcutService.ts` | §9 Web 层全局快捷键（前端静态绑定表直接触发 → `/command/execute`，2026-10-02） |

**验证**：跑编辑器 → 顶栏「工具」下拉出现两项且浮于视口之上 → DevTools 可打开（见
ping/pong 回声）→ 网格显示勾选切换生效（视口网格出现/消失）→ 层级面板右键出现
「创建 ▸」子菜单（浮于视口之上），点开执行创建空物体、树自动刷新。
→ 层级面板右键菜单呈 Unity 壳结构：复制/粘贴/重命名置灰不可点、快捷键右对齐显示
（Ctrl+C/Ctrl+V/F2），删除项随选中态置灰/恢复。
