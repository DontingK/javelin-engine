# 引擎阶段路线（Engine Roadmap）

> 版本：v0.2（2026-10-31）
> 说明：阶段路线总览与一期完成关账。**一期 = 渲染基础（已完成）**；**二期 = 引擎功能（输入 / 碰撞检查 / Ray / 编辑器基本）**；**三期 = 光照（clustered Forward+ 五批）**；**四期 = 高质量渲染（后处理 / 阴影分级 / 环境光照 / 实时 GI / 光线追踪，2026-10-31 立项）**。各期细节以对应专档为准，本文只做总览与状态。
> 用户拍板（2026-09-25）：三期路线——一期渲染基础完 → 二期引擎功能 → 三期光照（阴影随光照；Deferred/后处理列三期扩展）；**2026-10-31 拍板：高质量光照/光追/天空盒环境光/实时 GI 等后期项归四期**（三期收口为「有光照」= Forward+ 成形+单张硬影+视觉天空盒，质量升级整体下期）。

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
- **验收**：~~点击拾取场景物体正确（为编辑器拾取闭环）~~——编辑器拾取已改道 GPU ID-buffer 并于 2026-10-23 实测验收（§2.4 拾取闭环收官）；Ray 线定位收窄为**运行时物理查询**，验收 = PhysicsTest 单测（用户运行）。

进度（2026-10-28 落地，**2026-10-30 销账——PhysicsTest 14 用例 + GameSceneTest 主相机用例全绿**；mouseRay 运行时面 **2026-10-31 用户实测验收**）：**L1 场景级 raycast（运行时物理查询线）**——拍板：
API 落点 = 静态门面 `Physics`（Unity 口径）、首批查询面 = 单命中 `raycast` + 全命中
`raycastAll`（2×3 重载：默认/限距/layerMask；射程口径三拍定稿=**maxDistance 查询参数**
（Unity 口径）——同日 jME 式 `Ray.limit` 方案曾短暂落地即回退，沿革见 Collision 档 §10）。
`physics.query` 包新增 `Physics`
（绑定场景 `setScene`，JavelinEngine.setScene 绑定/shutdown 解绑）+ `RaycastHit`（point/normal
复用缓冲 + distance + collider/node 引用）；查询源 = `Scene.getColliders` 注册表投影
（GameScene 与 cameras/renderables 同构增 colliders 分流表，EditorScene 透传用户场景）；
过滤序 = 非激活/禁用跳过 → layerMask → 最近命中；distance/maxDistance 一律世界单位（入口
一次性方向归一化，同 Unity Ray 语义）；退化值闸门（NaN/±Inf → `!isFinite` 显式挡）；
未命中 out 不变（零分配复用）；未绑定场景抛 IllegalStateException（错误显性化）。
PhysicsTest 14 用例（最近命中/限距含边界/layerMask/激活过滤/摘出注销/
排序 clear/归一化/世界变换端到端/未绑定抛/零方向）。**随批相机→射线组装链**：`Scene.getMainCamera`
（Unity `Camera.main` 口径——首个激活且 enabled 的 isMain，无则 null；GameSceneTest 主相机用例）
+ `JavelinEngine.mouseRay(camera, out)`（Input 鼠标坐标；尺寸自取渲染表面——等效 Unity
`Camera.main.ScreenPointToRay(Input.mousePosition)`；**Camera.screenToRay 增简式
`(x, y, out)` 尺寸自取 = 引擎静态实例渲染表面 `JavelinEngine.getInstance().renderSurface()`
（构造赋值/shutdown 摘除 + `renderSurface()` 访问口），显式尺寸重载保留给单测/离屏/编辑器
逻辑坐标路径**；归运行时装机验证）。设计归 Engine_Collision_Design §10（v0.2）/
Engine_Scene_Design §5.2。

### 2.4 编辑器基本——✅ 主体验收收官（2026-10-14；物体拾取 ID-buffer 2026-10-23 用户实测验收——拾取闭环收官）

- **目标**：场景编辑最小闭环——场景视口（飞行相机）+ 层级面板 + 拾取 + Transform 移动/旋转/缩放 + 场景保存/加载；
- **前置**：
  - **.scene 序列化**（现为 P2，编辑器存取必须）；
  - **资产格式层**（原阶段④剩余，编辑器读写的前提）：~~.shader 二进制格式 + 解析器（声明注入 + shaderc + 打包）~~——**✅ 已落地（2026-09-26，提交 e03c057）**：导入管线在编辑器 `importer/shader`（归属拍板：不经 javelin-tool），Capsule 二进制产物 + GUID 文件头规则 + .smesh 产物/拓扑/法线一并补齐；~~.mat 格式 + AssetManager 读写未做~~——**✅ 已落地（2026-10-23 用户实测验收通过）**：.mat = Material 文本序列化（伴生 .meta 口径），MaterialImporter + project.create.material 创建命令，Engine_Material_Design §3.2；
  - **编辑器形态已定：Swing 外壳 + JCEF Web 面板**（~~内嵌 imgui / Nuklear / 独立 Swing 三选一~~ 作废——视口=Vulkan Canvas 挖洞浮于 CEF 之上，浮层遮挡约束由此而来，Editor_Design §1）；
  - `Editor_Design.md`（✅ 已建档 v0.1：JavelinEditor=JavelinEngine 子类、CEF 窗口体系、JS↔Java 桥、资产管线、线程模型）；
- **产出**：编辑器应用骨架 + 上述最小闭环；
- **验收**：搭建场景 → 摆放/移动物体 → 保存 → 重载还原（用户运行）。

进度（2026-10-01 落地，**2026-10-04 用户实测验收通过**——单测 + 编辑器「建树 → 保存 → 重启还原」，含生命周期帧驱动状态机与 Ctrl+S）：**.scene 序列化已落地**（Engine_Scene_Design §9——GameScene/
Node/Component/Transform/Camera/MeshRenderer write/read + read 全量恢复零收尾
+ 文本通道保存命令 file.save/Ctrl+S）；~~MeshRenderer 材质引用闭环与
`.mat` 格式仍待做（§9.4 已知限制）~~——**.mat 已落地（2026-10-23 用户实测验收通过，Engine_Material_Design §3.2）**：guid 化材质引用通道（`material=@guid:`）随框架分流既有，本批补导入器/创建命令。

**拾取方案拍板（2026-10-06 用户；2026-10-23 统一/落点/共存三增补）**：拾取终态 = **GPU ID-buffer 全线统一**（物体拾取 + Gizmo 轴拾取终态均写 ID 附件、点击读回像素——不依赖用户物体挂 Collider、不给物体隐藏挂网格碰撞体，Unity 编辑器同路线）；编辑器侧射线拾取退役、「过渡期先落 CPU 数学拾取」开放项**关账不落**；落点 = **pass 本体进引擎 core、运行时也可用**（有意分歧 Unity：Unity 拾取为编辑器内部实现、运行时无对应 API，短板不抄）；RHI 前置 = 拾取附件 + 图像读回路径，~~后置实现、非当前排期~~——**已落地并验收（2026-10-23 用户实测，Engine_Picking_Design.md v0.1：RHI 读回环/PickPass 覆盖制/PickSystem 双通道/编辑器消费 + 轴拾取全线迁移，CylinderCollider 过渡线退役）**（详见 Editor_Design §10 / Engine_Picking_Design）。射线检测与 GPU 拾取**共存**：游戏内大多走射线检测（Physics.Raycast 语义），运行时场景级 raycast（Collider 注册表）照常归 §2.3 Ray 期——射线=物理查询（有 Collider 才打得中），ID-buffer=渲染拾取（看得见即拾得到），两 API 并存不冲突。

