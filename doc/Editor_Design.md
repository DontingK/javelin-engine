# 编辑器模块架构设计（v0.1）

> 本文档描述 `javelin-editor` 模块的整体架构。模块尚在快速演进期，文档随代码同步更新。
> 最近一次全面梳理：2026-09-25（含本次窗口接入引擎的改造）；2026-09-28 增补设计基准（完全参考 Unity）。

## 1. 定位与边界

`javelin-editor` 是 Javelin Engine 的桌面编辑器，技术栈为 **Swing + JCEF（Java Chromium Embedded）**：
前端 UI 是跑在 CEF 里的 Web 页面（Vite  dev server，如 `http://localhost:5173/page/main.html`），
后端是 JVM 内的引擎运行时。一块 AWT `Canvas` 嵌入主窗口作为 Vulkan 渲染呈现面。

**设计基准：完全参考 Unity**（拍板 2026-09-28）。编辑器的交互口径、面板组织、命名与
概念体系以 Unity Editor 为准绳——已对齐先例：Scene 视图相机控制（`EditorCameraAppState`）、
`viewportRect`（0-1 左下原点）、Layer/Tag/LayerMask、Hierarchy/Inspector/Project/Console
面板命名、Node≈GameObject 的场景模型。后续编辑器功能设计遇行为分歧时，**以 Unity 的
做法为默认答案**；仅当引擎架构差异（CEF/Web UI 栈、挖洞式视口等）确实无法对齐时才
自行设计，并在对应设计档注明偏离点与理由。

核心职责：

1. **项目生命周期管理**：新建 / 打开 / 最近项目列表
2. **资产管线**：GUID 元数据（`.meta`）、导入器 SPI、Library 产物管理、内置资源释放
3. **引擎宿主**：`EditorApp extends JavelinEngine`，主窗口 `MainFrame` 实现引擎 `Window` 契约
4. **前后端桥接**：JS ↔ Java 双向调用（注解驱动）

**明确不做的事**：游戏逻辑运行、场景编辑逻辑（二期规划）、输入系统（归引擎 core）。

## 2. 依赖关系

```
javelin-editor
 ├─ javelin-core（JavelinEngine / Window / AssetLibManager / 序列化 / settings 模型）
 ├─ javelin-rhi、javelin-tool、javelin-log
 ├─ javelin-ast（annotationProcessor，@Reflective 反射注册）
 └─ 第三方：
    ├─ jcefmaven 146.0.10（CEF 分发与启动）
    ├─ gradle-tooling-api 8.14.3（项目编译 / nativeCompile）
    ├─ lwjgl-jawt（Canvas HWND）、lwjgl-tinyfd（原生文件对话框/错误弹窗）
    ├─ jackson-databind（桥接 JSON）、jsvg（Swing SVG）
    └─ slf4j、joml
```

构建附带 `generateBuiltinAssetIndex` 任务：扫描 `builtin-asset-core/editor` 资源目录，
生成 `builtin-asset.index`（构建期生成进 resources），供内置资源释放器索引。

## 3. 启动流程与窗口体系

编辑器按"三大窗口"组织启动序列：

```
EditorApp.main()
  └─ ProjectFrame（项目管理器，projectManager.html）
       └─ 打开项目 → ProjectMethod.openProject()
            └─ ProjectLoadingFrame（加载页，projectLoading.html）
                 └─ 后台线程 "Engine Main"：
                      GradleProject → MetaManager → ProjectAssetLibManager
                      → AssetImportPipeline → BuiltinAssetReleaser
                      releaseAll() → refresh() → startAndWait() → loadIndex()
                      └─ EditorApp extends JavelinEngine → editorApp.start()
                           └─ JavelinEngine: createWindow()=MainFrame → create() → showWindow()
                                └─ MainFrame（主编辑窗口，main.html）
                                     ├─ CEF UI 组件（Web 前端，铺满）
                                     └─ CanvasSurface（Vulkan 呈现面，矩形区域由前端 rectChange 驱动）
```

