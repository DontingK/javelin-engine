# 引擎 GPU 拾取设计（Engine Picking Design）

> 版本：v0.2（2026-11-08 范围收窄并当日用户实测验收通过：**GPU ID-buffer 只服务场景物体
> 拾取，Gizmo 柄拾取撤回 CPU 碰撞体射线**——用户实机反馈「轴太细不好选、环太粗」：ID 附件
> 是「看得见即拾得」，细几何没有额外拾取宽容度，只能靠把可见几何做粗；改按 Babylon
> `colliderMeshes` 口径「可见几何与拾取体解耦」。§1/§7/§8 相应改写）
> 　　v0.1（2026-10-23 拍板 + 首版实现 + 用户实测验收通过）
> 定位：GPU ID-buffer 拾取的全链设计——RHI 读回能力 / core 拾取 pass 与调度系统 / 编辑器消费。
> 拍板出处：Editor_Design.md §10（2026-10-23 三增补：拾取全线统一 ID-buffer、pass 本体落 core 运行时可用、射线检测与 GPU 拾取共存；**2026-11-08 修订：Gizmo 柄不进 ID 附件**）。
> 配套：`doc/RHI_Design_Core.md`（读回 API）、`doc/Editor_Design.md` §10、`doc/Engine_Collision_Design.md`（碰撞体复用为拾取体）。

## 1. 定位与拍板

**场景物体拾取走 GPU ID-buffer（2026-10-23 拍板；2026-11-08 收窄适用范围）**：~~物体拾取 + Gizmo 轴拾取（含 hover/拖拽）全线统一~~ → **只有场景物体**：拾取 pass 把每个可拾取物体以帧内分配的 pick ID 写进离屏 R32_UINT 附件（0=背景/未命中），点击读回该像素 ID 查表得对象。不依赖用户物体挂 Collider、不给物体隐藏挂网格碰撞体；原「过渡期先落 CPU 数学拾取」开放项关账不落。

**Gizmo 柄拾取撤回 CPU 碰撞体射线（用户拍板 2026-11-08，理由「轴太细不好选、环太粗」）**：ID 附件「看得见即拾得」——细轴就难选、环只能靠加粗可见管径换宽容度（v0.1 期内曾把可见环管径从 0.005 加到 0.012）。改按 Babylon `colliderMeshes` 口径：**可见几何与拾取体解耦**，拾取体更粗（移动/缩放轴 = 加粗圆柱 Collider、旋转环 = 解析式环带、中心均匀柄 = 胖方块），可见几何回到 Babylon 原比例（细杆箭头 / 细环）。Gizmo 几何不再画进拾取附件（PickPass 只画不透明+透明桶），`Gizmo` 桶仍归 GizmoPass 绘制；hover 无读回延迟（顺带把 PickSystem 无监听者的 hover 投递整条关掉）。

**覆盖制（用户拍板 2026-10-23）**：拾取 pass 不消费材质 shader 的 Pass 声明，用引擎自有 pick 着色器绘制——「看得见即拾得」，用户 shader 零声明负担（Unity 编辑器拾取同为换内部 shader 路线；声明制「忘声明即拾不到」被否）。

**落点 = pass 本体进 core（用户拍板 2026-10-23）**：`PickPass`/`PickSystem` 归 `javelin-core`，默认不挂、消费方 `PickSystem.enable()` 启用；编辑器与运行时游戏同为消费方。有意分歧 Unity（Unity 拾取为编辑器内部实现、运行时无对应 API——短板不抄）。

**射线检测与 GPU 拾取共存（用户拍板 2026-10-23）**：射线**拾取**退役≠射线退役——运行时游戏内射线检测（Physics.Raycast 语义）照常归 Ray 期，两 API 并存：射线=物理查询（有 Collider 才打得中），ID-buffer=渲染拾取（看得见即拾得）。

