# 编辑器模块架构设计（v0.1）

> 本文档描述 `javelin-editor` 模块的整体架构。模块尚在快速演进期，文档随代码同步更新。
> 最近一次全面梳理：2026-09-25（含本次窗口接入引擎的改造）；2026-09-28 增补设计基准（完全参考 Unity）。
> 2026-10-04 §10 增补变换 Gizmo v0.1（移动三轴）落地记录；2026-10-05 §10 更新为工具族架构（事件驱动选中 + 距离自适应 + 引擎渲染激活过滤）。
> 2026-10-06 §10 增补编辑器物体拾取终态拍板（GPU ID-buffer，后置实现）；同日 §10 增补变换 Gizmo 拖拽落地记录（含 hover 高亮变色与轴近平行不可选变灰，2026-10-14 随拖拽用户验收）；同日 §10 增补 Transform 变更检测落地（NodeTransformChangeEvent 帧级快照 diff，2026-10-23 用户验收）。
> 2026-10-14 §10 变换 Gizmo 拖拽用户实测验收通过（二期编辑器最小闭环 §2.4 主体收官）。
> 2026-10-14 §10 增补场景视图工具栏（Tools 组 + 局部/世界坐标切换；含游戏视图同高占位，当日实测验收通过）。
> 2026-10-17 UI 栈重构拍板：编辑器去 Web 化（纯 Swing + FlatLaf），设计档=Editor_Swing_Design.md。
> 2026-10-18 jcef 桥包/jcefmaven/jackson 与 engine-editor-web 前端工程整体清退（Editor_Swing_Design §7.1/§9）。
> 2026-10-23 本文 CEF/Web 历史清理：§4 桥接章删除（jcef 体系已不存在）、§1/§2/§3 改写为纯 Swing 架构、§9 历史/失效条目移除——原「按新架构改写」欠账销账。
> 2026-10-23 §10 拾取拍板三增补：编辑器拾取全线统一 GPU ID-buffer（轴拾取终态迁移、编辑器侧
> 射线拾取退役、CPU 过渡拾取开放项关账）+ 落点=pass 本体进引擎 core（运行时也可用，有意分歧
> Unity）+ 射线检测与 GPU 拾取共存（运行时 raycast 照常保留）。
> 2026-10-23 同日落地实现（当日用户实测验收）：RHI 读回环 + PickPass 覆盖制 + PickSystem + ViewportPickAppState
> + Gizmo 轴迁移（CylinderCollider 过渡线退役）——设计档=Engine_Picking_Design.md v0.1。
> 2026-10-24 §10 增补视口聚焦（层级双击 + F 键 → 编辑器相机平滑框住选中物；拍板=双击一律聚焦、
> 展开/折叠只认三角手柄、F 键同批、平滑过渡）。
> 2026-11-08 §6.3 增补导入期引用解析壳管理器（`ShellAssetManager`）——首次导入依赖产物竞态
> 修复（FileNotFoundException 根治：导入解析与依赖产物生成解耦；Material 残档拒绝口径不变，
> 当日用户实测验收）。
> 2026-11-08 §10 变换 Gizmo 三工具齐备：旋转/缩放工具落地 + 几何改程序化生成（`GizmoMeshes`
> 照搬 Babylon 规格，`axisY.smesh` 退役）+ 缩放恒局部；**同批柄拾取改 CPU 碰撞体射线**
> （可见几何与拾取体解耦——「轴太细/环太粗」根治，Gizmo 不再进 GPU 拾取附件）；
> 2026-11-08 用户实测全项通过验收（含三工具同屏修正）。

## 1. 定位与边界

`javelin-editor` 是 Javelin Engine 的桌面编辑器，技术栈为 **纯 Swing + FlatLaf**（2026-10-17
去 Web 化拍板，CEF/Web 前端已整体清退）：面板/Dock/菜单全 Swing 组件，一块 AWT `Canvas`
嵌入主窗口作为 Vulkan 渲染呈现面（零拷贝）。UI 框架设计见 `Editor_Swing_Design.md`。

