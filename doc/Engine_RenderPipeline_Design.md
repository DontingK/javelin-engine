# 引擎渲染管线设计（Engine Render Pipeline Design）

> 版本：v0.11
> 日期：2026-09-24（v0.5 2026-11-08；v0.6 2026-11-10；v0.7~v0.10 2026-11-13；v0.11 2026-11-14）
> 说明：引擎侧（javelin-core）渲染管线专档——一帧渲染的 Pass 组织（哪些 Pass、什么顺序、读写哪些资源、扩展机制）。
> v0.2 变更（2026-09-25）：**钩子点枚举（PipelineHook）移除**——编排改为**有序 Pass 列表**（`RenderPass.addToGraph`），扩展 = 向列表指定位置插入自定义 Pass（6.2）；散点表述同步清扫。
> v0.3 变更（2026-10-28）：**光照拍板 clustered Forward+（一步到位）**——ClusterBuildPass（compute）入 Pass 清单转正三期（2.2/3.1/3.2/4.3）、set1 改 cluster 套件多 binding（§7）、管线 4.5 两路光照一致性取舍**消解**（正透明同读一份 cluster，Engine_Lighting_Design v0.1 单点）；原「tiled/clustered 为 P2」口径作废。
> v0.4 变更（2026-10-31）：**ShadowPass 补单张硬影拍板**（§4.1，明细归 Engine_Lighting_Design §4.5）；**SkyboxPass 入 Pass 清单**（§3.1/3.2，插 Opaque 后/Transparent 前，cubemap+纯视觉，设计归 Engine_Skybox_Design v0.2（2026-11-02 实装拍板）——三期批次⑤）；§9 待定表天空盒旧口径收口。
> v0.5 变更（2026-11-08）：**HYBRID 档（Deferred 复用）实装**（§4.2-实装/§4.3-实装、§8 步骤二）——GBufferPass + LightingPass 上线，管线按 `PipelineProfile` 双 Pass 列表分派、运行期可切；不透明几何走 `Pass GBuffer` 同名桶（严格制既有机制），透明与天空盒两档共用；**不引入 lightingRT/Composite**（LightingPass 直写既有颜色终点，HDR 切换点仍归四期第 1 项）；**HYBRID 强制 1x**（MSAA-GBuffer 归四期）。
> v0.6 变更（2026-11-10）：**HYBRID 档验收关账**——用户实机两路光照一致确认；随验一坑（**Pitfalls #35**：set0 相机块 stageFlags VERTEX-only，LightingPass fs 读 `cam.view` 越权读零全黑——修=改 `ALL_GRAPHICS` 超集）。遗留口径：非光照材质（default.shader）HYBRID 档下 albedo 仍乘光照（无灯=黑，Deferred 物理正确），「直读 albedo」非光照语义契约归四期材质专档。
> v0.7 变更（2026-11-13）：**后处理链设计方案出档（待拍板）**——四期第 1 项（HDR 切换点 + Bloom/Tonemap）具体化落新档 `Engine_PostProcess_Design.md`；本文 §4.6/§4.7 占位收为其指针，§5.1 `lightingRT` 行指向该档（方案未拍板、未落码）。
> v0.9 变更（2026-11-13）：**管线拆成两个独立实现类 + MSAA 移除**（同日用户拍板）——新增 `AbstractRenderPipeline`（共用骨架：单条有序 Pass 列表 + 场景 Pass 身份集 + 后处理链 + 清屏/Pass 管理 + 链展开模板）、`DefaultHybridRenderPipeline` 瘦身为 HYBRID 独用、新增 `ForwardRenderPipeline`；**启动期**由 `JavelinSetting.renderPipeline` 选实现类、**运行期不再切档**（删 `setProfile`），「工具｜混合渲染」菜单退役，编辑器固定 HYBRID；**引擎/编辑器/设置层的 MSAA 全部删除**（`AntialiasingMode`/`antialiasing` 字段/MSAA 附件族/`isMsaaPath`/各 Pass MSAA 分支与 resolve 调用点），RHI 多采样设施保留休眠（§5.3）。
> v0.8 变更（2026-11-13）：**后处理链步骤一落码待验收**（同日用户下令开工，按方案 §7 建议默认）——`lightingRT` per-view 资源、`PipelineContext.sceneColorTarget()`、`PostProcessNode`/`PostProcessChain` 具体化 + 链展开（**展开点在最后一个场景 Pass 之后、overlay 制 Pass 之前**）、`TonemapPass`（高光肩部曲线）+ 工具菜单开关；HDR = 链非空才生效（§5.1 `lightingRT` 行转实装）。实装记录与两处实装边界见后处理档 §9。
> v0.10 变更（2026-11-13）：**后处理链步骤二 Bloom 落码待验收**——`BloomPass`（链中节点，单节点内 8 个覆盖制全屏 Pass）+ `RenderingSystem.bloomRtFor`（per-view 尺寸递减 1/2..1/16 RT 族）+ 内置 `bloom*.vert|frag` 走 SpvShader 导入链 + Bloom **独立开关**（`JavelinSetting.bloom`/`tools.bloom`，与后处理/Tonemap 解耦）；§5.1 资源表补 `postRt`/`bloomRt` 行；实装记录见后处理档 v0.4 §9.6。
> v0.11 变更（2026-11-14）：**管线与后处理节点解耦（零行为变化）**——`RenderPipeline` 删 4 个效果开关方法、改暴露 `postProcess()`；`AbstractRenderPipeline` 不再 `new`/持有具体节点（`orderedChain` 的 `instanceof` → `PostProcessNode.isTerminal()`）；节点创建/开关归组合根 `PostProcessSetup`（后处理档 v0.5 §9.7）。
> 职责边界：
> - 本文管**「画哪些、先后顺序、读写哪些 RT」**（引擎侧渲染管线，RHI 12 节概念）；
> - RHI 文档管**「图怎么推导执行」**（依赖拓扑、屏障、图缓存——22 节「声明在引擎，推导执行在 RHI」）；
> - 帧描述结构、RenderingSystem、RenderList 收集归 `Engine_Rendering_Design.md`（待写）——本文消费其产物（RenderView / RenderList / 场景快照），不重复定义。
> 用户拍板（2026-09-24）：**文档按混合管线完整设计，落地先 Forward；阴影纳入 Pass 清单与资源设计、实现后置；后处理设计链式结构与挂点、Pass 细节占位。**