**批次范围（用户拍板 2026-10-23）**：物体+Gizmo 轴一并迁移（hover 每帧读回代价在本批设计——见 §6 异步环形读回）；轴的现役 CylinderCollider CPU 射线过渡线退役销账。**2026-11-08 修订**：Gizmo 一侧回撤（轴拾取重回碰撞体射线，形态见 §1 第二条与 §7）——CylinderCollider 过渡线以「加粗拾取体」形态回归；场景物体一路不变。

## 2. 架构总览

三段分层，全链引擎主线程单线程串行（无并发面）：

```
javelin-rhi   RHIClearUintColor / copyImageToBuffer / Buffer.read / Fence.isSignaled
              / Format.bytesPerPixel / TextureReadbackRing（2 槽读回环，屏障+copy 收口 RHI 内）
javelin-core  RenderingSystem.pickIdFor/pickDepthFor（附件生命周期，depthFor 同模式）
              PickPass（覆盖制拾取 pass，管线尾插，RenderPipeline.addPass 挂/摘）
              PickSystem（调度：帧表 3 环 + hover/click 双通道 + ID 分配，JavelinEngine 帧末驱动）
              RenderObject.owner/pickId + SceneRenderBridge 赋 owner（ID→对象数据面）
javelin-editor ViewportPickAppState（指针喂入 + 点击门控 + 选中收口）
              TransformGizmoAppState（柄 hover = CPU 射线打拾取体；拖拽 pickRay 仍是纯数学）
              JavelinEditor.onInitialize（构造 PickSystem + enable + 挂消费 AppState）
```

帧内数据流（引擎线程序）：

```
earlyUpdate   ViewportPickAppState.update：pointer(px,py)（dpi 换算后附件像素）
              + LMB 门控通过时 requestClick(px,py)
              TransformGizmoAppState.update：pickHandle(pickRay)（CPU 拾取体）→ hoveredNode/拖拽
renderFrame   buildFrameGraph：PickPass.addToGraph（帧表重建 + 附件声明 + 回调录制）
              execute：回调逐物体 assignPickId + push constants + draw（R32_UINT 附件）
mainLoop 末   PickSystem.frameEnd：收割读回环（帧号校验查表 → click listener；hover 无监听即不投递）
              → 投递本帧像素 copy（点击优先；hover 仅指针移动时）
```

## 3. RHI 读回能力（javelin-rhi 新增六项）

- **`RHIClearUintColor`**：整数附件清屏值（sealed 族第三成员）——Vulkan 规范要求 UINT 附件清屏用 uint32 分量，float 清是验证层 mismatch（`toVkClearValue` instanceof 分支）。
- **`CommandBuffer.copyImageToBuffer(Texture, Buffer, TextureRegion)`**：`vkCmdCopyImageToBuffer`（TRANSFER_SRC_OPTIMAL、调用方预转屏障）——`copyBufferToTexture` 的读回镜像，「RHI 内部路径」纪律同款。
- **`Buffer.read(offset, length, out)`**：map→拷出→unmap（对称 update；GPU 完成可见性由调用方 fence 保证）。
- **`Fence.isSignaled()`**：`vkGetFenceStatus` 非阻塞查询（读回环跳帧判定）。
- **`Format.bytesPerPixel()`**：非压缩格式逐像素字节数（读回容量计算；压缩格式抛不支持）。
- **`TextureReadbackRing`**：多槽（拾取=2）读回环——每槽 cmd+fence+staging buffer 池化复用；`submit(tex, oldLayout, x,y,w,h, tag)` 非阻塞投递（槽在飞行=GPU 落过返回 false 跳帧）、`poll()` 非阻塞 FIFO 收割（最旧完成槽先出——点击结果不被更新的 hover 结果跳过）。屏障与 copyImageToBuffer 收口在环内（22.4 迁移纪律，uploadTexture 的读回方向延伸）。

布局时序（复用既有跨帧先例）：图执行后附件处 COLOR_ATTACHMENT_OPTIMAL；读回屏障 COLOR→TRANSFER_SRC；下帧拾取 pass 首用屏障 UNDEFINED→COLOR_ATTACHMENT（LayoutTracker 每帧重置、CLEAR 覆写——UNDEFINED 源=内容可弃，规格合法，depth 附件跨帧同款既有形态）。

