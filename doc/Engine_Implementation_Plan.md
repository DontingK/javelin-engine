# 引擎实现计划（Engine Implementation Plan）

> 版本：v0.1（2026-09-23）
> 目标：按 `Engine_Design.md` 总纲，把 javelin-core 从"窗口壳"建成可跑场景的引擎，最终消费 RHI 完成多相机渲染 Demo。
> 规格来源：`doc/Engine_Design.md`（总纲）、`Engine_Scene_Design.md`（v0.2，**2026-09-25 审定**：Transform 拍板为 Component 子类）、`Engine_Window_Design.md`、`Engine_Assets_Design.md`、RHI 设计系列（消费侧契约：9 节 RenderView、19 节多相机、20 节材质实例、27 节帧交接）。
> 现状：窗口/Time/AssetManager/SubsystemManager 已挂入 JavelinEngine（57f2719）；**RHI 六阶段全部关账（MultiCameraDemo 验收通过，2026-09-25）**；**阶段①③验收通过（2026-09-24 落地、2026-09-25 验收）：JavelinEngine 接线 RHI 初始化序列 + RenderingSystem/FrameDescription + v0.1 Forward 管线 + MultiCameraDemo**；**阶段②验收通过（2026-09-25 落地、单测全绿）：Node/Component/Transform/GameScene + GameSceneTest 13 用例**。

## 1. 约束（遵守 AGENTS.md 红线）

1. **不编译验证**：编译 / 运行交给用户执行，我提供运行命令；
2. **不写不确定代码**：不确定的设计先问用户；
3. **Vulkan / LWJGL / GLFW API 签名不确定时向用户要明确签名**（红线 4）；
4. **问题定位**：第一优先级加日志，用户运行后看日志；
5. **AOT / 禁反射**：对象创建走 `@Reflective` + `ReflectiveFactory`；序列化走 `Serializable` + Capsule 手写协议；
6. **不改 RHI 已发布 API 语义**：引擎侧需求产生缺口时回 RHI 文档增订（见 `Engine_Design.md` §5）。

## 2. 总路线（六阶段，依赖顺序）

```
① 引擎接线 RHI（初始化序列搬家 + shutdown 逆序 + JavelinEngine.main 出画面）
   ↓
② 场景系统实现（Node/Component/Transform + 生命周期，按 Engine_Scene_Design v0.2）
   ↓
③ 渲染系统·引擎侧（帧描述设计 → RenderingSystem → Camera/RenderView → 多相机 Demo 验收）
   ↓
④ 材质系统（.mat 设计 → 加载 → MeshRenderer 材质路径）
   ↓
⑤ 子系统完善 + 输入子系统（注册时机成文 + Action 映射）
   ↓
⑥ 物理 / 音频（选型拍板后各成阶段，顺序视选型依赖）
```

每阶段独立可验证：① JavelinEngine.main 跑清屏/三角形；② 场景生命周期纯单测；③ 19 节多相机 Demo；④ 换材质可见差异；⑤ 输入驱动相机移动；⑥ 物理/音频各自最小 Demo。

**RHI 侧遗留修复**不阻塞主线，插入时机见 §4：PSO 缺 `VkPipelineRenderingCreateInfo`（等拍板）、Buffer staging 上传。

## 3. 阶段明细

### 阶段 ① 引擎接线 RHI（纯搬家，零新设计）——✅ 验收通过（2026-09-25）

- **目标**：`JavelinEngine.main` 直接跑起来出画面，替代 TriangleTest 成为验收载体；
- **产出（已实现）**：
  - JavelinEngine 持有 RHI 初始化序列：`RHI.createInstance` → `createDevice` → `new FrameLoop`，工厂可覆盖（createInstanceConfig/createDevicePrefs/createSwapChainDesc/createFrameLoop）；
  - `initialize()` 扩展 + **shutdown 逆序**：frameLoop.close（内部 waitIdle）→ renderingSystem.close → device → instance → window（finally 链）；
  - mainLoop 形态对齐 27.1 单线程退化：pollEvents → Time.update → update() → frameLoop.renderFrame；无渲染系统时 graphFactory 回退纯清屏（默认 main 出画面）；
- **验收**：`JavelinEngine.main` 窗口出清屏（用户运行）；关闭窗口无 Vulkan 验证层报错；
- **对应文档**：27.1、`Engine_Window_Design.md`。

### 阶段 ② 场景系统实现——✅ 验收通过（2026-09-25）