---

## 1. 设计目标与总则

1. **兑现 RHI 14 节承诺**：RenderGraph 本身只是通用编排框架，引擎层在其上提供**开箱即用的默认混合管线**（Deferred 不透明 + Forward 透明，UE/Unity HDRP 同款），同时支持完全接管（模式 B，14.1）。
2. **混合管线 ≠ v0.1 实现范围**：文档完整设计混合管线的 Pass 清单与资源，落地分三步走（见第 8 节），第一步只有 Forward。
3. **红线**：
   - 不得改动 RHI 已发布的 API 语义；产生缺口回 RHI 文档增订（Engine_Design 5 节）；
   - 引擎层**零 Vulkan 句柄、零布局/屏障语义**——读写声明用语义化用途（22.3），Pass 回调只录绘制；
   - 禁止一个物体一个 Pass（RHI 7 节）；同一个 Pass 内按材质循环 DrawCall。

## 2. 概念分层

### 2.1 引擎渲染管线 vs RHI 管线（PSO）

一套引擎管线**复用多个 GraphicsPipeline**：GBuffer PSO、光照 PSO、透明 PSO、后处理 PSO；
同一个 PSO 被多个 Pass、多个相机共享。对照表见 RHI 12.3，本文不重复。

### 2.2 Pass 类型

| 类型 | 用途 | 本文出现位置 |
|------|------|-------------|
| 图形 Pass | 几何绘制 / 全屏效果 | 管线主体 |
| 计算 Pass | **clustered 光分簇（ClusterBuildPass，2026-10-28 转正三期）**、Bloom 降采样、粒子模拟 | 其余仍扩展点（P2）；v0.1 管线全图形 |

**不建拷贝 Pass**：Deferred→Forward 的深度复用直接走透明 Pass 内 `addDepthOutput(depth, LOAD)` + `addTextureRead(depth)` 的读写同资源模式（RHI 7 节 TransparentPass 既定写法），兑现 RHI 14.5「尽量避免真实深度拷贝」。

### 2.3 两种使用模式（RHI 14.1）

- **模式 A**：用默认管线 `DefaultHybridRenderPipeline`，只调材质/灯光、或向 Pass 列表插入自定义 Pass，不手写图（普通场景）；
- **模式 B**：实现 `RenderPipeline` 接口完全接管图编排（管线实验、定制渲染）。

## 3. 默认混合管线总览

### 3.1 完整形态（目标态）

```
ClusterBuildPass（compute，每视口光分簇索引）        [三期新增，Engine_Lighting_Design]
   ↓ clusterGrid/clusterIndices（着色消费，不依赖几何）
ShadowPass（可选，主方向光 depth-only）          [设计完成，实现后置]
   ↓ shadowMap
GBufferPass（Deferred：本视口全部不透明物体）     [设计完成，实现第二步]
   ↓ GBuffer×3 + depth
LightingPass（全屏三角形，Deferred 光照）         [设计完成，实现第二步]
   ↓ lightingRT（+ depth 复用）
SkyboxPass（视口天空盒，深度 LEqual 远平面）      [三期批次⑤新增，Engine_Skybox_Design]
   ↓ 现混合目标叠加
TransparentForwardPass（Forward：透明由远及近）   [v0.1 实现]
   ↓ lightingRT 叠加
PostProcessChain（…→Tonemap，链式可插拔）        [步骤一 HDR+Tonemap 已实装 2026-11-13]
   ↓
CompositePass（写 backbuffer；多相机 LOAD 叠加） [v0.1 实现]
```

### 3.2 Pass 清单与 v0.1 落地状态

| # | Pass | 类型 | 输入 | 输出 | v0.1 |
|---|------|------|------|------|------|
| 0 | ClusterBuildPass | 计算 | lightList（视空间灯列表）、相机参数 | clusterGrid（offset/count）+ clusterIndices（变长索引池） | ➖ 不作 Pass——批次③ **CPU 构建**（collectFrame 收集域，Engine_Lighting_Design §6） |
| 1 | ShadowPass | 图形（depth-only） | shadowCasters | shadowMap | ✅ 三期批次④-A（2026-11-01 实机验收） |
| 2 | GBufferPass | 图形 | RenderList.passes["GBuffer"]（不透明）、UBO1 | albedo/normal/worldPos/depth | ✅ 批次④-B（2026-11-08 落地） |
| 3 | LightingPass | 图形（全屏） | GBuffer×3、depth、shadowMap、UBO1(camera)、set1 cluster 套件 | 颜色终点（与 Opaque 同址） | ✅ 批次④-B（2026-11-08 落地） |
| 3.5 | SkyboxPass | 图形（全屏/视口） | skybox cubemap、UBO1(camera)、depth（LEqual 读） | 现混合目标 LOAD 叠加 | ✅ 三期批次⑤（2026-11-02 实机验收） |
| 4 | TransparentForwardPass | 图形 | RenderList.transparent、depth、UBO1 | 颜色终点（叠加） | ✅ |
| 5 | PostProcessChain | 链（0..n 个 Pass） | 颜色终点 / 链上游输出 | 链下游 | ⚠️ 空链直通（步骤一 Tonemap 已实装，链非空 = HDR，2026-11-13） |
| 6 | CompositePass | 图形（全屏） | 链输出 | backbuffer | ✅（单相机可省略） |