**设计基准：完全参考 Unity**（拍板 2026-09-28）。编辑器的交互口径、面板组织、命名与
概念体系以 Unity Editor 为准绳——已对齐先例：Scene 视图相机控制（`EditorCameraAppState`）、
`viewportRect`（0-1 左下原点）、Layer/Tag/LayerMask、Hierarchy/Inspector/Project/Console
面板命名、Node≈GameObject 的场景模型、变换 Gizmo 移动三轴（`TransformGizmoAppState`，
RGB=XYZ、Global 世界轴口径）。后续编辑器功能设计遇行为分歧时，**以 Unity 的做法为默认答案**；仅当引擎架构差异确实无法对齐时才
自行设计，并在对应设计档注明偏离点与理由。

核心职责：

1. **项目生命周期管理**：新建 / 打开 / 最近项目列表
2. **资产管线**：GUID 元数据（`.meta`）、导入器 SPI、Library 产物管理、内置资源释放
3. **引擎宿主**：`JavelinEditor extends JavelinEngine`，主窗口 `MainFrame` 实现引擎 `Window` 契约
4. **Swing UI 框架**：Dock / 面板注册 / 菜单体系（设计档=Editor_Swing_Design.md）

**明确不做的事**：游戏逻辑运行、场景编辑逻辑（二期规划）、输入系统（归引擎 core）。

## 2. 依赖关系

```
javelin-editor
 ├─ javelin-core（JavelinEngine / Window / AssetLibManager / 序列化 / settings 模型）
 ├─ javelin-rhi、javelin-tool、javelin-log
 ├─ javelin-ast（annotationProcessor，@Reflective 反射注册）
 └─ 第三方：
    ├─ gradle-tooling-api 8.14.3（项目编译 / nativeCompile）
    ├─ lwjgl-jawt（Canvas HWND）、lwjgl-tinyfd（原生文件对话框/错误弹窗）
    ├─ jsvg（Swing SVG）
    └─ slf4j、joml
```

构建附带 `generateBuiltinAssetIndex` 任务：扫描本模块 `builtin-asset-editor` 资源目录，
生成 `builtin-asset.index`（构建期生成进 resources），供内置资源释放器索引。**索引
分模块生成（2026-11-07）**：引擎级内置资产 `builtin-asset-core` 已迁回 `javelin-core`
模块（`javelin-core/build.gradle` 同名任务产出自己的索引），释放器按
`ClassLoader.getResources` 枚举全部 `builtin-asset.index` 合并释放、模块各扫各的。

## 3. 启动流程与窗口体系

编辑器按「三大窗口」组织启动序列（全 Swing，CEF 运行时零使用）：

```
ProjectFrame（启动器：最近列表 + 新建/打开三模式）
  └─ 打开项目 → ProjectLoadingFrame（加载页）
       └─ 后台线程 "Engine Main"：
            GradleProject → PluginManager 装载（先于资产初始化）
            → MetaManager → ProjectAssetLibManager
            → AssetImportPipeline → BuiltinAssetReleaser
            releaseAll() → refresh() → startAndWait() → loadIndex()
            └─ JavelinEditor extends JavelinEngine → start()
                 └─ JavelinEngine: createWindow()=MainFrame → create() → showWindow()
                      └─ MainFrame（主编辑窗口，纯 Swing + FlatLaf + 自研 Dock）
                           └─ ViewportTabs 单 Canvas + CanvasSurface（Vulkan 呈现面，Dock 布局驱动）
```

| 窗口 | 基类 | 说明 |
|------|------|------|
| `ProjectFrame` | `JFrame` | 启动器：最近列表 + 新建/打开三模式（tinyfd 选目录） |
| `ProjectLoadingFrame` | `JFrame` | 无装饰加载页；承载插件装载、资产初始化与引擎启动引导链 |
| `MainFrame` | `JFrame` | 主编辑窗口；实现引擎 `Window` 接口（见 §5）+ Dock/面板/菜单体系 |
| `CanvasSurface` | `Canvas` | 引擎 `RenderSurface` 实现（见 §5），挂 ViewportTabs 单 Canvas |

