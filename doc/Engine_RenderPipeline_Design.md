# 引擎渲染管线设计（Engine Render Pipeline Design）

> 版本：v0.2
> 日期：2026-09-24
> 说明：引擎侧（javelin-core）渲染管线专档——一帧渲染的 Pass 组织（哪些 Pass、什么顺序、读写哪些资源、扩展机制）。
> v0.2 变更（2026-09-25）：**钩子点枚举（PipelineHook）移除**——编排改为**有序 Pass 列表**（`RenderPass.addToGraph`），扩展 = 向列表指定位置插入自定义 Pass（6.2）；散点表述同步清扫。
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
| 计算 Pass | tiled 光照、Bloom 降采样、粒子模拟 | 仅扩展点（P2），v0.1 管线全图形 |

**不建拷贝 Pass**：Deferred→Forward 的深度复用直接走透明 Pass 内 `addDepthOutput(depth, LOAD)` + `addTextureRead(depth)` 的读写同资源模式（RHI 7 节 TransparentPass 既定写法），兑现 RHI 14.5「尽量避免真实深度拷贝」。

### 2.3 两种使用模式（RHI 14.1）

- **模式 A**：用默认管线 `DefaultHybridRenderPipeline`，只调材质/灯光、或向 Pass 列表插入自定义 Pass，不手写图（普通场景）；
- **模式 B**：实现 `RenderPipeline` 接口完全接管图编排（管线实验、定制渲染）。

## 3. 默认混合管线总览

### 3.1 完整形态（目标态）

```
ShadowPass（可选，主方向光 depth-only）          [设计完成，实现后置]
   ↓ shadowMap
GBufferPass（Deferred：本视口全部不透明物体）     [设计完成，实现第二步]
   ↓ GBuffer×3 + depth
LightingPass（全屏三角形，Deferred 光照）         [设计完成，实现第二步]
   ↓ lightingRT（+ depth 复用）
TransparentForwardPass（Forward：透明由远及近）   [v0.1 实现]
   ↓ lightingRT 叠加
PostProcessChain（Bloom→Tonemap→…，链式可插拔）  [结构落地，Pass 占位]
   ↓
CompositePass（写 backbuffer；多相机 LOAD 叠加） [v0.1 实现]
```

### 3.2 Pass 清单与 v0.1 落地状态

| # | Pass | 类型 | 输入 | 输出 | v0.1 |
|---|------|------|------|------|------|
| 1 | ShadowPass | 图形（depth-only） | shadowCasters | shadowMap | ❌ 设计完成，后置 |
| 2 | GBufferPass | 图形 | RenderList.opaque、UBO1 | albedo/normal/worldPos/depth | ❌ 设计完成，第二步 |
| 3 | LightingPass | 图形（全屏） | GBuffer×3、depth、shadowMap、UBO1(lights) | lightingRT | ❌ 设计完成，第二步 |
| 4 | TransparentForwardPass | 图形 | RenderList.transparent、depth、UBO1 | lightingRT / backbuffer（叠加） | ✅ |
| 5 | PostProcessChain | 链（0..n 个 Pass） | lightingRT / 链上游输出 | 链下游 | ⚠️ 空链直通 |
| 6 | CompositePass | 图形（全屏） | 链输出 | backbuffer | ✅（单相机可省略） |

### 3.3 v0.1 实现形态（先 Forward，已拍板）

```
OpaqueForwardPass   直写 backbuffer（CLEAR），深度写独立 depth RT（CLEAR）
   ↓
TransparentForwardPass  backbuffer LOAD 叠加，depth LOAD 复用
```

- 无阴影、无 GBuffer、后处理为空链（直通）；
- **单相机全屏时省略 CompositePass**——TransparentForwardPass 已是管线终点，直写 backbuffer，少一次全屏拷贝；
- 多相机时每视口一套上述 Pass 链，共享 backbuffer 的相机 LOAD 叠加（RHI 8/19 节）；
- 引入 Deferred/后处理后，管线终点改为写 lightingRT → 链 → Composite 写 backbuffer（结构变化 → 图缓存正常失效，18.5）。

