# 引擎阶段路线（Engine Roadmap）

> 版本：v0.1（2026-09-25）
> 说明：三期路线总览与一期完成关账。**一期 = 渲染基础（已完成）**；**二期 = 引擎功能（输入 / 碰撞检查 / Ray / 编辑器基本）**；**三期 = 光照**。各期细节以对应专档为准，本文只做总览与状态。
> 用户拍板（2026-09-25）：三期路线——一期渲染基础完 → 二期引擎功能 → 三期光照（阴影随光照；Deferred/后处理列三期扩展）。

---

## 1. 一期：渲染基础——✅ 完成（2026-09-25 关账）

### 1.1 交付清单

| 模块 | 内容 | 验收 |
|------|------|------|
| 引擎接线 RHI（实现计划阶段①） | JavelinEngine 生命周期（窗口 → Instance/Device/FrameLoop → 渲染系统）、shutdown 逆序、vsync→呈现模式 | `JavelinEngine.main` 清屏出画面 |
| 场景系统（阶段②） | Node/Component/Transform/GameScene + 生命周期 + Layer/Tag + 扁平组件注册表 | GameSceneTest 用例全绿（用户运行） |
| 渲染系统 v0.1 Forward（阶段③） | RenderingSystem 注册表/每帧收集/UBO1 帧环形收编、多相机 RenderView、透明排序、每视口深度纹理 | MultiCameraDemo 验收（分屏/透明排序/resize），**RHI 阶段⑥正式关账** |
| Pass 编排重构 | PipelineHook 机制移除（零调用方），管线 = 有序 `RenderPass` 列表（`addToGraph`），`passes()` 活列表扩展 | 行为对等，structureHash 不变 |
| 场景渲染接入 | `SceneRenderBridge` pull 翻译层（阶段②遗留关闭）：Camera→RenderCamera、MeshRenderer→RenderObject、多相机横向均分视口；JavelinEngine.setScene + scene.update 驱动 | SceneRenderDemo（双相机分屏 + Spinner 旋转 + 透明混合）用户运行正常 |
| 材质桥（材质档 §4 全项） | set 2 布局按 Shader 缓存 / `MaterialBlock` std140 排布器 / `materialSet` 懒创建（运行期改值即时生效）/ `pipelineFor(Material, layout)`（PassState→PSO）/ `Shader.forwardPass()` / 透明分桶 = Blend 态（**已演进：改 Material.surfaceType 材质裁决 + Pass 名分桶严格制（passes map，forwardPass 废除），2026-10-17 方案 B，材质档 §4.1/§4.2**） | MaterialBlockTest（CPU 单测）+ 两 Demo 材质路径迁移；旧内置程序路径删除 |

### 1.2 一期关键拍板（决策存档）

- **着色器源码不进 core**：GLSL 放启动/应用层资源，SPIR-V 由启动层编译（shaderc 仅 testImplementation）；
- **渲染衔接走 pull**：每帧从 `Scene.getCameras/getRenderables` 翻译进 RenderingSystem（推翻 push 预登记）；
- **管线 = 有序 Pass 列表**：`PipelineHook` 枚举移除（曾致 POST_LIGHTING 漏触发），扩展 = 列表插入（RHI 14.3 方式 1/2）；
- **Transform 是 Component 子类**、**无 createNode**（setParent 挂到已入场景父节点自动入场景）、摘除即出场景；
- **视锥剔除/Aabb 后置**（优化阶段回加，届时 FrustumIntersection.testAab 用 6×float 重载）；
- **用户侧零 GPU 暴露 + 冻结语义**：Mesh/Texture2D 纯 CPU 容器，上传逻辑全在桥接（MeshUpload/TextureUpload），上传后资产不可变；
- **资产/序列化类字段禁 final**（AGENTS 红线 9），只读靠 API 面。

### 1.3 一期结束时的能力边界

