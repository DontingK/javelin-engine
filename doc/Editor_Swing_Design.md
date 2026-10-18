# 编辑器 Swing UI 框架设计（v0.1）——去 Web 化重构总纲

> 状态：**M1（窗口 + Dock 骨架）已落地，2026-10-17 用户实测验收通过**。
> **M6 全项提前完成**：三窗口 2026-10-17 迁 Swing；jcef 桥包 / jcefmaven / jackson
> 2026-10-18 整体清退，engine-editor-web 前端工程一并删除——引擎-编辑器全链路纯
> JVM、无 CEF 进程与 Vite dev server 依赖（落地待验收，见 §9 M6 行注）。
> **M2 层级面板已实装**（2026-10-18，待验收——实装拍板与坑见 §7.5）；控制台与
> M3/M4 面板占位待实装。
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
- ~~ProjectFrame / ProjectLoadingFrame / pack Dialog 的迁移（留 M6）~~——已于 2026-10-17
  三窗口全部提前完成，CEF 运行时零使用。
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
| CEF 三窗口（项目管理/加载页/打包对话框） | `CefFrame` 子类 | 三窗口全部迁 Swing（2026-10-17：`window/ProjectFrame` 最近列表+新建/打开三模式、`window/ProjectLoadingFrame` 引导链原样、`window/PackDialog` 表单+实时日志流——原 BuildMethod 逻辑迁入 Java 直调，/build、/project(recent/open/create) 桥随消费方消亡退役）；`CefFrame`/CEF `Dialog` 已删除，JcefCreator 无触发点——**CEF 运行时零使用**，jcef 桥包成死代码（M2~M4 面板迁移收口，最终随 M6 整体清退） |

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

- ~~`CefFrame` 不删除~~：三窗口迁完后 `CefFrame`/CEF `Dialog` 均已删除（2026-10-17），
  `JcefCreator` 无任何触发点——CEF 进程不再启动、jcef 桥包成死代码（桥方法随 M2~M4
  面板迁移退役，最终随 M6 整体清退 jcefmaven）。
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
| `DockTabs` | TabNode 渲染：自绘标签条（JComponent.paintComponent，贴行紧凑、tab 高 24 对齐场景视图工具栏规格）+ 内容区 BorderLayout。标签条常显（2026-10-18 复拍板，推翻 M1「单面板不可关无缝」——层级/属性单面板组也带 tab 头，Unity 口径）；唯一例外=视口组无缝（ViewportTabs 自绘 [Scene][Game] 条，再加即双条） |
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

**清退实况（2026-10-18，早于面板迁移整体完成）**：三窗口迁完后桥已无任何运行时消费方，
「随各面板迁移即废弃」的逐批节奏失去意义——jcef 包整体删除（桥方法 / par / vo /
EventBridgeForwarder / 桥基础设施全量），jcefmaven、jackson 依赖同批移除（jackson 唯一
消费方 JSONUtil 服务桥协议），M2~M4 面板一律 Java 直调全新实装。保留物：EditorEventBus
事件体系（消费方直订，§7.3）；Inspector 三 VO（Node/Component/Field）迁 `inspector` 包
与 InspectorReflector 同包，作 M4 数据模型；事件包桥语义剥离（BridgeEvent / JsEvent /
PongEvent 删，五场景事件转纯 EditorEvent）。

### 7.2 Vue → Swing 组件映射

| web 面板 | Swing 实装 | 数据源变化 |
|----------|-----------|-----------|
| HierarchyPanel | `JTree` + 自定义 Renderer | ✅ 2026-10-18 实装（§7.5）：`SceneTreeSnapshot` 直调 EditorScene——submitTask 帧边界组装快照、invokeLater EDT 换树；四事件分工刷新 + 选中 EditorSelection 双向同步 |
| ConsolePanel | `JList` + DefaultListModel（HTML 渲染列 styled） | ConsoleOut 输出通道改 append（EDT hop），build.out.* 同收口 |
| ProjectPanel | ✅ 2026-10-18 实装（§7.6）：左 `JTree` + 右 `JList`（图标+名称）+ 面包屑 JButton 行 | `ProjectTreeSnapshot` 直调（纯 IO 后台线程组装）；选中直写 EditorSelection |
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
等通道逐项对齐 Inspector_Design）。每面板迁移=独立批次，迁一个验一个——桥已随 M6
提前整包清退，各面板即 Java 直调全新实装（§7.1）。层级 2026-10-18 先落（§7.5），
项目面板同日次落（§7.6），控制台待续。

### 7.5 层级面板实装注记（M2 首面板，2026-10-18 落地待验收）