> **两实现类（v0.9 拆分）**：`ForwardRenderPipeline` = Shadow → OpaqueForward → Skybox → Transparent；
> `DefaultHybridRenderPipeline` = Shadow → GBuffer → Lighting → Skybox → Transparent。两实现共用同一套
> set1 cluster/阴影数据与同一 `javelinLighting` 函数（光照一致由构造保证），共用
> `AbstractRenderPipeline` 的编排与后处理链。空后处理链时单相机全屏终点即最后一个场景 Pass
> （无 Composite、无 lightingRT）；链非空即 HDR 生效（场景写 lightingRT、链尾 Tonemap 写显示终点）。

### 3.3 v0.1 实现形态（先 Forward，已拍板）

```
OpaqueForwardPass   直写 backbuffer（CLEAR），深度写独立 depth RT（CLEAR）
   ↓
TransparentForwardPass  backbuffer LOAD 叠加，depth LOAD 复用
```

- ~~无阴影、无 GBuffer~~ → 阴影（批次④-A）与 GBuffer/Lighting（批次④-B）已落地；后处理仍为空链（直通）；
- **单相机全屏时省略 CompositePass**——TransparentForwardPass 已是管线终点，直写 backbuffer，少一次全屏拷贝；
- 多相机时每视口一套上述 Pass 链，共享 backbuffer 的相机 LOAD 叠加（RHI 8/19 节）；
- 引入 Deferred 后管线终点仍直写 backbuffer（未引入 lightingRT）；将来接后处理时终点改为 lightingRT → 链 → Composite（结构变化 → 图缓存正常失效，18.5）。

## 4. Pass 逐个设计

### 4.1 ShadowPass（实现后置；v0.x = 单张硬影，2026-10-31 拍板）

- **职责**：主方向光（`Scene.getSun()`，主光解析口径 2026-10-28 改定，Engine_Lighting_Design §4）视角渲染全部 `RenderList.shadowCasters`，depth-only；
- **输出**：shadowMap（D32_SFLOAT，默认 2048²，灯光正交包围盒决定 frustum）；
- **uniform**：light viewProj（UBO1）+ 每物体 model（push）；
- **消费方**：GBuffer（着色时采样）与 Forward 不透明着色分支——需要材质系统提供带阴影采样的着色路径；
- **资源归属**：全局唯一（主光单张图），不属于任何视口；位于不透明几何 Pass 之前的独立位置；
- **v0.x 形态（数据侧明细归 Engine_Lighting_Design §4.5）**：单张硬影——无 PCF 软化、无 CSM 级联；深度 bias 先给 rasterization 侧保守值（漏影/彼得潘宁实测再调）；两路（Forward 注入段/LightingPass）消费同一 shadowMap+sunViewProj。

### 4.2 GBufferPass

- **职责**：本视口全部不透明物体 → MRT；
- **输出**（格式为预估，**最终布局随材质/着色模型专档拍板**）：

| 附件 | 预估格式 | 内容 |
|------|---------|------|
| albedo | R8G8B8A8_UNORM | 基色 + 透明裁切 |
| normal | R16G16B16A16_SFLOAT | 世界空间法线（+余量） |
| worldPos | R32G32B32A32_SFLOAT | 世界位置（可优化为深度重建，P2） |
| depth | D32_SFLOAT | 深度（视口独立，RHI 19.4-2） |

- **绘制**：`RenderList.opaque`（已按材质排序）循环——每材质一次 PSO 切换，同材质内顺序无关；
- **uniform**：set0 UBO1（camera）+ push（model/tpf）+ set2（材质；旧「set1=材质」编号已作废）。

**实装形态（批次④-B，2026-11-08）**：

- **对象来源** = `RenderList.passObjects("GBuffer")`（严格制同名桶，与 GizmoPass 同构）+ `!isTransparent()` 过滤——不透明材质须声明 `Pass GBuffer`；未声明者不进桶、不绘制，收集期一次性 WARN（红线 15）。`RenderList`/`collectList` 零改动。
- **布局落定**：albedo `R8G8B8A8_UNORM`（`_Color × _MainTex`）/ normal `R16G16B16A16_SFLOAT`（世界空间，归一）/ worldPos `R32G32B32A32_SFLOAT`（**w = 有效标志**，几何 1.0 / 清屏 0.0）；depth 复用本视口既有 D32（不新增）。
- **清屏**：G-Buffer 三张恒 CLEAR（视口私有暂存；分屏/预览走区域清屏四参重载），深度按 ClearFlags 分支（`DONT_CLEAR` LOAD，其余 CLEAR）。
- **PSO 颜色格式按 shader Pass 名注册**（`RenderingSystem.registerPassColorFormats`，`"GBuffer"` = 三张 MRT；缓存键已含 passName）；采样档恒 `COUNT_1`（HYBRID 强制 1x）。
- **不消费阴影**：本 Pass 不采样 shadowMap（光照统一在 LightingPass），故不声明其图依赖。

