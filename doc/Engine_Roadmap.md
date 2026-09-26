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
| 材质桥（材质档 §4 全项） | set 2 布局按 Shader 缓存 / `MaterialBlock` std140 排布器 / `materialSet` 懒创建（运行期改值即时生效）/ `pipelineFor(Material, layout)`（PassState→PSO）/ `Shader.forwardPass()` / 透明分桶 = Blend 态 | MaterialBlockTest（CPU 单测）+ 两 Demo 材质路径迁移；旧内置程序路径删除 |

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

### 2.1 输入子系统——✅ 已落地（2026-09-26，待用户验收）

- **目标**：键鼠状态采集（GLFW 事件源）+ Action 映射；消费方演示 = 输入驱动相机移动；
- **设计档**：`Engine_Input_Design.md`（v0.1，含拍板记录：KeyCode 纯语义枚举零平台关联（键码映射归 Window 实现私有）/ Input 静态门面对齐 Time / 动作层三次收束——ActionMap 概念移除、bind 与查询合流 Input（唯一使用方式）/ ESC 关窗便利逻辑移除）；
- **架构**：Window.attachInputSink 事件出口（平台键码经 Window 侧翻译成引擎枚举投递）→ InputSubsystem（双缓冲帧边沿，earlyUpdate 发布本帧快照 + 动作绑定全局单表）→ Input 静态门面（原始查询 + 动作 bind/按名查询）；失焦清态防卡键；
- **验收**：InputCameraDemo（WASD 移动/右键拖拽转视角/SPACE 边沿/失焦不卡键）——**待用户运行确认**；
- **对应实现计划**：阶段⑤主体。

### 2.2 碰撞检查

- **目标**：场景对象的相交查询（游戏逻辑 + 编辑器拾取共用）；
- **前置**：**Aabb 回归**——视锥剔除后置时随 Mesh.bounds 一起删除，碰撞与 Ray 都需包围盒回来（Mesh 补"从 positions 算 Aabb"）；`Engine_Collision_Design.md`（待写：碰撞体形态——Collider 组件 vs MeshRenderer Aabb、查询 API 面）；
- **产出**：碰撞体组件 + 窄相查询 API（盒/球/射线公共部分）；
- **验收**：两物体相交/分离查询正确的最小 Demo；
- **待定拍板**：只做窄相查询（自研，推荐——拾取与逻辑查询够用）还是连带阶段⑥物理库选型（刚体动力学）一起定。

### 2.3 Ray

- **目标**：屏幕点 → 世界射线（逆 viewProj）→ 射 vs 碰撞体 → RaycastHit；
- **前置**：②的碰撞体与包围盒；
- **产出**：Ray/RaycastHit 数据类型 + Camera 屏幕射线生成 + 场景级 raycast 查询；
- **验收**：点击拾取场景物体正确（为编辑器拾取闭环）。

### 2.4 编辑器基本

- **目标**：场景编辑最小闭环——场景视口（飞行相机）+ 层级面板 + 拾取 + Transform 移动/旋转/缩放 + 场景保存/加载；
- **前置**：
  - **.scene 序列化**（现为 P2，编辑器存取必须）；
  - **资产格式层**（原阶段④剩余，编辑器读写的前提）：~~.shader 二进制格式 + 解析器（声明注入 + shaderc + 打包）~~——**✅ 已落地（2026-09-26，提交 e03c057）**：导入管线在编辑器 `importer/shader`（归属拍板：不经 javelin-tool），Capsule 二进制产物 + GUID 文件头规则 + .smesh 产物/拓扑/法线一并补齐；**.mat 格式 + AssetManager 读写未做**（下一步）；
  - **编辑器形态拍板**（内嵌 + imgui-java / 内嵌 + Nuklear / 独立 Swing——管线已预留 targetOverride 渲染到纹理、预览视口简化形态两个口子）；
  - `Engine_Editor_Design.md`（待写：editor 作为 JavelinEngine 子类的架构、编辑态/运行态关系、与 javelin-tool 分工）；
- **产出**：编辑器应用骨架 + 上述最小闭环；
- **验收**：搭建场景 → 摆放/移动物体 → 保存 → 重载还原（用户运行）。

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
| 二期 | 阶段⑤ 子系统 + 输入；**新增**：碰撞检查 / Ray / 编辑器基本（原计划未列，路线档补入） | 🔄 输入子系统已落地（2026-09-26，待用户验收）；碰撞/Ray 未开始（编辑器窗口层已就绪：EditorApp 接引擎 + CanvasSurface，见 Editor_Design） |
| 三期 | 灯光 + ShadowPass（Deferred/后处理为扩展） | 未开始 |
| 未定 | 阶段⑥ 物理 / 音频（选型拍板后各成阶段） | 未开始 |

## 5. 跨期遗留（不阻塞当期推进）

- RHI 遗留：Buffer staging 上传（deviceLocal）、描述符池环形、backbuffer 句柄入 structureHash；
- 场景：environment/sun（三期灯光一并定）、.scene 序列化（二期编辑器前置）；
- 资产：动态 Mesh/动态纹理专项（设计要点已记录，材质档 §6 / 资产档 §2.8）；
- 渲染优化：视锥剔除（Aabb 回归时一并）、遮挡剔除/GPU Instancing/LOD（P2）。

---

> （注：部分内容可能由 AI 生成）