- **目标**：组件模型落地，生命周期由主循环驱动；
- **规格**：`Engine_Scene_Design.md` **v0.2**（2026-09-25 审定，更正 v0.1：Transform 拍板为 Component 子类）；
- **产出（已实现）**：
  - `Node`（容器；name/active/layer/tags；构造经 addComponent 自动持有 Transform；getComponent(s) 线性扫描；isActiveInHierarchy 沿 Transform 父链；持 `Scene` 接口归属字段）；
  - `Component`（抽象基类：node 反引用、enabled、isActiveAndEnabled、六回调 onAwake/onEnable/onStart/onUpdate/onDisable/onDestroy；`@Reflective` + ReflectiveFactory 零反射创建）；
  - `Transform`（**组件**，v0.2 拍板推翻 v0.1"独立类"；TRS + 父子层级 + localToWorld 脏标记级联重算 + setParent 防环 + Iterable 子变换遍历）；
  - `Scene` 接口（getName/getCameras/getRenderables/update）+ `GameScene`：add/remove（attachSubtree/detachSubtree：整树递归、归属检查、onAwake 全树先于 onEnable）+ update 扁平注册表驱动 onStart（每组件一次）/onUpdate + 组件注册表（cameras/renderables/全量 components，O(1) 增删，不每帧遍历场景树）；
  - 内置组件：Camera（fov/near/far/main）、MeshRenderer（阴影标记 + implements Renderable，mesh/material 字段待阶段④资产管线）；Layer/LayerMask/Tag（31 层预定义 + 位掩码，Unity 风格）；材质骨架 Material/Shader（extends AbsAsset，随 AbsAsset 改继承 ObjectId 落地）；
- **拍板（2026-09-25）**：**无 createNode**；`setParent` 挂到已入场景的父节点自动整树入场景，**摘除即出场景**（整棵子树 onDisable/onDestroy + 注销；不同于 Unity 出生即入场景——本引擎节点可裸存在）；已属场景禁止跨场景 setParent（抛异常）；**渲染衔接走 pull**（推翻 ③ 节原"scene.add 自动调 addCamera/addRenderer"预登记路径：引擎层每帧从 getCameras/getRenderables 取组件，翻译成 RenderCamera/RenderObject 喂 RenderingSystem）；onAwake 对整树触发含 inactive 节点（Unity 语义），仅 onEnable 按激活过滤；
- **验收 ✅（2026-09-25 用户运行全绿）**：`GameSceneTest` 13 用例——add/remove/setActive 回调序与级联边界、setParent 自动入/出场景（含孙节点）、跨场景/二次 remove 抛异常、注册表内容、update 驱动、Transform 父链世界矩阵与防环、重复 Transform 拒绝；`gradlew :javelin-core:test --tests com.javelin.core.scene.GameSceneTest`；
- **遗留**：~~GameScene→RenderingSystem 的 pull 翻译接线~~——**已关闭（2026-09-25 场景渲染接入）**：`SceneRenderBridge` 落地（Camera→RenderCamera、MeshRenderer→RenderObject、多相机横向均分视口），MeshRenderer.mesh/material 字段已补；environment/sun、.scene 序列化仍 P2；
- **对应文档**：`Engine_Scene_Design.md`（v0.2）1–6 节。

### 阶段 ③ 渲染系统·引擎侧——第一步 ✅ 验收通过（2026-09-25）