## 4. Pass 逐个设计

### 4.1 ShadowPass（实现后置）

- **职责**：主方向光（`Scene.sun`）视角渲染全部 `RenderList.shadowCasters`，depth-only；
- **输出**：shadowMap（D32_SFLOAT，默认 2048²，灯光正交包围盒决定 frustum）；
- **uniform**：light viewProj（UBO1）+ 每物体 model（push）；
- **消费方**：GBuffer（着色时采样）与 Forward 不透明着色分支——需要材质系统提供带阴影采样的着色路径；
- **资源归属**：全局唯一（主光单张图），不属于任何视口；位于不透明几何 Pass 之前的独立位置。

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
- **uniform**：UBO1（camera）+ push（model/tpf）+ set1（材质贴图）。

### 4.3 LightingPass

- **职责**：全屏三角形（`cmd.draw(3,1)`，RHI 19.3 既定），读 GBuffer + shadowMap 算光照；
- **输入**：GBuffer×3、depth（软阴影/重建用）、shadowMap、UBO1（camera + lights）；
- **输出**：lightingRT（R16G16B16A16_SFLOAT，HDR）；
- **光源数据**：UBO1 内 lights 数组（方向/点光，数量上限与打包格式随材质/灯光专档拍板，预估 16 盏）；
- **计算化**：v0.x 用图形全屏 Pass；tiled/clustered 计算光照为 P2 扩展点（插于光照 Pass 之前的计算 Pass）。

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
- **着色**：v0.x Forward 透明着色为简化模型（Lambert + 环境项）——**与 Deferred LightingPass 的光照模型一致性属已知取舍**，统一光照模型待材质专档拍板后回填两处；
- **输出**：混合态目标（混合管线=lightingRT LOAD；v0.1=backbuffer LOAD）。

### 4.6 PostProcessChain（结构落地，细节占位）

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
| shadowMap | D32_SFLOAT | 2048²（默认） | 全局（主光） | 常驻，灯光参数变化重建内容 | ❌ |
| GBuffer×3 | 见 4.2 | 视口大小 | 每视口 | 随视口创建，resize 重建 | ❌ |
| lightingRT | R16G16B16A16_SFLOAT | 视口大小 | 每视口 | 随视口创建，resize 重建 | ❌（直写 backbuffer） |
| 后处理中间 RT | 链内自定 | 链内自定 | 链持有 | 帧临时（RHI 17.4 别名，P1） | ⚠️ 空链无 |

### 5.2 规则

- **每视口独立 depth/GBuffer/lightingRT**（RHI 19.4-2：不可跨视口共用，否则互相污染）；
- resize → 重建 + 图缓存 structureHash 失效（RHI 23.6-3），由 FrameLoop 的 recreate 路径咬合；
- **命名规范**：`{ViewName}_{资源名}`（如 `Main_GBufferAlbedo`），对接 RHI `setDebugName`（13.3），RenderDoc 抓帧可直接辨认；
- 资源创建/销毁只走 RHI 资源生命周期（Cleaner 两帧规则），引擎不裸建 VkImage。

### 5.3 MSAA 抗锯齿（2026-10-06 拍板并落地，四片连做，已实测验收）

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
    PipelineProfile profile();                 // HYBRID / FORWARD_SIMPLE（预览视口用，14.5-5）
    void buildGraph(RenderGraphBuilder graph, PipelineContext ctx);
}

