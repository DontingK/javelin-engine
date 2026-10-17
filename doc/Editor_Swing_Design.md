# 编辑器 Swing UI 框架设计（v0.1）——去 Web 化重构总纲

> 状态：**M1（窗口 + Dock 骨架）已落地，2026-10-17 用户实测验收通过**。
> 本文是编辑器 UI 栈从 JCEF/Web 迁往纯 Swing + FlatLaf 的重构总纲。后续里程碑落地随进度
> 在此档更新版本段与验收状态。
>
> **拍板记录（2026-10-17）**：
> - **UI 栈切换**：编辑器主窗口弃用 JCEF/Web（main.html 及其面板/布局/桥），改纯 Swing + FlatLaf。
>   动因（按权重排序）：
>   1. **插件扩展 UI 的边界问题（核心动因）**——Web UI 真身在 CEF 渲染进程里，Java 侧插件要
>      贡献面板/Inspector 字段/工具栏按钮，必须穿 Java↔JS 桥 + 前端动态组件加载双轨协议，
>      版本对齐成本随插件数线性涨。Swing 侧插件与宿主 UI 同一对象图直接组合，无进程墙、
>      无语言墙（IntelliJ/Eclipse 同模型）。这是编辑器插件生态的先决条件。
>   2. **混排坑族**：Web 右键浮层被视口 Canvas 盖（已用 Swing 菜单绕过）；rectChange 挖洞
>      上报链 + 最大化 resize 三段补链（Editor_Design §9.10）等纯"两套体系缝合"的维护成本。
>   3. **进程成本**：CEF 常驻渲染进程 + Vite dev server 依赖。
> - **视口零拷贝路径是保留项不动**：`CanvasSurface`（AWT Canvas + JAWT HWND +
>   `VK_KHR_win32_surface`）原样保留——Swing 混排 AWT Canvas 本就是唯一真零拷贝形态，重构后
>   Canvas 从"web 布局算 rect 绝对定位挖洞"改为"Swing 布局直挂"，反而消灭整条上报链。
> - **三决策**：① Dock **自研轻量**（不做第三方库引入）；② 主窗口**一步切换 + 面板占位**
>   （不做 JCEF 面板嵌入过渡）；③ 皮肤基调 **FlatLaf 亮色**（`com.formdev:flatlaf` 单依赖起步；
>   2026-10-17 当日复拍板由 Dark 改亮色——深色后置随主题切换能力再做）。
>   理由见 §3。

## 1. 目标与非目标

**目标**：

1. 纯 Swing + FlatLaf 的主编辑窗口（菜单条 / Dock 布局 / 状态栏），对齐 Unity 交互口径
   （Editor_Design §1 设计基准不变）。
2. 自研轻量 Dock 框架：布局树模型 + 标签组 + 分割，**面板可注册、可扩展**（对齐
   MenuCommand/MenuRegistry 已验证的扩展点模式）。
3. 视口（Vulkan Canvas）无缝迁入：渲染路径零改动，布局归属从 web 挖洞改为 Swing 布局。
4. 渐进迁移路线：六面板 Vue→Swing 按里程碑逐个实装，每期独立可验收。

**非目标（本重构不做）**：

- 视口渲染/交换链/RHI 任何改动（CanvasSurface 只换宿主布局，不动实现）。
- 菜单与命令系统改动（MenuCommand/CommandService/MenuRegistry 已是 Swing 域，M1 仅挂载）。
- ProjectFrame / ProjectLoadingFrame / pack Dialog 的迁移（留在 M6，期间继续 CEF）。
- 布局拖拽停靠（tab 拖拽重组树）、布局持久化（M5）。
- 引擎自绘 UI（远期终局形态，见 §8.4）。

## 2. 现状基线（重构对象盘点）