进度（2026-10-11 编辑器骨架大破土 → **2026-10-13 编辑→打包全流程闭环**）：Swing+JCEF 编辑器全链落地——菜单/命令体系（Editor_Menu_Design v0.10：三域命令 + Swing 菜单 + 图标 + 「构建」域）、层级树与场景增删、Inspector v0.1/v0.2（节点头 + Transform/Camera 组件面板）、Scene/Game 视图切换、资产管线（.meta/GUID/导入器/Library + SceneImporter 场景导入）；**构建域 v0.9/v0.10：打包对话框 JAR/AOT 双形态**——JAR = jlink 裁剪 JRE 运行时、AOT = GraalVM native-image 原生 exe（外置 DLL + reflect-config 处理器生成 + resource-config includes），产物 Builds/ 下双击可玩，**2026-10-13 AOT/JAR/编辑器三端实测验收**；**Gizmo 拖拽实测验收通过（2026-10-14）——§2.4 最小闭环主体验收收官**，物体拾取按终态拍板后置（GPU ID-buffer；~~CPU 过渡拾取为开放项~~——开放项已于 2026-10-23 关账不落，§2.4 拍板段；**ID-buffer 拾取已落地并于 2026-10-23 用户实测验收——§2.4 拾取闭环收官**）。

修复批次（2026-10-17，用户实测复现链定位）：打包 jar 产物 lib 目录路径错位（`build/jar/lib`→`Builds/jar/lib`，依赖落错位、启动脚本 classpath 落空）+ 内置资源释放后 Library 索引不重扫（MetaManager 构造器 sync 早于 releaseAll、refresh 查产物存在即跳过——`@guid:` 引用解析 null 渲染崩）双修复；连带排障定位旧格式 Mesh 产物不迁移坑（Editor_Design §6.3 已知边界，自动检测待拍板）。详见 Editor_Menu_Design v0.10 追记 / Editor_Design §6.3 / Engine_Features §3.6/§4。

进度（2026-10-17，2026-10-23 用户实测验收通过）：**项目面板落地**——Unity 两栏（左目录树/右内容区
 + 面包屑 + 双击进入），`/project/tree` 全量树（仅 Assets/src 两根，.meta/隐藏项
 过滤）+ `/project/select` 选中上报（EditorSelection project 通道接线）；ctx.project
 右键域首期（Editor_Menu_Design v0.11：showInExplorer/copyPath/reimport 三实做 +
 Unity 壳占位，右键三入口）。详见 Editor_Design §10 / Editor_Menu_Design v0.11。

进度（2026-10-18，2026-10-23 用户实测验收通过）：**M3 项目面板 Swing 实装（ProjectPanel）**——原 web 版
随去 Web 化清退后的 Java 直调全新实装：ProjectTreeSnapshot 后台线程纯 IO 全量树
（仅 Assets/src 两根，.meta/隐藏项过滤）+ 左树目录投影/右区直接子项/面包屑/
双击进入 + 选中直写 EditorSelection（Node 直持 File，目录变更即上报）+ ctx.project
右键域三入口先落选中再弹菜单。详见 Editor_Swing_Design §7.6。

进度（2026-10-18，**用户实测验收通过**）：**M4 检查器面板 Swing 实装（InspectorPanel）**——
InspectorReflector/三 VO 数据链复用（Web 期 v0.1~v0.3 产物零改动接入）+ 引用化
收口（objId→Node/Component 直持）+ markDirty 收口进回写出口；swing.inspector
两层装配（面板层 + widget 控件七件——数字输入/下拉/多段行/折叠面板/色块拾色/
层掩码勾选/资产只读）；NodeTransformChangeEvent 首个消费方（Gizmo 拖拽定点刷
Transform 三行）+ 编辑保护（重建延后提交补拉）。验收期实测修正：归属校验比对
对象（getGameScene 本体）、Scrollable 内容容器（高度不拉伸/窄面板不水平滚）、
节点头 Web 布局回归（图标 36px 占两行+图层/标签并排）、折叠头视觉分层与四周
边框。详见 Editor_Swing_Design §7.7 / Inspector_Design v0.4。

进度（2026-10-22 落地，2026-10-23 用户实测验收通过）：**层级根行选中 + 脏星标行宽修复**——场景根行
可选中（=场景文件进 project 槽，检查器按 .scene 文件既有分发链零改动；根行高亮
存活修复回声自灭 bug + project.select 跨域对齐：选本场景文件高亮/选他物熄灭）；
scene.dirty 置星改 nodeChanged 重算行尺寸（repaint 不重算、文本被旧行宽裁出
省略号）。详见 Editor_Swing_Design §7.5 增量 / Inspector_Design §11。

进度（2026-10-22，**用户实测验收通过**）：**检查器输入交互增强（Inspector_Design
v0.6）**——数字键入即提交（失焦才应用作废）、文本提交时机归使用方（默认回车/失焦、
可选每键通道）、label 按住左键左右拖调值（Unity scrub 口径，步长可配：默认整 1/浮
0.1、range 字段=注解 step）；滚轮调值与无限拖试做后移除。详见 Inspector_Design §12。

进度（2026-10-17 拍板→当日落地，**用户实测验收通过**）：**编辑器 UI 栈去 Web 化重构
 M1（Editor_Swing_Design）**——主窗口弃 JCEF 改纯 Swing + FlatLaf 亮色：自研轻量
 Dock（二分布局树 + PanelRegistry 面板注册扩展点 + 自研分割条拖拽驱动）、视口
 ViewportTabs 单 Canvas 不 reparent 直挂（rectChange 挖洞上报链及最大化补链废除）、
 JMenuBar 挂载（MenuRegistry 单源复用，DevTools/RefreshPage 随 Web 退役）；排障
 五坑记档专档 §10-5/§10-6（heavyweight 事件形态/dragger 卡死/Canvas minimum
 钳制/弹性侧策略/关闭零重挂）。M2~M6 面板迁移路线见专档里程碑表。

进度（2026-10-17 落地，2026-10-23 用户实测验收通过）：**M6 窗口迁移全项提前——三窗口迁 Swing，CEF 运行时
 零使用**——ProjectFrame（最近列表 + 新建/打开三模式，tinyfd 选目录，Java 直调
 RecentProjectManager/GradleProject）、ProjectLoadingFrame（不确定进度条 + 资产导入/
 引擎启动引导链原样）、打包 PackDialog（构建形态/启动场景/构建模式/窗口元信息表单 +
 ConsoleOut 实时日志流——原 BuildMethod 桥逻辑迁入 Java 直调），启动与构建链均不再
 依赖 Vite dev server；/project(recent/open/create)、/build 两域桥随消费方消亡退役
 （§7.1）；CEF Dialog/CefFrame 删除，jcef 桥包成死代码待 M2~M4 面板迁移收口。
 详见 Editor_Swing_Design §2/§9。

进度（2026-10-18 落地，2026-10-23 用户实测验收通过）：**M6 收口——jcef 桥包整体清退**：jcef 包全量删除
（桥方法/par/vo/EventBridgeForwarder/桥基础设施），jcefmaven、jackson 依赖移除
（jackson 唯一消费方 JSONUtil 服务桥协议），jcef-bundle 运行时缓存（353MB）删除；
事件包桥语义剥离（BridgeEvent/JsEvent/PongEvent 删，五场景事件转纯 EditorEventBus
事件），Inspector 三 VO 迁 inspector 包作 M4 数据模型。**引擎-编辑器全链路纯 JVM
达成**，原「桥随面板逐批退役」计划作废，M2~M4 面板一律 Java 直调实装；engine-editor-web
前端工程随之整体删除——代码库去 Web 化收官。详见
Editor_Swing_Design §7.1/§9/§11。

进度（2026-10-18 落地，2026-10-23 用户实测验收通过）：**M2 首面板——层级面板 Swing 实装**：`swing/hierarchy`
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