UI 框架（Dock 二分布局树 / PanelRegistry 面板注册 / 视口单 Canvas 不 reparent）全量设计在
`Editor_Swing_Design.md`。

## 4. 前后端桥接

> 本章已清（2026-10-23）：jcef 桥接体系（JsJavaBridgeHandler / JavaScriptBridge / JcefCreator /
> CefFrame）已随 2026-10-18 去 Web 化整体删除，历史描述不存档。编辑器 UI 与数据链现状 =
> Swing 直调（面板/命令/检查器全 Java 内调，设计档=Editor_Swing_Design.md）；跨线程事件走
> editor.event 事件总线（契约在类 javadoc）。

## 5. 引擎接入（window/cef 包）

### 5.1 MainFrame implements Window

按 `GLFWWindow` 语义对齐（`doc/Engine_Window_Design.md`）：

- 生命周期幂等：`create()`（隐藏创建）→ `showWindow()` → `close()`；closed 后重开抛异常
- `DO_NOTHING_ON_CLOSE` + `windowClosing` 只置 `close` 标志，引擎轮询 `closeRequested()` 后调 `close()` 收尾
- `width()/height()` 返回**窗口逻辑像素**（`getWidth/Height`，未创建回退 setting）；
  物理像素换算在 `RenderSurface` 侧
- `isResized()/acknowledgeResize()` 委托 canvas；`isVisible()` = shown 且非最小化
- `renderSurface()` 未创建时抛异常
- 渲染区矩形：由 Dock/ViewportTabs Swing 布局直接驱动 canvas 边界（M1 起无前端上报链）

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
- **guid 真值单源 = 伴生 `.meta`（2026-10-29 拍板，推翻「文件头真值 + meta 镜像」
  双源形态）**：guid 头（`// @javelin-guid:`）**只剩 `src/main/java` 下 `.java` 专属**
  （代码树不产 meta、头是唯一真值，GuidHeaderManager 仅服务脚本）；`.shader`/`.smesh`
  及其余一切资产 guid 真值=伴生 `.meta`（createNewMeta 一律随机 guid），头不再是真值
  载体。**内置资源 `.meta` 随包一起打包释放**：`builtin-asset-*` 资源同目录提交伴生
  `.meta`（guid=原文件头值迁移——guid 全程不变，既有场景引用不断、旧项目零迁移）。
  起因=焦点扫描死循环（Engine_AssetReload_Design §5.1 坑点）：v0.16「用户 .shader 不写
  guid 头」与管线 `libraryFileFor`「按文件头解析产物 guid」组合——无头文件产物恒判
  缺失，每轮焦点扫描重提交、模态框失焦/关框回焦点自持循环。
- **唯一无 meta 例外：`src/main/java` 下 `.java`**（代码树不产 meta；脚本导入器
  未落地、meta 无消费方——头 GUID 脚手架保留，meta 形态待脚本功能真落地再拍，
  2026-10-25）。
- `MetaManager`：构造即 `sync()`（建缺、清孤儿、重建缓存），维护 guid↔path 三向缓存，
  GUID 重复视为致命错误抛异常；`updateIndex()` 生成 `Library/index.asset`。
- 类型检测按扩展名粗分（Texture/Shader/Audio/Scene/Material/Script/Mesh/Data）。

### 6.3 导入管线（importer 包）