组件落位 `swing/hierarchy` 两件：`HierarchyPanel`（JTree 封装 + 渲染 + 交互）与
`SceneTreeSnapshot`（原 /scene/tree 桥 VO 的直调形态：ready/sceneName/dirty + 节点
投影 name·active·children，NodeSnap.name 可变供定点改名）。实装拍板与坑：

1. **TreeModel 取舍**：DefaultTreeModel + DefaultMutableTreeNode 包装（userObject =
   NodeSnap / 快照本体），不写全自定义 TreeModel——增删/改名通知机制现成
   （nodeChanged / structureChanged），无平行父子结构要自维护。§7.2 原计划行
   「自定义 TreeModel」按此收窄为「自定义 Renderer」。
2. **快照通道**：`build` 经 submitTask 帧边界组装、`invokeLater` 回传 EDT 整体
   换树。桥时代「CompletableFuture + 2s await 阻塞取值」形态废弃（EDT 不可阻塞）；
   未就绪（ready=false：启动竞态 / loadScene 失败）Swing Timer 0.5s × 30 次重试
   （原 web 面板口径平移）。时序红利：面板构造于 `initialize()` 建窗期、首拉任务
   在首帧消费——晚于 loadScene，正常路径首拉即 ready，重试仅兜底。
3. **选中对齐通道升级**：web 时代外源选中（创建命令自动选中等）靠重拉携带
   selectedNodeId 对齐；Swing 侧改订 scene.select 事件即时高亮 +
   scrollPathToVisible（顺带展开父链——创建命令后新节点自动可见，不等 0.25s
   重拉）。程序化同步期抑制选中监听回写，防事件回声。
4. **换树陷阱（setRoot 清选中）**：model.setRoot 触发 valueChanged（选中被清），
   换树期必须抑制回写——否则向 EditorSelection 单源误报 null，随后高亮虽恢复、
   单源已丢真值。展开态按节点引用换树前收集 / 换树后恢复（结构命令刷新不折叠）。
5. **JTree 行为补齐（JDK 源码查证 BasicTreeUI）**：默认右键不改选中
   （selectPathForEvent 仅左键分支）、点空白不清选中（handleSelection 对行带外
   直接 return）、点展开手柄只切换不选中。按 Unity 口径补齐：右键行带命中即选中
   （y 落行高即命中，x 不限——含手柄与行右空白）+ 先落选中再弹 ctx.hierarchy
   域菜单（MenuPopupFactory 统一入口，enabled 求值读 EditorSelection 防旧态）；
   右键 / 左键点空白 = 清除选中。
6. **渲染**：全行 box 图标 14px（原 web 口径）；场景根 = 场景名 + 脏星标（live
   dirty 标志——scene.dirty 置位 repaint 即亮、重拉以快照 dirty 覆盖熄灭）；
   inactive 灰显（快照携带自身 active；SceneWatch 尚未检测 active 翻转，灰显随
   结构性变更顺带生效，active 检测落地时补刷新链）。
7. **订阅生命周期**：构造期一次订阅（面板经 DockRoot 缓存常驻，无退订路径，
   编辑器同寿）；回调一律 invokeLater 跳 EDT（§7.3 规则）。事件分工沿用并扩充：
   scene.changed 重拉整树 / scene.dirty 置星 / scene.nodeRenamed 定点改名 /
   **scene.select 高亮对齐（本面板为首个消费方）**。
8. **全链引用化（2026-10-18 当日拍板，用户发起）**：桥清退后「id 值 +
   ObjectCache 弱引用反查」的句柄链整体废弃——EditorSelection 存 volatile
   Node（setSelectedNode）、三事件（select / renamed / transformChanged）引用
   载荷、Create 命令父节点引用快照（去 ObjectCache 复原）、NodeSnap 携 Node
   引用作回查键（树索引 IdentityHashMap）。决策依据：弱隔离早已名存实亡——
   引擎侧消费方（Gizmo/TransformWatch）收到事件即 volatile Node 强引用长持、
   ObjectCache.update() 零调用方清理从未跑过；已删检测实际一直靠 getScene
   归属校验兜底（DeleteMenuCommand 先例）；场景实例会话常驻无泄漏差异。
   **快照拷贝本体保留**——跨线程一致性（EDT 不活读引擎对象图）与句柄问题
   正交，NodeSnap 引用仅作回查键、EDT 不读其字段。ObjectCache 随本批退役
   （core 侧零消费方，ObjectId.id 保留作日志/调试口径）；Inspector 三 VO 的
   id 锚点无运行时消费方，M4 实装时随引用化改造。将来场景切换命令落地时须
   setSelectedNode(null) 收口。