| 窗口 | 基类 | 说明 |
|------|------|------|
| `ProjectFrame` | `CefFrame` | 项目管理器；`windowClosing` 直接 dispose |
| `ProjectLoadingFrame` | `CefFrame` | 无装饰加载页；承载资产初始化与引擎启动 |
| `MainFrame` | `CefFrame` | 主编辑窗口；实现引擎 `Window` 接口（见 §5） |
| `CefFrame` | `JFrame` | 抽象基类：封装 `CefBrowser` 创建/关闭、`loadURL`、子类布局钩子 `addJcefComponent` |
| `CanvasSurface` | `Canvas` | 引擎 `RenderSurface` 实现（见 §5） |

三个窗口各持静态 `instance` 单例（前端桥反查用）。

## 4. 前后端桥接（jcef 包）

### 4.1 JS → Java：`JsJavaBridgeHandler`

- 类加载时扫描 classpath 下所有带 `@BridgeMethod` 注解**类**（包扫描参数当前为空串，即全量扫），
  反射实例化后注册其中带注解的**方法**；URL 规则：`类前缀 + 方法注解值`（方法注解为空时退化为方法名）。
- 前端发 `url::json` 格式 query，后端在专用线程池（8~16 核，队列 2000，AbortPolicy）异步执行，
  通过 `CefQueryCallback` 回传 JSON。
- 方法封装在 `JavaBridgeMethod`：JSON 反序列化第一个参数 → 反射调用 → 序列化返回。
  异常按领域分类映射 HTTP 风格码（400 提示/校验、422 领域、500 未知、502 构建），
  并弹 tinyfd 原生错误框。当前桥方法：`JavelinMethod`（`/render/rce` 渲染区矩形）、
  `ProjectMethod`（项目管理）、`FileSystemMethod`（目录选择）。
- **注意**：所有桥方法在桥接线程池执行，不在 EDT；需要触 UI 必须自行 `SwingUtilities`。

### 4.2 Java → JS：`JavaScriptBridge`

静态工具，通过 `cefBrowser.executeJavaScript` 调前端 `window.__javelinBridge.onMessage(type, data)`。
`CefFrame.send()` 是预留入口（当前注释状态）。

### 4.3 CEF 生命周期：`JcefCreator`

静态块即初始化 `CefApp`（install dir `jcef-bundle`、`--disable-gpu`、非 windowless）。
`createBrowser(url)` = 新建 client + 注册消息路由 + `createBrowser`。
注意：**首次触碰该类即启动 CEF**，不可在纯引擎路径误加载。

## 5. 引擎接入（window/cef 包）

### 5.1 MainFrame implements Window

按 `GLFWWindow` 语义对齐（`doc/Engine_Window_Design.md`）：

- 生命周期幂等：`create()`（隐藏创建）→ `showWindow()` → `close()`；closed 后重开抛异常
- `DO_NOTHING_ON_CLOSE` + `windowClosing` 只置 `close` 标志，引擎轮询 `closeRequested()` 后调 `close()` 收尾
- `width()/height()` 返回**窗口逻辑像素**（`getWidth/Height`，未创建回退 setting）；
  物理像素换算在 `RenderSurface` 侧
- `isResized()/acknowledgeResize()` 委托 canvas；`isVisible()` = shown 且非最小化
- `renderSurface()` 未创建时抛异常
- `rectChange(RectPar)`：前端推送渲染区矩形 + dpi → `canvas.setBounds/setDpi`（`invokeAndWait` 上 EDT）

> **历史坑**：`Window` 接口方法原叫 `show()`，与 AWT `Component.show()`（deprecated）在
> `MainFrame` 上构成双重重写，已改名为 `showWindow()`（2026-09-25，全项目 + 文档同步）。

### 5.2 CanvasSurface implements RenderSurface

- JAWT 取 HWND（三层 finally 成对释放 `FreeDrawingSurfaceInfo`/`JAWT_FreeDrawingSurface`/JAWT 结构体），
  句柄缓存、`removeNotify` 失效
- `width()/height()` = Swing 逻辑尺寸 × dpi（物理像素喂交换链）
- `ComponentAdapter` 置 resize 标志；`setDpi` 实际变化时也置标志（dpi-only 场景交换链重建）

## 6. 资产管线

### 6.1 项目目录模型（project 包）

`Project` 抽象定义目录布局（Gradle 项目唯一实现 `GradleProject`）：

