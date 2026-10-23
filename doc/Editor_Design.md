# 编辑器模块架构设计（v0.1）

> 本文档描述 `javelin-editor` 模块的整体架构。模块尚在快速演进期，文档随代码同步更新。
> 最近一次全面梳理：2026-09-25（含本次窗口接入引擎的改造）；2026-09-28 增补设计基准（完全参考 Unity）。
> 2026-10-04 §10 增补变换 Gizmo v0.1（移动三轴）落地记录；2026-10-05 §10 更新为工具族架构（事件驱动选中 + 距离自适应 + 引擎渲染激活过滤）。
> 2026-10-06 §10 增补编辑器物体拾取终态拍板（GPU ID-buffer，后置实现）；同日 §10 增补变换 Gizmo 拖拽落地记录（含 hover 高亮变色与轴近平行不可选变灰，2026-10-14 随拖拽用户验收）；同日 §10 增补 Transform 变更检测落地（NodeTransformChangeEvent 帧级快照 diff，2026-10-23 用户验收）。
> 2026-10-14 §10 变换 Gizmo 拖拽用户实测验收通过（二期编辑器最小闭环 §2.4 主体收官）。
> 2026-10-14 §10 增补场景视图工具栏（Tools 组 + 局部/世界坐标切换；含游戏视图同高占位，当日实测验收通过）。
> 2026-10-17 **UI 栈重构拍板：编辑器去 Web 化（纯 Swing + FlatLaf），设计档=Editor_Swing_Design.md**；
> 本文 §1 的技术栈描述与 §3/§5 的窗口/桥接章节随 M1 落地后按新架构改写，rectChange 挖洞链、
> §9.10 补链等随 M1 废除（见重构档 §2 废除清单）。
> 2026-10-18 M6 提前收官：jcef 桥包/jcefmaven/jackson 与 engine-editor-web 前端工程整体清退
> （Editor_Swing_Design §7.1/§9）——本文 §5 桥接相关描述全部成历史沿革；§1/§3/§5 按新架构
> 改写的欠账仍挂（改写为独立批次，不随清退顺手做）。
> 2026-10-23 §10 拾取拍板三增补：编辑器拾取全线统一 GPU ID-buffer（轴拾取终态迁移、编辑器侧
> 射线拾取退役、CPU 过渡拾取开放项关账）+ 落点=pass 本体进引擎 core（运行时也可用，有意分歧
> Unity）+ 射线检测与 GPU 拾取共存（运行时 raycast 照常保留）。
> 2026-10-23 同日落地实现（当日用户实测验收）：RHI 读回环 + PickPass 覆盖制 + PickSystem + ViewportPickAppState
> + Gizmo 轴迁移（CylinderCollider 过渡线退役）——设计档=Engine_Picking_Design.md v0.1。

## 1. 定位与边界

`javelin-editor` 是 Javelin Engine 的桌面编辑器，技术栈为 **Swing + JCEF（Java Chromium Embedded）**：
前端 UI 是跑在 CEF 里的 Web 页面（Vite  dev server，如 `http://localhost:5173/page/main.html`），
后端是 JVM 内的引擎运行时。一块 AWT `Canvas` 嵌入主窗口作为 Vulkan 渲染呈现面。

**设计基准：完全参考 Unity**（拍板 2026-09-28）。编辑器的交互口径、面板组织、命名与
概念体系以 Unity Editor 为准绳——已对齐先例：Scene 视图相机控制（`EditorCameraAppState`）、
`viewportRect`（0-1 左下原点）、Layer/Tag/LayerMask、Hierarchy/Inspector/Project/Console
面板命名、Node≈GameObject 的场景模型、变换 Gizmo 移动三轴（`TransformGizmoAppState`，
RGB=XYZ、Global 世界轴口径）。后续编辑器功能设计遇行为分歧时，**以 Unity 的
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
| `Dialog` | `JDialog` | 通用 CEF 对话框容器（`window/cef/dialog` 包，2026-10-11 落地待验收）：URL 传参构造（默认 dialog.html）、模态 `APPLICATION_MODAL` 挂 MainFrame（未创建时无主屏中央）、`DISPOSE_ON_CLOSE` 连 CefBrowser 跟手销毁；不持单例、调用方管理实例；首个消费方=构建·打包对话框（独立前端页 page/pack.html） |
| `CefFrame` | `JFrame` | 抽象基类：封装 `CefBrowser` 创建/关闭、`loadURL`、子类布局钩子 `addJcefComponent` |
| `CanvasSurface` | `Canvas` | 引擎 `RenderSurface` 实现（见 §5） |

