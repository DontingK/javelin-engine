# 引擎功能清单（Engine Features）

> 版本：v0.1（2026-10-14 建档）
> 定位：按模块分级盘点每个功能的**完成状态**与**验收状态**。`doc/Engine_Roadmap.md` 叙阶段沿革（何时做、为何做），本文按模块点名现状（有什么、验没验）——待验收挂账与后置项一眼可扫。
> 维护纪律：功能落地、验收翻状态时同步更新本文（与红线 12「文档先行」同一收口动作）；本表只记结论与日期，拍板细节、坑点归各设计档。
>
> 图例：
> - **完成状态**：✅ 完成 ｜ 🔄 部分（注明缺口）｜ ⬜ 未做/后置（注明归期或拍板）
> - **验收状态**：✅ 已验收（附方式+日期：单测 / 用户实测 / 三端实测）｜ ⏳ 待验收（已落地未实测）｜ —（随消费方链路验收/支撑设施，无独立验收面）

---

## 1. javelin-rhi —— Vulkan 渲染后端

> 设计档：`doc/RHI_Design.md` 及分卷一~四；进度档：`doc/RHI_Implementation_Progress.md`；排障：`doc/Vulkan_Pitfalls.md`

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Instance / Device / Swapchain / FrameLoop 基础设施 | ✅ | ✅ 六阶段各 Demo 用户实测，MultiCameraDemo 终验（2026-09-25 关账） |
| Uniform 通道（UBO1 共享缓冲+动态偏移 / push constants） | ✅ | ✅ 随渲染 Demo |
| RenderGraph / Attachment / 多相机 RenderView | ✅ | ✅ MultiCameraDemo（分屏/透明排序/resize） |
| PSO / PipelineCache / SPIR-V 加载 | ✅ | ✅ 随渲染 Demo |
| 呈现模式（FIFO / MAILBOX=vsync 语义） | ✅ | ✅ 用户实测（2026-10-06/07 帧节奏排查关账） |
| MSAA resolve attachment（能力查询 / 深度不 resolve） | ✅ | ✅ 用户实测（2026-10-06） |
| resize 重建 + 显存手动 dispose 优先（Cleaner 仅兜底） | ✅ | ✅ 用户实测（2026-10-09 OOM 修复验收） |
| Buffer staging（deviceLocal 上传） | ⬜ 后置 | — |
| 描述符池环形 | ⬜ 后置 | — |
| backbuffer 句柄入 structureHash | ⬜ 后置 | — |
| R32Uint 拾取附件 + 图像读回 | ✅（2026-10-23，Engine_Picking_Design v0.1：TextureReadbackRing 环 + copyImageToBuffer/Buffer.read/Fence.isSignaled/RHIClearUintColor/Format.bytesPerPixel） | ✅ 用户实测（2026-10-23，随拾取全链） |

## 2. javelin-core —— 引擎核心

### 2.1 基础设施（serialize / reflective / settings / obj）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 二进制序列化（Capsule / BinaryImporter / write-read 契约、共享引用保真） | ✅ | ✅ 单测 + .scene 往返用户实测（2026-10-04） |
| 反射体系（@Reflective + 注册器，Class.forName 显式化、零注解扫描） | ✅ | — 随序列化 / AOT 链验收 |
| 设置系统（JavelinSetting：vsync / maxFps / backgroundColor / antialiasing 落盘） | ✅ | ✅ 用户实测（2026-10-06/07 vsync/maxFps 关账） |
| ObjectId / ObjectCache 对象身份与引用缓存 | ✅ | — 随序列化 / 桥链 |

### 2.2 场景系统（scene）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Node / Component / Transform / GameScene 组件模型 | ✅ | ✅ GameSceneTest 单测（一期） |
| Layer / Tag / LayerMask 数据模型 | ✅ | ✅ 单测 + Inspector 头部编辑（2026-10-11） |
| 生命周期帧驱动状态机 | ✅ | ✅ 用户实测（2026-10-04） |
| .scene 文本序列化 + 保存/重载（含内嵌材质） | ✅ | ✅ 用户实测（2026-10-04 建树→保存→重启还原） |
| 渲染激活过滤 isActiveInHierarchy（Unity 口径，翻转即时注销） | ✅ | ✅ 随 Gizmo 显隐 / 编辑器日常 |
| Node.userData 运行态挂载（不入序列化） | ✅ | — 随 Gizmo 轴判别 |
| 场景级 raycast（Collider 注册表查询，L1） | ⬜ 归 Ray 期 | — |
| environment / sun | ⬜ 三期（随光照） | — |