| 现状 | 位置 | 重构处置 |
|------|------|----------|
| 主窗口 = CEF 铺满 + Canvas 挖洞叠放 | `MainFrame extends CefFrame` | 重写为 Swing 布局宿主，废除 rectChange 链 |
| 视口矩形上报（ResizeObserver→`/render/rce`） | `JavelinMethod.rectChange` / `useRenderRectReport` | **整条废除**，resize 走 Canvas 自身 ComponentAdapter |
| Dock（固定四区 + tab 条） | web `useEditorLayout.ts` + `DockContainer.vue` | Swing 侧重建（§5），功能面只多不少 |
| 六面板（层级/属性/项目/控制台/Scene 视口/Game 视口） | web `panels/` | M1 占位 → 逐个实装（§7） |
| 顶部菜单条 | web `MenuBar.vue`（触发）+ Java `MenuRegistry`（单源） | `setJMenuBar` 挂 Swing 产出，web 条删除 |
| 右键域菜单 / JPopupMenu | 已 Swing（`/menu/open`） | 保留，入口从桥调用改为 Java 直调 |
| 状态栏 | web `StatusBar.vue` | Swing 重建（M1 简版） |
| 事件桥 EventBridgeForwarder（Java→JS 推送） | jcef 包 | 面板迁完且无 JS 消费方后拆除（M6） |
| CEF 三窗口（项目管理/加载页/打包对话框） | `CefFrame` 子类 | 暂留（M6 迁 Swing，届时删 jcefmaven 依赖） |

## 3. 技术选型

- **FlatLaf Dark**（`com.formdev:flatlaf`，建议 3.5.x 最新）：单依赖零传递；全部外观细节走
  `UIManager` 属性覆盖，后续向 Unity 深灰观感微调（accent/边框/间距）不换库。
  安装时机=**main 首行、任何 Swing 组件创建之前**（`FlatLaf.setup(new FlatDarkLaf())`）。
- **自研轻量 Dock**：第三方 Swing Dock 库基本停更（FlexDock 2011 后维护停滞、DockingFrames
  活跃但重且深度定制难），且面板注册表必须与自家扩展点体系（菜单域先例）打通——IntelliJ
  同路线。骨架工作量可控（二分树 + 标签条 + 分割条），复杂交互（拖拽停靠）分期后置。
- **jsvg 保留**：Swing 侧 SVG 图标渲染已有依赖（`swing/SvgIcon`），web `public/icon` 的图标
  资产直接复用迁移，不重画。

## 4. 窗口体系

### 4.1 MainFrame 重构（契约不变、实现换芯）

`Window` 接口契约（Engine_Window_Design）**逐条保留**：`create/showWindow/closeRequested/
pollEvents/attachInputSink/renderSurface/isResized/acknowledgeResize/width/height/isVisible`
语义全部不变，引擎侧零感知。变化只在内部实现：

```
旧：CefFrame(JPanel=JCEF 铺满) + CanvasSurface(绝对定位挖洞, rect 由 /render/rce 驱动)
新：MainFrame extends JFrame
     ├─ setJMenuBar(MenuBuilder 产出——MenuRegistry 单源，命令域零改动)
     ├─ contentPane（BorderLayout）
     │    ├─ CENTER：DockRoot（§5 默认布局树）
     │    └─ SOUTH：StatusBar（JLabel 组合，M1 简版）
     └─ CanvasSurface 归 ViewportTabs 持有（§6），不再挂 MainFrame
```

- `CefFrame` 不删除：ProjectFrame/ProjectLoadingFrame/dialog 继续用它到 M6。MainFrame 与
  CefFrame 解耦后，CEF 的启动/关闭生命周期不再与主窗口绑定（`JcefCreator` 仍由未迁窗口触发）。
- **废除清单**（M1 随实现删）：`JavelinMethod` 的 `/render/rce`、`MainFrame.rectChange`、
  `addJcefComponent` 的最大化补链（Editor_Design §9.10）——Swing 布局下 Canvas 尺寸由
  LayoutManager 收敛，无事件链时序问题。
- **dpi 通道重设计**：现状 dpi 来自 web `devicePixelRatio`，随 rect 上报。Swing 侧来源改为
  `Canvas.getGraphicsConfiguration().getDefaultTransform()` 的 scaleX（EDT 取）。M1 简化：
  Canvas 首次显示时取一次；**多显示器拖跨/dpi 变化跟随记为开放项**（§10）。

### 4.2 关闭与线程口径

- 关闭流程不变：`DO_NOTHING_ON_CLOSE` + `close` 标志 + 引擎轮询 `closeRequested()` 统一收尾。
- 线程模型不变（Editor_Design §8）：EDT=UI，引擎主线程=帧循环，桥线程池随面板迁移逐步退役。
  新增口径：**Swing 面板 ↔ 引擎状态互访规则**（先例收口，见 §7.3）。

## 5. Dock 框架设计

### 5.1 数据模型：二分布局树

```
DockNode（抽象）
 ├─ SplitNode（direction: HORIZONTAL|VERTICAL, left: DockNode, right: DockNode,
 │             weight: double 0~1——右占比，分割条拖动只改此值）
 └─ TabNode（panels: List<String> 面板 id，activeIndex: int）
```