进度（2026-10-19 落地，2026-10-23 用户实测验收通过）：**.mat 资产格式（阶段④剩余收官件）**——
「.mat 就是 Material 的序列化」拍板：源 = Material 文本序列化（.scene 源同款
协议、伴生 .meta 口径，零改 core）；MaterialImporter（SceneImporter 同款，
shader 残档拒绝导入）+ project.create.material 创建命令（创建族首项实做，
落盘即 submitImport）+ project.changed 面板刷新链（首个改盘命令接线，
Editor_Design §10 遗留销账）+ .mat 文件类型注册。详见
Engine_Material_Design §3.2 / Editor_Menu_Design v0.13。

进度（2026-10-20 落地，同日用户实测验收通过）：**插件体系 + editor.core 迁移 + i18n 基建 + 菜单
 i18n 首批**（Editor_Plugin_Design v0.1）：插件契约/装载管线/错误分级落地（ServiceLoader
发现，editor.core 即插件无特权——三个 Setup 迁 EditorCorePlugin.setup 经 PluginContext
收口，引擎依赖懒取）；装载接入 ProjectLoadingFrame（先于资产初始化，Plugins/ 目录
项目级）；i18n 体系（I18nKey 自持 namespace + I18nScope/EditorLang 门面 + I18n 注册
中心——`!key!` 兜底不抛、去重 warn、miss/used 采集）+ 菜单 label 全链 I18nKey
（key=命令 id 约定，域基类默认 label，12 命令类覆写全删），`lang/zh_cn.properties`
36 条（含 create.material 注册补漏 ac8a514）。待拍板挂账：命令 id 前缀化/FileType 冲突策略/默认语言。详见 Editor_Plugin_Design。

进度（2026-10-30 落地，✅ 2026-10-31 用户实测验收销账）：**i18n 全量化批次**（Editor_Plugin_Design
v0.3 §9）——编辑模块 ~300 条非日志用户可见字面量全迁（异常/弹窗/面板与窗口标题/
检查器全件/打包日志行），双语言包 zh_CN·en_US 各 354 键对齐；异常面收 I18nKey
（LangException 受检新基类 + TipException/ShaderParseException 构造器收键）；
@InsField/@InsHeader/InsEnumLabel 的 label 改语言包局部键（反射层三解析点经
EditorLang，§7.1 组件显示名口径拍板落地）；源码机检 CjkStringLiteralCheckTest 落
测试红线（§4.5-3，引导期/日志白名单档内注明）；测试侧 EditorLangTestBase 夹具
（zh_CN 包 + 门面注入，幂等）。

进度（2026-10-24 设计定档，**未实现**）：**资产热重载设计（Engine_AssetReload_Design
v0.2）**——改 .shader/.mat/贴图编辑器实时刷新；当日两稿拍板反转：v0.1 令牌表流派
（持有者钩子+依赖登记）被推翻，终态=**反射遍历流派**（持有引用形态不动，重载协调器
反射走场景对象图换 Asset 字段指针；core 仅增 invalidate/reload，Walker 居编辑器模块
不进 AOT 镜像）；配套=Material 补默认值后处理（pack 缺 entry NPE 实锤）、RenderingSystem
孤儿两帧退役（含纯贴图热重载缺口判据：贴图槽实例变亦退役重建）、Scene 根豁免、导入
失败不 invalidate=天然回退；批次①~④待派发。详见 Engine_AssetReload_Design。

进度（2026-10-24 落地，用户实测验收通过）：**hierarchy.create.cube 立方体创建命令**——
新增内置资产 box.smesh（1×1×1 居中，六面各 4 顶点外向绕序）+ CreateBoxMenuCommand
实装替换占位注册行（menu.hierarchy 创建族第二项，Editor_Menu_Design v0.14）。

进度（2026-10-25 落地，当日用户实测验收通过）：**内置原始体三件套 sphere/capsule/cylinder**——
三份内置 .smesh 由旋转体生成脚本离线产出（一次性工具，用户拍板不入库；母线
绕 Y + 绕序叉积自检、guid 重跑稳定；Unity 口径：球直径 1 / 圆柱高 2×直径 1 /
胶囊总高 2×直径 1），CreateSphere/Capsule/CylinderMenuCommand 照 cube 模式实装
（sphere 替换占位、另两项新增菜单项，Editor_Menu_Design v0.15）。

进度（2026-10-25 落地，当日用户实测验收通过）：**project.create.shader 着色器创建命令（创建族第二项，
Editor_Menu_Design v0.16）**——NewShader.shader 极简前向模板（_Color Color 属性
示范 Properties→MaterialBlock 通路）落选中目录、重名编号去重；**用户文件不写
guid 头**（拍板：guid 头=内置资源专属，重释放稳定语义不外溢用户文件）——guid
真值归伴生 .meta 随机分配、导入器走头缺失回退 meta 既有路径；配套
MetaManager.createNewMeta 收窄：缺头 .shader/.smesh 不再注入路径种子头、改随机
guid（.java 保持注入——脚本无 meta、头是唯一真值）。配套 .shader 检查器只读诊断台落地（Inspector_Design v0.7：guid 双源解析+Library 产物+属性/Pass 表+导入缺失诊断态）。

进度（2026-10-25 落地，当日用户实测验收通过；方案用户拍板）：**.mat 联动第一项：AssetInput 资产
通用输入（Inspector_Design v0.8）**——Unity ObjectField 口径（构造传 Class 声明
接受类型、guid 三态 wire、选择窗+右区拖放+外源刷新三通道、Consumer 提交）；core
补 AssetManager.guidOfPath 反查口（选择窗路径条目取 guid）。控件本批无宿主，
材质编辑面板批次消费联调；**同日追加：材质检查器 Shader 行换装落地**（MaterialInspectorContent：None/加载失败拒绝回滚、setShader 即 core 重置默认值、TextExporter 即时写回 + submitImport 重导——Unity 资产即时保存口径、写回失败从盘面重载）。**参数编辑同日落地（v0.9）**：类型感知控件全复用 widget 家族（COLOR=ColorField/VECTOR=VectorRow 四段/FLOAT·RANGE=JeNumberInput 区间硬钳/面型=JeSelect）+ 提交=写内存 + 防抖 500ms 写盘重导（换选/onDetach flush 兜底）+ 材质信息分组固定不可折叠（CollapsiblePanel setCollapsible）。**GUID_ASSET 通道升可编辑同日落地（v0.10）**：MeshRenderer mesh/material 行换装 AssetInput（通道级通用，VO 新增 assetType 声明类型）、回写放开（None 清空合法/空串拒绝/guid 经 loadGuid 解实例未索引拒绝）——只读前身 GuidAssetField 退役；运行期在用引用热切归资产热重载批次。

进度（2026-10-25 落地，当日用户实测验收通过）：**材质编辑即时联动渲染（Inspector_Design v0.11）**——用户报「改 .mat 场景画面不动」：.mat 面板编辑载体改 loadGuid 缓存共享实例优先（命中且 shader guid 与盘面一致 → 直接编辑场景 MeshRenderer 持有的同一实例，数值/面型提交即时联动渲染）；shader 分歧守卫（防旧实例防抖写盘把换绑悄悄导回旧 shader）+ **Shader 换绑例外不落共享实例**（渲染侧 MaterialResources 按 shader 首绑烤死 UBO 尺寸/贴图绑定数，live setShader 会越界/错配）——改文本重解析实例上换绑+写盘+重导；追修 GUID_ASSET 回显漏传与显示名去后缀、选择窗 editor 组内置资产过滤。