- **能**：场景里摆 Node（MeshRenderer+Transform）即渲染；多相机分屏/叠加；材质颜色/贴图/透明混合；resize 自适应；材质参数运行期修改即时生效。
- **不能**：灯光/阴影/后处理（三期）；资产文件加载（.shader/.mat 靠程序化构建，二期补格式）；输入/碰撞/Ray（二期）；视锥剔除（优化项）。

---

## 2. 二期：引擎功能

> 目标：让游戏/编辑器"可交互"。依赖顺序：**①输入 → ②碰撞检查 → ③Ray → ④编辑器基本**（Ray 与碰撞是编辑器拾取的前置）。

### 2.1 输入子系统——✅ 已验收关账（2026-09-27 用户验收通过）

- **目标**：键鼠状态采集（GLFW 事件源）+ Action 映射；消费方演示 = 输入驱动相机移动；
- **设计档**：`Engine_Input_Design.md`（v0.1，含拍板记录：KeyCode 纯语义枚举零平台关联（键码映射归 Window 实现私有）/ Input 静态门面对齐 Time / 动作层三次收束——ActionMap 概念移除、bind 与查询合流 Input（唯一使用方式）/ ESC 关窗便利逻辑移除）；
- **架构**：Window.attachInputSink 事件出口（平台键码经 Window 侧翻译成引擎枚举投递）→ InputSubsystem（双缓冲帧边沿，earlyUpdate 发布本帧快照 + 动作绑定全局单表）→ Input 静态门面（原始查询 + 动作 bind/按名查询）；失焦清态防卡键；
- **验收**：**已通过（2026-09-27）**——InputCameraDemo（WASD 移动/右键拖拽转视角/SPACE 边沿/失焦不卡键）+ RawInputDemo（原始层对照）；性能基线存档：IMMEDIATE 下空载 ~10180 fps、avg 0.1ms/帧，引擎每帧固定成本地板过关；
- **对应实现计划**：阶段⑤主体。

### 2.2 碰撞检查

- **目标**：场景对象的相交查询（游戏逻辑 + 编辑器拾取共用）；
- **前置**：**Aabb 回归**——视锥剔除后置时随 Mesh.bounds 一起删除，碰撞与 Ray 都需包围盒回来（Mesh 补"从 positions 算 Aabb"，设计归碰撞档 §5）——**✅ 已随碰撞片③回归（2026-10-05）**；`Engine_Collision_Design.md`（v0.1：**L0 纯数学 + Collider 组件族已落地、片①②③④全片单测验收关账（2026-10-06）**——Ray/形状/Aabb + 缩放=射线逆变换吸收；**已拍板：碰撞体=组件（负责形状）、刚体=独立组件后置**；场景级查询 API 面归 L1 Ray 期定）；
- **产出**：碰撞体组件 + 窄相查询 API（盒/球/射线公共部分）；
- **验收**：~~两物体相交/分离查询正确的最小 Demo~~——**✅ 单测全绿已验收（2026-10-06）**（L0 解析解对照 + Collider 管线缩放往返），场景级拾取 Demo 归 §2.3 Ray 一并验收；
- **待定拍板**：只做窄相查询（自研，推荐——拾取与逻辑查询够用）还是连带阶段⑥物理库选型（刚体动力学）一起定。

### 2.3 Ray

- **目标**：屏幕点 → 世界射线（逆 viewProj）→ 射 vs 碰撞体 → RaycastHit；
- **前置**：②的碰撞体与包围盒；
- **产出**：Ray/RaycastHit 数据类型 + Camera 屏幕射线生成 + 场景级 raycast 查询（**Ray 已随碰撞 L0 落地**（2026-10-05 片①）；**Camera.screenToRay 已进核心并单测验收**——2026-10-05 随 Gizmo 轴拾取落地、CameraTest 2026-10-06 验收，Unity ScreenPointToRay 口径）；
- **验收**：点击拾取场景物体正确（为编辑器拾取闭环）。

### 2.4 编辑器基本——✅ 主体验收收官（2026-10-14；物体拾取 ID-buffer 拍板后置）