- **SplitNode 恒两子**（二分树）：Unity/IntelliJ 的任意布局都可由二分递归表达，实现上直接
  映射 `JSplitPane`（保留其非连续布局与分割条交互），不自研分割条。多区需求=嵌套二分。
- **尺寸重分配策略（弹性侧）**：`DockSplitPane` 的 resizeWeight 固定为"含视口面板
  （viewport）的一侧吸收容器尺寸变化，其余侧保持原尺寸"——拖外层分割条/改窗口大小时
  层级/属性/底栏等定宽面板不随之伸缩（Unity/IntelliJ 口径；2026-10-17 拍板，此前按
  weight 比例分配会导致层级面板跟手伸缩）。
- **区域最小尺寸 = 面板描述符契约**：`PanelDescriptor.minimumSize`（组内取最大经
  `DockTabs.getMinimumSize` 上汇，分割条钳制与窗口收缩走标准 minimum 链生效；
  面板换实装约束跟描述符不丢）。当前常量（EditorPanelSetup 可调）：层级 200 宽 /
  属性 260 宽 / 底栏 120 高（2026-10-17 拍板）。
- **TabNode 只存面板 id**：面板组件实例不进布局树——树可整体序列化（M5 持久化），面板状态
  生命周期与布局解耦；同一面板 id 同时只在一个 TabNode（关闭=从树摘除，重开=按默认落点
  重新插入，M1 即支持）。
- 默认布局（对齐当前 web 四区，Unity 口径）：

```
SplitNode(H, 0.25)                            // 右占比=属性区
 ├─ SplitNode(V, 0.3)                         // 左中列
 │    ├─ SplitNode(H, 0.8)                    // 上：层级 ‖ 视口
 │    │    ├─ TabNode[层级]
 │    │    └─ ViewportTabs[Scene, Game]       // §6 视口区
 │    └─ TabNode[项目, 控制台]                 // 底部区——贯穿层级与视口区（Unity 口径）
 └─ TabNode[属性]                              // 右列全高
```

### 5.2 面板注册表（扩展点，对齐菜单域先例）

```java
/** 面板描述符（注册单元）——布局树只引用 id */
public final class PanelDescriptor {
    String id;            // 域风格 id（如 hierarchy / inspector / project）
    String title;        // 标签标题（中文）
    String icon;          // SVG 资源路径
    boolean closable;
    Supplier<JComponent> factory;   // 即装即造，面板组件不缓存于注册表
}

/** 面板生命周期契约（对应 web DockPanelExpose.onShow/onHide） */
public interface EditorPanel {
    default void onShow() {}
    default void onHide() {}
}
```

- `PanelRegistry`：集中显式注册（`EditorPanelSetup.setup()`，对齐 `EditorMenuSetup` 风格，
  **不做注解扫描**）；id 重复抛异常 fail-fast；`create(id)` 按 factory 造组件。
- **插件预留**：第三方 jar 实现面板 → 调 `PanelRegistry.register` + `MenuRegistry` 加
  「窗口」域菜单项（`window.panel.xxx`，Unity Window 菜单口径）即可出现在编辑器里——
  与插件贡献菜单命令同一模式，这就是本重构的核心收益落点（§8）。

### 5.3 渲染层组件

| 组件 | 职责 |
|------|------|
| `DockRoot`（JComponent） | 持布局树，树→组件递归实例化；`rebuild()` 整树重排（M1 粗粒度：结构变化即重挂，面板组件经 detach 缓存复用不重建） |
| `DockSplitPane` | SplitNode 渲染，包装 JSplitPane（非连续布局，拖完才 relayout——规避逐像素 swapchain 重建，§10-5） |
| `DockTabs` | TabNode 渲染：自绘标签条（JComponent.paintComponent，贴行紧凑、tab 高 24 对齐场景视图工具栏规格）+ 内容区 BorderLayout |
| `DockTab` 标签 | 标题 + 关闭 ×（closable）+ 左图标（jsvg） |

- 间距守 §"编辑器 Web UI 设计规范"同源的紧凑口径：常驻布局间距 ≤2px（浮层菜单例外）——
  该规范随迁移平移为 Swing 侧规范（FlatLaf 默认 padding 偏大，`UIManager` defaults 统一
  收口覆盖，见 §10-3）。

### 5.4 交互分期

- **M1（本批）**：标签点击切换、标签关闭（closable，**组内局部重排不塌缩**——空组保留
  占位；关闭路径零树重挂，防画布 reparent，见 §10-6 坑③）、分割条拖动（自研驱动）。