### 2.3 相机（render.Camera）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 透视 / 正交投影（FOV 弧度制） | ✅ | ✅ CameraTest 单测 + Demo |
| cullingMask / viewportRect / depth 渲染排序 / per-camera 清屏 | ✅ | ✅ 多相机 Demo + 编辑器日常 |
| ClearFlags（SOLID_COLOR / DEPTH_ONLY / DONT_CLEAR） | ✅ | ✅ 同上（Skybox 项后置） |
| Camera.screenToRay（Unity ScreenPointToRay 口径、viewportRect 归一化） | ✅ | ✅ CameraTest 单测（2026-10-06） |
| ClearFlags Skybox 项 | ⬜ 待天空盒 | — |
| main 相机语义消费 | ⬜ 未落地 | — |

### 2.4 渲染系统与管线（render）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| RenderingSystem / FrameLoop / UBO1 帧环形收编 | ✅ | ✅ 一期 Demo（清屏出画面起） |
| RenderPass 有序列表 + AbstractForwardPass + 透明分桶 | ✅ | ✅ SceneRenderDemo 用户运行 |
| Material.surfaceType 面型裁决（分桶 + PSO 覆盖 Blend/ZWrite，方案 B，材质档 §4.1） | ✅ | ⬜ 待验收（MaterialTest 单测 + 序列化往返断言已补，demo/编辑器网格待用户跑） |
| RenderList Pass 名分桶严格制（passes map + RenderPass.shaderPassName + pipelineFor passName 维；GizmoPass 去身份过滤/去双画，材质档 §4.2） | ✅ | ⬜ 待验收（编辑器 gizmo 拖拽/显示待用户跑） |
| SceneRenderBridge pull 翻译（场景→RenderObject） | ✅ | ✅ 同上 |
| GizmoPass 末尾置顶（颜色 LOAD + 深度 CLEAR 两层深度语义） | ✅ | ✅ 随 Gizmo 链（2026-10-14 拖拽验收） |
| MSAA 视口附件（isMsaaPath 边界：stacking/预览降 1x、overlay 恒 1x） | ✅ | ✅ 用户实测（2026-10-06） |
| overlay 挂点（DebugText / RenderStats，buildFrameGraph） | ✅ | ✅ 用户实测（2026-09-27） |
| 视锥剔除 | ⬜ 后置优化（Aabb/Mesh.bounds 已备） | — |

### 2.5 资产（assets）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Mesh / VertexAttribute / VertexLayout | ✅ | ✅ 一期 |
| Shader / Material 数据模型 + 渲染侧桥（MaterialBlock std140 / materialSet / pipelineFor） | ✅ | ✅ MaterialTest 单测 + Demo（2026-09-25） |
| Material.surfaceType 字段 + Capsule 序列化（旧场景回退 OPAQUE） | ✅ | ⬜ 待验收（SceneSerializationTest 往返断言已补） |
| Material 序列化（.scene 内嵌往返闭环） | ✅ | ✅ 用户实测（2026-10-04） |
| Mesh.bounds / Aabb（set 即算 / 惰性兜底 / 上传前钉死） | ✅ | ✅ 单测（2026-10-06） |
| AssetManager 资产索引查询（listAssetPaths、调用先重载 index.asset） | ✅ | ✅ 随打包启动场景下拉（2026-10-11） |
| .mat 资产格式 + AssetManager 读写（MaterialImporter + project.create.material + .mat 文件类型，材质档 §3.2） | ✅ | ⬜ 待验收（创建→导入→.scene 引用渲染差异，2026-10-19） |
| 动态 Mesh / 动态纹理 | ⬜ 后置（材质档 §6 / 资产档 §2.8） | — |