```
项目根
 ├─ Assets/           用户资产（场景等）
 ├─ Library/          导入产物 + index.asset（guid → 源路径）
 ├─ .javelin/         编辑器数据（project.settings、assets/ 内置资源释放目标）
 │   └─ assets/{name}/
 ├─ Game/             游戏设置（layer/tag/javelin/game.settings，Binary 格式）
 └─ src/main/java     脚本（.java = 自包含 GUID 资源）
```

监听目录 `getWatchAssetDir()`：上述 `src/main/java`、`Assets`、`.javelin/assets`。
`GradleProject` 还负责：`compileJava`/`nativeCompile`（tooling-api，行回调输出）、
`createNewProject`（模板复制 + 变量替换 + 默认设置/场景生成）。
`RecentProjectManager` 持久化 `~/.javelin/recent.properties`（上限 10）。

### 6.2 元数据（meta 包）

- **伴生 `.meta`**：每个资源文件同级 `文件名.meta`（文本序列化），存 guid / 类型 / 导入设置 / 源文件 mtime。
- **自包含 GUID**：`src/main/java` 下 `.java` 与**全部文本格式资产（`.shader` / `.smesh`）**
  不建 `.meta`，GUID 以 `// @javelin-guid:` 文件头形式由 `GuidHeaderManager` 提取/注入
  （拍板 2026-09-26：内置资源无伴生 .meta，GUID 以文件头为唯一真值——删 `Library`/`.javelin`
  缓存重编译后 GUID 不变，引用不失效；无头文件首次导入自动注入确定性 GUID）。
- `MetaManager`：构造即 `sync()`（建缺、清孤儿、重建缓存），维护 guid↔path 三向缓存，
  GUID 重复视为致命错误抛异常；`updateIndex()` 生成 `Library/index.asset`。
- 类型检测按扩展名粗分（Texture/Shader/Audio/Scene/Material/Script/Mesh/Data）。

### 6.3 导入管线（importer 包）

- `AssetImporter` SPI：扩展名注册表驱动（扫描实例化所有实现）。产物统一命名
  `Library/{guid}.asset`。**产物 guid 优先取源码文件头**（`.shader` 自包含真值源），
  **文件头缺失才回退 meta 的 guid**（拍板 2026-09-26）。当前实现：`SimpleMeshImporter`
  （.smesh 文本格式 → Mesh Capsule 二进制产物，补齐落盘 2026-09-26）；
  **`ShaderImporter`（.shader → 解析 → #version 后注入 → shaderc 编译 → Capsule 二进制
  产物，2026-09-26 落地）**——管线中首个真正写出二进制产物的导入器，
  组成：`ShaderParser`（v0.13 语法）/ `ShaderInjector`（声明注入）/ `ShaderImporter`（编排+导出）。
- `AssetImportPipeline`：生产者 `refresh()`（扫监听目录，mtime 变化或产物缺失才提交）/
  `reimportAll()`（清 Library 全量）；消费者为 CPU 核数个工作线程 + 无界 LinkedBlockingQueue；
  `ImportTask` 记录状态/耗时/错误。
- `BuiltinAssetReleaser`：按构建期 `builtin-asset.index` 把 classpath 内置资源释放到
  `.javelin/assets/{name}/` 并写 meta（资源自带 GUID 文件头校验）。

### 6.4 Library 管理（asset 包）

`ProjectAssetLibManager extends AssetLibManager`（core）：Library 目录 + `index.asset` 加载，
`loadIndex()` 失败降级 warn（首次打开项目无索引属正常）。

## 7. 设置与序列化

- **settings 包**：`SettingsManager` 三分设置——项目设置（`.javelin/project.settings`，文本）、
  游戏设置（`Game/game.settings`，Binary，来自 core）、编辑器全局设置（`~/.javelin/editor.settings`，文本）。
  加载策略：文本 → 回退 Binary → 回退默认实例。
- **serializable 包**：编辑器域文本序列化 `TextExporter/TextImporter`（`javelin-text-serialization`
  头 + 对象 id 引用表 + `@guid:`/`@ref:`/`@null` 特殊值），与 core `BinaryExporter` 互补，
  用于 `.meta`、`.scene`、`project.settings` 等可读文件。