- **目标**：场景编辑最小闭环——场景视口（飞行相机）+ 层级面板 + 拾取 + Transform 移动/旋转/缩放 + 场景保存/加载；
- **前置**：
  - **.scene 序列化**（现为 P2，编辑器存取必须）；
  - **资产格式层**（原阶段④剩余，编辑器读写的前提）：~~.shader 二进制格式 + 解析器（声明注入 + shaderc + 打包）~~——**✅ 已落地（2026-09-26，提交 e03c057）**：导入管线在编辑器 `importer/shader`（归属拍板：不经 javelin-tool），Capsule 二进制产物 + GUID 文件头规则 + .smesh 产物/拓扑/法线一并补齐；**.mat 格式 + AssetManager 读写未做**（下一步）；
  - **编辑器形态已定：Swing 外壳 + JCEF Web 面板**（~~内嵌 imgui / Nuklear / 独立 Swing 三选一~~ 作废——视口=Vulkan Canvas 挖洞浮于 CEF 之上，浮层遮挡约束由此而来，Editor_Design §1）；
  - `Editor_Design.md`（✅ 已建档 v0.1：JavelinEditor=JavelinEngine 子类、CEF 窗口体系、JS↔Java 桥、资产管线、线程模型）；
- **产出**：编辑器应用骨架 + 上述最小闭环；
- **验收**：搭建场景 → 摆放/移动物体 → 保存 → 重载还原（用户运行）。

进度（2026-10-01 落地，**2026-10-04 用户实测验收通过**——单测 + 编辑器「建树 → 保存 → 重启还原」，含生命周期帧驱动状态机与 Ctrl+S）：**.scene 序列化已落地**（Engine_Scene_Design §9——GameScene/
Node/Component/Transform/Camera/MeshRenderer write/read + read 全量恢复零收尾
+ 文本通道保存命令 file.save/Ctrl+S）；MeshRenderer 材质引用闭环与
`.mat` 格式仍待做（§9.4 已知限制）。

**拾取方案拍板（2026-10-06 用户）**：物体拾取终态 = **GPU ID-buffer**（编辑器专用 pass 写 ID 附件、点击读回像素——不依赖用户物体挂 Collider、不给物体隐藏挂网格碰撞体，Unity 编辑器同路线）；RHI 前置 = 拾取附件 + 图像读回路径，**后置实现、非当前排期**（详见 Editor_Design §10）。运行时场景级 raycast（Collider 注册表）仍归 §2.3 Ray 期，两 API 并存；过渡期是否先落 CPU 数学拾取为开放项。

进度（2026-10-11 编辑器骨架大破土 → **2026-10-13 编辑→打包全流程闭环**）：Swing+JCEF 编辑器全链落地——菜单/命令体系（Editor_Menu_Design v0.10：三域命令 + Swing 菜单 + 图标 + 「构建」域）、层级树与场景增删、Inspector v0.1/v0.2（节点头 + Transform/Camera 组件面板）、Scene/Game 视图切换、资产管线（.meta/GUID/导入器/Library + SceneImporter 场景导入）；**构建域 v0.9/v0.10：打包对话框 JAR/AOT 双形态**——JAR = jlink 裁剪 JRE 运行时、AOT = GraalVM native-image 原生 exe（外置 DLL + reflect-config 处理器生成 + resource-config includes），产物 Builds/ 下双击可玩，**2026-10-13 AOT/JAR/编辑器三端实测验收**；**Gizmo 拖拽实测验收通过（2026-10-14）——§2.4 最小闭环主体验收收官**，物体拾取按终态拍板后置（GPU ID-buffer；CPU 过渡拾取为开放项）。

修复批次（2026-10-17，用户实测复现链定位）：打包 jar 产物 lib 目录路径错位（`build/jar/lib`→`Builds/jar/lib`，依赖落错位、启动脚本 classpath 落空）+ 内置资源释放后 Library 索引不重扫（MetaManager 构造器 sync 早于 releaseAll、refresh 查产物存在即跳过——`@guid:` 引用解析 null 渲染崩）双修复；连带排障定位旧格式 Mesh 产物不迁移坑（Editor_Design §6.3 已知边界，自动检测待拍板）。详见 Editor_Menu_Design v0.10 追记 / Editor_Design §6.3 / Engine_Features §3.6/§4。

