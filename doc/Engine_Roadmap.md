# 引擎阶段路线（Engine Roadmap）

> 版本：v0.6（2026-11-11）
> v0.6 变更（2026-11-11）：§6 跨期遗留收敛至新建 `Engine_Backlog.md`（「已计划、尚未完成」的唯一登记处），本节留指针；「插件域待拍板」更正为 2 项。
> v0.5 变更（2026-11-10）：**三期批次④-B 验收关账**——HYBRID 档用户实机两路光照一致（随验坑 Pitfalls #35 set0 阶段可见性读零已修）；三期五批全部关账。§3/§5 状态同步。
> v0.4 变更（2026-11-08）：**三期批次④-B（Deferred 复用）落地**——GBufferPass+LightingPass+`PipelineProfile` 双档可切（同场景两路光照一致待用户验收）；§3/§5 三期状态同步为「收官（除 ④-B 待验外无剩余项）」。
> 说明：阶段路线**总纲**——各期目标、拍板、验收标准与**状态摘要**；一期关账总结、二期引擎功能、三期光照、四期高质量渲染立项。各期细节以对应专档为准；**批次索引**（每个批次一行：日期｜改动要点｜落点设计档｜验收）2026-11-08 拆分入分卷（见下方索引）。
> 拍板沿革：三期路线 2026-09-25 拍板（一期渲染基础完 → 二期引擎功能 → 三期光照，阴影随光照；Deferred/后处理列三期扩展）；**2026-10-31 拍板：高质量光照 / 光追 / 天空盒环境光 / 实时 GI 等后期项归四期**（三期收口为「有光照」= Forward+ 成形 + 单张硬影 + 视觉天空盒，质量升级整体下期）。

## 分卷索引（拆分后，2026-11-08）

| 文档 | 内容 |
|------|------|
| `Engine_Roadmap.md`（本文，总纲） | 各期目标 / 拍板 / 验收标准 / 状态摘要、§5 六阶段映射表、§6 跨期遗留 |
| `Engine_Roadmap_Progress.md` | **批次索引**——§2 二期（§2.3 Ray / §2.4 编辑器基本）与 §3 三期散件逐条一行、§4 排障基建散件（`Engine_DebugTools_Plan` 批次）；§5 批挂靠归档（每批落哪期哪个阶段）+ §6 已收口专项归档 |

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

> **批次索引**（L1 场景级 raycast 一条：要点 / 落点 `Engine_Collision_Design.md` §10 / 验收日期）→ `Engine_Roadmap_Progress.md` §2.3。

### 2.4 编辑器基本——✅ 主体验收收官（2026-10-14；物体拾取 ID-buffer 2026-10-23 用户实测验收——拾取闭环收官）

- **目标**：场景编辑最小闭环——场景视口（飞行相机）+ 层级面板 + 拾取 + Transform 移动/旋转/缩放 + 场景保存/加载；
- **前置**：
  - **.scene 序列化**（现为 P2，编辑器存取必须）；
  - **资产格式层**（原阶段④剩余，编辑器读写的前提）：~~.shader 二进制格式 + 解析器（声明注入 + shaderc + 打包）~~——**✅ 已落地（2026-09-26，提交 e03c057）**：导入管线在编辑器 `importer/shader`（归属拍板：不经 javelin-tool），Capsule 二进制产物 + GUID 文件头规则 + .smesh 产物/拓扑/法线一并补齐；~~.mat 格式 + AssetManager 读写未做~~——**✅ 已落地（2026-10-23 用户实测验收通过）**：.mat = Material 文本序列化（伴生 .meta 口径），MaterialImporter + project.create.material 创建命令，Engine_Material_Design §3.2；
  - **编辑器形态已定：Swing 外壳 + JCEF Web 面板**（~~内嵌 imgui / Nuklear / 独立 Swing 三选一~~ 作废——视口=Vulkan Canvas 挖洞浮于 CEF 之上，浮层遮挡约束由此而来，Editor_Design §1）；
  - `Editor_Design.md`（✅ 已建档 v0.1：JavelinEditor=JavelinEngine 子类、CEF 窗口体系、JS↔Java 桥、资产管线、线程模型）；
- **产出**：编辑器应用骨架 + 上述最小闭环；
- **验收**：搭建场景 → 摆放/移动物体 → 保存 → 重载还原（用户运行）。

**拾取方案拍板（2026-10-06 用户；2026-10-23 统一/落点/共存三增补）**：拾取终态 = **GPU ID-buffer 全线统一**（物体拾取 + Gizmo 轴拾取终态均写 ID 附件、点击读回像素——不依赖用户物体挂 Collider、不给物体隐藏挂网格碰撞体，Unity 编辑器同路线）；编辑器侧射线拾取退役、「过渡期先落 CPU 数学拾取」开放项**关账不落**；落点 = **pass 本体进引擎 core、运行时也可用**（有意分歧 Unity：Unity 拾取为编辑器内部实现、运行时无对应 API，短板不抄）；RHI 前置 = 拾取附件 + 图像读回路径，~~后置实现、非当前排期~~——**已落地并验收（2026-10-23 用户实测，Engine_Picking_Design.md v0.1：RHI 读回环/PickPass 覆盖制/PickSystem 双通道/编辑器消费 + 轴拾取全线迁移，CylinderCollider 过渡线退役）**（详见 Editor_Design §10 / Engine_Picking_Design）。射线检测与 GPU 拾取**共存**：游戏内大多走射线检测（Physics.Raycast 语义），运行时场景级 raycast（Collider 注册表）照常归 §2.3 Ray 期——射线=物理查询（有 Collider 才打得中），ID-buffer=渲染拾取（看得见即拾得到），两 API 并存不冲突。