### 2.6 输入（input）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 三层架构（Window 回调→InputSubsystem 双缓冲边沿→Input 静态门面） | ✅ | ✅ InputCameraDemo + RawInputDemo 用户实测（2026-09-27） |
| bind/查询合流 Input + 失焦清态防卡键 | ✅ | ✅ 同上 |
| 性能基线（IMMEDIATE 空载 ~10180 fps） | ✅ | ✅ 存档（2026-09-27） |
| 独占全屏 | ⬜ 未做 | — |

### 2.7 碰撞（physics / math）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| L0 纯数学层（Ray / 球 / 盒 / 圆柱相交例程，解析解对照） | ✅ | ✅ 单测全绿（2026-10-06） |
| Collider 组件族 + fitFromMesh（Box 精确 / Sphere 保守 / Cylinder 半径取 max） | ✅ | ✅ 单测（2026-10-06） |
| 世界 Aabb（行和公式=精确值，8 角点仅对照基准） | ✅ | ✅ 单测（2026-10-06） |
| L1 场景查询 API 面 | ⬜ 归 Ray 期 | — |
| L2 物理（刚体 / MeshCollider / 物理库选型） | ⬜ 待拍板（窄相 vs 物理库） | — |

### 2.8 子系统 / 调试 / 窗口（subsystem / appstate / debug / window）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Subsystem / AppState 双层（底层冻结可暂停 / 控制层挂卸单心跳） | ✅ | ✅ 一期接线 Demo |
| 窗口抽象 + GLFW 实现（Window 与 RenderSurface 分离、showWindow） | ✅ | ✅ 一期 |
| DebugText 点阵调试文本（含汉字、行级 printf / attr 调色 / clear） | ✅ | ✅ 用户实测（2026-09-27；AOT 资源路径 2026-10-13 复验） |
| RenderStats / GPUInfo（11 类存活计数 + draw call 快照双缓冲）+ F3 overlay | ✅ | ✅ 用户复跑（2026-09-27） |
| vsync 语义（MAILBOX 防撕裂不限速）+ maxFps 限帧器（0=不限） | ✅ | ✅ 用户实测（2026-10-06/07） |

### 2.9 Inspector 注解面（inspector）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| @InsField 显式 opt-in + 读 getter 写 setter 铁律 + 浮点 4 位小数口径 | ✅ | ✅ Inspector v0.1 用户实测（2026-10-11） |

## 3. javelin-editor —— 编辑器

> 设计档：`doc/Editor_Design.md` / `doc/Editor_Menu_Design.md` / `doc/Inspector_Design.md`
> （engine-editor-web 前端工程已随 2026-10-18 去 Web 化清退整体删除）

### 3.1 窗口与桥接

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Swing + JCEF 窗口体系（MainFrame / Canvas 视口、浮层遮挡约束、菜单一律 Swing） | ✅ | ✅ 编辑器日常使用 |
| 纯 Swing 主窗口 + 自研 Dock（FlatLaf 亮色 / 二分布局树 / PanelRegistry / ViewportTabs 单 Canvas / JMenuBar 单源挂载） | ✅ | ✅ 用户实测（2026-10-17，去 Web 化 M1） |
| 项目管理器 ProjectFrame（Swing 版：最近列表 + 新建/打开三模式 + tinyfd 选目录，替换 CEF 页） | ✅ | ⏳ 待验收（2026-10-17；启动链不再依赖 Vite dev server） |
| 项目加载窗 ProjectLoadingFrame（Swing 版：不确定进度条 + 资产导入/引擎启动引导链原样） | ✅ | ⏳ 待验收（2026-10-17） |
| ~~Web 前端基座（Vue 面板体系 / 组件 / 色板单点 style.css :root）~~ | 🪦 已删除 | 2026-10-18 engine-editor-web 整体移除（三窗口+桥清退后无消费方；图标资产随 SvgIcon 体系迁 editor resources） |
| JS↔Java 桥（方法桥协议 + 事件总线双向 + EDT hop / 引用计数退订） | 🪦 已清退 | 2026-10-18 jcef 包整包删除（桥方法/par/vo/EventBridgeForwarder 全量），jcefmaven、jackson 依赖移除；事件体系保留——BridgeEvent/JsEvent/PongEvent 删、五场景事件转纯 EditorEventBus 事件；Inspector 三 VO 迁 inspector 包（M4 数据模型） |
| ~~通用 CEF 模态对话框 Dialog~~ | 🪦 已退役 | 2026-10-17 随去 Web 化删除（唯一消费方打包对话框迁 Swing；原待验收场景作废） |
| 打包对话框 PackDialog（Swing 版：构建形态/启动场景/构建模式/窗口元信息表单 + 实时日志流，构建链 Java 直调） | ✅ | ⏳ 待验收（2026-10-17；构建链本体已随 2026-10-13 三端验收） |