进度（2026-10-25 落地，当日用户实测验收）：**资产管线 meta 统一与重导检出**——.shader/.smesh 落伴生 .meta（guid=文件头镜像）、BuiltinAssetReleaser 内容字节比对驱动重导、needImport 统一 mtime 判定（gizmo 被 box 遮挡=Library 旧产物劫持案根治链，Editor_Design §6.3）。

进度（2026-10-25 落地，**当日用户实测验收通过**）：**贴图资产管线（Engine_Texture_Design v0.1）**——三层资产模型
拍板：Image 像素资产=导入产物（磁盘一份全项目共享）、Texture2D 改造为引用层（持 image 引用 +
filter/wrap/mipLevels 设置——一图多设置免复制，消解 Unity 单层「多设置须复制文件」痛点；原单层
骨架 2026-09-25 时期产物本轮改造，冻结语义保留覆盖 setImage）；ImageImporter（.png/.jpg/.jpeg
同入口，ImageIO 解码→RGBA8→Library 产物，伴生 meta 随机 guid 同 .mat/.scene 口径）+ image 文件
类型/图标 + alpha 通道保留修复（原 decode 写死 0xFF 丢透明度）；消费链（Material 贴图槽/
TextureUpload/RHI）零改动兼容；纹理资产（.texture 用户文件+自动伴生默认 Texture2D）与
Inspector 贴图槽编辑挂账另批。

进度（2026-10-25 落地，**当日用户实测验收通过**）：**纹理资产与创建菜单（Engine_Texture_Design v0.2 +
 Editor_Menu_Design v0.17 + Inspector_Design v0.12）**——project.create.texture 创建命令
（texture.texture=Texture2D 文本序列化「.texture 就是 Texture2D 的序列化」，guid 与伴生
 .meta 同源四处一致，CreateMaterialMenuCommand 同款落盘即导入链）+ TextureImporter 文本
 导入器（image=@guid 引用解/null=纹理壳合法）+ .texture 检查器面板（图=AssetInput 换绑
 即时写回重导/None 合法、采样 filter/wrap 下拉防抖写盘；**编辑载体=文本私有实例拍板**——
 Texture2D 冻结语义使共享已上传实例不可编辑，与 .mat v0.11 共享实例路线分歧，即时联动归
 资产热重载批次）+ AssetInput/AssetPickerDialog 升多后缀（Image=[.png/.jpg/.jpeg]）+
 RenderingSystem null-image 占位守卫（纹理壳不崩）+ texture.svg 棋盘格图标。同批修复：
 ShaderParser 2D 类型关键字死分支（readIdentifier 拒数字开头致 2D 属性恒炸——首个 .shader
 贴图属性实使用暴露，ShaderParserTest 自 2026-09-26 起即覆盖此分支但从未跑绿）、材质面板
 参数/贴图槽行 label 误用绑定名改 displayName（Unity 口径）。遗留：材质贴图槽行换装
 Texture2D（下批）、编辑即时联动归热重载批次。

进度（2026-10-27 落地，**2026-10-30 用户实测验收通过**）：**资产热重载批次①（Engine_AssetReload_Design v0.3）**——
反射遍历流派最小闭环：core 仅 `loadCache` 放 protected（拍板 2026-10-27：reload 归编辑器
`ProjectAssetLibManager`，core 接口不加 API）；`invalidate/reload`（丢弱缓存条目 + 重读盘上
产物，失败保持旧引用残档口径）+ 反射 Walker（`editor/asset/reload`，材质补默认值后处理，
setter 旁路口径）+ `requestReconcile` 孤儿回收（RenderingSystem mark-and-sweep 两帧退役，
`MaterialResources` 增 builtFromShader/SlotBinding 贴图槽对账——活集口径已核实无误杀）+
`AssetReloadService`（订 ImportListener → submitTask 引擎帧首 reload→换指针→对账→
AssetReloadedEvent）+ 材质面板最小钩子（AssetReloadedEvent 重拉编辑载体，保 v0.11 即时
联动不退化）。验收口：改 .shader/.mat → 手动 reimport → 画面当帧变 + F3 存活计数不涨；
.png 像素源/焦点扫描/.scene 豁免归批次②③。

进度（2026-10-27 落地，**2026-10-30 用户实测验收通过**）：**坏 shader stub 产物 + 渲染回退默认 shader
（Engine_Material_Design v0.3）**——判据 = `Shader.isValid()`（至少一个 Pass 且 vs/fs
SPIR-V byte[] 均非 null，拍板：无有效二进制 = 坏 shader）；ShaderImporter 编译失败落
**stub 产物**（空字段 Shader + guid，任务 SUCCESS 带 errorMessage——原「保持旧版本
天然回退」口径作废，§6/验收 #5 同步）；渲染链（bridge.sync 注册分支 + materialSet/
pipelineFor 入口）经 `DefaultShader` 门面回退默认材质单例（内置 `core/shader/default.shader`
平色紫，JavelinEngine.initialize 注入 AssetManager，missing-material 语义不变）；
reconcile 活集显式加默认材质防误退役；修复后 reimport 真产物经热重载 walker 换代恢复
（stub 带 guid 可匹配，无「需重载场景」边界）；诊断台坏态显示 + 创建命令 SUCCESS 带
errorMessage 也弹框（红线 15）。附带收益：坏 shader 不再炸帧循环（mainLoop 对
renderFrame 无异常隔离，原 RenderObject ctor 抛错直通 shutdown）。

进度（2026-10-27 落地，**2026-10-30 用户实测验收通过**）：**焦点扫描资产刷新 + 导入进度模态框（热重载
批次②焦点扫描部分，Engine_AssetReload_Design §5.1）**——MainFrame windowGainedFocus →
EditorFocusGainedEvent（总线解耦窗口与扫描）→ AssetReloadService 后台线程跑
`AssetImportPipeline.refresh()`（现成扫描逻辑复用：walkAssetDir → needImport/未导入 →
submitImport，纯 IO 不占 EDT；导入中不重扫防重复提交）→ 有待导入弹 **ImportProgressDialog
模态小框**（不确定进度条 + 剩余项数，Timer 轮询 `getImportingCount()` 归零自动关；不可
手动关防焦点回落重扫重弹循环；模态泵内 Timer 正常触发）；自身写盘 mtime 幂等无防抖特判。
**同批 i18n**：进度框 / 创建着色器弹框 / 着色器诊断台状态行 10 条文案随批接 EditorLang
（lang/zh_cn.properties `asset.import.*`/`create.shader.*`/`shader.inspector.*`——新功能
文案不再攒批进第二批，Editor_Plugin_Design §7.2-0）。批次②剩余 Scene 根豁免仍后置
（外部改 .scene 重导 Library 但场景 warn 跳过）。

进度（2026-10-29 落地，**2026-10-30 用户验收销账——单测两套绿 + 编辑器实机多材质渲染，随验收修复文本列表 guid 硬编码 Asset.class 崩 + default.scene 文本头手迁**）：**Mesh 子网格 + MeshRenderer 多材质
（Engine_Assets_Design §2.9 / Engine_Scene_Design §5.1 v0.5）**——拍板：子网格 =
单索引数组 + SubMesh 区间列表（Unity subMeshCount/SetTriangles 语义，RHI drawIndexed
三参扩展 firstIndex 范围绘制）；材质列表 i 位对应子网格 i，每 (renderer, 子网格) 一
RenderObject（透明分桶/排序/PSO/热重载对账按 RenderObject 粒度天然成立，RenderingSystem
零改动）；**缺失兜底语义变化**：materials 缺失位/空列表/shader 无效 → DefaultMaterial
（原 DefaultShader，2026-10-29 改名——共享默认材质单例懒建一次，语义不变）
默认材质兜底渲染（原「material null 不渲染」口径作废，仅 mesh null 不渲染）；
.smesh 增 [SUBMESH_N] 段（与 [INDEX] 互斥，缺省=单子网格向后兼容）；检查器
GUID_ASSET_LIST 通道多材质行（行数跟随子网格数，Inspector_Design v0.13）；
getMaterial()/setMaterial() 兼容门面（既有调用点零改动）。验收口：多子网格 .smesh
+ materials[2] → 两子网格各按材质渲染；缺材质位 → 默认材质可见。

