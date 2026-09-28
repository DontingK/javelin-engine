# 编辑器菜单与命令系统设计（v0.1）

> 定位：`javelin-editor` 的菜单/命令子系统——命令层（Command）+ 菜单结构注册（MenuRegistry）
> + 双轨渲染（顶栏 Swing 弹窗 / 面板 Web 右键）。
> 定稿：2026-09-28。讨论沉淀路径：菜单遮挡约束 → 渲染形态 → engine2 体系对照 → 融合方案。

## 1. 背景与约束

**设计基准**：编辑器整体完全参考 Unity（`Editor_Design.md` §1，拍板 2026-09-28）——
菜单组织、菜单项语义、快捷键习惯均以 Unity Editor 为默认答案。

### 1.1 渲染遮挡约束（第一约束）

主窗口是「挖洞式视口」结构（见 `Editor_Design.md` §3）：CEF 浏览器组件铺满整窗，
AWT `Canvas`（Vulkan 呈现面）作为独立组件**浮在 CEF 之上**、只占视口矩形（rectChange 驱动）。

推论：**Web 页面内画的任何浮层（下拉菜单、居中弹窗）延伸进视口矩形，就会被上层的
Canvas 盖住**。顶栏菜单的下拉必然覆盖视口区域，因此顶栏菜单不能用纯 Web 渲染——
这是原 engine2 代码里「弹 Swing 子菜单」TODO 注释的真实动因。

由此得到本系统的渲染判定规则：

| 浮层位置 | 渲染方式 |
|---|---|
| 不进视口矩形（层级/项目面板等左右栏区域） | Web 自渲染 |
| 必须盖视口（顶栏下拉、跨全窗口的浮层） | Swing 原生弹窗（heavyweight popup 天然浮于 Canvas 上） |

同类潜在坑备案：Web 居中模态弹窗（现有 `ConfirmDialog`/`PromptDialog`）将来若覆盖
视口区域会撞同一堵墙，届时按上表规则处理。

### 1.2 多入口统一

动作的触发入口（现有与规划）：

1. 顶栏菜单（Swing 弹窗）
2. 面板右键菜单（Web 渲染）
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
| `setPanel(this)` 注入上下文 | ❌ 弃 | 全局编辑器状态（EditorSelection 等） | 动作强绑 panel 则顶栏/web/快捷键进不来；上下文全局化后四入口同一命令 |
| 注解声明 + `addGroup` 硬编码顺序（两处咬合） | ❌ 弃 | 声明式结构单源 | engine2 加一个菜单项要动三处（建类/标注解/改组装）；单源后一处 |
| 一动作一个类（30+ 文件） | ⚠️ 简化 | lambda 集中注册 | 模板代码占比高、注解 value 与 `name()` 双份真相、包扫描是已知性能坑；命令量大后再考虑类化，注册协议不变 |
| 面板右键 = JPopupMenu | ⚠️ 换轨 | 面板右键走 Web 渲染 | §1.1 判定规则：面板在左右栏不进视口；Swing 弹窗只保留顶栏一处 |
| 无命令 id / 无 checked / 无 shortcut | ❌ 补 | Command 字段补齐 | Web 触发（桥协议）、勾选命令（网格开关）、快捷键显示都需要 |

## 3. 命令层（Command / CommandService）

### 3.1 Command 模型

```java
public class Command {
    String id;                    // 稳定英文标识，如 "view.devtools"（桥协议与四入口的公共锚点）
    String label;                 // 显示文本，如 "开发者工具"
    String shortcut;              // 如 "Ctrl+Shift+I"；第一期仅显示，不接全局触发（§9）
    Runnable action;              // 执行体
    Supplier<Boolean> enabled;    // 置灰判定，null = 恒可用
    Supplier<Boolean> checked;    // null = 非勾选命令；有值 = 勾选菜单项（JCheckBoxMenuItem / web checked）
}
```

### 3.2 上下文契约

命令**不持有 panel / Swing 组件 / web 上下文引用**；执行时从**全局编辑器状态**取上下文
（如 EditorSelection 当前选中实体 id——场景树任务落地）。这是对 engine2
`PanelMenu.setPanel` 注入模式的替代：上下文全局单源，四个触发入口行为一致。

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
MenuRegistry.menu("view", "视图")
        .item("view.grid")
        .separator()
        .item("view.devtools");
MenuRegistry.menu("ctx.hierarchy", "场景树右键")   // 菜单域：无顶层标签，右键拉取用
        .item("hierarchy.create.empty")
        ...