### 3.2 菜单与命令（menu / selection）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 菜单/命令体系（MenuCommand 三域 + MenuRegistry 任意嵌套 + Swing 渲染 + SvgIcon 图标 + EditorSelection 单源） | ✅ | ✅ 首批实测（2026-10-04）+ v0.7 批（2026-10-11） |
| file.save / Ctrl+S | ✅ | ✅ 用户实测（2026-10-04） |
| 层级右键域（empty / delete / plane / Unity 结构重排占位） | ✅ | ✅ 随场景树批次（2026-10-04） |
| hierarchy.create.camera（v0.8） | ✅ | ⏳ 待验收（2026-10-11） |
| file.open 实做（文件类型注册器分发打开 + 脏场景三键确认） | ✅（2026-10-18） | ⏳ 待验收 |
| build.compile | 🔄 引擎侧 GradleProject.compile 已备、菜单先禁置灰 | — |
| GPU ID-buffer 拾取（编辑器物体+轴全线统一；pass 落 core 运行时也可用；与射线检测共存） | ✅（2026-10-23 拍板并实现，Engine_Picking_Design v0.1） | ✅ 用户实测（2026-10-23，清单见该档 §9） |

### 3.3 层级树与场景管理（scene / event）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 层级树（/scene/tree 帧通道 + scene.changed 增删刷新 + 选中单源 + accent 高亮 + dirty 星标镜像） | 🪦 web 实装已随 2026-10-18 去 Web 化清退 | 历史验收（2026-10-04 / 2026-10-11）；Swing 版见下行 |
| 层级树 Swing 实装（HierarchyPanel：SceneTreeSnapshot submitTask 帧快照→EDT 换树 + scene.changed/dirty/nodeRenamed/select 四事件分工 + 展开态按引用恢复 + 右键 ctx.hierarchy 先落选中） | ✅ | ⏳ 待验收（2026-10-18，Editor_Swing_Design §7.5） |
| 选中/事件链全链引用化（EditorSelection 存 volatile Node + 三事件引用载荷 + Create 命令引用快照 + NodeSnap 引用回查键；ObjectCache 退役、快照拷贝本体保留） | ✅ | ⏳ 待验收（2026-10-18 拍板，Editor_Swing_Design §7.5-8） |
| 场景根行选中=场景文件（根行点击 setSelectedPath 进 project 槽→检查器 .scene 文件链分发零改动；syncSelection 空事件不清根行修回声自灭 + project.select 跨域对齐：选本场景文件高亮根行/选他物熄灭；未存盘场景无文件退清选中） | ✅（2026-10-22） | ⏳ 待验收 |
| 脏星标行宽修复（scene.dirty 置星改根行 model.nodeChanged 重算节点尺寸——repaint 不重算、旧行宽裁出省略号） | ✅（2026-10-22） | ⏳ 待验收 |
| Scene/Game 视图切换（sceneModel 双模式 volatile 直写 + Game 页 resize 画布跟随） | ✅ | ⏳ 待验收（2026-10-11） |
| Transform 变更检测（TransformWatchAppState 帧级快照 diff → scene.nodeTransformChanged + 置脏） | ✅ | ⏳ 待验收（2026-10-06；触发场景=Gizmo 拖拽，拖拽本体已验 2026-10-14） |
| GAME 模式输入隔离（飞行 / Gizmo 输入仍活） | ⬜ 留白 | — |