进度（2026-10-29 落地，**待用户验收**）：**热重载范围收窄只 shader + DefaultMaterial
改名 + material() 双重检查锁**（Engine_AssetReload_Design v0.4 / Engine_Material_Design
v0.4）——拍板判据 = **文件真值源 + 渲染侧按实例身份烤死**：Shader（文件源 + set2 布局/
UBO 尺寸/PSO 缓存键按身份烤死）必须 reload；.mat（编辑=检查器内存直改即时生效——
数值参数 UBO 每绑重打包/面型活读/贴图槽实例换代，文件是镜像非源）、.texture/.smesh/
像素源（GPU 上传烤死须重上传）**摘出 reload 链「后面再加」**（基建不动——walker/
reload/reconcile 全保留，targetClass 加回分支即恢复；外部手改 .mat 不自动生效）。
DefaultShader → DefaultMaterial 改名（门面身份=共享默认材质单例，懒建一次全引擎复用，
identity 稳定；API 面全保留）；material() 改双重检查锁（已建走无锁快路径，仅首次建
进同步块）。验收口：改 .shader → reimport → 画面当帧变；改 .mat → 不 reload（检查器
内存编辑仍即时生效）。

进度（2026-10-29 落地，✅ 2026-10-31 用户实测验收销账）：**插件装载错误显性化 + 语言包注册制随批**
（Editor_Plugin_Design §3.4/§4.3）——装载期任何插件报错（本体/第三方、发现/装载/
namespace 冲突/setup 失败，含语言包路径缺失）一律弹错误框显示 namespace+错误信息
（EDT invokeAndWait 阻塞至确认，显示 namespace+包路径+错误信息——包路径供排障
定位，文案硬编码中文——装载期 i18n 未注册），确认后
System.exit 关闭编辑器；**原「第三方 warn 跳过/降级装载」口径作废**（静默跳过坏
插件 = 排障难题，红线 15 推到装载期）。语言包注册制：registerLanguage（支持语言
菜单选项源）+ registerPack「域+语言+路径」显式注册（固定路径规范废除，一文件一
域，集中式语言包可翻译其他插件域），editor.core setup 注册 en_US/zh_CN；缺键逐键
回退该域基线（第一个注册的语言包，英文不特殊）。

进度（2026-10-30 落地，**同日用户验收销账——单测+实机全过**）：**资产热重载注册表流派 + 运行期可变 + Cleaner 重构随批**
（Engine_AssetReload_Design v0.5 / Engine_Texture_Design v0.3 / Engine_Material_Design v0.5 /
Vulkan_Pitfalls #26）——
- **Walker 改对象注册表全量扫描**：`ObjectId` 增构造回调 registrar（引擎运行时零开销）+ 新增
  `EditorObjectRegistry`（弱引用收拢存活 ObjectId，snapshot 入口清理）——覆盖范围不再依赖
  GameScene 可达性；Walker 只扫单对象字段不递归，换指针 **setter 优先**，Material 保参退回
  `setShader` 单点；导入回调总线化（`AssetImportEvent` 替 `ImportListener`）。
- **Texture2D 冻结废除**：运行期可变（setImage 换图作废旧 GPU 句柄重传、采样设置直接生效）+
  `revision` 驱动 `RenderingSystem.materialSet` 两帧退役重建；**Material.setShader 改 Unity 保参**
  （同名同类型保留值）+ `textureRevision`。语义作废两条：Texture2D 上传冻结、Material「换 shader
  清空全填默认」。
- **Cleaner Registration 合一**：手动 dispose 与 Cleaner 兜底共用两帧延迟队列、废除句柄登记集合；
  `VulkanDevice.dispose` 前 flush；compute 内部 layout 补注册。
- **logback 文件输出启用**（info/error 双 rolling，20MB 切分，logs/ 入 .gitignore）。
- 验收口：改 .shader → reimport → 画面当帧变 + F3 存活计数不涨；运行期换图/改采样即时生效；
  resize/热刷无显存增长；logs/ 生成。

修复批次（2026-11-07，落地待用户实机验收）：**检查器旋转行跳动根治**——用户报
「调整旋转时跳动、另外两个值也会变动」；根因 = 原 XYZ 欧拉规范域 Y∈(-90°,90°)
越界后引擎帧级回显的等价翻转三元组覆盖用户显示值、下一次提交按翻转表示反向推进
（拖动跨 90° 逐帧振荡：X/Z 闪 0↔180、Y 在 89↔91 来回弹）。修法 = 欧拉口径切 Unity
同款 Z-X-Y（RotationMath 换算单点——偏航/翻滚不再翻转、俯仰 ±90° 越界翻转=Unity
固有）+ 面板级自旋回声抑制（近期写入环 + 事件原始四元数同旋转判定跳过回写）。
随用户实机复验追修：首版判定器未按模长归一（float32 四元数非严格单位化）致抑制
失灵「x 越 -90° 仍跳动」——修 = 归一化判据 + 判定数据源改事件原始四元数。回归断言
RotationMathTest；详见 Inspector_Design v0.15。

---

## 3. 三期：光照

- **目标**：场景开灯出可见差异——灯光数据通路 + 有光照着色 + 阴影 + 视觉天空盒；**质量升级（后处理/阴影软化级联/环境光照/实时 GI/光追）整体归四期（2026-10-31 拍板）**；
- **拍板（2026-10-28，`Engine_Lighting_Design.md` v0.1 单点；主光裁决 2026-10-31 改向 v0.5）**：**clustered + SSBO（Forward+ 形态）一步到位，跳过 UBO 数组 / 逐盏画光体积中间档**——只面向 PC、质量优先；正透明同读一份 cluster（管线档 4.5 两路光照一致性取舍**消解**）；默认参数 = 16×16 tile × Z32 指数切分、每 cluster 索引上限 64（截尾=去最弱）、光列表容量 4096；主光 = **最强激活方向光自动裁决**（`Scene.getSun()`，对齐 Unity 拍板 2026-10-31——原显式 isMain 方案推翻，同强度取挂入序首个）；shadowMap 挂点 = set1 binding4；
- **内容**（四批，灯光档 §9）：
  1. **RHI 底座前置（新增段）**：~~SSBO 实装（现仅设计稿 API）+ ComputePipeline/dispatch + RenderGraph compute Pass 节点与 Buffer 读写同步——compute 写 SSBO → 图形 Pass 读回最小闭环为验收~~ **✅ 批次①落地并验收关账（2026-10-31 用户实测，细则见 §5 阶段表三期行）**；
  2. **Light 组件 + 收集**：DIRECTIONAL/POINT/SPOT（字段级归灯光档 §4）+ Scene lights 注册表 + 视锥剔除/强度预排序/视空间 64B 打包——**✅ 批次②落地并验收关账（2026-10-31 当日用户实测）**：Light/RenderLight/LightCollector + set1 binding0/1 数据面（LightingParamsBlock UBO 动态偏移 + lightList SSBO）+ ShaderInjector fs 声明注入/保留字生效 + F3 行 8 灯数 + 单测（LightCollectorTest/GameSceneTest 灯用例）；验收 = 单测全绿 + 挂灯实机 F3 灯数显示（着色消费归批次③，本批无画面验收项；细则灯光档 §9 批次②行）；
  3. **cluster 构建 + Forward 消费（= Forward+ 成形）**：ClusterBuildPass（compute，每视口，三步构建）+ 着色器光照段注入 + Lambert + 环境项 + SPOT（后半）；
  4. **ShadowPass + Deferred 复用（2026-11-01 拆分拍板：④-A/④-B 两步派发——Deferred 复用牵材质着色模型/材质专档拍板，混批风险大）**：**④-A ShadowPass 单张硬影 ✅ 已落地并验收关账（2026-11-01 用户实机全项）**——v0.x = 单张硬影（2026-10-31 拍板：单张 2048² 正交包围盒、无 PCF/无 CSM/bias 保守值，质量分级后置灯光档 §10）+ 注入段阴影采样分支（javelinLighting 三参签名扩 worldPos）；实装形态/挂点五拍板（PSO 静态 bias 字段/per-view 幂等挂载/binding4 并入 set1 布局/深度比较采样器（**随验 LINEAR→NEAREST 拍板**）/sunViewProj 扩块 @336——灯光档 §4.5.1 全录）；**④-B GBuffer/LightingPass 同一 cluster 直喂 + 混合/纯 Forward 可配置切换**（拍板后归派发）；
  5. **天空盒（2026-10-31 建档）**：cubemap 形态（RHI 新纹理种类底座）+ SkyboxPass（管线 3.5 位）+ ClearFlags.SKYBOX 兑现 + 资产/场景接线——**纯视觉不参与光照**（ambient 升级归 GI 探针期）；`Engine_Skybox_Design.md` v0.4 单点——**三步全部验收关账（步骤一/二 2026-11-02、步骤三资产面 3a/3b/3c 2026-11-07 用户实机全链：.hdr 拖入导入/场景根行换绑/视口即时换背景/转动无痕）**；随验六连排障全修（SKYBOX 默认值/清屏拖影/sRGB 量化偏暗/相机位置黑区/采样器 CLAMP_TO_EDGE 棱/转换器纬度归一化角部——坑 Pitfalls #30/#31/#32，档 §1.3~§1.5）；