- **目标**：场景 → 帧描述 → RHI，多相机 Demo 验收（RHI 阶段⑥终点）；
- **前置（已完成）**：`Engine_Rendering_Design.md` v0.1（帧描述字段级定义 = 27.2 交接契约落地、RenderingSystem 与 FrameLoop 调用关系、Y 翻转落点、UBO1 收编）+ `Engine_RenderPipeline_Design.md` v0.1（Pass 组织；拍板：文档混合/实现先 Forward、阴影纳入清单后置、后处理只留挂点）；
- **场景系统前置的处理（用户拍板 2026-09-24）**：跳过场景层（Engine_Scene_Design 未审定），RenderingSystem 暴露 addCamera/addRenderer 直接注册 API~~——阶段②落地时 scene.add 按组件类型自动调用同一套 API~~（**2026-09-25 阶段②拍板改为 pull**：引擎层每帧从 Scene 接口取组件翻译后调用，见阶段②节），本层不变；
- **产出（已实现，v0.1 Forward 形态）**：
  - `Camera` 组件 → `RenderCamera`（位姿 + 视口矩形；recalculate 生成视图/投影，**投影接管 Y 翻转**——perspective zZeroToOne + m11 取负，JOML）；
  - `Mesh` 资产最小实现（Engine_Assets_Design v0.2：per-attribute + 规范序固定 location 表 + 首次上传冻结；hostVisibleCoherent 内存，deviceLocal 待 Buffer staging；AABB 随视锥剔除一并后置）+ `VertexAttribute`；
  - `RenderingSystem`：注册表 + UBO1 帧环形收编（2 槽 × 1280B，相机块 256B 对齐 + 时间块；描述符 range=256B 防末槽越界）+ 内置无光照 PSO 缓存（VertexLayout×混合 为键）+ 每相机深度纹理（resize 换绑交 Cleaner）+ 每帧收集（opaque 分桶排序 / transparent 视图空间 z 升序；**视锥剔除/AABB 属优化点，用户拍板后置 2026-09-25**）；
  - v0.1 Forward 管线 `DefaultHybridRenderPipeline`：OpaqueForward（区域 CLEAR，分屏互不覆盖）→ TransparentForward（LOAD 叠加、深度复用、alpha 混合）；钩子点 8 个 + PostProcessChain 空链直通；**着色器源码不进 core**（用户拍板 2026-09-25）：GLSL 放 `src/test/resources/shaders/`，`TestShaders`/`RhiTestBase.compileShaderResource` 读资源 + shaderc 编译 SPIR-V 后 installBuiltinShaders；
  - mainLoop 收口：renderFrame(graphFactory) 内 collectFrame（acquire 后声明，24.1）→ 逐视口 buildGraph；
  - `MultiCameraDemo`（test 源）：左右分屏双相机 + 3 旋转四边形（含 1 透明）；运行 `gradlew :javelin-core:runTest -DmainClass=com.javelin.core.MultiCameraDemo`；
- **验收 ✅（2026-09-25 用户运行通过）**：**19 节多相机多物体 Demo**——分屏区域清屏正确、双相机各自画面、透明混合排序正确、物体不颠倒、resize 不崩；**RHI 阶段⑥正式关账**；
- **后续（路线一第二、三步）**：+ShadowPass/GBuffer/Lighting 切混合（渲染管线专档第 8 节）；后处理链具体化；
- **对应文档**：`Engine_Scene_Design.md` 5–7 节、`Engine_Rendering_Design.md`（待写）、RHI 9/19/27 节。

### 阶段 ④ 材质系统——桥接半落地（2026-09-25，随 MultiCameraDemo 验收）

- **目标**：材质资产落地，渲染可见差异；
- **前置**：先写 `Engine_Material_Design.md`（`.mat` 格式 = shader 引用 + 参数值，序列化走 Capsule；材质实例与 RHI 20 节 Material 的创建/缓存桥接）；
- **已落地（场景渲染接入一并，材质文档 §4 全项）**：
  - 渲染侧桥接三环节：set 2 布局按 Shader 缓存（binding0 = MaterialBlock UBO + binding1..n 贴图槽）/ materialSet 懒创建（std140 排布器 MaterialBlock、TextureUpload 懒上传 + 占位解析、SamplerDesc 缓存）/ pipelineFor(Material, layout)（PassState → GraphicsPipelineDesc，缓存键 shader+layout）；
  - `Shader.forwardPass()`（Pass 名分类 v0.1 前向规则）+ 透明分桶 = 前向 Pass Blend 态（RHI 21.1 引擎决定队列）；
  - 场景侧：MeshRenderer.mesh/material 字段 + `SceneRenderBridge` pull 翻译；JavelinEngine.setScene + mainLoop 驱动 scene.update + collectFrame 前 sync；
  - 验收载体：`SceneRenderDemo`（GameScene + Spinner 组件 + 双相机分屏 + 透明材质）、`MaterialBlockTest`（CPU 单测）、MultiCameraDemo 迁移材质路径（内置程序路径删除）；
- **待办（仍为阶段 ④ 范围）**：
  - `.mat` 资产格式 + 加载（AssetManager 接入）；
  - 多 Pass 材质与 RHI 20 节模型的对齐检查；
- **验收**：换 `.mat` 出可见差异（颜色/纹理）的 Demo（用户运行）；
- **对应文档**：`Engine_Material_Design.md`（v0.1 §4 已落地标注）、RHI 20、`Engine_Scene_Design.md` 5.1。

### 阶段 ⑤ 子系统完善 + 输入子系统