### 4.3 LightingPass

- **职责**：全屏三角形（`cmd.draw(3,1)`，RHI 19.3 既定），读 GBuffer + shadowMap 算光照；
- **输入**：GBuffer×3、depth（软阴影/重建用）、shadowMap、UBO1（camera）+ set 1 光照块（lights）；
- **输出**：lightingRT（R16G16B16A16_SFLOAT，HDR）；
- **光源数据**：set 1 **cluster 套件**（2026-10-28 拍板改定，原「lights 数组块、预估 16 盏」口径作废）——LightingParamsBlock（方向光+元数据+环境）+ lightList / clusterGrid / clusterIndices 三 SSBO，契约归 `Engine_Lighting_Design.md` §3、key 单点 Uniform_Keys §4；
- **计算化**：消费方为全屏图形 Pass；上游 ClusterBuildPass（compute 分簇）先行走图（RHI compute 节点，批次①）——本 Pass 自身的 tiled/clustered 化描述作废（已是 cluster 消费侧）；

**实装形态（批次④-B，2026-11-08）**：

- **覆盖制**（SkyboxPass/ShadowPass 同款）：引擎内置 shader 走资产导入链（`builtin-asset-core/lighting/lighting.vert/.frag` → SpvShaderImporter）；frag 带 `//@javelinLighting` 标记，导入期注入**相机块 + 光照段函数 + G-Buffer 三采样**（binding5/6/7）。
- **着色单源**：frag 直接调 `javelinLighting(viewNormal, viewPos, worldPos)`——与材质 Forward fs 是**同一常量**（`ShaderInjector`），同一 set1（cluster + shadowMap），「两路光照一致」由构造保证。
- **重建视空间量**：读 G-Buffer worldPos/worldNormal，用 `cam.view` 换算 `viewPos`/`viewNormal`（与材质 vs 同式）；背景像素按 `worldPos.w < 0.5` discard，保留清屏色（天空盒随后覆盖）。
- **清屏职责**：HYBRID 档颜色终点由本 Pass 清屏（区域清屏与 ClearFlags 分支照搬 OpaqueForward；`DEPTH_ONLY`/`DONT_CLEAR` 走 LOAD）。
- **无深度附件**：背景剔除靠 G-Buffer 有效标志而非深度测试（PSO `depthAttachmentFormat = null`，RHI 已支持颜色-only PSO）。
- **描述符**：set0 = 帧相机布局、set1 = 光照套件布局（同一 `frameStorageLayoutDesc`，含 G-Buffer 与 shadowMap）；零 push、无 set2。
- **未引入 lightingRT**：直接写既有颜色终点（与 OpaqueForward 同址），HDR/后处理切换点仍归四期第 1 项。

### 4.4 深度复用（决策记录）

**不建 CopyDepth Pass**。TransparentForwardPass 内直接：
```
addDepthOutput(depthTex, RHILoadOp.LOAD)   // 深度测试复用 Deferred 深度
addTextureRead(depthTex)                   // 着色可读（如软粒子）
```
同一 Pass 对同一资源的读写声明为 RHI 7 节既有模式，屏障由图推导合并（同一 Pass 合并、写优先于读）。

### 4.5 TransparentForwardPass

- **职责**：`RenderList.transparent`（由远及近，场景层排序——**不属于 RenderGraph**，RHI 14.5-3）Forward 绘制，叠加到上游输出；
- **状态**：颜色混合 ON（alpha blend）、深度测试 ON / 深度写入 OFF（PSO 按 `Material.surfaceType=TRANSPARENT` 覆盖得出，2026-10-17 方案 B——见 Material_Design §4.1）、depth LOAD 复用（4.4）；
- **着色**：v0.x Forward 透明着色为简化模型（Lambert + 环境项）——**与 Deferred LightingPass 读同一套 set1 cluster 契约、同一着色公式**（2026-10-28 clustered 拍板，原「两路一致性属已知取舍」消解，统一光照模型待材质专档回填两处）；
- **输出**：混合态目标（混合管线=lightingRT LOAD；v0.1=backbuffer LOAD）。

### 4.6 PostProcessChain（细节具体化归 `Engine_PostProcess_Design.md`，2026-11-13）

> **章节定位**：本节保留链的结构契约与挂点；**节点契约、HDR 切换点的启用条件、中间 RT、Tonemap/Bloom 形态、与多相机/AA 的交互** 已于 2026-11-13 具体化到 `Engine_PostProcess_Design.md`（四期第 1 项）——本节不再展开，改向该档。**步骤一（HDR 切换点 + Tonemap）同日已落码、2026-11-14 验收**：链非空 = HDR 生效，链由 `AbstractRenderPipeline` 在最后一个场景 Pass 之后统一展开（overlay 制 Pass 之前，避免链尾 Tonemap 覆盖 Gizmo/调试线）；实装见该档 §9；**步骤二 Bloom 同日已落码、2026-11-14 验收**（独立开关，见该档 §9.6）；**管线只承载链与展开、不认识具体节点**（`PostProcessNode.isTerminal` 排序 + 组合根 `PostProcessSetup` 持有/增删链，2026-11-14 解耦，见该档 §9.7）。

- **结构**：有序 Pass 列表，链首=场景颜色（混合管线 lightingRT / v0.1 backbuffer），链尾=Composite 的输入；

