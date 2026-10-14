# 引擎功能清单（Engine Features）

> 版本：v0.1（2026-10-14 建档）
> 定位：按模块分级盘点每个功能的**完成状态**与**验收状态**。`Engine_Roadmap.md` 叙阶段沿革（何时做、为何做），本文按模块点名现状（有什么、验没验）——待验收挂账与后置项一眼可扫。
> 维护纪律：功能落地、验收翻状态时同步更新本文（与红线 12「文档先行」同一收口动作）；本表只记结论与日期，拍板细节、坑点归各设计档。
>
> 图例：
> - **完成状态**：✅ 完成 ｜ 🔄 部分（注明缺口）｜ ⬜ 未做/后置（注明归期或拍板）
> - **验收状态**：✅ 已验收（附方式+日期：单测 / 用户实测 / 三端实测）｜ ⏳ 待验收（已落地未实测）｜ —（随消费方链路验收/支撑设施，无独立验收面）

---

## 1. javelin-rhi —— Vulkan 渲染后端

> 设计档：`RHI_Design.md` 及分卷一~四；进度档：`RHI_Implementation_Progress.md`；排障：`Vulkan_Pitfalls.md`

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
| R32Uint 拾取附件 + 图像读回 | ⬜ 后置（物体拾取前置，2026-10-06 拍板） | — |

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
| RenderPass 有序列表 + AbstractForwardPass + 透明分桶（PassState Blend） | ✅ | ✅ SceneRenderDemo 用户运行 |
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
| Material 序列化（.scene 内嵌往返闭环） | ✅ | ✅ 用户实测（2026-10-04） |
| Mesh.bounds / Aabb（set 即算 / 惰性兜底 / 上传前钉死） | ✅ | ✅ 单测（2026-10-06） |
| AssetManager 资产索引查询（listAssetPaths、调用先重载 index.asset） | ✅ | ✅ 随打包启动场景下拉（2026-10-11） |
| .mat 资产格式 + AssetManager 读写 | ⬜ 未做 | — |
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

## 3. javelin-editor + engine-editor-web —— 编辑器

> 设计档：`Editor_Design.md` / `Editor_Menu_Design.md` / `Inspector_Design.md`

### 3.1 窗口与桥接

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| Swing + JCEF 窗口体系（MainFrame / Canvas 视口、浮层遮挡约束、菜单一律 Swing） | ✅ | ✅ 编辑器日常使用 |
| Web 前端基座（Vue 面板体系 / 组件 / 色板单点 style.css :root） | ✅ | ✅ 随各面板验收链 |
| JS↔Java 桥（方法桥协议 + 事件总线双向 + EDT hop / 引用计数退订） | ✅ | ✅ 随各面板验收链（2026-09-28 起） |
| 通用 CEF 模态对话框 Dialog（URL 可传参通用容器） | ✅ | ⏳ 待验收（2026-10-11 落地；打包对话框为其消费方已随 2026-10-13 跑通，通用性待其他消费方） |

### 3.2 菜单与命令（menu / selection）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 菜单/命令体系（MenuCommand 三域 + MenuRegistry 任意嵌套 + Swing 渲染 + SvgIcon 图标 + EditorSelection 单源） | ✅ | ✅ 首批实测（2026-10-04）+ v0.7 批（2026-10-11） |
| file.save / Ctrl+S | ✅ | ✅ 用户实测（2026-10-04） |
| 层级右键域（empty / delete / plane / Unity 结构重排占位） | ✅ | ✅ 随场景树批次（2026-10-04） |
| hierarchy.create.camera（v0.8） | ✅ | ⏳ 待验收（2026-10-11） |
| build.compile | 🔄 引擎侧 GradleProject.compile 已备、菜单先禁置灰 | — |
| 物体拾取（GPU ID-buffer 终态拍板） | ⬜ 后置非当前排期（2026-10-06 拍板） | — |

### 3.3 层级树与场景管理（scene / event）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 层级树（/scene/tree 帧通道 + scene.changed 增删刷新 + 选中单源 + accent 高亮 + dirty 星标镜像） | ✅ | ✅ 用户实测（2026-10-04 / 2026-10-11） |
| Scene/Game 视图切换（sceneModel 双模式 volatile 直写 + Game 页 resize 画布跟随） | ✅ | ⏳ 待验收（2026-10-11） |
| Transform 变更检测（TransformWatchAppState 帧级快照 diff → scene.nodeTransformChanged + 置脏） | ✅ | ⏳ 待验收（2026-10-06；触发场景=Gizmo 拖拽，拖拽本体已验 2026-10-14） |
| GAME 模式输入隔离（飞行 / Gizmo 输入仍活） | ⬜ 留白 | — |