## 4. 拾取附件（RenderingSystem）

- `pickIdFor(cameraName)`：R32_UINT，usage = COLOR_ATTACHMENT|TRANSFER_SRC（读回前置），**恒 1x**（与主画面 MSAA 档解耦——点击像素与附件像素一一对应，无需 resolve），交换链尺寸惰性创建。
- `pickDepthFor(cameraName)`：D32_SFLOAT 深度，拾取 pass 自有（与主深度解耦——主深度在 MSAA 路径是多采样对，拾取无 resolve 语义耦合）。
- 生命周期同 depthFor：`ensureDepthSize` resize 换绑（recreate 首行 waitIdle，帧首销毁安全）、`removeCamera` 退役两帧规则、`close` 显式释放。

## 5. PickPass（覆盖制拾取 pass）

- **挂载**：`PickSystem.enable()` 尾插管线（GizmoPass 同款 `addPass` 模式）；图声明 `view.name()+"_Pick"`，`addColorOutput(pickIdFor, CLEAR, RHIClearUintColor(0))` + 自有深度 CLEAR，viewport/scissor 同 view。
- **shader**：**走资产导入链**（2026-11-07 改向，ShadowPass/SkyboxPass 同款）——源码 `builtin-asset-core/pick/pick.vert/.frag`（core 模块资源树）经 `SpvShaderImporter` 编译 SPIR-V 落 Library，`PickPass` 经 `AssetManager.loadAsset("core/pick/pick.vert|.frag")` 装载（缺失/坏 stub 显性报错不兜底，红线 15）。~~原「GLSL 源放 javelin-tool、`gradlew :javelin-tool:generatePickSpv` 离线编译成 spv 内嵌 core resources、PickPass 读 classpath」路线作废~~（2026-11-07 清退：`tool/pick/PickAssets.java`、`tool/resources/pick/`、`core/resources/pick/*.spv`、`generatePickSpv` 任务一并删除）。vs：location0 position + set0 UBO1 viewProj + push model；fs：push pickId → `out uint`。push 布局 = model@0(64B) + **pickId@64**（复用 80B 声明，tpf 槽位让渡——拾取无时间语义；ALL_GRAPHICS 可见）。
- **绘制桶**：不透明桶 → 透明桶（同深度测试 LEqual/ZWrite On——近者胜；Unity 口径透明物可选中，拾取输出常量 ID 无 blend 语义；透明桶画序即主通道视图空间 z 序但深度测试使序无关）。~~"Gizmo" 桶（深度关、置顶）~~ → **2026-11-08 起不进拾取附件**（Gizmo 柄拾取改 CPU 碰撞体射线，见 §7）。编辑器透明面（Grid）命中后由消费方按节点归属裁决为点空。声明了 `Pass Pick` 的用户物体会进同名自定义桶，本 pass 不消费（覆盖制）。
- **PSO**：`GraphicsPipelineDesc` 色格式 `List.of(R32_UINT)`、set 布局仅 set0（`RenderingSystem.frameSetLayoutDesc()`，无材质 set2）、CullMode NONE（看见即拾得含背面）、Blend Off（UINT 附件必须）、pushConstantSize 80、COUNT_1；缓存键 = layout:topology:depthOn。
- **ID 分配**：回调逐物体 `PickSystem.assignPickId(ro)`（帧内 1 起跨视口全局递增）→ `RenderObject.pickId` + 帧表登记 id→owner（owner = `SceneRenderBridge` 注册时赋的 MeshRenderer；Demo 直注册对象 owner=null 恒为背景）。

## 6. PickSystem（调度，core.render.picking）

