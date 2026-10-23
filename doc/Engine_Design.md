# 引擎设计（Engine Design）

> 版本：v0.2（v0.1 基础上增 §3.1 功能模块化方向拍板存档）
> 说明：引擎侧（javelin-core）设计总纲，列出系统大方向、职责边界与文档归属。
> 本文**只定方向与划分**，各系统的字段级/流程级设计在其专档中展开（已有专档的给出链接，未建的标注"待写"）。
> 职责边界：本文管"引擎层有什么系统、各管什么"（Unity 式组件模型 + 子系统框架 + 引擎侧渲染组织）；
> RHI 文档（`RHI_Design.md` 系列）管"怎么渲染"（图、Pass、绑定、呈现、帧执行、线程模型），本文不重复。

## 1. 系统全景（大方向）

| # | 系统 | 职责一句话 | 现状 | 设计文档 |
|---|---|---|---|---|
| 1 | 引擎外壳与主循环 | 生命周期（init→loop→shutdown）、帧调度、Time | 骨架已有，RHI 未接入 | 本文 §2.1 |
| 2 | 子系统框架 | 引擎级全局服务的统一契约与调度（init/earlyUpdate/lateUpdate/shutdown + 暂停开关） | 契约已重构、调度已接入 mainLoop；具体子系统待建 | 待写（§2.2） |
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
- **待定**：`JavelinSetting.vsync` 消费方；onInitialize/update 钩子是否保留给最终用户。（shutdown 逆序已落地：子系统 shutdownAll → frameLoop.close（含 waitIdle）→ 渲染系统 → device → instance → window）

### 2.2 子系统框架

- **职责**：`Subsystem` 接口（init/earlyUpdate/lateUpdate/shutdown，全 default）+ `SubsystemManager` 注册与调度。子系统是**引擎级全局服务**（物理/音频/输入），与附加到 Node 的 Component 互补。结构规则（拍板 2026-09-26）：启动前 register、initAll 后**结构冻结不可移除**，暂停用 enabled 开关；init 抛异常剔除、不进调度。渲染贡献不走子系统钩子——需要绘制的模块向 `renderPipeline.passes()` 注册 Pass（Engine_RenderPipeline_Design 6.2）。
- **现状**：契约已重构、调度已接入 mainLoop 与 shutdown（earlyUpdate → scene.update → lateUpdate，shutdown 首相位清理）；`AppStateSubsystem`（appstate 包）是首个实例。appstate 是**控制程序层**（游戏流程/场景切换/编辑器全局状态）：运行时动态挂卸（线程安全队列、帧边界生效）+ 单 update 心跳，经 AppStateSubsystem 桥接进子系统调度——与底层子系统的「冻结不可移除」相对。
- **待写专档**：子系统注册时机（init 顺序与依赖）、子系统与帧描述的关系。

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
| `reflective` | ReflectiveFactory 零反射创建（GraalVM AOT 禁反射）；处理器同步生成 native-image reflect-config.json（注册类+全部 @Reflective 类、构造器级，core/game 分支各一份模块子目录防 fat-jar 冲突，2026-10-13） | 已有 |
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

三者在 `Subsystem` 契约里已有对应挂点（earlyUpdate 物理步进、lateUpdate 同步回写；渲染贡献经管线 Pass 列表注册），框架不为它们特设机制。

### 3.1 功能模块化与自注册（方向拍板存档，2026-10-23）

> 讨论起点：Unity 很多功能以安装包（UPM）交付，我们是否照做。拍板：**不做包管理器，
> Gradle 依赖坐标即包格式**。Unity 被迫发明 UPM 是因为它没有构建系统（C# 由编辑器现场
> 编译，需要 manifest/依赖解析/registry 自建发行基建）；我们有 Gradle，版本解析、传递
> 依赖、冲突处理全部白送。**编辑器插件轴（Editor_Plugin_Design）与本节是两条平行轴**：
> 插件体系扩展的是**编辑器**（Plugins/ 目录 jar 动态装载）；本节是**引擎运行时功能模块**
> 轴（编译期 Gradle 依赖，游戏项目侧）。

**体验目标**：游戏项目 `build.gradle` 加一行依赖即启用模块、删依赖即停用，**零注册代码**。
现状缺的正是这半件——「分发」Gradle 已解决，「jar 自描述自动注册」没有通道（当前子系统
接线在 `JavelinEngine.registerSubsystem()` protected 覆写钩子里，扩展方必须写源码）。

**机制拍板（与编辑器插件体系同构，零新发明）**：

- core 侧 SPI（暂名 `EngineExtension`）：jar 内 `META-INF/services` 声明 + ServiceLoader
  在引擎 init 早期（`initAll` 之前，结构冻结前）扫**主 classpath**——不需要 Plugins/ 目录、
  不需要动态 classloader、没有 unload 问题（游戏运行时本就是静态 classpath，比编辑器侧
  jar 装载更简）；`Subsystem.init(JavelinEngine)` 引擎引用 init 期传入，Extension 无参构造
  即可，ServiceLoader 实例化无障碍；
- **组件轴免开发**：`@Reflective`（javelin-ast 编译期注册进 ReflectiveFactory）已就是
  「jar 自描述」的组件半边，与 ServiceLoader 的子系统半边互补；AOT 兼容顺带成立
  （`META-INF/services` 是 GraalVM native-image 支持的标准机制）。

**模块清单与依赖形状**（两类的扩展面不同）：

| 类 | 模块 | 扩展面 |
|----|------|--------|
| 只依赖 core（CPU 侧） | physics（第一消费者）、audio、animation、navigation、net | Subsystem 钩子即够 |
| 依赖 core + rhi（渲染侧） | particles、runtime-ui（引擎自绘 UI）、2d、terrain、postfx | 另需「向 RenderGraph 贡献 Pass」扩展点（编辑器 DebugLinePass/PickPass 已趟过模式，但那是编辑器内写死；模块化后要把 Pass 贡献也开口，实现期设计） |

**不做模块的**（防过度包化）：光照（默认管线本体，非可选功能）、拾取/调试线/DebugDrawText
（编辑器/诊断功能，已有归属）、输入/序列化/资产管线（地基，core 即全部）。scripting
（脚本运行时）与资产格式插件属**方向级决策**，不按功能模块排队。

**physics 切分边界**（第一消费者预拍）：纯数学 L0（Aabb/相交例程）与 Collider 组件族留
core（拾取/调试线已消费、场景反序列化不依赖模块存在）；Rigidbody 组件与 PhysicsSubsystem
进模块；**模块不依赖 rhi**（纯 CPU 侧，渲染调试走编辑器已有 DebugLine/组件绘制器）；
Rigidbody 带 `@Reflective`，模块自己的编译单元完成注册。

**负面路径**（模块化唯一要新写的错误面）：场景残留组件、运行时缺模块——反序列化走
ReflectiveFactory 查无此类，须给明确报错（fail-fast 指认「类名 X 未注册，可能来自模块 Y，
请添加 Gradle 依赖」），不落笼统反射异常。

**落地纪律**（现状已满足，保持即可）与**时机**：子系统接线集中在
`JavelinEngine.registerSubsystem()` 一处、组件全带 `@Reflective`；SPI 与 physics 模块
同一批次做（建模块 + SPI + Rigidbody/子系统打包一批次），不提前建空转基建。audio/
animation 同构复制。

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