- `AssetImporter` SPI：扩展名注册表驱动（扫描实例化所有实现）。产物统一命名
  `Library/{guid}.asset`。**产物 guid 一律取伴生 meta 的 guid**（2026-10-29 单源
  拍板，推翻 2026-09-26「文件头优先、meta 兜底」双源——文件头只剩 .java 例外）。当前实现：`SimpleMeshImporter`
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
- `AssetImportPipeline`：生产者 `refresh()`（扫监听目录，`needImport` 统一判
  **源 mtime ≠ meta mtime** 或产物缺失才提交——`.java` 例外查产物存在性）/ `reimportAll()`
  （清 Library 全量）；消费者为 CPU 核数个工作线程 + 无界 LinkedBlockingQueue；
  `ImportTask` 记录状态/耗时/错误；导入成功回写 meta（导入设置 + mtime 对齐源文件，
  含 .shader/.smesh——其导入设置自此真持久化）。
- **导入期引用解析 = 壳管理器（`ShellAssetManager`，2026-11-08 落地并实测验收）**：`.texture`/
  `.mat`/`.scene` 文本源里的 `@guid` 引用在导入期**不再加载 Library 产物**——任务携带的管理器
  即壳：引用存在性按 `MetaManager.getMetaByGuid` 判定（meta 登记=资产存在），命中即
  `ReflectiveFactory` 造壳 + `setGuid` 返回；其余接口面显性抛错（导入只走 loadGuid，键
  `text.shellUnsupportedMethod`）。**根因（首启 FileNotFoundException 报障）**：索引登记以 .meta
  为准、不检查产物（MetaManager.collectIndexEntries）+ 导入多 worker 并行无依赖序——`.texture`
  可先于被引用 `.png` 解析，`loadGuid` 撞「索引有 guid、产物未落盘」直接开文件抛错（反向缺口=
  guid 未入索引时解 null、空壳静默导入）。**语义边界**：仅去掉「依赖产物必须已生成」——产物只写
  引用 guid（BinaryOutputCapsule 对 Asset 引用只写 TYPE_ASSET+guid），导出正确性与依赖导入完成
  解耦；「引用须指向项目内存在的资产（meta 登记）」保留，MaterialImporter 残档拒绝
  （shader=@null 或解不回→不落残产物）口径不变。接线 = `AssetImportPipeline` 构造改
  `(Project, MetaManager)`（不再收 AssetManager）、默认 `submitImport(File)` 任务携带壳；
  `submitImport(File, AssetManager)` 重载留作真实装载逃生口（当前无调用方）。
- `BuiltinAssetReleaser`：按构建期 `builtin-asset.index` 把 classpath 内置资源释放到
  `.javelin/assets/{name}/`，**伴生 `.meta` 随包同释放**（2026-10-29：`builtin-asset-*`
  同目录 `.meta` 一并打进包里；首次释放拷包内 meta + 补默认导入设置，包缺 meta=
  打包异常抛错显性化）。**内容字节比对驱动重导
  （2026-10-25）**：内容相同→跳过重拷 + meta 的 mtime 对齐当前文件（不触发重导；
  meta 缺失时从包内补释放）；内容变化→重拷且**不动 meta**（旧 mtime 与新文件 mtime 不等被 refresh 检出重导）；
  首次释放→拷包内 meta（mtime 保持 0 与文件 mtime 不等，首导入由 refresh 检出）。修掉的坑：旧实现 dev 每次
  启动无条件重拷（mtime 恒新）+ refresh 只查产物存在性——内置源更新永不重导，Library
  旧产物静默劫持运行时行为（**2026-10-25 gizmo 被 box 遮挡案根因**：10-04 旧产物仍是
  `Pass Forward` 声明、箭头落 Opaque 桶按场景深度绘制——排查法=产物时间戳 vs 源
  git 改动日期对照）。**释放完须重写 Library 索引（2026-10-17 修复）**：MetaManager
  构造器 sync/updateIndex 早于释放执行（`.javelin/assets` 尚空，内置条目被漏）；
  `releaseAll()` 末尾补 `metaManager.updateIndex()` 收口。
  已知边界（遗留待拍板）：2026-10-04「Mesh 产物自描述 guid」修正前导入的 `.smesh`
  旧产物不自动迁移（meta 补建时 mtime 已对齐、不触发重导）——guid null 致场景保存时
  内联空壳 Mesh（已上传、CPU 副本已释放），对策=删产物重导；是否加旧格式自动检测待议。