```java
// 链节点契约（引擎侧接口，占位细节）
interface PostProcessPass {
    String name();                                   // 参与 structureHash（18 节）
    void declareReadsWrites(PassDef pass);           // 声明读写（链内中间 RT 由链分配命名）
    void record(CommandBuffer cmd, ChainContext ctx);
}

// 链操作 = 向管线追加节点的一种特化（落地后经 pipeline.passes() 列表表达，6.2）
pipeline.postProcess().addLast(bloomChain);        // Bloom 多级降采样（细节占位）
pipeline.postProcess().addLast(tonemapPass);       // HDR→LDR（细节占位）
pipeline.postProcess().remove("Bloom");
```

- **空链 = 直通**：链首输入直接作为链尾输出交 Composite，不产生任何 Pass；
- **HDR 切换点**：首个后处理 Pass 引入时，场景颜色从「直写 backbuffer（LDR）」变为「先写 lightingRT（RGBA16F HDR）、Tonemap 后回 LDR」——这是管线结构变化，图缓存正常失效，非兼容性破坏；
- **挂点对齐（已拍板 2026-09-25）**：不设命名钩子枚举；链的注入/移除经 `postProcess()` 表达，链节点落地时实现 `RenderPass` 进入管线 Pass 列表——扩展统一走 6.2 的有序列表机制，文档不双写。

### 4.7 CompositePass

> **v1 收窄（2026-11-13，方案待拍板）**：`Engine_PostProcess_Design.md` §3.6 建议 v1 不实装 Composite——链尾 Tonemap 全屏直出 `colorTarget` 并接管清屏/叠加语义即可；出现「链尾输出非最终显示内容」的节点时再按本节补。

- **单相机全屏**：消费链输出 → backbuffer（CLEAR），viewport=全屏；**空后处理链时可省略**（3.3）；
- **多相机**：每视口链尾 → backbuffer **LOAD** 叠加，viewport/scissor=该相机视口；叠加顺序=RenderView 声明序（后声明的画在上层，RHI 19.5-3）；
- 独立输出目标（编辑器渲染到纹理）：终点换目标纹理，语义不变。

### 4.8 UI Pass（P2 占位）

- 链尾、backbuffer LOAD、正交投影、独立于 3D 管线深度；
- 引擎无 UI 系统前不实现，仅预留「UI 是管线终点后的独立 Pass 组」的位置。

## 5. 视图级资源规划

### 5.1 资源分组

| 资源 | 格式 | 尺寸 | 归属 | 生命周期 | v0.1 |
|------|------|------|------|---------|------|
| depth | D32_SFLOAT | 视口大小 | 每视口 | 随视口创建，resize 重建 | ✅ |
| msaaColor | 同 backbuffer 格式 | 交换链大小 | 每视口 | 随视口创建，resize 重建（AA 关恒不创建） | ✅（2026-10-06） |
| msaaDepth | D32_SFLOAT | 交换链大小 | 每视口 | 随视口创建，resize 重建（AA 关恒不创建） | ✅（2026-10-06） |
| shadowMap | D32_SFLOAT | 2048²（默认） | 全局（主光） | 常驻，灯光参数变化重建内容 | ✅ 批次④-A |
| GBuffer×3 | RGBA8 / RGBA16F / RGBA32F（见 4.2） | 视口大小（同 depth 尺码） | 每视口 | 随视口创建，resize 换绑，惰性建 | ✅ 批次④-B |
| lightingRT | R16G16B16A16_SFLOAT | 视口大小 | 每视口 | 随视口创建，resize 换绑，惰性建（链非空才创建） | ✅ 四期第 1 项**步骤一已实装**（2026-11-14 验收；`RenderingSystem.lightingRtFor`，后处理档 §3.3/§9） |
| lightList（SSBO） | 视空间 64B/灯 | 容量 4096 | 每视口 | 视口级常驻，每帧重写（host 上传） | ✅ 批次②首视口单缓冲 → 批次③ per-view（2026-11-01 待验收） |
| clusterGrid + clusterIndices（SSBO） | uint×2/cell + uint 变长池 | 2MB + 4MB（1080p 默认参数） | 每视口 | 视口级常驻，每帧重写（**CPU 构建**——批次③拍板 2026-11-01，GPU Pass 化归四期；灯光档 §6 修订）；视口尺寸变化重建 | ✅ 三期批次③已实装（2026-11-01 待验收） |
| postRt（后处理中间 RT） | R16G16B16A16_SFLOAT | 视口大小 | 每视口 | 随视口创建，resize 换绑，惰性建（链内非首尾节点输出） | ✅ 步骤一已实装（2026-11-14 验收；`RenderingSystem.postRtFor`，后处理档 §3.4/§9） |
| bloomRt ×4（Bloom 多级 RT） | R16G16B16A16_SFLOAT | 视口大小 >>(level+1)（1/2..1/16） | 每视口 | 随视口创建，resize 换绑，惰性建（Bloom 开才创建） | ✅ 步骤二已实装（2026-11-14 验收；`RenderingSystem.bloomRtFor`，后处理档 §3.7/§9.6） |

### 5.2 规则

- **每视口独立 depth/GBuffer/lightingRT**（RHI 19.4-2：不可跨视口共用，否则互相污染）；
- resize → 重建 + 图缓存 structureHash 失效（RHI 23.6-3），由 FrameLoop 的 recreate 路径咬合；
- **命名规范**：`{ViewName}_{资源名}`（如 `Main_GBufferAlbedo`），对接 RHI `setDebugName`（13.3），RenderDoc 抓帧可直接辨认；
- 资源创建/销毁只走 RHI 资源生命周期（Cleaner 两帧规则），引擎不裸建 VkImage。