- **M5 二期**：面板关闭后经「窗口」菜单恢复；空组塌缩与拖拽重组树（tab→tab 并组 /
  tab→区边缘插入新 SplitNode）——<b>结构变更必然重挂组件树，画布 HWND 随之重建，
  需配 RHI 侧 surface 重建能力（检测 nativeHandle 变化→重建 VkSurfaceKHR+交换链）
  一并拍板</b>；布局持久化（树 → 文本序列化，走 `serializable` 包 TextExporter，
  存 `.javelin/` 下，与 project.settings 同域）。

### 5.5 heavyweight Canvas 混排（已知风险，非设计变更）

视口 Canvas 是 heavyweight 组件，Swing popup（菜单/tooltip）覆盖它时 PopupFactory 自动升级
heavyweight popup——当前菜单 Swing 化已在挖洞 Canvas 上实测工作，M1 列为回归验证项（§10-1）。
Dock 树中 Canvas 只出现在 ViewportTabs（§6），不进普通 TabNode，规避 reparent 链。

## 6. 视口接入（ViewportTabs 特化）

现状语义保留：**单 CanvasSurface 双视口共用**（Scene/Game 活动者上报 rect、Game 标签切
GAME 模式、工具栏随视图）。Swing 化设计：

```
ViewportTabs（TabNode 特化子类，落点=center 区）
 ├─ 标签条：[Scene] [Game]（普通标签交互）
 ├─ 北（EAST 之上）：ViewToolbar——随活动标签切换（Scene 工具栏=现 web 版 Tools 组+
 │    局部/世界切换的 Swing 化；Game 工具栏=空占位，Unity 显示比例/Stats 族后置）
 └─ CENTER：ViewportHost（常驻，BorderLayout.CENTER 挂 CanvasSurface）
```

- **Canvas 不 reparent**：标签切换只换工具栏 + `setSceneModel(SCENE|GAME)`（Java 直调
  GizmoMethod 同名语义），Canvas 常驻 ViewportHost——换 parent 会走 `removeNotify` →
  HWND 释放重建 → 交换链重建，一票否决 reparent 方案。
- resize：Canvas 自身 ComponentAdapter（已有），无上报链；分割条/窗口 resize 全走 Swing
  布局收敛。
- 输入：`attachInputSink` 挂 Canvas 不变；Canvas 在视口区域即输入坐标 = 视口局部坐标，
  语义与现状一致（现状就是 Canvas 自身坐标）。
- **多视口（各自相机各自 swapchain）是 RHI 侧扩展，记开放项后置**（现单交换链单 Canvas，
  与现状能力持平，不因重构倒退也不提前）。

## 7. 面板迁移路线（M2~M4）

### 7.1 数据通道总原则：桥退役、Java 直调

web 面板的桥方法（`/scene/tree`、`/project/tree`、`/inspector/info` 等）本质是"给 JS 用的
Java API 出口"。Swing 面板在 JVM 内，**直接调 Java 服务**（SceneModel / ProjectMethod 同款
逻辑抽出的服务方法 / EditorSelection / EditorEventBus），桥方法随各自面板迁移即废弃。
EventBridgeForwarder 的 Java→JS 推送，被"面板直订 EditorEventBus"取代（§7.3 线程口径）。

### 7.2 Vue → Swing 组件映射

| web 面板 | Swing 实装 | 数据源变化 |
|----------|-----------|-----------|
| HierarchyPanel | `JTree` + 自定义 TreeModel/Renderer | `/scene/tree` 帧 pull → 直调 SceneModel（submitTask 帧队列取树快照，EDT 更新 model） |
| ConsolePanel | `JList` + DefaultListModel（HTML 渲染列 styled） | ConsoleOut 输出通道改 append（EDT hop），build.out.* 同收口 |
| ProjectPanel | 左 `JTree` + 右 `JList`（图标+名称）+ 面包屑 JButton 行 | `/project/tree` 逻辑抽 service 直调（纯 IO 可后台线程）；选中直写 EditorSelection |
| EntityInspectorPanel | 表单自绘（两列：label 列 + 控件列，Inspector_Design 通道保留） | `/inspector/info`/`setValue` 桥 → InspectorReflector 直调 |
| SceneViewportPanel | ViewportTabs 工具栏（§6） | `/gizmo` setTool/setSpace → 直调 TransformGizmoAppState 镜像（static volatile 口径不变） |
| GameViewportPanel | ViewportTabs 标签占位 | setSceneModel 直调 |

