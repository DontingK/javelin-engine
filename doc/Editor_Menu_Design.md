# 编辑器菜单与命令系统设计（v0.4）

> 定位：`javelin-editor` 的菜单/命令子系统——命令层（Command）+ 菜单结构注册（MenuRegistry）
> + 菜单渲染统一 Swing 弹窗（Web 浮层菜单会被视口 Canvas 盖）。
> 定稿：2026-09-28。讨论沉淀路径：菜单遮挡约束 → 渲染形态 → engine2 体系对照 → 融合方案；
> v0.2：菜单域收敛为**三类**（顶栏 / 场景树 / project），右键两域设**上下文域基类**；
> v0.3：命令定义形态改为**类实现 MenuCommand 接口（三域基类）**，lambda 保留为快捷通道；
> v0.4：**渲染判定规则作废、菜单统一 Swing**——2026-09-30 实测层级面板右键 Web 菜单
> （已做 boundary 夹紧）仍被视口 Canvas 遮挡，「按浮层位置判定渲染方式」不可靠，
> `DropdownMenu.vue` Web 右键路径删除、右键域并入 `/menu/open` Swing 链路。

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
        .item("hierarchy.create.empty")
        ...
MenuRegistry.menu("ctx.project", "项目面板右键")
        .item("project.new.folder")
        ...
```

- 菜单项只存命令 id（不存在则组装时记 warn 跳过——命令未注册不算错误，容忍分批落地）；
- 菜单域**三类**（现在只有这三类，见 §3.2）：**顶栏菜单**（有顶层标签，供 `/menu/list`
  顶栏渲染）与两个**右键域**（`ctx.hierarchy` 场景树、`ctx.project` 项目面板，无顶层标签，
  供面板右键拉取）；
- 启动期一次性构建（`EditorMenuSetup`），不做动态增删（§9）。

## 5. 渲染（统一 Swing）

v0.4 起所有菜单浮层一律 Swing JPopupMenu（§1.1）；Web 侧只做触发转发（顶栏标签渲染、
右键坐标上报），不渲染菜单本体。顶栏与面板右键共用同一条 `/menu/open` →
`MenuPopupFactory` 链路，仅坐标语义不同。

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
    public List<MenuItemVo> children;   // 子菜单，预留（当前扁平）
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
| `tools.devtools` | 开发者工具 | 工具 | `CefFrame.openDevTools()`——同时是事件桥 ping/pong 验收的 DevTools 入口 |
| `tools.grid` | 网格显示 | 工具 | checked 勾选命令，切换 `EditGridAppState` 网格开关——验证 checked 求值链路 |

顶栏菜单域按 Unity 组织（文件 / 编辑 / 资产 / 游戏对象 / 组件 / 工具 / 窗口 / 帮助），
首期只落有命令的域、空域不渲染——先落「工具」一域。注：Unity 无「视图」顶级菜单，
Scene 视图类开关（如网格）在 Unity 属视图内 Gizmos 工具栏——我们暂以「工具」域承载，
待将来场景视图工具栏落地后再对位迁移（届时注意 §1.1 遮挡约束：工具栏浮进视口矩形
不能走 Web）。

场景树 / project 右键域的命令、域基类与前端选中上报，随场景树、project 对接任务
落地（菜单协议与域基类设计已就绪，首批只做顶栏命令）。

## 9. 预留（明确不做）

- **快捷键全局触发**：`Command.shortcut` 第一期仅显示。全局触发需要 Web keydown →
  命令桥触发或引擎输入系统全局接入，专门一轮设计；
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
| `MenuRegistry` | §4 声明式结构 |
| `EditorMenuSetup` | 启动期组装（`JavelinEditor.onInitialize` 调用）：注册命令类 + 搭菜单 |
| `MenuPopupFactory` | §5.1 JPopupMenu 构建 + 状态求值 + 坐标换算 |
| `MenuMethod`（`jcef/method`）+ `MenuItemVo`（`domain/vo`） | §6 桥协议 |

**前端（engine-editor-web）**

| 件 | 职责 |
|---|---|
| `MenuBar.vue` 改造 | 拉取顶栏标签 + 点击转发 `/menu/open` |
| `DropdownMenu.vue` | ~~新建（v0.1 落地，v0.4 删除）~~ Web 右键渲染路径随统一 Swing 作废 |

**验证**：跑编辑器 → 顶栏「视图」下拉出现两项且浮于视口之上 → DevTools 可打开（见
ping/pong 回声）→ 网格显示勾选切换生效（视口网格出现/消失）。