// buildGraph 的输入（引擎侧上下文，字段级定义归 Engine_Rendering_Design.md）
class PipelineContext {
    RenderView view;                    // 视口/相机绑定（视图矩阵由相机生成，Y 翻转入投影——已拍板）
    FrameContext frame;                 // 场景快照 + 各 RenderList + UBO1 写入句柄
    Texture backbuffer;                 // 本帧终点（单相机可不进 ctx，由 FrameLoop 包进 graphFactory）
    Texture depth;                      // 本视口深度
    Texture msaaColor;                  // 本视口 MSAA 颜色附件（null = AA 关；与 msaaDepth 成对，5.3）
    Texture msaaDepth;                  // 本视口 MSAA 深度附件（overlay 家族仍用 1x depth）
    // Deferred 阶段扩展：gbuffer×3 / lightingRT / shadowMap
}
```

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

- 主视口：HYBRID（v0.1 用 FORWARD 分支实现）；编辑器材质预览等：FORWARD_SIMPLE（直写目标、少 RT，RHI 14.5-5）；
- 每视口独立 `buildGraph` 调用；共享 backbuffer 的相机靠 LOAD 叠加自动串行（RHI 8 节依赖推导）。

### 6.4 与图缓存咬合

- 相机增删、Pass 列表组成变化、后处理链变化 → 结构变化 → 正常失效（RHI 18.5），**不做特殊优化**；
- 运行数据（相机矩阵、物体列表、清屏值）不参与 structureHash——每帧变、不触发重编译；
- 相机集合稳定的常规帧：整套多视口 Pass 链 + 屏障计划跨帧复用（RHI 19.4-5）。

## 7. uniform / 描述符通道约定（按 Pass）

通道划分已拍板（RHI 实现进度，2026-09-24）：**push = 每物件/每帧高频小量（model/tpf）；set0 UBO1 = 全帧共享引擎内置（camera/time/lights）；set1 = 材质贴图**。

| Pass | set0 UBO1 | push | set1 材质 |
|------|-----------|------|----------|
| GBuffer | camera（动态偏移切视口，19.4-1） | model/tpf | 基色/法线等贴图 |
| Lighting | camera + lights（+ tpf） | 一般不用 | 一般不用 |
| Forward（v0.1 不透明/透明） | camera | model/tpf | 材质贴图 |
| Shadow | light viewProj | model | 一般不用 |

- **shadowMap 描述符挂点**（set0 扩展 binding vs 独立集合）：待材质系统专档拍板，本文预留——影响 Lighting 与 Forward 的 set0 布局，属材质阶段决策；
- 动态 UBO 偏移（每视口/每物件切槽）沿用 RHI 19 节机制，**不引入 `setUniform(String, ...)` 字符串路径**（19.4-1 已否决理由）。

## 8. 实现路线（已拍板：实现先 Forward）

| 步 | 内容 | 验收 |
|----|------|------|
| 一 | Forward 两 Pass（OpaqueForward 直写 backbuffer + TransparentForward LOAD 叠加）+ 空后处理链 + 单相机省略 Composite；接 FrameLoop/renderFrame，多相机多物体 Demo（阶段⑥终验，Engine_Design 2.5） | 多相机分屏正确、透明排序正确、图缓存命中 |
| 二 | +ShadowPass；+GBuffer/Lighting 切换混合（`PipelineProfile` 开关切换两套 buildGraph 分支，同场景验证两路光照一致——4.5 取舍的回填点） | 阴影正确；混合/纯 Forward 可配置切换 |
| 三 | 后处理链具体化（Bloom 计算降采样链 + Tonemap；HDR 切换按 4.6） | 链插拔不破坏管线直通路径 |

每步独立的约束：不改动 RHI 已发布 API；新增引擎 Pass 仍走「声明读写 + 回调录制」。

## 9. 待定（P2）

- GBuffer 布局与材质/着色模型绑定（4.2 格式拍板）、灯光数量上限与打包、shadowMap 描述符挂点（第 7 节）；
- tiled/clustered 计算光照（LightingPass 计算化）、SSR 等光照后扩展 Pass 示例（插入 Pass 列表）；
- 单 Pass 多视口（RHI 8 节备选）、后处理中间 RT 别名内存（RHI 17.4，P1）；
- UI Pass 组、环境（天空盒/雾，Scene.environment）。
- MSAA 后置项（5.3）：stacking 相机/预览视口/分屏区域清屏的 MSAA 路径、运行期切换
  档位（PSO/附件失效协议）、FXAA 档、区域清屏与 resolve 组合重载、末 pass storeOp
  DONT_CARE 带宽优化。

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