### 6.4 Library 管理（asset 包）

`ProjectAssetLibManager extends AssetLibManager`（core）：Library 目录 + `index.asset` 加载，
`loadIndex()` 失败降级 warn（首次打开项目无索引属正常）。
`AssetManager` 接口含 `listAssetPaths(suffix)`（2026-10-11 补）：按后缀枚举索引
相对路径（实现先重载 index.asset 再过滤——导入管线每次导入重写索引文件、内存表
不自动跟进；首个消费方=打包对话框启动场景下拉 /build/scenes）。

### 6.5 文件类型注册器（file 包，2026-10-18 落地、2026-10-23 用户验收）

`cn.donting.javelin.editor.file` 五件——`FileType` 接口（extensions 含点号小写 /
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
| EDT | Swing UI、窗口事件 | `MainFrame.close()` 的 `dispose` 经 `invokeLater` 回到 EDT |
| 引擎主线程 | `JavelinEditor.start()` 帧循环（`ProjectLoadingFrame` 以 `"Engine Main"` 线程启动） | `pollEvents()` 空实现（Swing 事件自发派发） |
| AssetImport-Worker 池 | 导入任务（CPU 核数） | `waitImportFinished()` 轮询等待 |

跨线程共享状态主要经 MetaManager 的 ConcurrentHashMap 缓存与导入任务队列。
跨线程场景访问统一走 `JavelinEngine.submitTask(Runnable)`（任意线程提交、
mainLoop 帧首消费、异常隔离，2026-09-30 场景树对接落地）；场景变更通知由
`SceneWatchAppState` 快照 diff 自动发现（0.25s 节流，基线静默），写入方零通知义务。

## 9. 已知问题与风险（检查结论）

1. **`ProjectLoadingFrame` 有 `ThreadUtil.sleep(2000)` 假等待**（纯为加载页观感）。
2. **导入成功即全量 `updateIndex()`**：每个任务成功都重扫监听目录重写索引，资产量大时是热点。
3. **`SettingsManager` javadoc 引用不存在的 `ProjectManagerJFrame`**（现为 `ProjectFrame`）。
4. **`MainFrame.width()` 语义与 GLFW 实现不同**（逻辑像素 vs framebuffer 像素），
   当前消费方（帧循环只用 surface 尺寸）不受影响，但若未来有按窗口宽高的逻辑需留意 dpi。
5. **窗口拖拽 resize 期画面非等比拉伸（2026-10-17 实测拍板：接受现状）**：拖动中
    文字/画面压缩/拉宽，松手立即恢复。机制=窗口客户区每步即变、交换链图像停留在
    旧尺寸等下一帧 renderFrame 里 recreate，失配期 DWM 把旧图非等比铺满新客户区
    （整帧形变，字形最显眼）；拖动期每中间尺寸付一遍交换链重建+图缓存失效重编+
    DebugText 顶点 buffer/深度纹理重分配的贵帧，失配窗口肉眼可见。重建路径本身
    健康（松手即愈）。**拍板：瞬时观感瑕疵不修**；若将来要治，刀口=resize 抖动期
    不追中间值（顶点 buffer/深度 MSAA 纹理按上限预留或拖动结束后再重分配），
    把失配窗口压回一帧。DebugText 侧文本消失（最长一拍 1Hz 刷新窗）已于同日修
    （网格 resize 交集保留，Engine_DebugText_Design §5）。

## 10. 演进方向