### 3.4 Inspector 面板（inspector / Swing）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| v0.1 节点头五件套（name/active/layer/tag）+ Transform 字段行 + Tag/Layer 下拉编辑 | ✅ | ✅ 用户实测（2026-10-11） |
| v0.2 Camera 组件接入 + ENUM/COLOR/RECT/LAYER_MASK 类型通道 + 星标即时化（scene.dirty） | ✅ | ⏳ 待验收（2026-10-11） |
| v0.3 GUID_ASSET 资产引用三态只读 + MeshRenderer 组件接入 | ✅ | ✅ 随 M4 Swing 版面验收销账（2026-10-18） |
| v0.4 M4 Swing 实装（InspectorPanel——Web 期 v0.1~v0.3 通道面 Java 直调重现：swing.inspector 面板层 + widget 控件七件 + VO 引用化 + markDirty 收口 + NodeTransformChangeEvent 定点刷新 + 编辑保护） | ✅（2026-10-18） | ✅ 用户实测（2026-10-18）——含验收期修正：归属校验/Scrollable 容器/节点头 Web 布局回归/折叠头分层 |
| v0.5 检查器注册器（InspectorRegistry——Node+资产统一接管 + 材质只读首期） | ✅（2026-10-21） | ✅ 用户实测（2026-10-21） |
| v0.6 输入交互增强（数字键入即提交不动显示文本保光标 + 文本提交时机归使用方 onInputChanged + label 拖动调值/数字框滚轮调值步长三级） | ✅（2026-10-22） | ✅ 用户实测（2026-10-22） |

### 3.5 视图工具（appstate.gizmo / 相机控制 / 网格）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 编辑器相机控制（RMB 环视+WASDQE 飞行 Shift×3 / MMB 平移 / 滚轮推拉 / Alt+LMB 环绕 / Alt+RMB 推拉） | ✅ | ✅ 响应性验收（2026-09-27；2026-10-04 水平反向修正） |
| 网格辅助线（EditGridAppState + _CamPos 材质下发） | ✅ | ✅ 实跑通过（2026-09-26）+ 编辑器日常 |
| 变换 Gizmo 工具族（三轴视觉 / 轴拾取射线 / 拖拽三段式平面相交法 / hover 高亮+近平行变灰） | ✅ | ✅ 用户实测（2026-10-14） |
| 场景视图工具栏（Tools 组：移动可用/旋转缩放置灰 + 局部/世界坐标切换；游戏视图同高占位空条） | ✅ | ✅ 用户实测（2026-10-14） |
| DebugDrawLine 调试线（编辑器专属世界空间线框：选中 mesh bounds 世界 AABB / 相机视锥 / BoxCollider 贴合盒——组件绘制器查表分派 + 覆盖制 DebugLinePass 插 Transparent 后深度咬合 + 每帧 clear 重提交跟随选中） | ✅（2026-10-23，Editor_DebugLine_Design v0.1） | ✅ 用户实测（2026-10-23，清单见该档 §7） |
| Gizmo 旋转 / 缩放工具 | ⬜ 待续 | — |

### 3.6 资产导入管线（importer / meta / asset）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| .shader 导入（解析 / 注入 / SPIR-V 编译 / Capsule 产物 + 自包含文件头 GUID） | ✅ | ✅ 用户亲测（2026-09-26） |
| .smesh 网格导入（拓扑 / 法线重算） | ✅ | ✅ 同上 |
| .meta / GUID / Library 资产管线骨架 | ✅ | ✅ 随导入链（2026-09-26） |
| SceneImporter（.scene→Library 二进制 + 保存即异步重导） | ✅ | ⏳ 待验收（2026-10-11） |
| 内置资源释放→索引收口（releaseAll 末 updateIndex——时序坑修复） | ✅ | ✅ 修复实测（2026-10-17） |
| 文件类型注册器（FileTypeRegistry：类型→图标 + 双击/命令打开分发） | ✅（2026-10-18） | ⏳ 待验收 |
| 自定义 Layer/Tag 设置管线（Settings 目录与 Game/ 接通） | ⬜ 待接（Layer 下拉空的根因） | — |