### 7.3 线程与事件口径（面板侧统一规则）

- **读引擎状态**（场景树、transform 快照）：`JavelinEngine.submitTask`（先例：场景树对接）
  ——引擎线程帧首执行，结果 EDT 呈现。
- **写引擎状态**（选中、gizmo 意图）：走既有 volatile/单源通道（EditorSelection、
  TransformGizmoAppState 意图镜像），EDT 直写安全（先例：/scene/select、GridMenuCommand）。
- **订 EditorEventBus**：事件可能引擎线程发出（SceneWatchAppState），**订阅方自行
  `SwingUtilities.invokeLater` 后再动 Swing 组件**——面板基类提供 hop 便利方法，规则集中
  写进 EditorPanel javadoc。

### 7.4 实装次序（按风险递增）

M2 层级 + 控制台 → M3 项目 + 视口工具栏 → M4 属性（436 行表单最重，GUID_ASSET 三态只读
等通道逐项对齐 Inspector_Design）。每面板迁移=独立批次，迁一个验一个，桥方法随批废弃。

## 8. 扩展点与插件预留

1. **面板贡献**：`PanelRegistry.register` + `MenuRegistry`「窗口」域（M5 随恢复命令一起落
   `window` 域结构与 `WindowMenuCommand`）。
2. **菜单/命令贡献**：已有（MenuCommand 域基类 + CommandService），零改动受益。
3. **Inspector 字段贡献者**：后置（随插件体系设计期拍板，Inspector_Design 修订时并入）。
4. **引擎自绘 UI（远期终局）**：Godot/Unity 形态——编辑器 UI 由引擎渲染，插件与宿主同帧同
   GPU。本重构的 PanelRegistry/命令域/事件总线抽象届时可整体平移为引擎内实现，Swing 仅作
   过渡宿主。记入 Editor_Design §10 演进方向。

## 9. 里程碑与验收

| 期 | 内容 | 验收标准 |
|----|------|----------|
| **M1 窗口+Dock 骨架（本批）** | FlatLaf Dark 初始化；MainFrame 重构（Window 契约保持）；DockRoot/DockSplitPane/DockTabs + 默认布局树；六面板占位（PanelRegistry+EditorPanelSetup）；ViewportTabs 接 CanvasSurface + Scene 工具栏 Swing 化；JMenuBar 挂载；StatusBar 简版；`/render/rce` 等废除清单落地 | 用户实测：编辑器启动渲染正常；视口 resize/最大化/分割条拖动全链路正常；右键菜单、顶部菜单可用；关闭流程正常 |
| M2 | 层级、控制台实装；`/scene/tree`/console 桥废弃 | 层级树操作（选/右键命令创建删除）与现状等价；控制台收 build 日志 |
| M3 | 项目面板实装；视口工具栏收口 | 项目面板树/选中/右键域与现状等价 |
| M4 | 属性面板实装 | Inspector_Design 各通道（含 setValue 往返、GUID_ASSET 只读）验收等价 |
| M5 | 「窗口」域菜单（关闭恢复）；布局持久化；拖拽停靠二期 | 关闭→恢复不丢面板；布局重启还原；拖拽停靠可用 |
| M6 | ProjectFrame/ProjectLoadingFrame/pack Dialog 迁 Swing；EventBridgeForwarder/桥包/jcefmaven 整体移除 | 无 CEF 进程；引擎-编辑器全链路纯 JVM |

## 10. 已知风险与坑预记

1. **heavyweight Canvas 上的 popup**：JPopupMenu/tooltip 覆盖视口需 PopupFactory 升级
   heavyweight——现状已工作，M1 回归项；出现被盖症状时查 popup 重量级策略，勿改 Canvas。
2. **EDT 违规高发区**：事件订阅回调（引擎线程）直接动组件=偶发卡死/异常，§7.3 规则面板
   基类收口，CR 口径同 rectChange 先例。
3. **FlatLaf 默认间距偏大**：tab/按钮 padding 与编辑器 ≤2px 紧凑口径冲突——`UIManager`
   defaults 在一处统一覆盖（禁散落各组件 `setBorder/insets`），色板从 web `var(--editor-*)`
   平移为同值自定义属性，单点维护。
4. **dpi 简化的边界**：M1 取首显 transform，多显示器拖跨/dpi 动态变化不跟随（记开放项，
   症状=跨屏后交换链分辨率错，重开项目自愈）。