- **远期终局=引擎自绘 UI（Godot/Unity 形态）**：PanelRegistry / 命令域 / 事件总线的抽象届时可
  平移，Swing 是过渡宿主（2026-10-17 起 UI 框架现状=Editor_Swing_Design.md，去 Web 化迁移已收官）。
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
  `_Color` 按轴红/绿/蓝，Cull Off 容忍外部工具导出混合绕序——**2026-11-08 起几何改
  程序化、`Cull Back` 是真依赖，见本 § 下一新增条**）。`gizmoNode`
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
  位置跟随后于拖拽 apply——同帧跟手不滞后一帧。
- **变换 Gizmo 三工具齐备 + 几何程序化 + 柄拾取改道（2026-11-08 落地，当日用户实测全项通过）**：几何改由
  编辑器内 `editor/mesh/GizmoMeshes` **运行期程序化生成**（原 `axisY.smesh` 资产退役）——
  规格照搬 Babylon `Gizmos/`（`axisDragGizmo.ts:87-124` 箭头 = 锥 `0.075` 高 / 底径
  `0.0375` + 细杆 `0.005`/`0.275`；`axisScaleGizmo.ts:316-345` 缩放柄 = 同杆 + `0.04` 方块头；
  `planeRotationGizmo.pure.ts:451-472` 环 = 直径 `0.6`），几何按约 0.3 单位建模、真实尺寸
  由工具节点缩放统一给出（模型约定 = 柄本体朝 +Y、环躺 XZ 平面，节点一次旋转把 +Y 转到
  目标轴——与移动轴既有写法一致）。**绕序**统一朝外（右手系 `cross(v1-v0, v2-v0)`
  外向约定，与内置 plane/box.smesh 同源）→ `gizmo.shader` 的 `Cull Back` 是真依赖（原
  注释「Cull Off 容忍外部混合绕序」随资产退役一并纠正）。**拾取 = CPU 射线打「拾取体」**
  （2026-11-08 用户实机反馈后改判，回撤 10-23 的「轴拾取统一进 ID 附件」：ID 附件是
  「看得见即拾得」，轴细就不好选、环只能加粗可见管径换宽容度；改按 Babylon
  `colliderMeshes` 口径——**可见几何与拾取体解耦**）：移动/缩放轴挂加粗
  `CylinderCollider`（`AXIS_PICK_RADIUS = 0.022`，比可见杆粗约 9 倍且包住可见锥头）、
  中心均匀柄挂加粗 `BoxCollider`（`0.07`）、旋转环无对应 L0 形状 → **解析式环带**
  （射线 ∩ 环平面 → 交点离环心距离落在 `[R±0.015]`，`0.015` 即 Babylon 粗环管径
  `0.03` 的半径）；拾取体尺寸与可见几何并列在 `GizmoMeshes`（模型单位随工具缩放 →
  屏幕恒定宽容度），驱动侧 `pickHandle` 取最近命中再过 `isSelectable` 视线裁决。
  可见几何回到 Babylon 原比例（**环管径 0.012 → 0.005**）；Gizmo 几何不再进拾取附件
  （PickPass 只画不透明+透明桶，`depthOn`/Gizmo 桶分支一并删除），hover 顺带零读回延迟
  （PickSystem 无 hover 监听者即不投递）。缩放基准 = 箭头全长
  `0.3375` 为 gizmo 单位（三工具 `scaleFactor()` 同值，屏幕尺寸与旧 9.9 长箭头一致）。
  **RotateTool**：三环（法线 = X/Y/Z = 各自旋转轴）；可选性闸门与移动轴**相反**——
  环法线与视线夹角 < 81°（Babylon `MaxDragAngle = 9π/20`，`|cos| ≥ 0.156`）才可抓，环
  侧视成线时变灰不可选。拖拽数学 = **环平面增量带符号夹角**：环平面即拖拽面（过枢轴、
  法线 = 环世界法线），每帧射线求交、`atan2(cross(v0,v1)·axis, v0·v1)` 得绕轴转角，
  构造四元数增量后经**父链世界旋转逆**换进父空间（`deltaLocal = R(parent⁻¹·axisWorld, angle)`），
  回写 `newLocal = deltaLocal × local`（父空间轴上转 ⟺ 世界轴上转）——带符号夹角已含
  相机侧信息，故**不需要** Babylon 的 `cameraFlipped`/`halfCircleSide` 翻符号补偿；Local
  模式环随目标旋转（轴随动），World 恒世界轴，工具零分支。**ScaleTool**：三轴柄 + 原点
  **中心均匀柄**（对等 Unity/Babylon）；拖拽沿用移动的平面相交法 + 世界位移按箭头世界长
  归一（`factor = 1 + 投影差 / 箭头世界长`，即拖过一个箭头长度该分量 ×2），均匀柄同式乘
  三轴；分量下限 `1e-3` 保号钳制（Babylon `MinimumAbsoluteScale` 口径）；**坐标轴恒局部**
  （`TransformTools.localAxesForced()`，Unity/Babylon 口径——世界轴缩放与父级非均匀缩放
  叠加无稳定语义，工具栏 Local/World 对缩放无效）。**驱动面改动**：注册三工具 +
  坐标空间判定 `localSpace || activeTool.localAxesForced()` + `scaleFactor()` 基准上提基类；
  公共视觉（三态写色/颜色表/shader 路径/材质构造）上提 `GizmoVisuals`（`sync` =
  柄节点数组 + 状态数组 + 可选性闸门），公共轴闸门 `axisHandleSelectable` 上提
  `TransformTools`；工具栏旋转/缩放两钮解禁（i18n 文案去「未实现」）。**显隐 = 每帧强制的
  可见性不变量**（`update` 内先诊断后收敛：只有 `activeTool` 的工具子树亮，其余 hide）——
  不依赖「切换时调一次 hide」的记忆性写法：三工具建时节点默认激活，装配期任何漏 hide /
  外部误翻转都被当帧收敛（首验实锤「三套几何同屏」即此因，2026-11-08 修为不变量 + 可见性
  翻转那帧打一行各工具激活态日志，排查口常驻）。**后置**：移动的
  双轴平面手柄、环上角度扇形显示、拖拽中箭头变长动画、角度/位移吸附、pivot/center 切换、
  环不可见 collider（拾取宽容度若实测不够再议）、W/E/R 快捷键；拾取判定半径抓取舒适度为
  实测后调项。