### 3.7 项目面板（project / Swing）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Unity 两栏布局（Swing ProjectPanel：左目录树 JTree + 右内容区 JList + 面包屑 + 双击进入 + JSplitPane 分栏拖拽；原 web 版 2026-10-17 落地、随去 Web 化清退） | ✅ Swing 实装（2026-10-18） | ⏳ 待验收 |
| ProjectTreeSnapshot 全量树直调（仅 Assets/src 两根；.meta/隐藏项过滤；目录优先+名称序；后台线程纯 IO → EDT） | ✅（2026-10-18） | ⏳ 待验收 |
| 选中直写 EditorSelection project 通道（Node 直持 File；目录变更即上报含面包屑导航——原桥「相对路径上报+闸门」随直调消亡） | ✅（2026-10-18） | ⏳ 待验收 |
| ctx.project 右键域首期（showInExplorer / copyPath / reimport 三实做 + Unity 壳占位；面板三入口先落选中再弹菜单） | ✅ 命令侧（2026-10-17）/ 面板接入（2026-10-18） | ⏳ 待验收 |

### 3.8 插件体系与 i18n（plugin / i18n）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 插件契约（Plugin：namespace/元数据/setup；PluginContext 注册门面含 i18n()） | ✅（2026-10-20，Editor_Plugin_Design §2） | ✅ 用户实测（2026-10-20） |
| ServiceLoader 发现 + editor.core 即插件（EditorCorePlugin + META-INF/services；三个 Setup 迁 setup 经 ctx 收口，旧直调点摘除） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 第三方装载管线（PluginClassLoader parent-first / PluginInstance.load 全程不抛 + 失败关 cl 释放 jar 锁 / 一个 jar 一个插件） | ✅（2026-10-20） | — 静态骨架，第三方 jar 实测随后置装载批 |
| PluginManager 宿主管线（classpath 恒先 fail-fast / jar 段 warn 跳过 + FAILED 账本 / namespace 冲突后来者跳过 / 语言包注册先于 setup） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 装载接入（ProjectLoadingFrame Engine Main 线程、GradleProject 后资产初始化前；Plugins/ 目录项目级） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| i18n 基建（I18nKey 自持 namespace + text() / I18nScope + EditorLang 门面 / I18n 注册中心：约定路径装载 UTF-8、!key! 兜底不抛、去重 warn、miss/used 采集、setLanguage 启动期锁定） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 菜单 i18n 首批（MenuCommand.label()→I18nKey 全链；key=命令 id 域基类默认；渲染点 .text()；lang/zh_cn.properties 36 条，含 create.material 补注册 2026-10-20） | ✅（2026-10-20） | ✅ 用户实测（2026-10-20） |
| 异常/弹窗消息 i18n（~116 条 TipException 等） | ⬜ 第二批 | — |
| 面板/窗口标题 i18n、Inspector 注解 label key 化、源码机检、i18n 报告命令 | ⬜ 后置批次（Editor_Plugin_Design §7.2） | — |

## 4. 构建与发布链（跨模块）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| project-template 游戏模板（quickBuild / GameMain / Game/ 设置持久化 / -PbuildMode） | ✅ | ✅ 随打包链（2026-10-13） |
| GradleProject 构建链（compile / jarBuild 进程编排） | ✅ | ✅ 用户实测（2026-10-13） |
| jlink 裁剪 JRE 运行时（jdk.unsupported 必含） | ✅ | ✅ 用户实测（2026-10-11） |
| 打包对话框（Swing PackDialog：构建形态/启动场景/构建模式/窗口元信息表单 + 实时日志流回显；原 CEF pack.html + /build 桥于 2026-10-17 迁 Swing，构建链逻辑原样） | ✅ | ✅ 构建链三端实测（2026-10-13）；Swing 迁移待验收（2026-10-17） |
| JAR 形态产物（Builds/jar，双击可玩） | ✅ | ✅ 三端实测（2026-10-13）；lib 目录路径错位修复（2026-10-17） |
| AOT 形态（native-image 原生 exe / 外置 DLL lib/ / AotGameMain / reflect-config / resource-config） | ✅ | ✅ 三端实测（2026-10-13，AOT/JAR/编辑器一致） |
| javelin-ast ReflectiveProcessor（编译期生成 reflect-config.json，core/game 分目录） | ✅ | ✅ 随 AOT（2026-10-13） |