**状态摘要**（逐批落地与验收流水见分卷）：编辑最小闭环主体 2026-10-14 验收（窗口层 / .scene 序列化 / Gizmo 轴拾取与拖拽）；编辑器骨架全链 2026-10-11 破土 → 打包 JAR/AOT 双形态 2026-10-13 三端验收；GPU ID-buffer 拾取 2026-10-23 验收（物体 + Gizmo 轴统一）；层级 / 项目 / 检查器三面板 Swing 实装与去 Web 化 M1~M6 收口 2026-10-17~18 落地、2026-10-23 验收；资产链（.mat / 贴图三层 / 纹理 / meta 统一 / 热重载 / 坏 shader 兜底）2026-10-19~30 陆续落地与验收；插件体系 + i18n 全量化 2026-10-20/30 落地、2026-10-31 验收；变换 Gizmo 旋转/缩放工具与几何程序化 2026-11-08 验收；检查器组件增删 2026-11-08 验收。

> **批次索引**（§2.4 共 40 条：每批一行「日期｜要点｜落点设计档｜验收」，含检查器注册器 / 视口聚焦两条仅见映射表的批次）→ `Engine_Roadmap_Progress.md` §2.4。

---

## 3. 三期：光照

- **目标**：场景开灯出可见差异——灯光数据通路 + 有光照着色 + 阴影 + 视觉天空盒；**质量升级（后处理/阴影软化级联/环境光照/实时 GI/光追）整体归四期（2026-10-31 拍板）**；
- **拍板（2026-10-28，`Engine_Lighting_Design.md` v0.1 单点；主光裁决 2026-10-31 改向 v0.5）**：**clustered + SSBO（Forward+ 形态）一步到位，跳过 UBO 数组 / 逐盏画光体积中间档**——只面向 PC、质量优先；正透明同读一份 cluster（管线档 4.5 两路光照一致性取舍**消解**）；默认参数 = 16×16 tile × Z32 指数切分、每 cluster 索引上限 64（截尾=去最弱）、光列表容量 4096；主光 = **最强激活方向光自动裁决**（`Scene.getSun()`，对齐 Unity 拍板 2026-10-31——原显式 isMain 方案推翻，同强度取挂入序首个）；shadowMap 挂点 = set1 binding4；
- **内容**（四批，灯光档 §9）：
  1. **RHI 底座前置（新增段）**：~~SSBO 实装（现仅设计稿 API）+ ComputePipeline/dispatch + RenderGraph compute Pass 节点与 Buffer 读写同步——compute 写 SSBO → 图形 Pass 读回最小闭环为验收~~ **✅ 批次①落地并验收关账（2026-10-31 用户实测，细则见 §5 阶段表三期行）**；
  2. **Light 组件 + 收集**：DIRECTIONAL/POINT/SPOT（字段级归灯光档 §4）+ Scene lights 注册表 + 视锥剔除/强度预排序/视空间 64B 打包——**✅ 批次②落地并验收关账（2026-10-31 当日用户实测）**：Light/RenderLight/LightCollector + set1 binding0/1 数据面（LightingParamsBlock UBO 动态偏移 + lightList SSBO）+ ShaderInjector fs 声明注入/保留字生效 + F3 行 8 灯数 + 单测（LightCollectorTest/GameSceneTest 灯用例）；验收 = 单测全绿 + 挂灯实机 F3 灯数显示（着色消费归批次③，本批无画面验收项；细则灯光档 §9 批次②行）；
  3. **cluster 构建 + Forward 消费（= Forward+ 成形）**：ClusterBuildPass（compute，每视口，三步构建）+ 着色器光照段注入 + Lambert + 环境项 + SPOT（后半）；
  4. **ShadowPass + Deferred 复用（2026-11-01 拆分拍板：④-A/④-B 两步派发——Deferred 复用牵材质着色模型/材质专档拍板，混批风险大）**：**④-A ShadowPass 单张硬影 ✅ 已落地并验收关账（2026-11-01 用户实机全项）**——v0.x = 单张硬影（2026-10-31 拍板：单张 2048² 正交包围盒、无 PCF/无 CSM/bias 保守值，质量分级后置灯光档 §10）+ 注入段阴影采样分支（javelinLighting 三参签名扩 worldPos）；实装形态/挂点五拍板（PSO 静态 bias 字段/per-view 幂等挂载/binding4 并入 set1 布局/深度比较采样器（**随验 LINEAR→NEAREST 拍板**）/sunViewProj 扩块 @336——灯光档 §4.5.1 全录）；**④-B GBuffer/LightingPass 同一 cluster 直喂 + 混合/纯 Forward 可配置切换 ✅ 已落地并验收关账（2026-11-10 用户实机：HYBRID 档两路光照一致；随验坑 Pitfalls #35 set0 阶段可见性读零已修）**：GBufferPass（三张 MRT，albedo/世界法线/世界位置+w 有效标志）+ LightingPass（全屏三角形，`javelinLighting` 单源注入=与 Forward 同一函数同一 set1）+ set1 扩 binding5/6/7（G-Buffer 三采样 per-view）+ `PipelineProfile` 双 Pass 列表（`JavelinSetting.renderPipeline` 持久化 + 工具域 `tools.renderPipeline` 运行期切换）+ HYBRID 强制 1x；**不引入 lightingRT/HDR/Composite**（四期第 1 项）；实装明细归管线档 §4.2/§4.3 实装形态段、光照档 §9 批次④-B 行、材质档 §4.2 `Pass GBuffer` 契约；
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
| 二期 | 阶段⑤ 子系统 + 输入；**新增**：碰撞检查 / Ray / 编辑器基本（原计划未列，路线档补入） | 🔄 输入子系统 ✅（2026-09-27）；碰撞检查 v0.1 ✅ 全片单测（2026-10-06，场景级查询归 Ray 期）；Ray 侧 Camera.screenToRay ✅（2026-10-06）+ **L1 场景级 raycast ✅（2026-10-28 落地、2026-10-31 用户实测验收）**；编辑器基本 ✅ 主体验收收官（2026-10-14）——窗口层 / .scene 序列化 / Gizmo 拾取与拖拽 / 骨架全链 / 打包 JAR·AOT 双形态 / 三面板 Swing 实装 / 去 Web 化 M1~M6 / 拾取 ID-buffer / 资产链（.mat·贴图三层·纹理·meta 统一·热重载·坏 shader 兜底）/ 插件体系与 i18n 全量化 / 变换 Gizmo 三工具齐备 / 检查器组件增删——**逐批落地与验收明细见 `Engine_Roadmap_Progress.md` §2.4** |
| 三期 | 灯光 + ShadowPass + SkyboxPass + Deferred 复用（clustered Forward+ 拍板 2026-10-28——五批实装：RHI SSBO/compute 底座→Light 组件收集→cluster 构建+Forward 消费→ShadowPass（单张硬影 2026-10-31 拍板、④-A/④-B 拆分 2026-11-01）→GBuffer/LightingPass（④-B 2026-11-08 落地、11-10 验收）；Engine_Lighting_Design v0.10 / Engine_RenderPipeline_Design v0.6 / Engine_Skybox_Design v0.4） | ✅ **批次①②③④-A④-B⑤全部落地并验收关账**（①~③ 2026-10-31/11-01 验收；④-A 2026-11-01 用户实机全项；⑤天空盒 2026-11-02/11-07 用户实机全链；④-B 2026-11-10 用户实机关账）——**三期全部收官**；批挂靠见 `Engine_Roadmap_Progress.md` §5 |
| 四期 | 高质量渲染（2026-10-31 立项，§4 单点——后处理链/SSAO/阴影质量分级/天空盒环境光照/探针实时 GI（DDGI）/光追 rayQuery） | ⬜ 立项收口（排序为初步依赖序，各项开工前逐项拍板方案） |
| 未定 | 阶段⑥ 物理 / 音频（选型拍板后各成阶段） | 未开始 |