- **帧表 3 环**：`tables[frameIndex%3]` = {帧号, id→owner}；PickPass.addToGraph 每帧 `beginFrame`（帧号幂等）重建。**读回时序保证**：帧 N 拷贝至迟 N+2 帧首可读（FrameLoop 帧首 fence await + 队列序），3 环覆盖收割窗口；收割时帧号校验，表被覆盖即丢弃告警（理论不可达防御）。
- **hover/click 双通道**（`frameEnd(frameIndex)`，JavelinEngine mainLoop renderFrame 后一行驱动）：
  1. 收割：`ring.poll()` FIFO——tag（=拷贝源帧号）对上 pendingClick 的投递帧 → clickListener；否则 → hoverListener（owner=null 即背景）。
  2. 投递：点击优先（`requestClick` 记录像素，槽忙保留下帧重试——点击保证送达）；hover 仅**指针移动**时投递（静止零读回）。点击在附件外=点空同步送达。
- **API**：`pointer(px,py)`（-1 停发 hover）/`requestClick(px,py)`/两个 listener/`enable()`；构造自注册 `engine.setPickSystem`；`close` 摘 pass + PSO + 读回环（shutdown 链在 waitIdle 后）。
- **线程模型**：全程引擎线程（pointer/requestClick 由消费方 AppState update 喂、frameEnd 引擎调、listener 同线程回调）。

## 7. 编辑器消费（javelin-editor）

- **JavelinEditor.onInitialize**：`new PickSystem(this, "Editor Camera Node")`（相机先注册拿基名，名字稳定）+ `enable()`；挂 `ViewportPickAppState(transformGizmo)`（挂载序在 gizmo 之后——点击门控消费其当帧 hover/拖拽态）。
- **ViewportPickAppState**：每帧 `pointer`（Input.mouseX/Y 逻辑坐标 × swapchain/canvas 换算附件物理像素）；点击门控 = LMB 按下 && !Alt（归相机环绕）&& SCENE 模型 && !gizmo 拖拽中 && hover 非柄（柄点击归 gizmo 拖拽）。click listener → 用户场景节点 `EditorSelection.setSelectedNode(node)`、背景/编辑器自有节点（网格/轴线）`setSelectedNode(null)`（Unity 点空清空口径；层级树/Inspector 订阅零改动自动联动）。~~hover listener → owner 节点直写 `gizmo.setHoveredFromPick`~~ → **2026-11-08 起 `setHoverListener(null)`**：gizmo 柄 hover 改 CPU 射线（见下条），PickSystem 无监听者即不投递 hover 拷贝（省一路逐帧读回）。
- **Gizmo 柄拾取 = CPU 射线打拾取体（2026-11-08 回撤，形态照 Babylon `colliderMeshes`）**：`TransformTools.pickHandle(ray)` 默认遍历工具子树里的 `Collider` 组件取最近命中（移动/缩放轴 = 加粗 `CylinderCollider`；中心均匀柄 = 加粗 `BoxCollider`），旋转环无对应 L0 形状 → `RotateTool` 覆写为**解析式环带**（射线 ∩ 环平面 → 交点离环心距离落在 `[R−tol, R+tol]`，tol = Babylon 粗环半径 0.015）。拾取体尺寸在 `GizmoMeshes` 里与可见几何并列（模型单位随工具缩放 → 屏幕恒定宽容度）；驱动侧再经 `isSelectable` 视线裁决（不可选视同未命中）。拖拽 beginDrag/updateDrag 的 pickRay 仍由 `screenToRay` 现算（纯拖拽数学，非命中测试）。
- **MoveTool/ScaleTool 挂回 CylinderCollider**：`collideWithRay` 命中线以「加粗拾取体」形态回归（2026-10-23 退役销账 → 2026-11-08 复用，尺寸不再 fitFromMesh 而按 Babylon 加粗规则显式给）。

## 8. 已知边界与后置