- **着色器配套口径（2026-10-31 用户拍板，2026-11-01/02 两次演化）**：**内置 default.shader = DefaultMaterial 兜底专用（纯紫=错误显性化产物），禁止改造成光照注入样例**——注入验证样例 = 批次③新增内置 Lit.shader；~~clusterBuild compute 与 SkyboxPass 走引擎内嵌 spv 链路~~（已双重作废：cluster 构建 CPU 化拍板 2026-11-01、GPU 归四期；着色器装载改走 SpvShader 资产导入链——shadow/skybox 内置 .vert/.frag 源码 + SpvShaderImporter，2026-11-01 ④-A 拍板推翻 tool 内嵌 spv）；内嵌 .spv 等非源码资源全迁源码走导入链已拍板（Skybox 档 §8 后置项，后续批执行）；
- **验收**：方向光/点光下物体明暗正确 + 主方向光投影正确（用户运行）；几十盏场景帧率稳（F3 帧时间不劣化超阈值）；混合/纯 Forward 可配置切换且两路光照一致（管线档 §8 步骤二回填点）；
- **后置项全部归四期**（2026-10-31 拍板，见 §4）：后处理链、clustered 后置项（前缀和计算化/脏标记跳重建/Morton/深度重建，灯光档 §10）、阴影质量分级（PCF/CSM/bias 精调，灯光档 §10）、GI、光追、天空盒环境光照。

---

## 4. 四期：高质量渲染（2026-10-31 立项，拍板：后期项整体归此期）

> 三期收官 = 「有光照」（Forward+ 成形 + 单张硬影 + 视觉天空盒）；四期 = 质量升级全谱。排序为初步依赖序，开工前逐项拍板方案（先方案后码）。

1. **后处理链**（Bloom/Tonemap + **HDR 切换点**，管线档 4.6——四期第一候选：链式结构已落地，具体化零新底座）；
2. **SSAO**（后处理链 Pass，管线档 4.6 挂点——**GI 第一步**，零新底座）；
3. **阴影质量分级**（PCF 软采样 → CSM 级联 → bias 精调/per-object 开关/stabilize，灯光档 §10——消费侧注入段与 shadowMap 底座三期已备，纯增量）；
4. **天空盒环境光照**（天空盒作 ambient 源：SH9 球谐或 mipmap 降采样，替换灯光档 §3.3 ambient 常量——**DDGI 落地前的过渡环境光**；天空盒视觉面三期批次⑤已备）；
5. **探针实时 GI**（GI 目标态拍板 2026-10-28：**DDGI 路线**，团结引擎实时 GI / HDRP APV 产业验证）——烘焙 GI 管线**不建**（只面向 PC 无移动端约束、lightmap 生产线/烘焙编辑器链不投入，探针实时为主场景）；探针系统复用三期批次① SSBO/compute 底座，求交后端可替换（软光栅/迷你深度图 → rayQuery，前端探针网格/时序混合/着色采样对求交后端无关）；Lambert+环境项的 ambient 即 GI 占位符，探针落地时按位置查询替换常数（含第 4 项的过渡环境光）；探针采样前置 = **3D 纹理/纹理数组**（RHI 现仅 Texture2D——cubemap（三期批次⑤）同族先行，纹理数组随探针实装前核实）；探针软技术门槛（重定位/边界混合/薄墙光渗抑制 visibility）届时专档；
6. **光线追踪**（光追方向 2026-10-28 记录）：**rayQuery（VK_KHR_ray_query，compute/fs 内 inline 求交）优先**、ray tracing pipeline（SBT/rgen-chit）后置；架构支持结论 = 声明式 RenderGraph 同步/有序 Pass 列表/set 化描述符天然兼容——GI 与 RT 均为「新资源种类进图 + 新消费者」，不返工管线骨架；缺口清单 = 加速结构（AS）新资源种类与 build 写/trace 读图状态、BDA（buffer device address）、GL_EXT_ray_query 编译链（ShaderImporter/工具链）、蒙皮动态几何 BLAS 重构（引擎无动画系统，RT 首范围=静态几何）；**前置约束 = 三期批次①通用性红线**（灯光档 §9——Buffer 进图同步 + storage 绑定按通用 RHI 资源面建，禁止光照特化窄接口——四期 GI/RT 是该红线的第一复用方）。

- **着色模型升级**（Lambert → BRDF，材质/着色模型专档）与 **clustered 后置项**（前缀和计算化/脏标记跳重建/Morton/深度重建，灯光档 §10）不设期，随四期各批次需要顺带或独立小批。

---

## 5. 与 `Engine_Implementation_Plan.md` 六阶段的映射