> **批挂靠归档（每批落哪期哪个阶段）与映射表明细版溯源** → `Engine_Roadmap_Progress.md` §5。

## 6. 跨期遗留（不阻塞当期推进）

> **已收敛至 [`doc/Engine_Backlog.md`](Engine_Backlog.md)**（2026-11-11 建档——「已计划、尚未完成」的单一登记处：未开工 / 半成品 / 后置项 / 待拍板 / 已知问题按域一项一行）。本节不再逐条维护。

- 原列于此的 RHI 遗留（Buffer staging / 描述符池环形 / backbuffer structureHash）、资产（动态 Mesh/纹理专项）、渲染优化（视锥剔除、遮挡剔除/Instancing/LOD）等 → `Engine_Backlog.md` §4~§6；
- **已落定、本表不再追踪**：~~environment/sun~~（2026-11-08 `Environment` 组件承接天空盒、sun 主光自动裁决三期批次②已落）、~~.scene 序列化~~（二期编辑器前置已落地）、groupId/包名迁移 + 版本定版 + 开源前置批（2026-10-31 已验收）——细则归档见 `Engine_Roadmap_Progress.md` §6；
- **插件域待拍板剩 2 项**（命令 id 前缀化 / FileType 冲突策略）→ `Engine_Backlog.md` PLG-01/PLG-02（原「3 项」中「组件显示名口径」已于 2026-10-30 拍板 key 化）。

---

> （注：部分内容可能由 AI 生成）