- **场景视图工具栏（2026-10-14 落地、当日用户实测验收通过）**：视口上方常驻条——SceneViewportPanel
  改列布局（工具栏 24px + 视口占位 flex:1），视口 rect 上报仍只测视口占位 div，
  Canvas 挖洞矩形自动让位（ResizeObserver 重报兜底）。内容：Tools 组（移动可用；
  旋转/缩放置灰占位——工具本体未落地；**2026-11-08 两钮解禁**）+ 竖分隔 + 局部/世界二段切换；新组件
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
  视图工具设计——Unity 口径为显示比例/缩放/Stats 一族）。快捷键（W/E/R）留扩展位
  （rotate/scale 工具本体 2026-11-08 已落地，见上条）。
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
  网格碰撞体，正违反本拍板初衷。~~Gizmo 轴拾取终态同样统一进 ID 附件（轴几何各写 pick ID），
  现役 CylinderCollider 轴拾取为过渡实现，ID-buffer 落地时迁移~~——**2026-11-08 用户改判：
  该条只对场景物体成立**（Gizmo 柄拾取见本 § 变换 Gizmo 条：ID 附件是「看得见即拾得」，
  轴细就不好选、环只能加粗可见管径；改按 Babylon `colliderMeshes` 口径走 CPU 碰撞体射线，
  拾取体与可见几何解耦）。