三个窗口各持静态 `instance` 单例（前端桥反查用）。
`Dialog` 为运行期按需实例（无单例、关闭即 dispose 销毁浏览器，重开新建）。

## 4. 前后端桥接（jcef 包）

### 4.1 JS → Java：`JsJavaBridgeHandler`

- 类加载时扫描 classpath 下所有带 `@BridgeMethod` 注解**类**（包扫描参数当前为空串，即全量扫），
  反射实例化后注册其中带注解的**方法**；URL 规则：`类前缀 + 方法注解值`（方法注解为空时退化为方法名）。
- 前端发 `url::json` 格式 query，后端在专用线程池（8~16 核，队列 2000，AbortPolicy）异步执行，
  通过 `CefQueryCallback` 回传 JSON。
- 方法封装在 `JavaBridgeMethod`：JSON 反序列化（至多一个请求参数）→ 反射调用 →
  序列化返回；**方法可声明 `CefBrowser` 参数——注入当前调用者的浏览器实例**
  （2026-10-11，位置任意至多一个，供 `JavaScriptBridge.send` 向调用页定向实时推送；
  首个消费方=构建日志 `build.out.info/warn/error`——console.* 前缀保留给控制台面板）。
  异常按领域分类映射 HTTP 风格码（400 提示/校验、422 领域、500 未知、502 构建），
  并弹 tinyfd 原生错误框。当前桥方法：`JavelinMethod`（`/render/rce` 渲染区矩形）、
  `ProjectMethod`（项目管理）、`FileSystemMethod`（目录选择）。
- **注意**：所有桥方法在桥接线程池执行，不在 EDT；需要触 UI 必须自行 `SwingUtilities`。

### 4.2 Java → JS：`JavaScriptBridge`

静态工具，通过 `cefBrowser.executeJavaScript` 调前端 `window.__javelinBridge.onMessage(type, data)`。
`CefFrame.send()` 是预留入口（当前注释状态）。**线程口径：executeJavaScript 线程约束
未证实，对齐 rectChange 先例一律 EDT hop 后调用**（EventBridgeForwarder 与桥方法直推
同口径）。首个定向实时消费方=构建日志 build.out.*（BuildMethod 的 ConsoleOut 行 →
调用者浏览器，见 §4.1；类型 `build.out.*`，console.* 前缀保留给控制台）。

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
  **`SceneImporter`（.scene → TextImporter 解析验证 → BinaryExporter 二进制 GameScene
  产物，2026-10-11 落地、2026-10-23 用户验收）**——场景纳入管线：guid 真值源=伴生 .meta（.scene 首行是
  文本协议头不能走文件头自包含，与 loadScene 同口径；meta 缺 guid 拒绝导入）；解析即验证
  （坏档不落残产物）；保存联动=file.save 成功后 submitImport 异步重导（管线线程池，产物即时
  新鲜）；loadScene 不动（仍读 Assets/ 文本源，产物供运行时/打包按 guid 消费）。已知边界：
  连续快速保存的同文件导入任务可能在多 worker 交叠写同一产物（极低概率、下次保存自愈），
  同文件任务去重归管线后续。
- `AssetImportPipeline`：生产者 `refresh()`（扫监听目录，mtime 变化或产物缺失才提交）/
  `reimportAll()`（清 Library 全量）；消费者为 CPU 核数个工作线程 + 无界 LinkedBlockingQueue；
  `ImportTask` 记录状态/耗时/错误。