| 路线期 | 实现计划阶段 | 状态 |
|--------|-------------|------|
| 一期 | 阶段① 引擎接线 / 阶段② 场景系统 / 阶段③ 渲染系统 | ✅ 完成 |
| 一期 | 阶段④ 材质系统——**桥接半边**（§4 渲染侧三环节 + Shader/Material 数据模型） | ✅ 完成 |
| 二期 | 阶段④ 剩余（.shader/.mat 资产格式 + javelin-tool）→ 并入编辑器基本前置 | 🔄 .shader 侧已落地（2026-09-26，e03c057）；.mat/AssetManager✅ 已落地（2026-10-23 用户实测验收通过） |
| 二期 | 阶段⑤ 子系统 + 输入；**新增**：碰撞检查 / Ray / 编辑器基本（原计划未列，路线档补入） | 🔄 输入子系统✅ 已验收关账（2026-09-27）；碰撞检查 v0.1✅ 全片单测验收关账（2026-10-06，§2.2，场景级查询归 Ray 期）；Ray 侧 Camera.screenToRay✅（2026-10-06，场景级 raycast 未做）；编辑器基本✅ 主体验收收官（2026-10-14）：窗口层 + .scene 序列化✅（2026-10-04）+ Gizmo 轴拾取✅/拖拽✅（2026-10-14）+ **编辑器骨架全链**（菜单/层级/Inspector/Scene-Game 切换/资产导入，2026-10-11）+ **打包 JAR/AOT 双形态✅（2026-10-13 三端验收——编辑→打包全流程闭环）**+ 项目面板/右键域首期（2026-10-17 落地、2026-10-23 用户验收）+ **UI 栈去 Web 化重构 M1✅（2026-10-17 用户验收——Swing+FlatLaf 主窗口/Dock/视口，M2~M6 见 Editor_Swing_Design）** + 三窗口迁 Swing + jcef 桥包/jcefmaven/jackson 清退（M6 全项提前完成，2026-10-17/18 落地、2026-10-23 用户验收——全链路纯 JVM）+ **M2 层级面板 Swing 实装（2026-10-23 用户验收，Editor_Swing_Design §7.5）** + **M3 项目面板 Swing 实装（2026-10-23 用户验收，Editor_Swing_Design §7.6）** + **M4 检查器面板 Swing 实装（2026-10-18 用户实测验收通过，Editor_Swing_Design §7.7 + Inspector_Design v0.4）+ 检查器注册器（Node+资产统一接管+材质只读，2026-10-21 用户实测验收通过，Inspector_Design v0.5）+ 层级根行选中（=场景文件 project 槽，2026-10-22 落地、2026-10-23 用户验收）+ 检查器输入交互增强（键入即提交+label 拖动调值，2026-10-22 用户实测验收通过，Inspector_Design v0.6）**；** + **插件体系 + editor.core 迁移 + i18n 基建 + 菜单 i18n 首批（2026-10-20 用户实测验收通过，Editor_Plugin_Design v0.1）+ **GPU ID-buffer 拾取✅（2026-10-23 用户实测验收，Engine_Picking_Design v0.1——物体+Gizmo 轴统一，CylinderCollider 过渡线退役）** + **hierarchy.create.cube 立方体命令✅（2026-10-24 用户实测验收，Editor_Menu_Design v0.14）** + **视口聚焦（层级双击 + F 键 → 相机平滑框住选中物，2026-10-24 用户实测验收，Editor_Design §10）** + **内置原始体三件套 sphere/capsule/cylinder✅（2026-10-25 用户实测验收，Editor_Menu_Design v0.15）** + **着色器创建命令 project.create.shader✅（2026-10-25 用户实测验收，Editor_Menu_Design v0.16）** + **资产管线 meta 统一与重导检出✅（2026-10-25 用户实测验收——旧产物劫持坑根治，Editor_Design §6.3）** + **检查器 .shader 诊断台→.mat 材质编辑全链 v0.7~v0.11✅（2026-10-25 用户实测验收，Inspector_Design——诊断台/AssetInput+Shader 行换装/参数编辑/GUID_ASSET 升可编辑/编辑即时联动渲染）** + **贴图资产管线（Image 像素资产/Texture2D 引用层三层模型 + ImageImporter，2026-10-25 用户实测验收，Engine_Texture_Design v0.1）** + **纹理资产 v0.2（project.create.texture + TextureImporter + .texture 检查器面板 + 材质贴图行换装内联 + AssetInput 多后缀，2026-10-25 用户实测验收，Engine_Texture_Design v0.2/Editor_Menu_Design v0.17/Inspector_Design v0.12）** + **Mesh 子网格 + MeshRenderer 多材质 + 检查器 GUID_ASSET_LIST 多材质行✅（2026-10-29 落地、2026-10-30 用户验收，Engine_Assets_Design §2.9/Engine_Scene_Design §5.1 v0.5/Inspector_Design v0.13）** |
| 三期 | 灯光 + ShadowPass + 天空盒（clustered Forward+ 拍板 2026-10-28——五批实装：RHI SSBO/compute 底座→Light 组件收集→cluster 构建+Forward 消费→ShadowPass（**单张硬影 2026-10-31 拍板、④-A/④-B 拆分 2026-11-01**）+Deferred 复用→SkyboxPass（cubemap+纯视觉，2026-10-31 建档）；Engine_Lighting_Design v0.8 / Engine_Skybox_Design v0.4） | 🔄 **批次①②③④-A⑤已全部落地并验收关账**（④-A ShadowPass 单张硬影=2026-11-01 用户实机全项：主光投影正确/调 castShadow 即时增减投影物/透明物不投影不接影/双视口不掉影/分辨率切档即时重建——**随验拍板 LINEAR→NEAREST**（斜投影边缘块状锯齿），细则见 §5 阶段表与灯光档 §9；随批两散件同日验收：project.create.scene 创建场景命令 + 启动场景记忆写环（重启恢复上次场景））——④-A 实装面：RHI 三补（SamplerDesc 深度比较重载 + GraphicsPipelineDesc depthBias 三参/空颜色格式 depth-only 新约 + VulkanSampler/VulkanGraphicsPipeline 填参）+ `ShadowMath` 纯逻辑视锥构造（JOML lookAt 同式基 + 行和半宽 + zZeroToOne，8 角覆盖单测）+ **着色器走资产导入链（2026-11-01 用户拍板推翻 tool 内嵌 spv 形态）**：SpvShader 资产类 + SpvShaderImporter（.vert/.frag 扩展名即 stage）+ 内置源 builtin-asset-core/shadow/ 伴生 meta + index 收新后缀 + FileType/glsl 图标/双语包键 + `ShadowPass`（覆盖制 depth-only / 128B 专属 push / PSO 静态 bias 2.0/1.5 / per-view 幂等挂载 / AssetManager 按路径装载，ShadowAssets+generateShadowSpv 已删）+ RenderingSystem（computeShadow 收集域 + shadowMap/compare 采样器常驻 + packing sunViewProj 扩块 @336 块 400B + binding4 并入 set1 布局 + shadowCasters 透明过滤）+ Opaque/Transparent addTextureRead(shadowMap) 边 + ShaderInjector（javelinLighting **三参签名** + binding4 sampler2DShadow 保留字 shadowMap）+ Lit.shader 双镜像 vs/fs worldPos 插值；**批次④-B（GBuffer/Lighting 复用+两路切换）未开始；批次⑤天空盒三步全部验收关账（步骤一/二 2026-11-02、步骤三资产面 2026-11-07 用户实机全链：.hdr 拖入导入/场景根行换绑/视口即时换背景/转动无痕——equirect 导入转六面 + CubemapImage 资产 + Scene.skybox 场景级引用 + 场景根行检查器；随验六连排障全修 SKYBOX 默认值/清屏拖影/sRGB 量化/相机位置黑区/采样器 CLAMP_TO_EDGE/转换器纬度归一化，坑 Pitfalls #30/#31/#32）**；三期剩余 = ④-B Deferred 复用 |
| 四期 | 高质量渲染（2026-10-31 立项，§4 单点——后处理链/SSAO/阴影质量分级/天空盒环境光照/探针实时 GI（DDGI）/光追 rayQuery） | ⬜ 立项收口（排序为初步依赖序，各项开工前逐项拍板方案） |
| 未定 | 阶段⑥ 物理 / 音频（选型拍板后各成阶段） | 未开始 |

> **2026-10-30 批映射注**：资产热重载注册表流派（Walker 全量扫描 + 导入事件总线）、Texture2D 运行期可变 +
> revision、Material.setShader 保参 + textureRevision、logback 文件输出 → 挂靠**二期「编辑器基本 + 资产管线」**；
> Cleaner Registration 合一 → 挂靠**一期 RHI 底座（既有关账能力上的防御性重构）**。状态 = **2026-10-30
> 用户验收销账（单测+实机全过，见 §2.4 本批进度段与各设计档版本沿革）**。

## 6. 跨期遗留（不阻塞当期推进）