### 3.4 Inspector 面板（inspector / web）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| v0.1 节点头五件套（name/active/layer/tag）+ Transform 字段行 + Tag/Layer 下拉编辑 | ✅ | ✅ 用户实测（2026-10-11） |
| v0.2 Camera 组件接入 + ENUM/COLOR/RECT/LAYER_MASK 类型通道 + 星标即时化（scene.dirty） | ✅ | ⏳ 待验收（2026-10-11） |

### 3.5 视图工具（appstate.gizmo / 相机控制 / 网格）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| 编辑器相机控制（RMB 环视+WASDQE 飞行 Shift×3 / MMB 平移 / 滚轮推拉 / Alt+LMB 环绕 / Alt+RMB 推拉） | ✅ | ✅ 响应性验收（2026-09-27；2026-10-04 水平反向修正） |
| 网格辅助线（EditGridAppState + _CamPos 材质下发） | ✅ | ✅ 实跑通过（2026-09-26）+ 编辑器日常 |
| 变换 Gizmo 工具族（三轴视觉 / 轴拾取射线 / 拖拽三段式平面相交法 / hover 高亮+近平行变灰） | ✅ | ✅ 用户实测（2026-10-14） |
| Gizmo 旋转 / 缩放工具 | ⬜ 待续 | — |

### 3.6 资产导入管线（importer / meta / asset）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| .shader 导入（解析 / 注入 / SPIR-V 编译 / Capsule 产物 + 自包含文件头 GUID） | ✅ | ✅ 用户亲测（2026-09-26） |
| .smesh 网格导入（拓扑 / 法线重算） | ✅ | ✅ 同上 |
| .meta / GUID / Library 资产管线骨架 | ✅ | ✅ 随导入链（2026-09-26） |
| SceneImporter（.scene→Library 二进制 + 保存即异步重导） | ✅ | ⏳ 待验收（2026-10-11） |
| 自定义 Layer/Tag 设置管线（Settings 目录与 Game/ 接通） | ⬜ 待接（Layer 下拉空的根因） | — |

## 4. 构建与发布链（跨模块）

| 功能 | 完成状态 | 验收状态 |
|------|---------|---------|
| project-template 游戏模板（quickBuild / GameMain / Game/ 设置持久化 / -PbuildMode） | ✅ | ✅ 随打包链（2026-10-13） |
| GradleProject 构建链（compile / jarBuild 进程编排） | ✅ | ✅ 用户实测（2026-10-13） |
| jlink 裁剪 JRE 运行时（jdk.unsupported 必含） | ✅ | ✅ 用户实测（2026-10-11） |
| 打包对话框（pack.html 三组选项 + /build/pack 形态分流 + 实时日志流回显） | ✅ | ✅ 用户实测（2026-10-13） |
| JAR 形态产物（Builds/jar，双击可玩） | ✅ | ✅ 三端实测（2026-10-13） |
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
| Inspector v0.2（Camera 组件 / 类型通道 / 星标即时化） | 2026-10-11 | 选中相机节点改参数；改任意属性星标即时亮 |
| Scene/Game 视图切换 | 2026-10-11 | 切 GAME 页看运行画面 + 窗口 resize |
| hierarchy.create.camera | 2026-10-11 | 层级右键建摄像机（Main Camera / 原点 / 默认参） |
| 通用 CEF 对话框 Dialog | 2026-10-11 | 任一模态弹窗操作（打包对话框已跑通，其他消费方待验） |
| SceneImporter 场景导入 | 2026-10-11 | 保存场景后核对 Library 重导产物 |
| Transform 变更检测 | 2026-10-06 | 拖 Gizmo 时 Inspector 数值实时刷新 + 星标亮（拖拽本体已验 2026-10-14） |

## 7. 已知问题（bug 类，非功能缺口）

- resize Cleaner 兜底只销毁 image 不释放内存（B 项未修）；
- loadScene 失败路径 getEditorGameScene() NPE 未补；
- CefFrame.dispose NPE / updateIndex 热点（Editor_Design §9）；
- Gizmo 拖拽中按 Alt 相机环绕与拖拽并动（已知边缘，后调）。

> 细节与修法归 `Editor_Design.md` §9 / 各排查记忆档，本文只挂账。

---

> （注：部分内容可能由 AI 生成）