5. **分割条拖拽 × heavyweight 画布（2026-10-17 M1 实测两轮拍板）**：
   - **坑**：鼠标拖分割条越过视口画布时拖拽冻结——画布是 heavyweight（独立原生
     窗口），LightweightDispatcher 轻量抓取链覆盖不到，拖动事件直接派给画布；且
     BasicSplitPaneDivider 的 `dragger` 只认完整释放序列，释放丢失（窗口外松手）
     即卡死无自愈（实验定位：视口换 JPanel 一切正常，坐实 heavyweight 因素）。
   - **布局口径拍板**：连续实时布局（拖动实时调整，弃非连续幽灵条）——中间尺寸
     的画布 resize/交换链重建频率被引擎帧率封顶（resized 标志按帧消费），风暴
     担忧过度；拖动期画面拉伸与窗口边缘 resize 同口径（§9.11 已拍板接受）。
   - **终案**：`DockSplitPane` 自研拖拽驱动——按下分隔条时剥离 UI 自装
     MouseHandler、挂 toolkit 级 AWTEventListener 用屏幕绝对坐标直算分割位（事件源
     是谁无关，画布越界照常驱动）；拖拽中出现新按下=释放丢失自愈收场重启。副作用
     =分隔条 hover 高亮随 UI 监听剥离（纯装饰损失）。第一版"救援转发"方案（把画布
     事件转换坐标转发给分隔条续跑 UI 拖拽）已废弃——它没有解决 dragger 卡死路径。
   - **根因实锤（读 JDK 源码 Container$LightweightDispatcher + Windows 原生层定论）**：
     拖动越过画布冻结的直接机制 = **AWT Windows 原生层只给"收到按下的组件"投递
     MOUSE_DRAGGED**（WmMouseDown 置该组件的 m_dragging、WmMouseMove 查该标志）。
     按下落在轻量分隔条 = 主窗口 HWND 的 m_dragging 置位；拖动跨入画布 HWND 时
     画布自己的 m_dragging=false → 投出的是 **MOUSE_MOVED** 而非 DRAGGED → 只认
     DRAGGED 的拖拽逻辑（Basic 的 MouseHandler 与第一版救援）全部断流。终案在
     驱动里把 MOVED 与 DRAGGED 等价处理（dragging 激活期的 MOVED 只可能来自画布
     侧），故彻底免疫。**通用教训：凡"轻量组件按下 + heavyweight 视口"的拖拽，跨
     画布事件必以 MOVED 形态到达，处理拖拽必须两种形态同接。**
   - **坑②（2026-10-17 日志实锤）：画布默认 minimum size 钳死分隔条**——AWT
     Canvas 未显式设最小尺寸时 getMinimumSize() 走 peer（≈当前尺寸），沿
     BorderLayout.minimumLayoutSize 链上溯（ViewportHost→ViewportTabs→DockTabs→
     内外 SplitNode）成为整个复合区的最小值，getMinimumDividerLocation() 被钉在
     画布当前高——症状"拖一小段就拖不动"（min 与起拖位置只差几十 px）。修法=
     MainFrame.create() 显式 canvas.setMinimumSize(1,1) 掐断传播源头。
6. **面板组件 detach 复用**：M1 的 rebuild() 粗粒度重挂必须保证 TabNode 摘面板时调
   onHide、重挂调 onShow 且组件实例不重建（factory 只在无实例时造）——否则层级树等
   有内部状态的面板迁入后会被静默重置。
7. **关闭路径禁止树重挂（坑③，2026-10-17 实测"关属性面板即坏"）**：关闭面板若做
   空组塌缩 + DockRoot 整树重建，视口组件即便有缓存也要从旧容器摘下重挂——画布
   removeNotify→HWND 销毁重建，引擎 VkSurfaceKHR/交换链仍绑旧句柄，present 死。
   "画布不 reparent"铁律必须延伸到一切运行期结构操作：M1 关闭=DockTabs 组内局部
   重排（树结构不动）；M5 塌缩/拖拽重组前必须先落 RHI surface 重建。
7. **图标资产迁移**：web `public/icon` 复制进 editor resources，路径常量单点收口；jsvg
   渲染线程口径（现 SvgIcon 已在 EDT 使用，无新问题）。

## 11. 依赖变更

- **+** `com.formdev:flatlaf:3.5.x`（最新 patch）。
- **jsvg / lwjgl-jawt / tinyfd / jackson** 保留（jackson 桥退役后随 M6 复查去留）。
- **−** `jcefmaven`：M6 全窗口迁完才移除，期间 CefFrame 路径必须可用。