- **目标**：子系统框架成文，首个功能性子系统落地；
- **前置**：先写 `Engine_Subsystem_Design.md`（注册时机与 init 顺序、子系统与帧描述的关系；契约已重构落地 2026-09-26：四钩子 + 冻结/暂停，渲染贡献走管线 Pass 注册）；
- **产出**：
  - **输入子系统**：键鼠状态采集（GLFW 事件源）+ Action 映射；消费方演示 = 输入驱动相机移动；（SubsystemManager 调度已接入 mainLoop/shutdown：earlyUpdate → scene.update → lateUpdate）
- **验收**：输入驱动相机在 ③ 的 Demo 里移动（用户运行）；
- **对应文档**：`Engine_Subsystem_Design.md`（待写）、`Engine_Window_Design.md`（事件源）。

### 阶段 ⑥ 物理 / 音频（选型拍板后各成阶段）

- **目标**：物理步进（earlyUpdate）+ 结果回写 Transform（lateUpdate）；音频上下文 + 3D 音源；
- **前置**：**物理选型**（自研 / JBullet / bullet 绑定）、**音频选型**（OpenAL 等）用户拍板；两者无相互依赖，可分两个并行阶段；
- **验收**：物理：刚体下落/碰撞最小 Demo；音频：播放 + 3D 衰减最小 Demo；
- **对应文档**：各自专档（待写，方向见 `Engine_Design.md` §3）。

## 4. RHI 侧遗留修复（插入时机）

| 项 | 说明 | 建议插入时机 |
|---|---|---|
| PSO 缺 `VkPipelineRenderingCreateInfo` | dynamic rendering 规范违反，desc 的 attachmentFormats 被忽略，纯靠驱动宽容 | 阶段 ③ 前（多相机 Demo 会放大 PSO 组合，先扶正） |
| Buffer staging 上传 | 当前仅 hostVisibleCoherent + UploadData，deviceLocal 会 vkMapMemory 失败 | 阶段 ④ 前（材质纹理上传需要） |
| backbuffer 句柄入 structureHash 每帧重编译 | 图缓存失效 | 阶段 ③ 帧描述落地时一并处理 |
| FrameArena 1MB / submit 等待阶段硬编码 | 已知问题，见进度文档 | 随用随修 |

## 5. 不做（P2，实现后置）

- `.scene` 序列化格式、Prefab 资产（阶段 ② 数据模型为其预留，格式另定）；
- 材质变体/关键字、遮挡剔除、GPU Instancing、LOD、Environment（雾/天空盒）；
- 多线程逻辑/渲染分离（27.5；当前单线程退化形态已对齐 27.1）；
- 玩法层扩展（基于已落地的 appstate 控制程序层：动态挂卸 + 单 update 心跳）。

## 6. 需要用户拍板的开放项

| 项 | 现状 | 建议 |
|---|---|---|
| `Engine_Scene_Design.md` 审定 | v0.1，标注"可能由 AI 生成" | **阶段 ② 前置**，逐节过一遍 |
| 帧描述字段级定义 | 只有一句话方向 | ✅ 已定（2026-09-24，`Engine_Rendering_Design.md` v0.1） |
| `.mat` 格式 | 未定 | 阶段 ④ 前写 `Engine_Material_Design.md`，与 `.scene` 格式统筹 |
| PSO 修复时机 | 已报告未修 | ✅ 已修（87080e9，随本次一并复跑确认） |
| 物理 / 音频选型 | 未定 | 阶段 ⑥ 前拍板即可，不阻塞主线 |
| `JavelinSetting.vsync` 消费方 | 无 | ✅ 已定（2026-09-24：vsync→FIFO，!vsync→IMMEDIATE，createSwapChainDesc） |
| `update()` / `onInitialize()` 钩子去留 | 现为 protected 空钩子 | 阶段 ② 后随用户类形态再定 |

## 7. 文档对照索引

| 阶段 | 设计文档 |
|---|---|
| ① 接线 RHI | `Engine_Design.md` §2.1、RHI 27.1、Window 专档 |
| ② 场景系统 | `Engine_Scene_Design.md` 1–4 节 |
| ③ 渲染系统 | `Engine_Rendering_Design.md` v0.1、Scene 5–7 节、RHI 9/19/27 |
| ④ 材质系统 | `Engine_Material_Design.md` v0.1（§3 产物格式已落地 2026-09-26）、RHI 20/21 |
| ⑤ 子系统+输入 | `Engine_Subsystem_Design.md`（待写） |
| ⑥ 物理/音频 | 各自专档（待写） |

---

> （注：部分内容可能由 AI 生成）