### 5.3 MSAA 抗锯齿（2026-10-06 拍板并落地，已实测验收；**2026-11-13 移除**）

> **⚠ MSAA 已移除（2026-11-13，用户拍板）**：引擎/编辑器/设置层的 MSAA 全部删除——`AntialiasingMode`
> 枚举、`JavelinSetting.antialiasing` 字段、`RenderingSystem` 的 MSAA 附件族（`msaaColorFor`/`msaaDepthFor`
> /两张视口纹理表/生效采样档）、`PipelineContext` 的 msaaColor/msaaDepth、`AbstractForwardPass.isMsaaPath`
> /`passSamples`、Opaque/Transparent/DebugLine 的 MSAA 分支（3 处 resolve 调用点改普通三轴附件声明）、
> 菜单守卫与两枚 i18n 键。**RHI 的多采样设施保留休眠**（`PassDef` resolve 重载 / `SampleCount` /
> `TextureDesc.sampleCount` / `Device.supported*SampleCounts`——不动已发布 API 面）。**AA 空窗至 TAA
> （P4-10）落地**；本节以下内容为历史实装记录。

**路线拍板**：离屏 MSAA 附件 + Vulkan 原生 resolve attachment + overlay 恒 1x。
不走 swapchain MSAA（桌面基本不支持）、不自建 blit pass、不动后处理链
（FXAA 将来直接加 AntialiasingMode 枚举值）。

- **设置面**：`JavelinSetting.antialiasing`（`AntialiasingMode`：OFF/MSAA_2/MSAA_4/
  MSAA_8，默认 OFF）。按 name 落盘、与 RHI `SampleCount` 值域解耦；旧设置文件缺键
  保默认。启动期消费（vsync 同口径），运行期切换需 PSO/附件失效协议，后置。
- **RHI 面**：`GraphicsPipelineDesc.sampleCount`（rasterizationSamples 同源接线，进
  equals/hashCode/缓存语义）+ `PassDef.addColorOutput(msaa, loadOp, clear, resolveTarget)`
  新重载（`Attachment.resolveTexture`：resolve 终点按本 Pass 颜色附件<b>写</b>参与
  依赖推导/屏障计划/structureHash，backbuffer 终态 PRESENT_SRC 转换自动覆盖）+
  执行器填 `VkRenderingAttachmentInfo` resolve 三件套（AVERAGE）+
  `Device.supportedColor/DepthSampleCounts()`（limits 位掩码解码）。
- **引擎面**：`JavelinEngine.resolveAntialiasingSamples()` 启动钳制（色彩+深度双支持
  约束，8→4→2→1 逐档降级，降档一次性 WARN）→ `RenderingSystem(device, frameLoop,
  slots, SampleCount)` 新构造 → `msaaColorFor/msaaDepthFor`（视口独立，depthFor 同款
  惰性创建/resize 换绑/Cleaner 销毁，AA 关恒 null）→ `PipelineContext.msaaColor/
  msaaDepth` → Opaque/Transparent MSAA 分支（多采样附件全量 CLEAR/LOAD + 通道结束
  resolve 回 colorTarget；PSO 采样档进 pipelineFor 缓存键，旧三参签名委托 COUNT_1）。
- **v0.1 边界（isMsaaPath 统一判定，Opaque/Transparent 必须同判定——附件集合一致，
  MSAA 内容 LOAD 叠加与深度复用都依赖上游同路）**：stacking 相机（DEPTH_ONLY/
  DONT_CLEAR）、targetOverride 预览视口、区域清屏分屏视口均降级 1x 直写——MSAA
  附件内容保活/区域 resolve 语义后置。Gizmo/DebugText overlay 零改动恒 1x（文本/
  gizmo 抗锯齿化反而糊；`ctx.depth()` 保持 1x 语义供 overlay 使用）。
- **坑（Vulkan_Pitfalls 候选）**：同一次 beginRendering 内附件采样数必须一致 + PSO
  rasterizationSamples 必须等于附件 samples（两处验证层都抓）；深度不做 resolve
  （AVERAGE 对深度非法，且场景深度仅 MSAA Pass 内部消费）；Opaque/Transparent 各
  resolve 一次是全量覆盖、幂等正确（MSAA 内容始终全量），末 pass storeOp
  DONT_CARE 带宽优化留后调。

## 6. 编排机制

### 6.1 RenderPipeline 接口（引擎侧）

```java
// 管线 = 每视口一实例（或共享实例 + 每视口 buildGraph 调用）
interface RenderPipeline {
    PipelineProfile profile();                 // 身份常量：HYBRID / FORWARD（实现类固定返回，运行期不可切）
    void buildGraph(RenderGraphBuilder graph, PipelineContext ctx);
    void setClearColor(RHIClearColor c);
    void setPostProcessingEnabled(boolean on); // 链非空 = HDR 生效
    boolean postProcessingEnabled();
    default boolean usesHdrSceneTarget() { return postProcessingEnabled(); }
    void addPass(RenderPass pass); void addPass(RenderPass pass, String afterPass); void removePass(RenderPass pass);
}

// buildGraph 的输入（引擎侧上下文，字段级定义归 Engine_Rendering_Design.md）
class PipelineContext {
    RenderView view;                    // 视口/相机绑定（视图矩阵由相机生成，Y 翻转入投影——已拍板）
    FrameContext frame;                 // 场景快照 + 各 RenderList + UBO1 写入句柄
    Texture backbuffer;                 // 本帧终点（单相机可不进 ctx，由 FrameLoop 包进 graphFactory）
    Texture depth;                      // 本视口深度
    Texture hdrSceneTarget;             // 本视口 HDR 场景色（链非空时非 null，§5.1；sceneColorTarget() 取值口）
    // Deferred 阶段扩展：gbuffer×3 / lightingRT / shadowMap
    // 三期光照扩展（Engine_Lighting_Design §8）：lightList / clusterGrid / clusterIndices
}
```