进度（2026-10-17，待验收）：**项目面板落地**——Unity 两栏（左目录树/右内容区
 + 面包屑 + 双击进入），`/project/tree` 全量树（仅 Assets/src 两根，.meta/隐藏项
 过滤）+ `/project/select` 选中上报（EditorSelection project 通道接线）；ctx.project
 右键域首期（Editor_Menu_Design v0.11：showInExplorer/copyPath/reimport 三实做 +
 Unity 壳占位，右键三入口）。详见 Editor_Design §10 / Editor_Menu_Design v0.11。

进度（2026-10-18，待验收）：**M3 项目面板 Swing 实装（ProjectPanel）**——原 web 版
随去 Web 化清退后的 Java 直调全新实装：ProjectTreeSnapshot 后台线程纯 IO 全量树
（仅 Assets/src 两根，.meta/隐藏项过滤）+ 左树目录投影/右区直接子项/面包屑/
双击进入 + 选中直写 EditorSelection（Node 直持 File，目录变更即上报）+ ctx.project
右键域三入口先落选中再弹菜单。详见 Editor_Swing_Design §7.6。

进度（2026-10-18，**用户实测验收通过**）：**M4 属性面板 Swing 实装（InspectorPanel）**——
InspectorReflector/三 VO 数据链复用（Web 期 v0.1~v0.3 产物零改动接入）+ 引用化
收口（objId→Node/Component 直持）+ markDirty 收口进回写出口；swing.inspector
两层装配（面板层 + widget 控件七件——数字输入/下拉/多段行/折叠面板/色块拾色/
层掩码勾选/资产只读）；NodeTransformChangeEvent 首个消费方（Gizmo 拖拽定点刷
Transform 三行）+ 编辑保护（重建延后提交补拉）。验收期实测修正：归属校验比对
对象（getGameScene 本体）、Scrollable 内容容器（高度不拉伸/窄面板不水平滚）、
节点头 Web 布局回归（图标 36px 占两行+图层/标签并排）、折叠头视觉分层与四周
边框。详见 Editor_Swing_Design §7.7 / Inspector_Design v0.4。

进度（2026-10-17 拍板→当日落地，**用户实测验收通过**）：**编辑器 UI 栈去 Web 化重构
 M1（Editor_Swing_Design）**——主窗口弃 JCEF 改纯 Swing + FlatLaf 亮色：自研轻量
 Dock（二分布局树 + PanelRegistry 面板注册扩展点 + 自研分割条拖拽驱动）、视口
 ViewportTabs 单 Canvas 不 reparent 直挂（rectChange 挖洞上报链及最大化补链废除）、
 JMenuBar 挂载（MenuRegistry 单源复用，DevTools/RefreshPage 随 Web 退役）；排障
 五坑记档专档 §10-5/§10-6（heavyweight 事件形态/dragger 卡死/Canvas minimum
 钳制/弹性侧策略/关闭零重挂）。M2~M6 面板迁移路线见专档里程碑表。

进度（2026-10-17 落地，待验收）：**M6 窗口迁移全项提前——三窗口迁 Swing，CEF 运行时
 零使用**——ProjectFrame（最近列表 + 新建/打开三模式，tinyfd 选目录，Java 直调
 RecentProjectManager/GradleProject）、ProjectLoadingFrame（不确定进度条 + 资产导入/
 引擎启动引导链原样）、打包 PackDialog（构建形态/启动场景/构建模式/窗口元信息表单 +
 ConsoleOut 实时日志流——原 BuildMethod 桥逻辑迁入 Java 直调），启动与构建链均不再
 依赖 Vite dev server；/project(recent/open/create)、/build 两域桥随消费方消亡退役
 （§7.1）；CEF Dialog/CefFrame 删除，jcef 桥包成死代码待 M2~M4 面板迁移收口。
 详见 Editor_Swing_Design §2/§9。