- `BuiltinAssetReleaser`：按构建期 `builtin-asset.index` 把 classpath 内置资源释放到
  `.javelin/assets/{name}/` 并写 meta（资源自带 GUID 文件头校验）。**释放完须重写 Library
  索引（2026-10-17 修复）**：MetaManager 构造器 sync/updateIndex 早于释放执行
  （`.javelin/assets` 尚空，内置条目被漏），而 refresh 对自包含 guid 文件只查产物存在性
  （已存在即跳过、不再触发 updateIndex）——缺失条目永久滞留，场景 `@guid:` 引用解析为
  null 直至 RenderObject 崩；`releaseAll()` 末尾补 `metaManager.updateIndex()` 收口。
  已知边界（遗留待拍板）：2026-10-04「Mesh 产物自描述 guid」修正前导入的 `.smesh`
  旧产物不自动迁移（needImport 只查产物存在性）——guid null 致场景保存时内联空壳 Mesh
  （已上传、CPU 副本已释放），对策=删产物重导；是否加旧格式自动检测待议。

### 6.4 Library 管理（asset 包）

`ProjectAssetLibManager extends AssetLibManager`（core）：Library 目录 + `index.asset` 加载，
`loadIndex()` 失败降级 warn（首次打开项目无索引属正常）。
`AssetManager` 接口含 `listAssetPaths(suffix)`（2026-10-11 补）：按后缀枚举索引
相对路径（实现先重载 index.asset 再过滤——导入管线每次导入重写索引文件、内存表
不自动跟进；首个消费方=打包对话框启动场景下拉 /build/scenes）。

### 6.5 文件类型注册器（file 包，2026-10-18 落地、2026-10-23 用户验收）

`com.javelin.editor.file` 五件——`FileType` 接口（extensions 含点号小写 /
icon 裸名 / canOpen / open：EDT 调用、内部自管线程）+ `IconFileType`（纯图标型，
.java/.json 首批）+ `SceneFileType`（.scene：图标+打开）+ `FileTypeRegistry`
（静态门面：扩展名→类型 LinkedHashMap、重复注册 fail-fast、find 按文件名
endsWith 匹配）+ `FileTypeSetup`（集中显式注册，接线 MainFrame.create 早于
面板首帧/菜单条安装——对齐 MenuRegistry/PanelRegistry 先例，不做注解扫描）。

消费方两处：ProjectPanel 条目图标裁决/双击打开（原 entryIcon 硬编码 if 串退役：
目录仍走路径规则，文件查注册表、未注册=unknown）；project.open 右键命令占位
转实做（OpenFileMenuCommand，enabled=选中文件 canOpen，与双击同一条分发链）。

场景打开（SceneFileType.open，Unity Open Scene 口径）：脏场景三键确认（用户
拍板 2026-10-18：保存并打开——复用 `SaveSceneMenuCommand.save`（本批抽出
`public static boolean` 返回成败，失败即中止打开）/ 不保存 / 取消，父窗
MainFrame）→ `submitTask` 帧边界单任务（save（可选）→ TextImporter 导入 →
meta guid 同步（口径同 loadScene）→ `setGameScene` → `setSelectedNode(null)`
选中链复位（Gizmo/TransformWatch/Inspector 经 SceneSelectEvent）→ clearDirty
（新场景即净）→ `SceneChangedEvent(false)` 树/检查器重拉）；失败 error 日志 +
tinyfd 弹窗（同 file.save 隔离口径）。首批注册（拍板）：.scene/.java/.json
三扩展（面板视觉零变化），.shader/.smesh 等要用时再补。

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
10. **窗口最大化时 JCEF 面板不跟随 resize（2026-10-17 实测修复）**：拖拽正常、最大化
    显示不全。根因=三层协作时序坑：content pane 为 null 布局，jcef UI 组件尺寸全靠
    componentResized 监听手动 setBounds；jcef（CefBrowserWr）原生窗口尺寸由
    setBounds→wasResized、ancestorResized/Moved→doUpdate→updateUI、paint→doUpdate
    三条事件链驱动，doUpdate 读的是内层 heavyweight canvas_ 的**当前**边界。拖拽每步
    都有一波事件、陈旧读数被下一波覆盖（自然收敛）；最大化只有一波事件，jcef 在内层
    canvas_ 布局到新尺寸**之前**触发的 doUpdate 让原生窗口最后收到旧尺寸、风暴过后
    无事件再纠正——实测症状即 AWT（jecf bounds）与 CEF 内部（JS rectChange）均已
    新尺寸、原生可见绘制区停在旧值。修复（`MainFrame.addJcefComponent`/
    `syncJcefBounds`）：windowStateChanged 后等事件风暴排空（两拍 invokeLater +
    100ms Timer），手动补跑 jcef 全链 setBounds→validate→paintImmediately
    （→doUpdate→updateUI），用最终尺寸覆盖陈旧值。教训：**null 布局下 jcef 组件
    resize 不是 setBounds 一条腿，原生窗口靠事件链收敛，最大化这类单发场景必须
    显式补链**。