```

- 菜单项只存命令 id（不存在则组装时记 warn 跳过——命令未注册不算错误，容忍分批落地）；
- 菜单域两类：**顶栏菜单**（有顶层标签，供 `/menu/list` 顶栏渲染）与**右键域**
 （无顶层标签，供面板右键拉取）；
- 启动期一次性构建（`EditorMenuSetup`），不做动态增删（§9）。

## 5. 渲染双轨

### 5.1 顶栏菜单：Web 标签 + Swing 弹窗

1. Web 顶栏（`MenuBar.vue`）挂载时 `/menu/list`（无 menuId）拉顶栏菜单
   `{id, label}` 列表渲染标签——观感与 Web 主题一致；
2. 点击标签 → `/menu/open {menuId, x, y}`（x = 标签左缘、y = 顶栏下缘，Web 坐标）；
3. Java：坐标换算 `MainFrame.getLocationOnScreen() + (x, y)`
   （Web CSS 像素与 Swing 逻辑像素 1:1，同 rectChange 口径；换算与弹窗均在 EDT 执行）
   → `JPopupMenu.show(contentPane, x, y)`；
4. `popupMenuWillBecomeVisible` 时逐项求值 enabled/checked 刷新置灰/勾选；
5. 菜单项 `ActionListener` → `CommandService.execute(id)`。

样式：JPopupMenu 做基本暗色对齐（背景/前景/字体），不深入 LAF 定制（§9）。

### 5.2 面板右键菜单：Web 自渲染

1. 面板右键 → `/menu/list {menuId: "ctx.xxx"}` 拉取该域结构
   （含**即时求值**的 enabled/checked——每次右键重新拉取，状态天然新鲜）；
2. Web 渲染菜单（通用下拉组件，`DropdownMenu.vue`）；
3. 点击 → `/command/execute {id}`，执行结果经 callJava 响应返回。

面板区域不进视口矩形（§1.1 判定规则），全程 Web，无遮挡问题。

## 6. 桥协议

| 方法 | 参数 | 返回/行为 |
|---|---|---|
| `/menu/list` | `{menuId?}`（省略 = 顶栏） | 菜单结构数组 `MenuItemVo[]` |
| `/menu/open` | `{menuId, x, y}` | Swing 弹窗（无返回数据） |
| `/command/execute` | `{id}` | 命令执行（异常走桥层错误协议） |

```java
public class MenuItemVo {        // 顶层与子项同构
    public String id;            // 菜单 id 或命令 id
    public String label;
    public String shortcut;      // 可空
    public boolean enabled;      // 即时求值
    public boolean checked;      // 即时求值（仅勾选命令）
    public List<MenuItemVo> children;   // 子菜单，可空
}
```

**与事件总线的分工**：命令走 callJava 请求/响应（动作需要结果反馈）；
事件桥（`EditorEventBus` 跨桥延伸）只负责**持久状态外溢**——场景 dirty、模式切换等
`BridgeEvent` 推送，前端订阅刷新顶栏标签等常驻 UI。两协议各管各的，不混用。

## 7. 状态模型

| 状态类型 | 机制 | 说明 |
|---|---|---|
| 瞬态（enabled / checked） | 弹出时/拉取时求值 | 菜单瞬态显示，显示瞬间算一次永远新鲜，零同步成本 |
| 持久（场景 dirty、编辑模式） | 事件桥推送 | 影响常驻 UI（标题 `*` 等），走 BridgeEvent |

## 8. 首批命令与菜单

| id | label | 菜单 | 语义 |
|---|---|---|---|
| `view.devtools` | 开发者工具 | 工具 | `CefFrame.openDevTools()`——同时是事件桥 ping/pong 验收的 DevTools 入口 |
| `view.grid` | 网格显示 | 工具 | checked 勾选命令，切换 `EditGridAppState` 网格开关——验证 checked 求值链路 |

顶栏菜单域按 Unity 组织（文件 / 编辑 / 资产 / 游戏对象 / 组件 / 工具 / 窗口 / 帮助），
首期只落有命令的域、空域不渲染——先落「工具」一域。注：Unity 无「视图」顶级菜单，
Scene 视图类开关（如网格）在 Unity 属视图内 Gizmos 工具栏——我们暂以「工具」域承载，
待将来场景视图工具栏落地后再对位迁移（届时须先过 §1.1 遮挡判定：浮进视口矩形的
工具栏不能走 web）。

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
| `Command` / `CommandService` | §3 命令模型与注册执行 |
| `MenuRegistry` | §4 声明式结构 |
| `EditorMenuSetup` | 启动期组装（`JavelinEditor.onInitialize` 调用）：注册命令 + 搭菜单 |
| `MenuPopupFactory` | §5.1 JPopupMenu 构建 + 状态求值 + 坐标换算 |
| `MenuMethod`（`jcef/method`）+ `MenuItemVo`（`domain/vo`） | §6 桥协议 |

**前端（engine-editor-web）**

| 件 | 职责 |
|---|---|
| `MenuBar.vue` 改造 | 拉取顶栏标签 + 点击转发 `/menu/open` |
| `DropdownMenu.vue` 新建 | 通用下拉/右键菜单组件（面板右键用，场景树任务首消费） |

**验证**：跑编辑器 → 顶栏「视图」下拉出现两项且浮于视口之上 → DevTools 可打开（见
ping/pong 回声）→ 网格显示勾选切换生效（视口网格出现/消失）。
