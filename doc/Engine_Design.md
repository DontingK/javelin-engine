# 引擎设计（Engine Design）

> 版本：v0.1
> 说明：引擎侧（javelin-core）设计总纲，列出系统大方向、职责边界与文档归属。
> 本文**只定方向与划分**，各系统的字段级/流程级设计在其专档中展开（已有专档的给出链接，未建的标注"待写"）。
> 职责边界：本文管"引擎层有什么系统、各管什么"（Unity 式组件模型 + 子系统框架 + 引擎侧渲染组织）；
> RHI 文档（`RHI_Design.md` 系列）管"怎么渲染"（图、Pass、绑定、呈现、帧执行、线程模型），本文不重复。

## 1. 系统全景（大方向）

| # | 系统 | 职责一句话 | 现状 | 设计文档 |
|---|---|---|---|---|
| 1 | 引擎外壳与主循环 | 生命周期（init→loop→shutdown）、帧调度、Time | 骨架已有，RHI 未接入 | 本文 §2.1 |
| 2 | 子系统框架 | 全局服务级模块的统一契约与调度（early/late update、pre/post render） | 契约已有，具体子系统待建 | 待写（§2.2） |
| 3 | 窗口系统 | 窗口抽象、GLFW 实现、与渲染表面分离 | 已实现 | `Engine_Window_Design.md` |
| 4 | 场景系统 | Node/Component/Transform、生命周期、场景增删 | 设计 v0.1，实现为空壳 | `Engine_Scene_Design.md` |
| 5 | 渲染系统（引擎侧） | RenderingSystem、渲染列表收集、帧描述生产、Pass 组织 | 未建 | 待写（§2.5） |
| 6 | 材质系统 | 引擎侧材质资产（.mat）、材质实例与 RHI Material 的桥接 | 未建 | 待写（§2.6） |
| 7 | 资源管理器 | 资产加载/缓存/释放（Library 目录） | 已实现 | `Engine_Assets_Design.md` |
| 8 | 序列化 | 二进制序列化框架（Exporter/Importer/Capsule） | 已实现 | 代码即文档（§2.8） |
| 9 | 基础设施 | AOT 反射工厂、对象标识/缓存、设置、调试 | 大部分已有 | 本文 §2.9 |

另列三个**候选子系统**（方向确认后再展开，见 §3）：输入、物理、音频。

## 2. 各方向说明

### 2.1 引擎外壳与主循环

- **职责**：`JavelinEngine` 持有 setting/window/assetManager/subsystemManager；`start()` 走 initialize → onInitialize → mainLoop → shutdown；`mainLoop` 的单线程退化形态对齐 RHI 27.1：pollEvents → Time.update → 子系统 earlyUpdate → scene.update（组件 onUpdate）→ 子系统 lateUpdate → 收集帧描述 → frameLoop.renderFrame。
- **现状**：窗口创建/关闭、Time 驱动、AssetManager/SubsystemManager 挂接已提交（57f2719）；RHI 初始化序列（Instance/Device/FrameLoop）目前只在测试类里，待搬入。
- **待定**：shutdown 逆序释放契约（frameLoop.close → device → instance → window）；`JavelinSetting.vsync` 消费方；onInitialize/update 钩子是否保留给最终用户。

### 2.2 子系统框架

- **职责**：`Subsystem` 接口（init/earlyUpdate/update/lateUpdate/preRender/render/postRender/shutdown）+ `SubsystemManager` 注册与调度。子系统是**全局服务**，与附加到 Node 的 Component 互补。
- **现状**：接口与 Manager 已有；`AppStateSubsystem`（appstate 包）是首个实例。
- **待写专档**：子系统注册时机（init 顺序与依赖）、渲染阶段（preRender/render/postRender）与引擎侧渲染管线的交接点、子系统与帧描述的关系。现有 `Subsystem` 注释里出现的 `scene.update`、`RenderingPipeline.render` 是契约中预设的调用点，对应类尚未实现。

### 2.3 窗口系统

已有专档，不展开。窗口归 `JavelinEngine` 工厂创建持有，与 RHI 的 RenderSurface 分离。

### 2.4 场景系统

- **职责**：Unity 式组件模型——`Node`（容器）/ `Component`（行为/数据）/`Transform`（独立脏标记树）；Scene 为根；生命周期 Awake→Enable→Start→Update→Disable→Destroy 由逻辑线程主循环统一驱动。
- **现状**：设计 v0.1 已覆盖核心类型与生命周期，待用户审定；实现侧仅有空 `GameScene` 桩。
- **专档待定项**：见 `Engine_Scene_Design.md` 第 8 节（.scene 序列化、Prefab、执行顺序优先级等，均 P2）。

### 2.5 渲染系统（引擎侧）