- **拾取落点拍板（2026-10-23 用户）：pass 本体进引擎 core，运行时也可用**——拾取 pass/ID 分配/
  读回调度归 core（默认不挂、按需启用），编辑器与运行时游戏同为消费方。**有意分歧 Unity**：
  Unity 拾取是编辑器内部实现（HandleUtility.PickGameObject 仅 UnityEditor 命名空间），运行时
  无对应 API——运行时拾取只有 Physics.Raycast+Collider 一条路、无 Collider 拾取是其已知短板，
  短板不抄（「分歧以 Unity 为默认答案」就此处显式越过并记录在案）。
- **射线检测与 GPU 拾取共存（2026-10-23 用户）**：射线**拾取**退役≠射线退役——游戏内大多走
  射线检测（射击命中/AI 感知等），运行时场景级 raycast（Collider 组件注册表，归 Ray 期）照常
  保留，与 ID-buffer 拾取两条 API 并存不冲突：射线=物理查询语义（有 Collider 才打得中），
  ID-buffer=渲染拾取语义（看得见即拾得到）。
- **GPU 拾取落地记录（2026-10-23，当日用户实测验收——设计档=Engine_Picking_Design.md v0.1）**：
  RHI 读回环（TextureReadbackRing 2 槽非阻塞）+ 覆盖制 PickPass（内嵌 pick.spv，拾取 pass 不消费
  材质 Pass 声明）+ PickSystem 双通道（hover/click，帧表 3 环防串帧）+ ViewportPickAppState 消费
  （点击门控/选中收口）+ ~~**Gizmo 轴拾取同批迁移**（hover 源改 PickSystem 直写、拖拽 pickRay 保留
  纯数学、MoveTool 摘 CylinderCollider——CPU 射线过渡线退役）~~（**2026-11-08 回撤，见本 § 变换
  Gizmo 三工具条**）；透明物体可拾取（Unity 口径同批
  确认）；已知边界（hover 1~2 帧延迟、多视口后置）见该档 §8，验收清单 §9。
- **视口聚焦（Frame Selected）已落地（2026-10-24，当日用户验收通过）**：双击层级节点行 →
  编辑器视口相机平滑滑到框住该节点（Unity 同款语义）。三拍板（2026-10-24 用户）：
  ①**双击一律聚焦**——有子节点的行也聚焦（有意越过 Unity 精确口径「父行双击=折叠」，
  展开/折叠只认三角手柄：JTree `setToggleClickCount(0)`，JDK 源码核验 BasicTreeUI
  对 clickCount≤0 不 toggle）；②**F 键同批**（视口内按 F 聚焦当前选中物——
  EditorCameraAppState javadoc 自 2026-09-27 预留「留待选中系统落地再补」到期兑付）；
  ③**平滑过渡**。落点：HierarchyPanel 双击 EDT 直写
  `EditorCameraAppState.requestFrame`（static volatile 意图镜像，先例
  TransformGizmoAppState）、引擎帧内消费。聚焦语义：**姿态（yaw/pitch）保持当前
  观察方向**，只动位置与环绕焦点距离（过渡完成后环绕焦点自然落在目标中心）；
  有网格=世界包围盒（`Mesh.getBounds`→`Aabb.transform` 行和公式）半对角恰落垂直
  半 FOV 框住（FRAME_MARGIN 1.2 余量、FRAME_DURATION 0.25s，经验初值待调），
  无网格/退盒=对准节点世界位置、距离维持当前（只平移对准不动远近）；smoothstep
  缓动、目标请求时刻定格（过渡期间节点移动不跟随）、**手动输入即打断**（Unity
  口径：用户接管）；僵尸引用按场景归属校验安全跳过（先例 TransformGizmoAppState）。
  已知边界：GAME 模式不门控（编辑器相机位姿照写、不进渲染列表，同 Gizmo 留白
  口径）；F 键依赖渲染窗口 OS 焦点（Swing 文本框打字理论收不到，F3 先例同口径，
  实测串扰再补门控）；距离按垂直 FOV 口径——极窄竖视口（横 FOV 更小）可能侧向
  裁边，v1 取舍。
- 内置资源索引生成挪入 javelin-tool，编辑器构建瘦身