- RHI 遗留：Buffer staging 上传（deviceLocal）、描述符池环形、backbuffer 句柄入 structureHash；
- 场景：environment/sun（三期灯光一并定）、.scene 序列化（二期编辑器前置）；
- 资产：动态 Mesh/动态纹理专项（设计要点已记录，材质档 §6 / 资产档 §2.8）；
- 渲染优化：视锥剔除（Aabb/Mesh.bounds 已随碰撞片③回归 2026-10-05，剔除本身仍后置）、遮挡剔除/GPU Instancing/LOD（P2）；
- 构建链：~~groupId/包名迁移拍板（2026-10-29）——目标命名 `cn.donting.javelin`（包根 `cn.donting.javelin.*` = 坐标 `cn.donting.javelin:javelin-*`，groupId 与包根完全对齐、artifactId 与目录名零改动；触发条件 = 开源/上 Maven Central——`com.javelin` 域名非自有；方案 = 一次性前缀替换 `com.javelin` → `cn.donting.javelin`，尾部全不动，无断代风险；独立一批纯重构）~~ **✅ 已执行并验收（2026-10-31，开源前置批；同日用户实测销账）**：主源码 = 用户 IDEA Shift+F6 重构（六模块主树 + 服务文件名 `META-INF/services/cn.donting.javelin.editor.plugin.Plugin` 自动迁移，MCP rename 实测不支持包级故手工）；随批补齐 IDEA 未覆盖面——javelin-core test 树 30 文件整树补迁（IDEA 未扫 test 源码根）、字符串字面量 15 处（`Class.forName` 生成类名×2、`@SupportedAnnotationTypes`/生成类名/reflect-config 路径等 ReflectiveProcessor×8、rhi 3 处漏改 import、javadoc 引用）、全部 build.gradle（group/mainClass×5/Automatic-Module-Name）；**project-template 同批整修**：坐标 `cn.donting.javelin:javelin-*`、mainClass GameMain/AotGameMain、死仓库清理（jcenter.bintray.com/oss.sonatype snapshots，现仅 mavenLocal+mavenCentral）、junit-bom 5.10.0→6.0.0 与 editor 对齐、compileOnly 重复行删除；**版本收根单点**：javelin-log logback 硬编码 1.5.13 改走根 ext（1.5.38）；doc 17 份 FQCN 引用统一替换（含批量替换误伤拍板沿革行已修复）；**用户游戏项目默认 groupId 占位符 = `com.javelin.game`**（GradleProject.replaceVar 查找串与 ProjectFrame 默认值、模板 group 占位符三处同步——用户项目包名与引擎坐标无关，非残留）；全仓机检 `com.javelin` 归零（源码/构建/资产/服务文件/文档）。**版本号拍板（2026-10-31 随批，同日二次反转）**：`1.0-SNAPSHOT` → `0.1.0` 固定无后缀（号诚实反映完成度、Central 拒收 SNAPSHOT 社区去 SNAPSHOT 化）→ **用户当日复盘改回 `0.1.0-SNAPSHOT`（终态：开发期保持 SNAPSHOT——构件语义=开发中、mavenLocal 同号覆盖发；release 发布时才去后缀定版，Central 不收 SNAPSHOT 约束移到发布批）**——收 `gradle.properties` 单点（Gradle 约定自动读取），7 个 build.gradle 删 `version =` 行继承根、project-template 版本与依赖坐标同步；README 消费示例坐标 `0.1.0-SNAPSHOT`（intro 阶段描述保留 `0.1.0` 为阶段名）。~~坑点：固定版本同号覆盖缓存~~ **随改回 SNAPSHOT 作废**（SNAPSHOT 每次重查，Tooling API 缓存陈旧坑不复存在）。~~**验收口径 = 整套 test 跑绿 + 编辑器新建项目/打包三端实机一致**（注意：mavenLocal 须重发新坐标 `cn.donting.javelin:javelin-*`，否则 project-template 解析不到依赖）~~ **✅ 2026-10-31 用户实测销账：test 全绿 + mavenLocal 重发新坐标 + 三端实机（编辑器新建项目/打包）与改前一致**；
- 开源前置（2026-10-29 拍板起步）：**License = MIT**（个人开发者作品，与 LWJGL BSD-3/JOML MIT 生态一致；Apache-2.0 的企业贡献者增益不适用）；**默认语言拍板 en_US（2026-10-29 落地，✅ 2026-10-31 验收——随 Features 语言设置三行已翻绿）**——`lang/en_US.properties` 新建（editor.core 全量 50 键英文翻译）+ `I18n.DEFAULT_LANGUAGE` 切 "en_US"，zh_CN.properties（原 zh_cn 同批大写规范）降为可选本地化包；**首选项菜单 + 语言设置全链落地（2026-10-29，✅ 2026-10-31 验收）——file.preferences 首选项命令（Editor_Menu_Design v0.18，「首选项」非「设置」命名拍板）+ 语言包注册制（I18n.registerLanguage 支持语言 + registerPack「域+语言+路径」显式注册，`lang/{lang}.properties` 固定路径规范废除——文件布局自定/一文件一域/集中式语言包可翻译其他插件域，否决 Lang 枚举）+ EditorSettings.language 字段 + 启动链 main setLanguage（已存值优先/未设置按系统语言 tag/**缺键逐键回退该域基线=该域第一个注册的语言包，英文不特殊**），确定写盘 + 重启生效拍板**；开源批待做 = ~~LICENSE~~（✅ 2026-10-31 落库：MIT 全文，版权行暂用开源惯用「The Javelin Engine Authors」——换个人/组织名改一行即可）+ ~~THIRD-PARTY + 思源黑体 OFL~~（✅ 2026-10-31：THIRD-PARTY-NOTICES.md 十一项清单 + OFL.txt 随字体同目录（版权声明行+官方全文，Blender SPDX 文本库取源）；**倒挂纠错**：FlatLaf 实为 **Apache-2.0**（jar 内 META-INF/LICENSE 实证——此前记 MIT 错）、JSVG=MIT（jar 实证）；Shaderc/JOML/logback/LWJGL/gradle-tooling-api/JUnit 维持原判；GraalVM native-build-tools 按 Apache-2.0 记（构建期插件、jar 无许可文件））+ ~~README~~（✅ 2026-10-31 双语落库：README.md 英文主档 + README.zh-CN.md 中文档互链——功能清单/环境要求/快速开始/模块表/坐标消费示例；编辑器 IDE 启动说明如实写「暂无 gradle run 任务」。**用户指正两处已修（2026-10-31）**：AOT 部署为核心目标须在定位段突出（intro 专句 + 打包条目升格「AOT 与 JAR 打包」+ 显式反射注册/reflect-config 编译期生成卖点），环境要求写 **GraalVM for JDK 25** 而非裸 JDK（JVM 开发任意 JDK 25 可、AOT 必须 GraalVM+Windows MSVC 前置分层注明））+ ~~`javelin-engine5` 命名收尾~~（✅ 2026-10-31 拍板**去 "5"**：GitHub 远端 `DontingK/javelin-engine`（SSH）+ `rootProject.name = 'javelin-engine'` 同批对齐；本地目录名 `javelin-engine5` 不动——改目录会断 IDE 工程引用/gradle 缓存/AGENTS 路径，纯本机事务零收益） + 插件域待拍板余 3 项（组件显示名/命令 id 前缀化/FileType 冲突策略，Editor_Plugin_Design §7.1）+ ~~groupId/包名迁移~~（上行已执行）；**README 已知的遗留呼应**：`./gradlew` 命令对 Windows cmd/PowerShell 用户不可用（根缺 gradlew.bat，归工程清理批）、distributionUrl 仍走腾讯镜像（同批）；

---

> （注：部分内容可能由 AI 生成）