## 8. 线程模型

| 线程 | 职责 | 注意 |
|------|------|------|
| EDT | Swing/CEF UI、窗口事件 | `MainFrame.close()` 的 `dispose` 经 `invokeLater` 回到 EDT；`rectChange` 用 `invokeAndWait` |
| 引擎主线程 | `EditorApp.start()` 帧循环（`ProjectLoadingFrame` 以 `"Engine Main"` 线程启动） | `pollEvents()` 空实现（Swing 事件自发派发） |
| js-java-bridge 池 | 桥接方法执行（8~16） | 不在 EDT；异常弹 tinyfd |
| AssetImport-Worker 池 | 导入任务（CPU 核数） | `waitImportFinished()` 轮询等待 |

跨线程共享状态主要经 MetaManager 的 ConcurrentHashMap 缓存与导入任务队列。
跨线程场景访问统一走 `JavelinEngine.submitTask(Runnable)`（任意线程提交、
mainLoop 帧首消费、异常隔离，2026-09-30 场景树对接落地）；场景变更通知由
`SceneWatchAppState` 快照 diff 自动发现（0.25s 节流，基线静默），写入方零通知义务。

## 9. 已知问题与风险（检查结论）

1. **`CefFrame.dispose()` NPE 风险**：`cefBrowser.close(true)` 在 `loadURL` 未执行过时 NPE；
   close 先于 create 的路径会踩到。
2. **三窗口静态 instance**：重复 new 会静默覆盖单例，前端桥反查到错误实例。
3. **`ProjectLoadingFrame` 有 `ThreadUtil.sleep(2000)` 假等待**（纯为加载页观感）。
4. **导入成功即全量 `updateIndex()`**：每个任务成功都重扫监听目录重写索引，资产量大时是热点。
5. **`JsJavaBridgeHandler` 类扫描参数为 `""`（全 classpath）**，启动慢且可能误注册；
   javadoc 里包名 `com.javelin.editor.jecf` 为历史笔误。
6. **`SettingsManager` javadoc 引用不存在的 `ProjectManagerJFrame`**（现为 `ProjectFrame`）。
7. **`MainFrame.width()` 语义与 GLFW 实现不同**（逻辑像素 vs framebuffer 像素），
   当前消费方（帧循环只用 surface 尺寸）不受影响，但若未来有按窗口宽高的逻辑需留意 dpi。
8. ~~**`importer/shader` 实现缺失**~~——**已落地（2026-09-26）**：`ShaderParser/ShaderInjector/ShaderImporter` 进 main 源集，.shader 经解析、注入、shaderc 编译产出 Capsule 二进制资产（§6.3）；`SimpleMeshImporter` 的产物落盘同日补齐（Mesh 存储即 `ArrayList<MeshAttributeData>` 节点列表，序列化字段直通零转换）。
9. `EditorApp.main()` 仍是旧的 `new ProjectFrame()` demo 入口；正式入口在 `ProjectLoadingFrame`。

## 10. 演进方向

- 二期：编辑器基本功能（场景编辑 UI ↔ 引擎 Scene 回写，待编辑器 UI 栈拍板）
  ——**场景树对接已落地（2026-09-30，待实测验收）**：HierarchyPanel 真数据
  （/scene/tree 帧通道拉取 + /scene/select 写 EditorSelection 单源）、
  hierarchy 右键域首批命令（create.empty/delete，只写不发通知）、
  SceneWatchAppState 快照 diff 自动通知（结构→scene.changed 重拉整树 /
  改名→scene.nodeRenamed 定点）、hierarchy 右键菜单统一走 Swing
  （/menu/open 传域 id+鼠标坐标弹 JPopupMenu，v0.4 定档——Web 右键浮层实测仍被
  视口 Canvas 盖，见 Editor_Menu_Design §1.1）；Inspector/project 面板对接待续。
- ~~shader 导入器（依赖 javelin-tool .shader 解析器，见材质档下一步）~~——已落地（2026-09-26，实现在 editor 自身而非 javelin-tool）
- 桥接包扫描收窄 + `JavaScriptBridge` 推送通道激活（`CefFrame.send`）
- 内置资源索引生成挪入 javelin-tool，编辑器构建瘦身