## 5. 支撑模块

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| javelin-tool（DebugText 字库光栅化 / shaderc 编译调用） | ✅ | ✅ 字库生成链（2026-09-27） |
| javelin-log（日志实现） | ✅ | — 支撑设施，随全链使用 |

## 6. ⏳ 待验收挂账一览

> 已落地、用户实测未做——验收后翻状态并从本表移除。

| 项 | 落地 | 验收触发场景 |
|----|------|-------------|
| Inspector v0.2（Camera 组件 / 类型通道 / 星标即时化） | 2026-10-11 | ~~待实测~~ 随 M4 Swing 版面验收销账（2026-10-18，Web 面板已删无独立验收面） |
| Scene/Game 视图切换 | 2026-10-11 | 切 GAME 页看运行画面 + 窗口 resize |
| hierarchy.create.camera | 2026-10-11 | 层级右键建摄像机（Main Camera / 原点 / 默认参） |
| SceneImporter 场景导入 | 2026-10-11 | 保存场景后核对 Library 重导产物 |
| Transform 变更检测 | 2026-10-06 | 拖 Gizmo 时 Inspector 数值实时刷新 + 星标亮（拖拽本体已验 2026-10-14） |
| ProjectFrame / ProjectLoadingFrame 迁 Swing | 2026-10-17 | 启动器建项目/最近列表打开/手动路径打开 → 加载窗进度 → 编辑器进主窗 |
| PackDialog 迁 Swing（含 /build 桥退役、CEF Dialog/CefFrame 删除） | 2026-10-17 | 构建 > 打包：JAR/AOT 分流（构建模式行随形态显隐）+ 启动场景默认选中 + 日志实时滚底 + 失败弹错 + 产物双击可玩 |
| jcef 桥包 / jcefmaven / jackson 整体清退（事件包桥语义剥离、Inspector VO 迁包） | 2026-10-18 | 编辑器全链回归：启动 → 开项目 → 编辑器主窗 → 构建打包，全程无 CEF 进程、无 Vite dev server；Inspector 反射通道（InspectorReflector + 三 VO）单测级自查 |
| M2 层级面板 Swing 实装（HierarchyPanel） | 2026-10-18 | 树展示/点选/右键创建删除（含场景根语境）；改名定点刷新；保存后星标熄灭；展开态跨命令刷新保持；创建后新节点自动选中可见 |
| M3 项目面板 Swing 实装（ProjectPanel） | 2026-10-18 | 左树目录导航/右区条目/面包屑跳转/双击进入；分栏拖拽；右键域三入口命令（资源管理器定位/路径入剪贴板/重导）菜单正确 |
| 全链引用化（选中/三事件/命令/树回查键 + ObjectCache 退役） | 2026-10-18 | 与层级面板同场景回归：创建/删除/改名/Gizmo 跟随选中链路全通即证引用化无回归 |
| 文件类型注册器 + file.open 实做 | 2026-10-18 | 项目面板双击已注册类型文件按注册器分发打开；脏场景下 open 弹三键确认 |

~~通用 CEF 对话框 Dialog~~ 挂账项作废（2026-10-17 类已随去 Web 化删除，验收场景消亡）。

## 7. 已知问题（bug 类，非功能缺口）

- ~~resize Cleaner 兜底只销毁 image 不释放内存~~ 已修并验收（A 项 resize 显式 dispose 2026-10-09 / B 项 `DestroyTask` 带 `memoryHandle` 成对 free 2026-10-23，均用户实测；坑点存档 Vulkan_Pitfalls #24）；
- loadScene 失败路径 getEditorGameScene() NPE 未补；
- CefFrame.dispose NPE / updateIndex 热点（Editor_Design §9）；
- Gizmo 拖拽中按 Alt 相机环绕与拖拽并动（已知边缘，后调）。
- 窗口拖拽 resize 期画面非等比拉伸、松手即愈（2026-10-17 拍板接受，Editor_Design §9-11）；

> 细节与修法归 `doc/Editor_Design.md` §9 / 各排查记忆档，本文只挂账。

---

> （注：部分内容可能由 AI 生成）