进度（2026-10-18 落地，待验收）：**M6 收口——jcef 桥包整体清退**：jcef 包全量删除
（桥方法/par/vo/EventBridgeForwarder/桥基础设施），jcefmaven、jackson 依赖移除
（jackson 唯一消费方 JSONUtil 服务桥协议），jcef-bundle 运行时缓存（353MB）删除；
事件包桥语义剥离（BridgeEvent/JsEvent/PongEvent 删，五场景事件转纯 EditorEventBus
事件），Inspector 三 VO 迁 inspector 包作 M4 数据模型。**引擎-编辑器全链路纯 JVM
达成**，原「桥随面板逐批退役」计划作废，M2~M4 面板一律 Java 直调实装；engine-editor-web
前端工程随之整体删除——代码库去 Web 化收官。详见
Editor_Swing_Design §7.1/§9/§11。

进度（2026-10-18 落地，待验收）：**M2 首面板——层级面板 Swing 实装**：`swing/hierarchy`
两件（HierarchyPanel + SceneTreeSnapshot），原 /scene/tree 桥通道的 Java 直调形态——
submitTask 帧边界组装快照 → invokeLater EDT 换树；四事件分工刷新（scene.changed 重拉 /
dirty 置星 / nodeRenamed 定点 / select 高亮——本面板为 scene.select 首个消费方）；
选中 EditorSelection 双向同步 + 展开态按节点引用跨刷新恢复；右键 ctx.hierarchy
先落选中再弹菜单（Unity 行带口径）。实装拍板与坑（TreeModel 取舍 / setRoot 清选中
陷阱 / JTree 行为源码查证）见 Editor_Swing_Design §7.5。同日拍板**全链引用化**：
桥清退后「id + ObjectCache 弱引用反查」句柄链废弃（弱隔离名存实亡——update
零调用方、消费方本就强引用长持，已删检测实际靠 getScene 归属校验），选中/三事件/
Create 命令/树回查键全部改 Node 引用，ObjectCache 退役；快照拷贝本体保留
（跨线程一致性，与句柄问题正交）。

进度（2026-10-20 落地，待验收）：**插件体系 + editor.core 迁移 + i18n 基建 + 菜单
i18n 首批**（Editor_Plugin_Design v0.1）：插件契约/装载管线/错误分级落地（ServiceLoader
发现，editor.core 即插件无特权——三个 Setup 迁 EditorCorePlugin.setup 经 PluginContext
收口，引擎依赖懒取）；装载接入 ProjectLoadingFrame（先于资产初始化，Plugins/ 目录
项目级）；i18n 体系（I18nKey 自持 namespace + I18nScope/EditorLang 门面 + I18n 注册
中心——`!key!` 兜底不抛、去重 warn、miss/used 采集）+ 菜单 label 全链 I18nKey
（key=命令 id 约定，域基类默认 label，12 命令类覆写全删），`lang/zh_cn.properties`
39 条。待拍板挂账：命令 id 前缀化/FileType 冲突策略/默认语言。详见 Editor_Plugin_Design。

---

## 3. 三期：光照

- **目标**：场景开灯出可见差异——灯光数据通路 + 有光照着色 + 阴影；
- **内容**：
  1. **Light 组件**（DIRECTIONAL/POINT，Scene 5.3 设计稿已备，主光 `Scene.sun` 待定）；
  2. **UBO1 槽内布局扩排**：现 1280B = 4×256B 相机块 + 256B 时间块，lights 块塞入需扩槽（槽内布局是引擎私有约定，改时同步着色器声明——渲染档第 6 节已预留此变更）；
  3. **着色模型**：v0.x Forward = Lambert + 环境项（管线档 4.5 取舍：与 Deferred LightingPass 的光照一致性属已知取舍，统一模型随材质/着色专档回填）；
  4. **ShadowPass**（设计已完成：管线档 4.1，shadowMap D32 2048²，depth-only，实现后置项到此兑现）；