### 7.6 项目面板实装注记（M3，2026-10-18 落地待验收）

组件落位 `swing/project` 两件：`ProjectPanel`（左右分栏 JTree/JList + 面包屑 +
右键域）与 `ProjectTreeSnapshot`（原 /project/tree 桥逻辑的直调形态：ready +
根层 + Node{relativePath/name/directory/File/children}）。实装拍板与坑：

1. **线程通道**：项目树纯 IO、不涉引擎状态——**不走 submitTask 帧队列**（区别于
   层级），ThreadUtil 后台线程组装快照、invokeLater 回 EDT 应用。一次性拉取：面板
   构造晚于引擎装配（instance 在 JavelinEditor 构造器置位、MainFrame.create 更晚）
   恒就绪，web 版 ready=false 节流重试口径随宿主时序消亡（防御分支仅 warn 留
   加载态）；project.changed 刷新事件随改盘命令第二批（Editor_Design §10 遗留）。
2. **选中直写 File**：Node 直持 File（快照由 rootDir 遍历产出，天然不越项目根）
   ——原桥「相对路径上报 + isSubFile 解析闸门」环节随直调消亡，
   EditorSelection.setSelectedPath 直写。与 web 版一处收窄：面包屑导航 web 版只切
   目录不重报选中，Swing 统一「目录变更即上报」——EditorSelection 与面板所见恒一致。
3. **状态收口**：当前目录=左树选中（onDirSelected 单点收口：右区条目刷新+面包屑
   重建+选中上报），面包屑/右区双击进入无独立状态——程序化走 selectDirByPath
   （scrollPathToVisible 展开父链 + setSelectionPath）触发选中事件驱动收口链。
   空选中（换树/清选中）不动目录锚点——右区内容保持，无清空语义。
4. **右键域三入口先显式上报再弹菜单**：左树行（右键即选中）/ 右区条目（行命中
   即选中）/ 右区空白（=当前目录语境），均先 setSelectedPath 再 MenuPopupFactory——
   不可依赖选中事件链：同目录重选无选中变更事件，显式上报防菜单 enabled 读旧态；
   左树空白右键忽略（无语境源，web 等价口径）。
5. **JList 细节**：locationToIndex 对空白也回最近行，须 getCellBounds 复核行带
   （层级面板 row band 同口径）；空白点选清本地高亮（Unity 口径）但不上报 null
   （web 等价：无清空语义）；换目录重置模型自动清选中，选中监听 null 不上报
   即免层级式的抑制标志。
6. **面板内分栏**：JSplitPane 用默认驱动即可（面板内无 heavyweight 画布——
   DockSplitPane 自研拖拽是视口画布断链特例）；连续实时布局 + resizeWeight 1.0
   （右区吸收增量、左树定宽）+ 首个 resize 落初始位 180px（构造期尺寸 0，
   setDividerLocation 不生效，DockSplitPane 同款坑）+ 左树最小宽 100px（分割条
   左界钳制源）。原 web「复用 SplitPanel」之说随迁移作废。

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
| M2 | 层级、控制台实装（`/scene/tree`/console 桥已随 M6 提前清退，本项只剩 Swing 实装） | 层级树操作（选/右键命令创建删除）与 Web 时代等价；控制台收 build 日志。**层级已落地（2026-10-18 待验收，§7.5）；控制台待续** |
| M3 | 项目面板实装；视口工具栏收口 | 项目面板树/选中/右键域与现状等价。**项目面板已落地（2026-10-18 待验收，§7.6）；视口工具栏收口待续** |
| M4 | 属性面板实装 | Inspector_Design 各通道（含 setValue 往返、GUID_ASSET 只读）验收等价 |
| M5 | 「窗口」域菜单（关闭恢复）；布局持久化；拖拽停靠二期 | 关闭→恢复不丢面板；布局重启还原；拖拽停靠可用 |
| M6 | ~~ProjectFrame/ProjectLoadingFrame/pack Dialog 迁 Swing + EventBridgeForwarder/桥包/jcefmaven 整体移除~~ | ✅ **全项提前完成**（2026-10-17 三窗口 + 2026-10-18 桥包/jcefmaven/jackson 清退，待验收）：引擎-编辑器全链路纯 JVM、无 CEF 进程与 Vite dev server；jcef-bundle 运行时缓存（353MB）一并删除 |

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
- **jsvg / lwjgl-jawt / tinyfd** 保留。
- **−** `jackson`：2026-10-18 随桥清退（唯一消费方 JSONUtil 服务桥协议，同批删除）。
- **−** `jcefmaven`：2026-10-18 随 jcef 桥包整体清退（运行时 jcef-bundle 缓存 353MB 一并删除）。