11. **窗口拖拽 resize 期画面非等比拉伸（2026-10-17 实测拍板：接受现状）**：拖动中
    文字/画面压缩/拉宽，松手立即恢复。机制=窗口客户区每步即变、交换链图像停留在
    旧尺寸等下一帧 renderFrame 里 recreate，失配期 DWM 把旧图非等比铺满新客户区
    （整帧形变，字形最显眼）；拖动期每中间尺寸付一遍交换链重建+图缓存失效重编+
    DebugText 顶点 buffer/深度纹理重分配的贵帧，失配窗口肉眼可见。重建路径本身
    健康（松手即愈）。**拍板：瞬时观感瑕疵不修**；若将来要治，刀口=resize 抖动期
    不追中间值（顶点 buffer/深度 MSAA 纹理按上限预留或拖动结束后再重分配），
    把失配窗口压回一帧。DebugText 侧文本消失（最长一拍 1Hz 刷新窗）已于同日修
    （网格 resize 交集保留，Engine_DebugText_Design §5）。

## 10. 演进方向

- **UI 栈重构（2026-10-17 拍板，设计=Editor_Swing_Design.md）**：主窗口弃 JCEF/Web，改纯
  Swing + FlatLaf（动因=插件扩展 UI 边界/混排坑族/CEF 进程成本）；首批（M1）=窗口 + 自研
  Dock 框架骨架 + 视口 ViewportTabs 接入 + 面板占位，之后按 M2~M6 逐面板实装直至 jcefmaven
  移除。视口 CanvasSurface 零拷贝路径不动。**引擎自绘 UI（Godot/Unity 形态）为远期终局**，
  PanelRegistry/命令域/事件总线的抽象届时可平移，Swing 是过渡宿主。