> **实现类**：`AbstractRenderPipeline`（骨架：单列表 + 身份集 + 后处理链 + 链展开模板）→
> `DefaultHybridRenderPipeline` / `ForwardRenderPipeline`；启动期由 `JavelinSetting.renderPipeline`
> 选类，编辑器固定 HYBRID。

### 6.2 Pass 编排与扩展（有序 Pass 列表；v0.2 起替代原钩子点枚举）

```java
// Pass = 图构建单元：自持附件声明 + 渲染回调（第 4 节每个 Pass 一个实现类）
interface RenderPass {
    String name();                                        // 语义标识
    void addToGraph(RenderGraphBuilder graph, PipelineContext ctx);
}
```

- 管线 = 有序 `RenderPass` 列表：`buildGraph` 按序调用各 Pass 的 `addToGraph`；
- **扩展（RHI 14.3 方式 1）**：经 `addPass(pass)`（列表尾追加）/ `addPass(pass, afterPass)`
  （插于命名 Pass 之后）/ `removePass(pass)`（摘除，2026-10-05 补齐「增删/插入」之删）
  组编自定义 Pass（如 SSR 插于光照后、透明前）——「插入位置」即原钩子点的语义，
  不再设命名枚举（原 PipelineHook 已于 2026-09-25 移除：零调用方 + v0.1 下
  POST_LIGHTING 等点位别名映射已造成漏触发）；清空列表自行填充 = 方式 2 完全自定义；
- **末尾置顶 Pass 形态（先例：编辑器 GizmoPass，2026-10-05 定稿）**：颜色 LOAD
  保画，深度二选一——**CLEAR = 恒置顶**（对清空深度按 LEqual 绘制即恒前，CompareOp
  无 ALWAYS、Unity ZTest Always 不可用，清深是唯一等效形态，与相机 DEPTH_ONLY
  叠加分支同语义；**两层深度语义**：置顶层内部物体靠 ZWrite On 自写自测保留
  互遮挡，对外丢弃深度是故意的）；**LOAD = 场景深度咬合**（Pass 间共享深度
  附件，对 Opaque 遗留深度正常测试——被场景遮挡，立体 cues 正确）。GizmoPass
  现行 CLEAR（需求=gizmo 不被场景遮挡但内部有层次；LOAD 试行一日被否）。
  绘制对象来源（严格制，2026-10-17）：管线 Pass 经 `RenderPass.shaderPassName()`
  声明消费的 shader Pass 名，从 `RenderList.passObjects(name)` 取自己的桶（收集侧
  已按 Pass 名分发，GizmoPass 已改此形态——原"回调内按材质着色器身份过滤"
  与"Opaque 先画 + 末尾重绘"双画废除）；末位 Pass 写深度不影响下游；
- 列表组成变化 = structureHash 正常失效（RHI 18.5）；Pass 实现**只准做声明 + 录制**，不碰屏障/布局（22.4 迁移纪律）。

### 6.3 每视口管线选择

- **管线 = 启动期选定**（v0.9 起）：两个独立实现类（`DefaultHybridRenderPipeline` / `ForwardRenderPipeline`），
  引擎启动按 `JavelinSetting.renderPipeline` 选类，**运行期不可切**（原「同实例双列表 + setProfile」机制
  随拆分删除；编辑器固定 HYBRID、不读该字段）；覆盖制 Pass（Gizmo/拾取/调试线）经 `addPass` 进当前
  实现类的唯一列表，无「两档同步」语义（已不存在双列表）；按视口分档（预览视口 FORWARD_SIMPLE）仍未实现；
- 每视口独立 `buildGraph` 调用；共享 backbuffer 的相机靠 LOAD 叠加自动串行（RHI 8 节依赖推导）。

### 6.4 与图缓存咬合

- 相机增删、Pass 列表组成变化、后处理链变化 → 结构变化 → 正常失效（RHI 18.5），**不做特殊优化**；
- 运行数据（相机矩阵、物体列表、清屏值）不参与 structureHash——每帧变、不触发重编译；
- 相机集合稳定的常规帧：整套多视口 Pass 链 + 屏障计划跨帧复用（RHI 19.4-5）。

## 7. uniform / 描述符通道约定（按 Pass）

通道划分已拍板（RHI 实现进度 2026-09-24；**2026-10-23 修订**——材质桥落地后编号定为 set2=材质，set1 拍板预留光照，旧「set1=材质」编号作废；**2026-10-28 再修订**——set1 光照改 clustered 多 binding 契约）：**push = 每物件/每帧高频小量（model/tpf）；set0 UBO1 = 全帧共享引擎内置（camera/time）；set1 = 光照数据（LightingParamsBlock UBO + lightList/clusterGrid/clusterIndices 三 SSBO——原「LightBlock 落此」预留口径作废，Engine_Lighting_Design §3、Uniform_Keys §4 单点）；set2 = 材质（MaterialBlock UBO + 贴图槽）**。