| 项 | 现状 | 后置方向 |
|----|------|---------|
| ~~hover 延迟 1~2 帧（GPU 管线深度固有）~~ | **2026-11-08 起 Gizmo 柄 hover 零延迟**（CPU 射线同帧出结果）；ID 附件 hover 通道已无消费方（PickSystem 无监听即不投递） | — |
| ~~拖拽起手依赖 settled hover~~ | 同上：CPU 射线同帧命中，按下即抓 | — |
| 透明物体 | **可拾取**（Unity 口径，2026-10-23 用户确认）——同深度测试近者胜；Grid 等编辑器透明面命中=点空清空 | 水平视角下网格平面之下（y<0）被挡物体的拾取为已知边缘 |
| 多视口 | 单目标视口（编辑器相机）；点击换算全屏口径 | 分屏 viewport 偏移换算后置 |
| hover 刷新 | 仅指针移动时重拾（场景动、鼠标不动 → hover 不刷新） | 变更驱动的 hover 失效后置 |
| R32_UINT 附件支持 | 未做 `vkGetPhysicalDeviceFormatProperties` 查询 | 验证层把关；格式查询工具后置 |
| 读回环 | 每帧最多 2 枚 4B 拷贝、槽池化 | 无优化需求 |
| GAME 视图 | 拾取停发（编辑器相机摘除、pointer 停喂） | — |

**视锥剔除零成本继承（设计性质，非拾取侧待办）**：PickPass 消费 `view.list()` 收集后的 RenderList——剔除落地在 `RenderingSystem.collectList` 收集侧（现状全量进列表、2026-09-25 拍板后置，Aabb/Mesh.bounds 地基已备），落地当天主通道与拾取 pass 同享已剔除列表，拾取侧零改动（覆盖制共用收集管线的红利）。

## 9. 验收清单（用户实测；2026-10-23 v0.1 全项已过）

1. 点击物体选中（层级树高亮 + Inspector 面板联动）；点空白清空选中。
2. hover 轴高亮、近平行轴变灰不可选、点轴直接拖拽（**2026-11-08 起 hover 源 = CPU 拾取体，行为不变；另验拾取宽容度：轴好抓、环细但好抓**）。
3. 拖拽中不误触发改选中；Alt+LMB 环绕不误选中。
4. MSAA_8 开启下拾取正确（拾取 pass 恒 1x 与主画面解耦）。
5. resize 窗口后拾取正确（附件换绑）。
6. 遮挡关系：近者胜（深度测试）；**透明物体可选中**（前透明物遮后物，前者优先）。~~轴恒压物体（置顶）~~ → 2026-11-08 起 Gizmo 不进附件，轴上点击由 gizmo hover 门控拦住（不落到被遮物体）。
7. 运行日志：`PickSystem enabled` / `pick PSO created` / `首帧 Pick 回调执行` / gizmo `拾取命中柄节点` 正常。

## 10. 文件清单

| 层 | 文件 |
|----|------|
| rhi | `RHIClearUintColor.java`（新）/ `RHIClearValue.java` / `CommandBuffer.java` / `VulkanCommandBuffer.java` / `Buffer.java` / `VulkanBuffer.java` / `Fence.java` / `VulkanFence.java` / `types/Format.java` / `TextureReadbackRing.java`（新） |
| tool | ~~`pick/PickAssets.java`（新）+ `resources/pick/pick.vert|pick.frag`（新）+ `build.gradle` generatePickSpv 任务~~ **2026-11-07 整体清退**（源码迁 core builtin-asset-core/pick 走导入链，§5 shader 条） |
| core | `RenderingSystem.java`（pickIdFor/pickDepthFor/frameSetLayoutDesc） / `RenderObject.java`（owner/pickId） / `SceneRenderBridge.java`（owner 赋值） / `render/pipeline/pass/PickPass.java`（新；**2026-11-08 去掉 Gizmo 桶绘制与 depthOn 分档**） / `render/picking/PickSystem.java`（新；**2026-11-08 hover 无监听即不投递**） / `JavelinEngine.java`（挂点+注册+shutdown） / ~~`resources/pick/pick.vert|frag.spv`（新产物）~~ **2026-11-07 删除**，改 `resources/builtin-asset-core/pick/pick.vert|pick.frag` + 伴生 .meta（源，经导入链编译） |
| editor | `appstate/ViewportPickAppState.java`（新；hover listener 已摘） / `appstate/gizmo/*`（**2026-11-08 柄拾取改 CPU 拾取体：`TransformTools.pickHandle` + Move/ScaleTool 挂 Collider + RotateTool 环带**） / `JavelinEditor.java`（挂载） |