- 二期：编辑器基本功能（场景编辑 UI ↔ 引擎 Scene 回写，待编辑器 UI 栈拍板）
  ——**场景树对接已落地（2026-09-30，2026-10-04 用户实测验收通过）**：HierarchyPanel 真数据
  （/scene/tree 帧通道拉取 + /scene/select 写 EditorSelection 单源）、
  hierarchy 右键域首批命令（create.empty/delete，只写不发通知）、
  SceneWatchAppState 快照 diff 自动通知（结构→scene.changed 重拉整树 /
  改名→scene.nodeRenamed 定点 / **变换→scene.nodeTransformChanged 定点——
  TransformWatchAppState 帧级快照 diff（2026-10-06 落地、2026-10-23 用户验收，用户拍板：
  帧级检测/载荷带新 TRS/置脏/**只盯当前选中节点**——消费方=Inspector 选中
  面板，全场景遍历无消费方；写入方零通知义务，选中节点实变自动发现同帧推送
  + 场景置脏星号亮；SceneSelectEvent 驱动 volatile 选中节点、选中变更静默
  重建基线、归属校验覆盖已删/卸载——机制细节见类 javadoc）**）、
  hierarchy 右键菜单统一走 Swing
  （/menu/open 传域 id+鼠标坐标弹 JPopupMenu，v0.4 定档——Web 右键浮层实测仍被
  视口 Canvas 盖，见 Editor_Menu_Design §1.1）；Inspector 面板对接待续（将消费
  scene.nodeTransformChanged，并配节点数据拉取口做 pull-on-mount 兜底；
  project 面板已落地，见下条）。
- **项目面板已落地（web 版 2026-10-17 随去 Web 化清退；Swing 版 2026-10-18 落地、2026-10-23 用户验收，
  Editor_Swing_Design §7.6）**：Unity Project 窗口布局——左树仅目录（全量树目录
  投影）+ 右区显示选中目录直接子项（目录优先+名称序）+ 面包屑逐级跳转 + 目录
  双击进入，JSplitPane 分栏可拖拽；数据源 `ProjectTreeSnapshot` 全量树快照
  （后台线程纯 IO 遍历——不涉引擎状态无需帧队列，仅暴露 Assets/src 两根，隐藏
  项与伴生 .meta 过滤，目录优先+名称序）；选中直写 EditorSelection project 通道
  （域菜单上下文源；Node 直持 File 天然不越项目根——原桥「相对路径上报+解析
  闸门」随直调消亡，目录变更即上报含面包屑导航）；右键 ctx.project 域三入口
  （左树目录/右区条目/空白=当前目录语境，先落选中再弹菜单）；图标裁决查
  FileTypeRegistry（§6.5，目录仍路径规则：resourcesRoot/sourceRoot/package/
  folder；文件未注册=unknown）；文件双击分发注册表打开（.scene 打开场景）。
  ~~遗留：project.changed 刷新事件~~——**已接线（2026-10-19）**：首个改盘命令
  project.create.material（.mat 创建，随材质导入管线落地）发布 project.changed、
  面板订阅重拉快照（lastSelectedDirPath 恢复目录锚点）；创建/删除/重命名/粘贴
  其余命令第二批（改盘命令家族逐个补发布）。
- **变换 Gizmo v0.1 视觉版已落地（2026-10-04；2026-10-05 用户重构为工具族架构）**：
  `appstate.gizmo` 包三件套——`TransformGizmoAppState`（驱动面）+ `TransformTools`
  基类（init/update/getName/show/hide/scaleFactor 契约）+ `MoveTool`（axisY.smesh
  +Y 箭头复用旋转出 X/Z：X 绕 Z -90°、Z 绕 X +90°；gizmo.shader unlit 纯色
  `_Color` 按轴红/绿/蓝，Cull Off 容忍外部工具导出混合绕序）。`gizmoNode`
  常驻编辑子场景（不入用户树、不落盘），工具子树挂其下；选中**事件驱动**——
  `SceneSelectEvent`（scene.select，payload nodeId）经 EditorSelection
  setSelectedNodeId 收口发布（桥上报/创建命令自动选中一处全覆盖）；位置跟随写
  gizmoNode（工具无关）；**距离自适应缩放** = 相机距离 / TOOLS_SCALE ×
  scaleFactor()（屏幕恒定尺寸口径）。显隐与工具切换走节点 setActive——配套
  **引擎侧渲染过滤**（SceneRenderBridge 按 isActiveInHierarchy 过滤，Unity
  inactive 口径，2026-10-05 拍板；setActive 只影响生命周期状态机藏不住渲染）。
  **置顶（两层深度语义，用户拍板 2026-10-05 定稿）**：GizmoPass 挂管线末尾
  （addPass——RenderPipeline 本轮补齐 removePass，「增删/插入」闭环）——颜色
  LOAD 保画 + 深度 CLEAR：gizmo 对外恒在场景之上不被遮挡（编辑操作对象始终
  可见）、内部箭头互有层次（ZWrite On 自写自测、画序内遮挡正确）；同日曾试
  深度 LOAD（被场景遮挡的立体咬合口径）按定稿需求切回，将来遮挡透视感可加
  Greater 鬼影变体递进；绘制对象按 gizmo.shader 实例身份过滤（材质
  getShader 同一性，全工具共用；不走 Layer——层表为二进制设置文件驱动，
  现有项目无 Gizmo 层需迁移）。
  **拖拽落地（2026-10-06；2026-10-14 用户实测验收通过）**：TransformTools 契约增拖拽三段式
  `beginDrag(axisNode, target, pickRay, camWorldPos) / updateDrag(target, pickRay) /
  endDrag()`——驱动侧管输入状态机（LMB 边沿/持续态、hover 检测与锁定、目标
  有效性），工具自持拖拽几何、按各自语义 apply（旋转/缩放将来各自实现）。
  驱动侧状态机：LMB 按下命中柄（hover 当帧结果）交所属工具 beginDrag、按住
  每帧 updateDrag（hover 锁定拖拽轴不重检测）、释放 endDrag；**Alt+LMB 归相机
  环绕不抢**（拖拽中按 Alt 与环绕并动为已知边缘）；按下/释放同帧（极速点击）
  直接收尾防拖拽态卡死；拖拽绑定开始时目标——**选中变更/目标失效强制结束**
  防跳变（含 onDetach 收尾）。MoveTool **平面相交法**（Unity 同款）：拖拽平面
  = 过锚点（按下时目标世界位置）、含拖拽轴、对相机最正交（法线 = 视线去掉轴
  分量，轴与视线近平行回退世界基向量）；每帧鼠标射线与平面求交，交点沿轴的
  投影差 = 世界位移（screenToRay 方向归一化 → 投影即世界距离；初始投影 offset
  补偿，按下瞬间零位移不跳）；轴方向 = 轴箭头节点世界 +Y（X/Z 经节点旋转指向，
  距离自适应均匀缩放 normalize 后方向不变——将来 Local 模式 gizmoNode 随节点
  旋转后同一代码直通）；世界位移经父世界矩阵逆回写 local（父旋转/缩放下世界
  平移 ≠ local 平移，每帧重取防父链陈旧）。
  **视觉三态（2026-10-06 用户需求：hover 变色 + 不可选变灰）**：hover 与拖拽
  统一走驱动侧每帧 `updateVisuals(highlightedNode, viewDir)` 同步契约（同步式
  而非 enter/leave 事件式——松开拖拽时鼠标往往仍悬停轴上，事件式会错误熄灭；
  拖拽中 hover 锁定即被拖轴）；**viewDir = 相机→选中目标方向、与鼠标位置无关**
  （用户拍板 2026-10-06——首版误用鼠标射线：屏幕边缘的射线偏角会被算进夹角，
  变灰随鼠标位置漂移）；三态 = 正常 RGB / 高亮黄（hover 与拖拽共用）/
  不可选灰——不可选判定与 isSelectable 同源（视觉与可选性一致）、优先级
  高亮 &gt; 变灰（hover 入口已被可选性闸门，高亮轴必可选）；工具按状态数组
  幂等写色（每轴独立 Material 实例，MaterialBlock 每次调用重打包 setColor
  即时生效）；gizmo 整体隐藏（无目标）时清 hover 并熄灭高亮防残留。
  **轴近平行不可选（2026-10-06 用户需求）**：`isSelectable` 契约（默认 true、
  工具按几何语义裁决——旋转柄几何相反正对最好抓）：移动轴与视线 |cos| 夹角
  超阈值（0.985 ≈ 10°，实测可调）时不可选并变灰（0.5 灰，实测可调）——屏幕上
  轴端视退化为点、拖拽投影被 1/sin 放大病态（俯视场景时 Y 轴让位 XZ 拖拽不被
  误抓）；射线检测按此过滤命中候选（不可选视同未命中、更远可选候选仍可胜出）。
  位置跟随后于拖拽 apply——同帧跟手不滞后一帧。缩放/旋转工具形态待续；拾取
  判定半径抓取舒适度加粗为实测后调项。
- **场景视图工具栏（2026-10-14 落地、当日用户实测验收通过）**：视口上方常驻条——SceneViewportPanel
  改列布局（工具栏 24px + 视口占位 flex:1），视口 rect 上报仍只测视口占位 div，
  Canvas 挖洞矩形自动让位（ResizeObserver 重报兜底）。内容：Tools 组（移动可用；
  旋转/缩放置灰占位——工具本体未落地）+ 竖分隔 + 局部/世界二段切换；新组件
  `JeToolButton`（icon/文本通用、active=accent 反色、disabled 置灰、间距≤2、色板
  var(--editor-*)）。前端本地态即点即亮（fire-and-forget 对齐 setModel 风格）、
  不持久化（重启回 移动+世界）。桥=新域 `/gizmo`（`GizmoMethod`：setTool/setSpace
  两小方法 + Par，ClassUtil 扫描自动注册；未知值 warn+忽略对齐 setModel 口径）；桥线程
  只写 `TransformGizmoAppState` 的 **static volatile 意图镜像**（跨线程镜像先例=
  GridMenuCommand——扫描反射实例化无构造注入、AppStateManager 非并发勿跨线程
  getState），update 帧内消费：切工具先 forceEndDrag 再旧 hide 新 show
  （TransformTools 契约预留的切换位兑现；工具名 getName() 大小写不敏感解析、线上
  惯例小写）；坐标空间仅影响 Gizmo（Inspector 恒 local，Unity Global/Local 口径）——
  Local = gizmoNode 每帧旋转对齐目标世界旋转（父链四元数自根向下 mul 组合，避开从
  含非均匀缩放的世界矩阵提旋转）、World = 恒单位（现状语义）；工具层轴方向/hover/
  变灰走箭头世界矩阵，gizmoNode 旋转后同一代码直通（设计预留兑现、MoveTool 零改动）。
  游戏视图同高占位空条（GameViewportPanel 同列布局、视觉规格一致；内容留待 Game
  视图工具设计——Unity 口径为显示比例/缩放/Stats 一族）。快捷键（W/E/R）与
  rotate/scale 工具本体留扩展位。
- **编辑器物体拾取终态拍板（2026-10-06 用户）：GPU ID-buffer，后置实现**——拾取 pass
  渲染场景时每个可拾取物体把自己的 pick ID（帧内稳定分配，0=背景/未命中）写进离屏整数附件
  （R32Uint，深度测试照常——近者胜），点击帧读回该像素 ID 即得选中对象（Unity 编辑器同路线，
  像素级精确、大网格零拾取代价）。**不依赖用户物体挂 Collider、不给物体隐藏挂载网格碰撞体**
  ——编辑器需求不进用户场景数据面（gizmo 轴挂 CylinderCollider 是编辑器自有节点，性质不同）。
  RHI 前置=拾取附件 + 图像读回路径（CopyImageToBuffer→staging→map，可 1×1 region 按点击坐标拷贝）；
  **实现后置、非当前排期**。
- **拾取全线统一拍板（2026-10-23 用户）：编辑器拾取一律 ID-buffer，编辑器侧射线拾取退役**——
  原开放项「过渡期先落 CPU 数学拾取（screenToRay→世界 Aabb 粗筛→逆变换进本地→三角形 MT 精判
  比 t——碰撞 L0 零件已全验收齐备）」就此**关账不落**：射线拾取场景物体绕不开给物体挂碰撞体/
  网格碰撞体，正违反本拍板初衷。Gizmo 轴拾取终态同样统一进 ID 附件（轴几何各写 pick ID），
  现役 CylinderCollider 轴拾取为过渡实现，ID-buffer 落地时迁移。
- **拾取落点拍板（2026-10-23 用户）：pass 本体进引擎 core，运行时也可用**——拾取 pass/ID 分配/
  读回调度归 core（默认不挂、按需启用），编辑器与运行时游戏同为消费方。**有意分歧 Unity**：
  Unity 拾取是编辑器内部实现（HandleUtility.PickGameObject 仅 UnityEditor 命名空间），运行时
  无对应 API——运行时拾取只有 Physics.Raycast+Collider 一条路、无 Collider 拾取是其已知短板，
  短板不抄（「分歧以 Unity 为默认答案」就此处显式越过并记录在案）。
- **射线检测与 GPU 拾取共存（2026-10-23 用户）**：射线**拾取**退役≠射线退役——游戏内大多走
  射线检测（射击命中/AI 感知等），运行时场景级 raycast（Collider 组件注册表，归 Ray 期）照常
  保留，与 ID-buffer 拾取两条 API 并存不冲突：射线=物理查询语义（有 Collider 才打得中），
  ID-buffer=渲染拾取语义（看得见即拾得到）。
- **GPU 拾取落地记录（2026-10-23，待用户实测验收——设计档=Engine_Picking_Design.md v0.1）**：
  RHI 读回环（TextureReadbackRing 2 槽非阻塞）+ 覆盖制 PickPass（内嵌 pick.spv，拾取 pass 不消费
  材质 Pass 声明）+ PickSystem 双通道（hover/click，帧表 3 环防串帧）+ ViewportPickAppState 消费
  （点击门控/选中收口）+ **Gizmo 轴拾取同批迁移**（hover 源改 PickSystem 直写、拖拽 pickRay 保留
  纯数学、MoveTool 摘 CylinderCollider——CPU 射线过渡线退役）；透明物体可拾取（Unity 口径同批
  确认）；已知边界（hover 1~2 帧延迟、多视口后置）见该档 §8，验收清单 §9。
- ~~shader 导入器（依赖 javelin-tool .shader 解析器，见材质档下一步）~~——已落地（2026-09-26，实现在 editor 自身而非 javelin-tool）
- 桥接包扫描收窄 + `JavaScriptBridge` 推送通道激活（`CefFrame.send`）
- 内置资源索引生成挪入 javelin-tool，编辑器构建瘦身