| Pass | set0 UBO1 | push | set1 光照 | set2 材质 |
|------|-----------|------|----------|----------|
| GBuffer | camera（动态偏移切视口，19.4-1） | model/tpf | cluster 套件（三期） | 基色/法线等贴图 |
| Lighting | camera（重建用） | 一般不用 | cluster 套件 + shadowMap（binding4，批次④） | 一般不用 |
| Forward（v0.1 不透明/透明） | camera | model/tpf | cluster 套件（三期）+ shadowMap（批次④） | 材质贴图/参数 |
| Shadow | light viewProj | model | 不用 | 不用 |

- **shadowMap 描述符挂点**：**已拍板（2026-10-28）set1 扩展 binding4 combined depth-sampler**（阴影属光照域，set0 维持纯相机；sunViewProj 走 LightingParamsBlock，Engine_Lighting_Design §3.3/§3.4）——原「待材质系统专档拍板、属材质阶段决策」口径作废；随批次④落位；
- 动态 UBO 偏移（每视口/每物件切槽）沿用 RHI 19 节机制，**不引入 `setUniform(String, ...)` 字符串路径**（19.4-1 已否决理由）。

## 8. 实现路线（已拍板：实现先 Forward）

| 步 | 内容 | 验收 |
|----|------|------|
| 一 | Forward 两 Pass（OpaqueForward 直写 backbuffer + TransparentForward LOAD 叠加）+ 空后处理链 + 单相机省略 Composite；接 FrameLoop/renderFrame，多相机多物体 Demo（阶段⑥终验，Engine_Design 2.5） | 多相机分屏正确、透明排序正确、图缓存命中 |
| 二 | +ShadowPass（✅ 批次④-A 2026-11-01）；+GBuffer/Lighting 切换混合（同场景验证两路光照一致——4.5 取舍的回填点）（✅ 批次④-B 2026-11-08 落地；**v0.9 2026-11-13 改为两个独立实现类、启动期选定、运行期不可切**） | 阴影正确✅；混合/纯 Forward 启动期可选 ✅（`JavelinSetting.renderPipeline` 持久化；编辑器固定 HYBRID） |
| 三 | 后处理链具体化（Bloom 计算降采样链 + Tonemap；HDR 切换按 4.6）——**归四期第 1 项**：步骤一（HDR + Tonemap）+ 步骤二（Bloom，独立开关）2026-11-13 落码、2026-11-14 用户实机验收，见 `Engine_PostProcess_Design.md` §9 | 链插拔不破坏管线直通路径 |

每步独立的约束：不改动 RHI 已发布 API；新增引擎 Pass 仍走「声明读写 + 回调录制」。

**与光照批次（Engine_Lighting_Design §9 四批 + 天空盒批次⑤）的咬合（2026-10-28；2026-10-31 天空盒补入；2026-11-08 批次④-B 收口）**：光照批次①（RHI SSBO/compute 底座）先于本表一切后续步骤；批次②③（cluster 构建 + Forward 消费）在步骤一形态上落地 = Forward+ 成形；步骤二 = 光照批次④（④-A ShadowPass ✅ / **④-B GBuffer+Lighting 复用同一 cluster ✅ = 三期收官**）+ 混合/Forward 切换；步骤三（后处理链具体化）**归四期第 1 项**（Roadmap §4，2026-10-31 立项——四期第一候选）。

## 9. 待定（P2）

- ~~GBuffer 布局与材质/着色模型绑定~~ **已定并实装（批次④-B，2026-11-08）**：三张 MRT 格式 + `Pass GBuffer` 声明契约，见 §4.2 实装形态；
- ~~灯光数量上限与打包、shadowMap 描述符挂点~~（2026-10-28 拍板收口：clustered 多 binding 契约 + set1 binding4，§7/Engine_Lighting_Design）；
- ~~tiled/clustered 计算光照~~（2026-10-28 转正三期主路线）；clustered 自身后置项（前缀和计算化/脏标记/Morton）归 Engine_Lighting_Design §10；SSR 等光照后扩展 Pass 示例（插入 Pass 列表）仍 P2；
- 单 Pass 多视口（RHI 8 节备选）、后处理中间 RT 别名内存（RHI 17.4，P1）；
- UI Pass 组、环境（雾，Scene.environment）；~~天空盒~~ **已转正三期批次⑤**（cubemap+纯视觉，SkyboxPass 插 3.5 位——设计归 Engine_Skybox_Design v0.2（2026-11-02 实装拍板落定），本档 §3 已同步）；天空盒环境光照（ambient 源）归 GI 探针期。
- ~~MSAA 后置项（5.3）~~ **已随 MSAA 移除作废（2026-11-13）**：stacking/预览/分屏的 MSAA 路径、运行期
  切档、FXAA 档、区域清屏与 resolve 组合重载全部不再做（AA 路线 = TAA，P4-10）；仅存「末 pass storeOp
  DONT_CARE 带宽优化」一项仍挂 REND-11。

## 10. 文档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| RenderView / RenderList / 帧描述 | `Engine_Rendering_Design.md`（待写） | 本文 PipelineContext 的上游产物 |
| RenderGraph / 屏障 / 图缓存 / 多相机 | RHI 7/8/18/19/22 节 | 本文 Pass 声明的执行端 |
| 默认混合管线承诺 / 扩展模式 | RHI 14 节 | 本文是其在引擎侧的兑现（方式 1 = Pass 列表插入，方式 2 = 完全接管） |
| MeshRenderer/Camera/Light、渲染列表收集 | `Engine_Scene_Design.md` 5/6 节 | 渲染组件 → RenderList → 本文 Pass 消费 |
| 帧循环 / FrameLoop / recreate | RHI 23/24 节 + 实现进度 | 管线终点 backbuffer 的供给方 |

---

> （注：部分内容可能由 AI 生成）