- **职责**：把场景变成 RHI 的输入——RenderingSystem 注册表（renderers/cameras/lights）、每帧收集（脏标记更新、视锥剔除、每相机 RenderList 排序）、**帧描述组装**（图声明 + 场景快照 + 各相机 RenderList，对齐 RHI 27.2）；引擎侧渲染管线/Pass 的高层组织（哪些 Pass、什么顺序，RHI RenderGraph 负责具体执行）。
- **现状**：全缺（RenderingSystem/FrameDescription/Pass 组织均无实现）；RHI 接收端（FrameLoop/RenderView/RenderGraph）已就绪。
- **待写专档**（本方向信息密度最高，拆两档）：
  - `Engine_Rendering_Design.md`：**已有 v0.1**——RenderingSystem、帧描述结构（FrameDescription 字段级定义，27.2 交接契约落地）、与 RHI FrameLoop 的调用关系（v0.1 单线程退化）、Y 翻转落点（投影矩阵，已拍板）、UBO1 收编。
  - `Engine_RenderPipeline_Design.md`：**已有 v0.1**——引擎侧 Pass 组织（默认混合管线完整设计 + v0.1 先 Forward 的落地路线；阴影纳入清单实现后置；后处理链式挂点细节占位；视图级资源规划、钩子点、uniform 通道约定）。

### 2.6 材质系统

- **职责**：引擎侧材质资产（`.mat` 文件：shader 引用 + 参数值）→ 加载 → RHI 20 节材质实例的创建与缓存；MeshRenderer.material 的赋值路径。
- **现状**：RHI 侧材质实例模型已有；引擎侧全缺，`.mat` 格式未定（与 `.scene` 序列化同属待定）。
- **待写专档**：`.mat` 格式、材质变体/关键字策略（P2）、与 AssetManager 的关系。

### 2.7 资源管理器

已有专档（`Engine_Assets_Design.md`，Mesh/VertexAttribute/VertexLayout）+ `AssetLibManager` 实现。材质资产接入后本系统即覆盖引擎全部资产类型。

### 2.8 序列化

- **职责**：`serialize` 包——Exporter/Importer + Capsule 模式的二进制序列化（含 binary 子包）。
- **现状**：框架已实现；场景/材质资产格式落地时的底座，格式设计归场景/材质专档。

### 2.9 基础设施

| 模块 | 职责 | 现状 |
|---|---|---|
| `reflective` | ReflectiveFactory 零反射创建（GraalVM AOT 禁反射） | 已有 |
| `obj` | ObjectId / ObjectCache 对象标识与缓存 | 已有 |
| `settings` | JavelinSetting / GameSettings / SettingManager | 已有 |
| `debug` | 调试支撑 | 已有 |
| `Time` | 帧时间（init/update 由主循环驱动，包级私有） | 已有 |

## 3. 候选子系统（方向确认后各写专档）

| 子系统 | 职责预想 | 依赖与前提 |
|---|---|---|
| 输入 | 键鼠/手柄状态采集、Action 映射 | GLFW 已有；窗口系统提供事件源 |
| 物理 | 碰撞/刚体世界、物理步进（earlyUpdate）、结果回写 Transform（lateUpdate） | 引擎选型未定（自研/JBullet/bullet 绑定） |
| 音频 | 音频上下文、3D 音源、总线 | 引擎选型未定（OpenAL 等） |

三者在 `Subsystem` 契约里已有对应挂点（earlyUpdate 物理步进、lateUpdate 同步回写、render 提交调试/粒子等），框架不为它们特设机制。

## 4. 文档规划

| 专档 | 状态 |
|---|---|
| `Engine_Design.md`（本文） | v0.1 |
| `Engine_Window_Design.md` | 已有 |
| `Engine_Scene_Design.md` | 已有 v0.1，**待审定** |
| `Engine_Assets_Design.md` | 已有 |
| `Engine_Subsystem_Design.md` | 待写 |
| `Engine_Rendering_Design.md`（帧描述/RenderingSystem） | 已有 v0.1 |
| `Engine_RenderPipeline_Design.md`（Pass 组织） | 已有 v0.1 |
| `Engine_Material_Design.md` | 待写 |
| 输入 / 物理 / 音频 | 方向确认后待写 |

## 5. 与 RHI 文档的边界重申

- RHI 已定的引擎侧消费契约：RenderView（9 节，Camera 的投影矩阵按 Vulkan NDC 约定生成）、多相机（19 节）、Material 实例（20 节）、帧交接（27.2 帧描述领先 1 帧）、单线程退化（27.1）。
- 引擎侧任何设计不得改动 RHI 已发布的 API 语义；如产生缺口，回 RHI 文档增订，不在引擎侧打补丁。