- **验收**：方向光/点光下物体明暗正确 + 主方向光投影正确（用户运行）；混合/纯 Forward 可配置切换（管线档第 8 节路线第二步的回填点）；
- **三期扩展（可选排序）**：Deferred 混合（GBuffer/Lighting，管线档 4.2/4.3 设计已备）、后处理链具体化（Bloom/Tonemap + HDR 切换点，管线档 4.6）。

---

## 4. 与 `Engine_Implementation_Plan.md` 六阶段的映射

| 路线期 | 实现计划阶段 | 状态 |
|--------|-------------|------|
| 一期 | 阶段① 引擎接线 / 阶段② 场景系统 / 阶段③ 渲染系统 | ✅ 完成 |
| 一期 | 阶段④ 材质系统——**桥接半边**（§4 渲染侧三环节 + Shader/Material 数据模型） | ✅ 完成 |
| 二期 | 阶段④ 剩余（.shader/.mat 资产格式 + javelin-tool）→ 并入编辑器基本前置 | 🔄 .shader 侧已落地（2026-09-26，e03c057）；.mat/AssetManager 未做 |
| 二期 | 阶段⑤ 子系统 + 输入；**新增**：碰撞检查 / Ray / 编辑器基本（原计划未列，路线档补入） | 🔄 输入子系统✅ 已验收关账（2026-09-27）；碰撞检查 v0.1✅ 全片单测验收关账（2026-10-06，§2.2，场景级查询归 Ray 期）；Ray 侧 Camera.screenToRay✅（2026-10-06，场景级 raycast 未做）；编辑器基本✅ 主体验收收官（2026-10-14）：窗口层 + .scene 序列化✅（2026-10-04）+ Gizmo 轴拾取✅/拖拽✅（2026-10-14）+ **编辑器骨架全链**（菜单/层级/Inspector/Scene-Game 切换/资产导入，2026-10-11）+ **打包 JAR/AOT 双形态✅（2026-10-13 三端验收——编辑→打包全流程闭环）**+ 项目面板/右键域首期（2026-10-17 待验收）+ **UI 栈去 Web 化重构 M1✅（2026-10-17 用户验收——Swing+FlatLaf 主窗口/Dock/视口，M2~M6 见 Editor_Swing_Design）** + 三窗口迁 Swing + jcef 桥包/jcefmaven/jackson 清退（M6 全项提前完成，2026-10-17/18 待验收——全链路纯 JVM）+ **M2 层级面板 Swing 实装（2026-10-18 待验收，Editor_Swing_Design §7.5）** + **M3 项目面板 Swing 实装（2026-10-18 待验收，Editor_Swing_Design §7.6）** + **M4 属性面板 Swing 实装（2026-10-18 用户实测验收通过，Editor_Swing_Design §7.7 + Inspector_Design v0.4）** + **插件体系 + editor.core 迁移 + i18n 基建 + 菜单 i18n 首批（2026-10-20 待验收，Editor_Plugin_Design v0.1）**；剩物体拾取（ID-buffer 后置） |
| 三期 | 灯光 + ShadowPass（Deferred/后处理为扩展） | 未开始 |
| 未定 | 阶段⑥ 物理 / 音频（选型拍板后各成阶段） | 未开始 |

## 5. 跨期遗留（不阻塞当期推进）

- RHI 遗留：Buffer staging 上传（deviceLocal）、描述符池环形、backbuffer 句柄入 structureHash；
- 场景：environment/sun（三期灯光一并定）、.scene 序列化（二期编辑器前置）；
- 资产：动态 Mesh/动态纹理专项（设计要点已记录，材质档 §6 / 资产档 §2.8）；
- 渲染优化：视锥剔除（Aabb/Mesh.bounds 已随碰撞片③回归 2026-10-05，剔除本身仍后置）、遮挡剔除/GPU Instancing/LOD（P2）；
- 编辑器：物体拾取 GPU ID-buffer（**终态拍板 2026-10-06**——RHI 前置：R32Uint 拾取附件 + 图像读回路径；实现后置非当前排期，见 Editor_Design §10）。

---

> （注：部分内容可能由 AI 生成）
