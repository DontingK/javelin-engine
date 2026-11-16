# 引擎 TAA 设计（Engine TAA Design）

> 版本：v0.7（2026-11-15，**TAA 四项收进 `TaaSetting` 条件子组：抗锯齿一行选中 TAA 才展开、组头可折叠、四项滑条**，见 §9.6；v0.6 = 子组条件显隐；v0.5 = 抗锯齿/锐化迁到相机、项目设置两组删除；v0.4 = 步骤一验收 + thinTAA 口径对齐；v0.3 = 去抖 + CR 重采样随实机修正，均见 §9.2）
> 定位：四期高质量渲染**施工序 ③**（`Engine_Roadmap.md` §4-3 下一项）——时域抗锯齿 TAA，收口「MSAA 已删（2026-11-13）→ AA 空窗至 TAA」这条既定路线（`Engine_PostProcess_Design.md` §4.2、`Engine_Backlog.md` P4-10）。
> 范围：**方案 + 拍板点 + 步骤一实装与验收 + 相机级迁移**（§6 十条拍板点按「建议默认」整套落地；实装记录与随实机修正见 §9；步骤二锐化见 §9.4）。
> **载体变更（v0.5）**：抗锯齿与其参数不再是项目设置（`TaaSettings` / `SharpenSettings` 已删除），而是**相机组件字段**——不同相机可以有不同的抗锯齿与锐化（对齐 Unity HDRP Camera Frame Settings）。

---

## 1. 目标与范围

**目标**：用「相机投影亚像素抖动 + 历史帧累积」的后处理 AA 取代已移除的 MSAA，让 HYBRID 档画面边缘不再有阶梯锯齿，且对 Deferred 全屏着色天然适配（MSAA 只对光栅化边缘生效，与 G-Buffer/全屏光照不合，2026-11-13 已拍板弃用）。

**本批做**（步骤一）：
- 相机投影**亚像素抖动**（Halton(2,3) 8 相，1 像素内）；
- **历史颜色缓冲**（per-view ping-pong，RGBA16F）；
- **重投影**（G-Buffer 世界位置 + 上一帧 jitter 后 viewProj 反投影）＋ **邻域颜色钳制**（防拖影）；
- **混合与失效触发**（首次 / resize / 切场景 / 相机跳变 / 开关切换）；
- 链上节点 `TaaPass` + 设置面（**v0.5 起改为 `Camera` 组件字段，见 §9.5**；当时的 `TaaSettings` + 项目设置窗口形态见 §3.8）+ 双语。

**本批不做**（§8 后置）：
- 锐化 pass（TAA 会糊，标准配锐化——放步骤二）；
- 逐物体运动矢量（速度附件 / 上一帧变换重建）；
- FORWARD 档支持（无 G-Buffer，另行立项）；
- YCoCg / 方差钳制升级、静态场景额外收敛加速。

---

## 2. 现状实证（方案依据，均为当期代码事实）

| 件 | 现状 | 出处 |
|----|------|------|
| 投影/视图矩阵 | `RenderCamera.recalculate()` 每帧重算：`view = lookAt`、`proj = perspective/ortho`（**Vulkan NDC、`zZeroToOne=true`、`m11` 取负做 Y 翻转**）、`viewProj = proj × view` | `RenderCamera.java:60-71` |
| 相机块写入 | `RenderingSystem.collectFrame` 逐相机 `recalculate()` → `computeShadow(cam)` → `uboBuffer.writeAligned(camOffset, cam.viewProj())`（set0 UBO1，256B 对齐动态偏移） | `RenderingSystem.java:808-816` |
| 灯/簇/阴影 | 同一循环内 `lightCollector.collect(..., cam.view(), cam.proj(), ...)`、`buildClusters`、`computeShadow(cam)` —— 全部消费**未抖动**矩阵 | `RenderingSystem.java:810-829` |
| G-Buffer | `gbufferAlbedoFor` = `R8G8B8A8_UNORM`（`a` = receiveShadow）、`gbufferNormalFor` = `R16G16B16A16_SFLOAT`、**`gbufferWorldPosFor` = `R32G32B32A32_SFLOAT`**（`w < 0.5` = 背景，`lighting.frag:14` 据此 `discard`） | `RenderingSystem.java:602-621`；`builtin-asset-core/lighting/lighting.frag` |
| 场景色 | `lightingRtFor(cam)` = `HDR_SCENE_COLOR_FORMAT`（`R16G16B16A16_SFLOAT`），per-view、交换链尺寸、resize 换绑 | `RenderingSystem.java:122/639/665` |
| 后处理链契约 | 节点 `addToGraph(graph, ctx, input, output)`：**input = 上一节点输出（链首 = lightingRT），output = 本节点输出**；返回不经链的调用直接报错 | `PostProcessNode.java:24-31` |
| 链序 | `orderedChain()`：非终节点按 `order()` 升序（同序保持注册序）→ 终节点（Tonemap）恒置链尾 | `AbstractRenderPipeline.java:131-148` |
| HDR 生效条件 | `usesHdrSceneTarget() = !postProcess.isEmpty()`；链非空 → 场景 Pass 写 `lightingRT`，`PipelineContext.sceneColorTarget()` 返回它 | `AbstractRenderPipeline.java:69-71`、`PipelineContext.java:62-73` |
| 每视口中间 RT | `postRtFor(cameraName, index)`（per-view `Map`，resize 换代、孤儿两帧退役） | `RenderingSystem.java:647` |
| 节点模板 | `SsaoPass`：覆盖制（不消费材质 Pass 声明）、全屏三角 VB、`uvRect` push 换算跨视口/半分辨率坐标、`descSetFor` 描述符缓存、`AbstractForwardPass.isRegionClear` 区域清屏 | `SsaoPass.java:160-330` |
| 组合根 | `PostProcessSetup`：id 常量（TONEMAP/BLOOM/SSAO）+ 惰性创建 + `setParams` 下发 + `isEnabled` 不触发创建 | `PostProcessSetup.java` |
| 设置面 | `GraphSetting`（`@Setting(label=graphics)` 页根）+ 四个 `@SettingGroup` 嵌套对象；字段级缺键默认由 `InputCapsule` 重载负责；`@Reflective` + `Serializable` | `GraphSetting.java`、`PostProcessSettings.java` |
| 下发入口 | `JavelinEngine.applyRenderSettings()`：读 `GraphSetting` → `PostProcessSetup.setParams` → 按开关增删链；**FORWARD 档 SSAO 忽略 + warn 一次**（照抄该形态即可） | `JavelinEngine.java:349-372` |
| 多视口 | Scene + Game 双视口各一条链、各自 `lightingRT`/RT 族（`Map<name, …>`） | `AbstractRenderPipeline.expandPostProcess`；`RenderingSystem` RT 族 |
| 编辑器 overlay | Gizmo / 调试线 / 拾取 / 调试视图是链**之后**的 overlay 制 Pass → 不被 TAA 处理 | `AbstractRenderPipeline.buildGraph`：链展开在最后一个场景 Pass 之后 |

**结论性事实**：
1. **重投影件齐全**——世界位置是 RGBA32F（精度足够亚像素重投影），且 `w` 天然携带「背景/几何」判据；
2. **抖动只能注入 UBO 写入点**——矩阵在 `collectFrame` 写 set0，`cam.viewProj()` 本身还被 CPU 侧（screenToRay、Gizmo 拾取）消费，**不能改 `RenderCamera` 内部矩阵**；
3. **历史 RT 需新开一族**——`postRtFor` 是「链中间 RT」语义（索引位与链序耦合），TAA 需要的是「跨帧常驻、自己 ping-pong」的缓冲，尺寸/生命周期同 `lightingRtFor`；
4. **矩阵进不了 push**——push 上限 80B，而 prevViewProj 一个矩阵就 64B，加 uvRect/参数必溢出 → 需自管参数 UBO（§3.6）。

---

## 3. 方案

### 3.1 总览：帧内位置

```
场景 Pass(GBuffer→Lighting→Skybox→Transparent 写 lightingRT，顶点用「抖动后 viewProj」)
   ↓ sceneColorTarget() = lightingRT（G-Buffer 世界位置/法线同步已按抖动栅格写入）
[链展开]  TAA(15) → SSAO(10)? 
```

> 链序按 `order()` 升序：**SSAO = 10 → TAA = 15 → Bloom = 20 → Tonemap（终）**。理由见 §3.6-1。
> 若 SSAO 关闭，链为 TAA(15) → Bloom(20) → Tonemap：TAA 直接对 lightingRT 做累积。

TAA 节点内部两步（§3.6）：

```
Pass A（TAA resolve）：input(含 AO) + 历史[prev] + worldPos → 写 历史[cur]（RGBA16F）
Pass B（copy）：历史[cur] → 链输出 output
```

**历史[cur] 与链输出同内容、差一个本帧抖动的亚像素偏移**（copy 去抖，§9.2-10），故「历史恒为 RGBA16F」不受链尾是否为交换链格式影响（§3.6-2）。

### 3.2 相机投影抖动（jitter）

**注入点**：`RenderingSystem.collectFrame` 的 set0 写入处——`cam.recalculate()` 之后、`computeShadow(cam)` 与 `lightCollector.collect` 使用**未抖动**矩阵（阴影级联拟合与簇构建绝不能被抖动污染，否则每帧阴影/簇都变、画面闪），只在写 UBO1 时用抖动版本：

```
jitteredViewProj = T_ndc(2·jx/W, 2·jy/H) × proj × view
```

- `T_ndc` = **NDC 空间平移**（左乘于 `proj × view` 之前的位置——即先 P·V 再平移 NDC），`jx/jy ∈ [-0.5, 0.5]` 像素，`W/H` = 本视口宽高（`cam.viewportWidth/Height`，抖动按**视口**算，多视口各抖各的）；
- 序列 = **Halton(2, 3) 前 8 相**（Babylon `thinTAAPostProcess` 默认 8，参考件 `doc/Babylon_Rendering_Reference.md` TAA 段），按「每视口 × 帧号」索引，帧号取 `frameIndex`（同一帧所有视口可共用相位，也可错相——共用即可，视口互不重叠）；
- 幅度参数 `jitterScale`（默认 1.0 = 全幅 1 像素内），设置面暴露（§3.8）；
- **实现注意（JOML 语义）**：`Matrix4f.translate()` 是**右乘**（`this = this × T`）。要「NDC 略前平移」必须写成 `new Matrix4f().translate(dx, dy, 0).mul(proj).mul(view)` 形式并在单测里断言「抖动 0 时与原 viewProj 逐元素相等」——本项列为步骤一单测项。

**抖动开关必须跟 TAA 开关走（铁律）**：TAA 不在链上时**不得抖动**——否则画面整体偏半像素且累积无收益。`RenderingSystem.setTaaJitterEnabled(boolean)` 由 `applyRenderSettings()` 按 TAA 开关置位（§3.8）。

**对既有系统的已知影响（可接受，逐条交代）**：

| 消费方 | 影响 | 处置 |
|--------|------|------|
| 阴影 CSM / cluster / 灯收集 | 无（用 `cam.view/proj`、`computeShadow(cam)` 前置） | 保持现状 |
| `Camera.screenToRay`（CPU 侧） | 射线与画面差 ≤ 1 像素 | 接受（Unity 同款误差量级） |
| GPU 拾取（PickPass 同用 set0 UBO1） | ID 缓冲微偏 < 1 像素 | 接受；hover 走 CPU 射线不受影响 |
| `Gizmo` 拖拽（CPU 射线） | 同上 | 接受 |
| 抖动关闭（TAA 关） | 与现状**逐像素等价** | 验收项（§5.2） |

### 3.3 重投影（逐像素运动矢量，相机级）

不建速度附件——用 G-Buffer 世界位置 + 上一帧 jitter 后 viewProj **反投影重建**（`Engine_PostProcess_Design.md` §4.2 已定的混合实现路线）：

```
wp = gbufferWorldPos(uv)                        // RGBA32F；w<0.5 = 背景
if (wp.w < 0.5) { out = currentColor; return; }  // 背景/天空：不混合（§4 边界 2/3）
prevClip = prevJitteredViewProj * vec4(wp.xyz, 1.0)
prevUV   = prevClip.xy / prevClip.w * 0.5 + 0.5   // 本引擎投影已含 Y 翻转（m11 取负）→ NDC y 向下与纹理 v 向下同向，不加额外翻转
prevUV 有效 = prevClip.w > 0 且 prevUV ∈ uvRect（本视口矩形）
```

- **坐标系口径**：源纹理（lightingRT / G-Buffer）与历史 RT 都是**全帧尺寸**（交换链尺寸），本视口只写 `viewport` 区域；片元侧一律 `uv = uvRect.xy + vUV * uvRect.zw`（照 `SsaoPass` 的 push 约定，`gl_FragCoord` 在分屏/半分辨率下不成立）；
- `prevUV` 用 **LINEAR** 采样历史（双线性轻微软化反而有利），采样器 `CLAMP_TO_EDGE`；
- `prevUV` 越出 `uvRect`（或 `w <= 0`）→ 历史无效，`alpha = 1`（直出当前帧）。

### 3.4 历史缓冲

`RenderingSystem.taaRtFor(cameraName, index)`（index = 0/1 ping-pong）：

- 格式 = `HDR_SCENE_COLOR_FORMAT`（RGBA16F，与 `lightingRT` 同）、尺寸 = 交换链尺寸 × 1x、per-view `Map<name, Texture[2]>`；
- **生命周期照 `lightingRtFor`**：惰性创建、resize 换代（`reconcile` 内与 G-Buffer/lightingRT 一起换代、旧纹理走 `retire` 两帧退役）、`dispose` 统一释放；
- 读写规则：本帧读 `hist[prev]`、写 `hist[cur]`，`cur = frameIndex & 1`（两帧前写过的缓冲本帧可安全覆盖——帧在飞数 ≤ 2，与 UBO1 `frameSlots` 同口径）。若 `frameSlots > 2`，则索引改为 `frameIndex % frameSlots` 更保险；本期取 `frameIndex & 1` 并**在实现时核对 `RenderingSystem.frameSlots` 值**（不一致则按 frameSlots 取模）。

历史有效性（CPU 侧每视口一个状态对象）：

| 字段 | 用途 |
|------|------|
| `prevJitteredViewProj`（Matrix4f） | 重投影矩阵（上一帧 jitter 后矩阵） |
| `prevFrameIndex` | 校验「上一帧真的渲染过本视口」（视口消失/重建 → 失效） |
| `valid` | 首次 / resize 换代 / 切场景 / 相机跳变 → false |

`collectFrame` 里每视口：`prev = cur; cur = 本帧 jittered 矩阵`（**先搬旧值再写新值**），TAA 节点在 `addToGraph` 期间读 `prev`。注意顺序：`collectFrame` → 管线 `buildGraph`，所以节点读到的必须已是「上一帧」。

### 3.5 混合、钳制与失效

**邻域钳制（防拖影的硬闸）**：对当前帧 `input` 取 **3×3 邻域**（半径 1 纹素，纹素步长按 `uvRect` 内的全帧像素算）求 RGB 分量 min/max → 历史色按此 AABB `clamp`。标准化后：

```
cur    = input(uv)
hist   = clamp( history(prevUV), min3x3(cur), max3x3(cur) )
alpha  = valid ? blendAlpha : 1.0        // blendAlpha = 1 - weight，weight = 历史权重
out    = mix(hist, cur, alpha)
```

- 默认 `weight = 0.88`（α ≈ 0.12），8 相序列约 8~16 帧收敛；参数面暴露（`@InsFloatRange(min=0.5, max=0.98)`）；
- **速度自适应**：`|prevUV - curUV|`（像素距离）超过 `1.5 × max(1, 邻域半径)` 时把 `alpha` 提到 ≥ 0.5（大位移下不信任历史，快速甩相机不拖糊），此判据在 shader 内单像素可算，无需额外通道；
- **钳制强度参数**（`clampScale`，默认 1.0）：AABB 按中心缩放（>1 放松、<1 收紧），给实机手感调节口。

**失效触发（全部在 CPU 侧，置 `valid=false`，效果等同 `alpha=1`）**：

| 触发 | 落点 |
|------|------|
| 首次启用 / RT 惰性创建 | `taaRtFor` 创建时 |
| resize（交换链重建） | `RenderingSystem.reconcile()` 换代时 |
| **切场景**（编辑器高频操作） | `JavelinEngine.setScene(...)` 调 `renderingSystem.invalidateTaaAll()` |
| 相机跳变（位置/朝向突变） | `collectFrame`：`prev` 与 `cur` 的 viewProj 逐元素差 > 阈值（如平移分量 > 场景尺度 5%、或旋转列方向差 > 5°）→ 单帧 full-alpha；阈值参数 `jumpThreshold`（默认宽松，只捕「传送/切相机」级跳变） |
| 视口上一帧未渲染 | `prevFrameIndex != frameIndex - 1` |
| TAA 关 → 开 | 首次自然失效（`valid=false` 初值），无需特殊处理 |
| 资产热重载（shader） | 颜色缓冲无关，不重置 |

> 灯快速变化（如主光强度动画）不需要重置——颜色变化由钳制吸收；只有「整幅几何/相机重定位」才允许残影风险。

### 3.6 节点形态与链序

**1. 链位置 `order() = 15`（SSAO=10 之后、Bloom=20 之前）**

- 在 SSAO 之后：AO 是半分辨率 + 世界位置采样，随像素抖动逐帧变化 → 由 TAA 时域累积把 AO 的采样噪声一并平滑（TAA 对所有像素生效，历史含 AO 结果）；
- 在 Bloom/Tonemap 之前：累积必须在 HDR 线性色域、Tonemap 之前（否则色调映射后的压缩会破坏时域一致性）；
- 若 SSAO 关闭，TAA 位于链首，直接读 `lightingRT`。

**2. 两步实现（Pass A resolve → Pass B copy）**

- **Pass A**：`addColorOutput(hist[cur], CLEAR)`；`addTextureRead(input)`、`addTextureRead(hist[prev])`、`addTextureRead(worldPos)`；viewport/scissor = 本视口；
- **Pass B**：`addColorOutput(output, CLEAR)`（区域清屏照 `AbstractForwardPass.isRegionClear`，同 SSAO 合成/Tonemap/Bloom 口径）；`addTextureRead(hist[cur])`；全屏直写；
- 为什么不用 MRT 单 Pass（output+hist 双附件）：链尾场景（只开 TAA、Tonemap 关闭）`output` = 交换链格式（RGBA8），与 RGBA16F 历史组成混合格式 MRT，需单独 PSO 分支且历史精度语义被链的组成牵制；**两 Pass 换一次全屏拷贝（1080p 约 8MB 写），实现与格式解耦**；
- 节点对链只暴露「一进一出」（`PostProcessNode` 契约零改动——`order()`/`isTerminal()` 默认值即可满足）。

**3. 资源面**

| 类型 | 内容 |
|------|------|
| 描述符集（resolve） | set0 = 不绑（不需要相机块，参数走自管 UBO）→ **仅 set1**：binding0 = dynamic uniform（参数块）、binding1/2/3 = `input`/`hist[prev]`/`worldPos` |
| 描述符集（copy） | 仅 1 个采样 binding（`hist[cur]`），沿用 SsaoPass 的「单采样布局」形态 |
| 参数 UBO | 每视口一块（128B × 帧槽数环形，`hostVisibleCoherent`），内容 = `mat4 prevJitteredViewProj`(64) + `vec4 uvRect`(16) + `vec4 params`(16)（x=alpha, y=jitterX, z=jitterY 像素, w=clampScale）+ `vec4 extra`(16)（x=valid, y=邻域纹素步长, z/w 预留）→ 112B，pad 到 128B |
| 采样器 | 历史 = LINEAR + CLAMP_TO_EDGE（自建）；`worldPos` 用引擎 `gbufferSampler()`（NEAREST，照 SSAO） |
| 全屏三角 VB / 布局 / PSO 缓存 | 照抄 `SsaoPass` 形态（VB 复用、`pipelines` 按 `kind:format` 缓存、`descSets` 按 `view|kind` 缓存并校验纹理身份） |

> 参数块写入口：`addToGraph`（每帧执行）里按 `ctx.frame().frameIndex()` 选帧槽写入；矩阵与 jitter 取自 `RenderingSystem` 的 prev/cur 缓存（§3.4）。
> **为什么不塞 push**：push 上限 80B < 64B 矩阵 + 必需参数；**为什么不动 set0 UBO1**：那是所有 PSO（含材质/shadow/GBuffer）共享的相机块布局，扩字段会牵动全链 PSO 与 `Engine_Uniform_Keys.md`。

**4. 着色器资产**（内置、走 SpvShader 导入链，照 SSAO 先例）：
`builtin-asset-core/postprocess/taa.vert`（全屏三角 + vUV）、`taa.frag`（resolve）、`taa_copy.frag`（copy），各带伴生 `.meta`（guid 单源口径）。

### 3.7 与管线的耦合面（零侵入）

- `TaaPass` 只实现 `PostProcessNode` → **不改 `AbstractRenderPipeline`、不改 `PostProcessChain`**（2026-11-14 解耦的直接收益，`Engine_PostProcess_Design.md` §9.7）；
- 组合根 `PostProcessSetup` 加 id `TAA` + 节点字段 + `setTaaParams(...)` + `setEnabled/isEnabled` 分支（照 SSAO 三处形态）；
- `JavelinEngine.applyRenderSettings()`：`TAA` 链路 = 读 `graph.getTaaSettings()` → `setTaaParams` → `applyPostProcessNode(TAA, enabled && !forward)`，FORWARD 档 warn 一次（照 SSAO 的 `ssaoForwardWarned` 形态）；同步 `renderingSystem.setTaaJitterEnabled(enabled && !forward)`。

### 3.8 设置面（**已被 §9.5 取代**：TAA / 锐化迁到相机组件，项目设置两组删除）

> 本节保留为实现当时的方案记录。**当前形态见 §9.5**：`TaaSettings` / `SharpenSettings` 两个类已删除，
> 参数改为 `Camera` 组件字段（`antialiasing` + 锐化强度/历史锐化/防闪烁/速度抑制四项，对齐 Unity HDRP）。

新 `TaaSettings`（`@Reflective` + `Serializable`，字段级缺键默认 = 字段初值，`InputCapsule` 自带默认值重载）：

| 字段 | 注解 | 默认 | 语义 |
|------|------|------|------|
| `enabled` | `@InsField(label="projectSettings.taa")` | `false` | 开关（仅 HYBRID 档生效，FORWARD 忽略 + warn） |
| `blendWeight` | `@InsFloatRange(0.5, 0.98)` | `0.88` | 历史权重（越大越稳、越糊） |
| `jitterScale` | `@InsFloatRange(0.1, 1.0)` | `1.0` | 抖动幅度（像素） |
| `clampScale` | `@InsFloatRange(0.5, 2.0)` | `1.0` | 邻域钳制强度 |
| `sharpen` / `sharpenStrength`（步骤二） | — | `false` / `0.4` | 锐化开关与强度（本批先不落字段，避免死开关——**红线 15 口径：未落地即不入面板**） |

- `GraphSetting` 加 `@SettingGroup(label="projectSettings.section.taa")` + 嵌套字段 + getter/setter + write/read（缺键保默认，照 `ssaoSettings` 四段）；
- 项目设置窗口**零改动**（注解驱动自动出「时域抗锯齿」组，`Editor_ProjectSettings_Design.md` v0.2 口径）；
- i18n 双语键：`projectSettings.section.taa` / `.taa` / `.taaBlendWeight` / `.taaJitterScale` / `.taaClampScale`（`editor.core` 两份语言包）。

### 3.9 落点文件清单

| 文件 | 改动 |
|------|------|
| `core/settings/TaaSettings.java` | 新建（§3.8） |
| `core/settings/GraphSetting.java` | 加组 + 四段读写 |
| `core/render/RenderingSystem.java` | ① jitter 注入 UBO1 写入处 + `setTaaJitterEnabled` + Halton 序列；② `taaRtFor(name, idx)` + 历史状态缓存（prev 矩阵/帧号/valid）+ `invalidateTaa(name)/invalidateTaaAll()`；③ prev/cur 矩阵与 jitter 的访问器；④ resize `reconcile` 失效 |
| `core/render/pipeline/pass/TaaPass.java` | 新建（§3.6） |
| `core/render/pipeline/PostProcessSetup.java` | `TAA` id + 节点 + `setTaaParams` |
| `core/JavelinEngine.java` | `applyRenderSettings` 接线 + jitter 开关 + `setScene` 失效 |
| `javelin-core/src/main/resources/builtin-asset-core/postprocess/` | `taa.vert` / `taa.frag` / `taa_copy.frag` + 三份 `.meta` |
| 编辑器 i18n | `en_US` / `zh_CN` 各 5 键 |
| 测试 | `TaaSettings` 往返/缺键默认；Halton 序列与「抖动 0 等价原矩阵」；prev/cur 搬运与帧号校验；`TaaPass` 资源生命周期（可选） |
| 文档 | 本档 v0.2（落码记录）／`Engine_Backlog.md` P4-10 状态／`Engine_Roadmap.md` 状态行／`Engine_PostProcess_Design.md` §4.2 回填指针 |

---

## 4. 边界与已知风险（须写进验收口径）

1. **运动物体拖影**：无逐物体运动矢量，重投影只按相机。快速平移/旋转的物体（细杆、快速旋转件）会后沿拖影——3×3 钳制 + 速度自适应 α 缓解，但**不根治**。接受并记档（§8 后置速度附件）。
2. **背景/天空像素**：`worldPos.w < 0.5` 直出当前帧（不混合）→ 天空盒自身**无 AA**；相机转动时天空盒几何边缘可能仍有轻微锯齿（天空盒是低频纹理映射，实机影响小）。可选升级（后置）：用相机旋转差做「无穷远平面重投影」近似。
3. **半透明物体**：透明 Pass 写 `lightingRT` 但不写 G-Buffer → 其像素按背景处理（直出当前帧）→ 透明物**无 AA 但也不污染历史**（无残影）。与现状（透明物本来就无 AA）一致。
4. **Gizmo / 调试线 / 拾取 overlay**：链后 Pass，不被 TAA 处理（与 Tonemap/Bloom 同口径）——它们自身无抗锯齿，编辑器口径接受。
5. **HDR 值域**：历史 RGBA16F；超高亮（Bloom 阈值以上）像素在钳制 AABB 内仍可累积，不会截断到 1.0。
6. **静止画面收敛**：α=0.12 时约 8~16 帧稳到亚像素级；抖动幅度 <1 像素，静止时不出现「呼吸感」（验收项）。
7. **多视口**：Scene/Game 各自独立历史、prev 矩阵、`uvRect`——互不串（验收项）。
8. **性能**：新增 = 2 次全屏写 + 2 张全帧 RGBA16F RT（1080p 每视口约 16MB）+ 3×3 邻域采样（9 taps）。预期 ≤ 0.5ms@1080p（验收记录帧时间，不设硬阈值）。
9. **失效遗漏即残影**：切场景/切相机是编辑器最高频操作，`setScene` 失效口**必须**接上（否则旧场景残影会被当成 TAA bug 报）。

---

## 5. 步骤划分与验收

### 5.1 步骤一（AA 生效最小闭环，全部落码后交实机）
jitter + 历史 ping-pong + 重投影 + 3×3 钳制 + 混合 + 失效（`setScene`/resize/跳变/帧号）+ `TaaPass` 两 Pass + 参数 UBO + `TaaSettings`（开关/权重/抖幅/钳制四项）+ 下发接线（含 FORWARD 忽略与 jitter 联动）+ 内置三 shader + 双语 + 单测三件。

### 5.2 步骤一验收清单（用户实机）
- **TAA 关**：与改前**逐像素等价**（AB 对比同场景静止截图）；UBO1 无抖动（`jitterScale` 生效前提是 TAA 开）
- **TAA 开**：静态场景边缘平滑，无锯齿闪烁、无「呼吸感」；放大看几何边缘呈亚像素过渡
- 相机缓慢平移/旋转：无拖影、无鬼影、无明显延迟感
- 快速甩相机：短暂糊（预期）但松手后 ≤ 0.5s 恢复锐利
- 有运动物体（旋转立方体/移动物）的场景：仅物体后沿有轻微拖影，地面/静态物无拖影
- **切场景**：无上一场景残影
- **resize**（拖窗口）：无黑边、无残留历史拉伸
- **双视口**（Scene+Game）：互不串（Game 视口历史不来自 Scene）
- SSAO/Bloom 同时开启：链序正确（AO → TAA → Bloom → Tonemap），无互相污染
- 编辑器 Gizmo / 调试线不被糊（overlay 在链后）
- **FORWARD 档**：开 TAA 被忽略 + `logs` warn 一次，画面与未开一致
- 重启编辑器：设置保持
- `logs/error` 零 ERROR

### 5.3 步骤二（锐化已落码，其余可选收尾）

**已落码（2026-11-15，P4-11）**：锐化 pass（`SharpenPass`）——**LDR 后（Tonemap 之后）+ 终节点**，算法照 Babylon `Shaders/fsr1Sharpen.fragment.fx`（FSR1 RCAS）移植。实装记录见 §9.4；**载体随后改为相机级**（`Camera.sharpenStrength`，项目设置「锐化」组已删，见 §9.5）。

**仍未做（可选收尾）**：YCoCg 方差钳制升级、静景收敛加速（静止时提高历史权重）、天空无穷远重投影。

---

## 6. 拍板点（10 条，均带建议；「动手」= 按建议默认）

| # | 项 | 建议 | 影响 / 备选 |
|---|----|------|-------------|
| 1 | 档位范围 | **仅 HYBRID 档；FORWARD 忽略 + warn 一次**（照 SSAO 先例） | 备选 = 前向补「深度重建世界位置」路径，工作量翻倍；前向档本就被定为「先不管」 |
| 2 | 链位置 | **TAA `order()=15`（SSAO 之后、Bloom 之前）** | 备选 = 最前（0，AO 落在 TAA 之后不被累积） |
| 3 | 抖动序列/幅度 | **Halton(2,3) 8 相、默认 1.0 像素上限（可调）** | 8 相 = Babylon 默认；16 相收敛更好但收敛更慢 |
| 4 | 重投影来源 | **G-Buffer 世界位置 + 上一帧 jitter 后 viewProj**（无速度附件） | 备选 = 深度重建（RHI 无深度采样路径，不可行）；速度附件 = 后置 |
| 5 | 钳制算法 | **3×3 min/max AABB（步骤一）**，YCoCg/方差钳制放步骤二 | AABB 简单有效；YCoCg 对高对比边缘更稳但多一次色彩变换 |
| 6 | 历史格式/尺寸 | **RGBA16F、交换链尺寸、per-view ping-pong 两张** | 备选 = RGBA8（掉 HDR 精度，不可取） |
| 7 | 节点形态 | **两 Pass（resolve 写历史 → copy 写链输出）** | 备选 = MRT 单 Pass（链尾 RGBA8 时需格式分支） |
| 8 | 背景/天空像素 | **直出当前帧（不混合）** | 备选 = 无穷远重投影（后置） |
| 9 | 运动物体 | **本批不做逐物体运动矢量，接受拖影 + 钳制缓解** | 速度附件 = 独立项（AST/管线档另立） |
| 10 | 锐化 | **步骤二再做**（本批不落 `sharpen` 字段，避免死开关） | 备选 = 本批一起做（+1 个全屏 pass） |

**铁律（非拍板项）**：
- TAA 不在链上时**禁止抖动**（`setTaaJitterEnabled` 与开关联动）；
- 阴影/簇/灯收集**永远**用未抖动矩阵；
- 切场景 / resize / 相机跳变 / 上一帧未渲染该视口 → 历史必须失效；
- 新增设置字段一律 `@Reflective` + 缺键默认 + 双语键，未落地字段不入面板（红线 15）。

---

## 7. 与邻档关系

| 邻档 | 关系 |
|------|------|
| `Engine_PostProcess_Design.md` §4.2 | TAA 的立项依据（替换 MSAA、挂本档链、前置件清单）——本档为其「方案件」，§4.2 表格的五件在本档 §3.2~§3.5 逐条落地 |
| `Engine_RenderPipeline_Design.md` §5.3 | AA 空窗（MSAA 已删）——TAA 落地即收口 |
| `Engine_SSAO_Design.md` | 同链先例：覆盖制、`uvRect`、G-Buffer 只读、HYBRID-only + FORWARD warn——`TaaPass` 全面照抄其资源形态 |
| `Engine_Shadow_Quality_Design.md` | 抖动不影响阴影（§3.2 消费方表）；CSM 的纹素吸附本就是「稳阴影」手法，与 TAA 抖动不冲突 |
| `doc/Babylon_Rendering_Reference.md` | TAA 段参考（抖动 8 相、history 采样、相机移动降权），外部检出 `D:\Workspaces\java\Babylon.js` `thinTAAPostProcess.ts` |
| `Engine_Uniform_Keys.md` | **不动**：TAA 参数走自管 UBO，set0/set1 布局零变化 |

---

## 8. 未做 / 后置（登记 `Engine_Backlog.md` 用）

| 项 | 归类 | 说明 |
|----|------|------|
| TAA 锐化 pass | 步骤二 | TAA 会糊，标准配锐化（LDR 后做，避免影响历史） |
| 逐物体运动矢量（速度附件） | 后置项 | 需新增「上一帧世界矩阵」通道或速度 RT；运动物体拖影根治项 |
| FORWARD 档 TAA | 后置项 | 无 G-Buffer；需深度重建世界位置或速度通道 |
| 天空「无穷远」重投影 | 后置项 | 相机旋转时天空盒边缘改善 |
| YCoCg / 方差钳制 | 后置项 | 高对比边缘稳定性升级 |
| 静景收敛加速（静止提高权重） | 后置项 | 手感优化 |

---

## 9. 实装记录（步骤一，2026-11-15）

### 9.1 落点与形态（实际代码，与 §3 的差异在此对账）

| 文件 | 实装要点 |
|------|----------|
| `core/settings/TaaSettings.java` | 新建：`enabled` / `blendWeight`(0.88) / `jitterScale`(1.0) / `clampScale`(1.0)，`@Reflective + Serializable`、字段非 final、`read` 缺键取字段初值（不走平读 0） |
| `core/settings/GraphSetting.java` | `@SettingGroup(label="projectSettings.section.taa")` 嵌套组（**排在阴影组之后 = 面板最末组**）+ getter/setter + `write`/`read` 四段（`read` 走 `readSerializable` + null 判保默认） |
| `core/render/RenderingSystem.java` | ① `writeCameraBlock(cam, offset, frameIndex)` 取代原两条 `writeAligned`：抖动只改 viewProj 一段（`view` 段原样）；② Halton(2,3) 8 相位值直接以「已减 0.5 的像素偏移」常量数组落地（`TAA_JITTER_X/Y`），相位 = `frameIndex % 8`；③ 抖动经 `applyTaaJitter`（`identity().translate(2·px/W, 2·py/H, 0).mul(viewProj)`，即 T×P×V）；④ `taaRtFor(name, 0/1)`（RGBA16F 全帧、per-view `#t0/#t1`）；⑤ `TaaHistory`（prev/cur 抖动矩阵 + `prevFrame`/`lastFrame`）；⑥ `taaPrevViewProj` / `taaHistoryValid(name, frameIndex)` / `invalidateTaa` / `invalidateTaaAll` / `setTaaJitterEnabled` / `setTaaJitterScale` / `frameSlots()`；⑦ `ensureDepthSize` 清 `taaRtTextures` + 作废历史帧号、`removeCamera` 走 `retireTaaRts` + 摘历史、`close` 释放纹理族 |
| `core/render/pipeline/pass/TaaPass.java` | 新建：两 Pass（`_TAA_Resolve` 写历史 / `_TAA_Copy` 写链输出）、`order()=15`、自管参数 UBO（每视口 `frameSlots × 256B`，set0 binding0 DYNAMIC + binding1/2/3 采样）、copy 单采样布局 + push `uvRect`、PSO/描述符集双缓存（纹理身份校验重建）、`close()` 释放自建资源 |
| `core/render/pipeline/PostProcessSetup.java` | `TAA` id + `taa` 字段 + `node`/`isEnabled` 分支 + `setClearColor` 同步 + `setTaaParams` |
| `core/JavelinEngine.java` | `applyRenderSettings`：`setTaaParams` + `setTaaJitterScale` + （链成员段）FORWARD warn 一次 + `applyPostProcessNode(TAA, enabled && !forward)` + **`setTaaJitterEnabled(taaActive)`（铁律：不在链上必停抖）**；`setScene` 调 `invalidateTaaAll()` |
| `builtin-asset-core/postprocess/taa.vert` / `taa.frag` / `taa_copy.frag` | 三件新建 + 三份 `.meta`（guid 新生成）；`taa.frag` 参数块 std140 = mat4(0,64)+vec4 uvRect(64)+vec4 params(80)+vec4 extra(96) 实需 112B；3×3 邻域 min/max 钳制 + `smoothstep(1,4,Δtexel)` 速度自适应降权 |
| 编辑器 i18n | `projectSettings.section.taa` / `.taa` / `.taaBlendWeight` / `.taaJitterScale` / `.taaClampScale` 五键 × 双语 |
| 测试 | `JavelinSettingTest` +2（`taaSettingsRoundTrip` / `taaSettingsDefaultsRemainWhenNestedKeyMissing`）；`render/TaaJitterTest`（相位域/循环、幅度 0 不抖、抖动 0 与原矩阵逐元素相等、1 像素 = 2/W 剪辑空间位移） |

### 9.2 随码细化的实现决定（§3 未写到、落码时定的）

1. **参数块定尺**：std140 实需 112B，动态偏移步长取 256（`minUniformBufferOffsetAlignment` 上限）、描述符 range 取 128（16 对齐）——每视口一块缓冲 × `frameSlots` 槽，槽号与 UBO1 同奇偶。
2. **抖动矩阵写法安全化**：以 `identity().translate(...).mul(viewProj)` 起手（identity 前置使 JOML 的 translate 前后乘语义**都不影响结果**），并把该逻辑抽成 `RenderingSystem.applyTaaJitter` 包内静态方法 —— 「抖动 0 与原矩阵逐元素相等 + 1 像素 = 2/W」由 `TaaJitterTest` 锁定（红线 4 的 API 不确定性用单测兜住，不靠猜）。
3. **重投影 UV 不额外翻转**：`prevUV = prevClip.xy / w * 0.5 + 0.5`（投影已含 Y 翻转），与 `uvRect` 同左上原点口径（照 SSAO）。
4. **历史可用判据**：`prevFrame == frameIndex - 1`（`TaaHistory.lastFrame/prevFrame` 每帧滚动）；首帧 / resize（帧号作废）/ 切场景（`invalidateTaaAll`）/ 上帧该视口未渲染 一律直出当前帧。相机跳变未单独检测——重投影位移由「出视口直出 + 速度自适应降权」覆盖（后续若实机发现跳变拖影，再补逐元素矩阵差阈值，登记为观察项）。
5. **Ping-pong 取值**：`current = frameIndex % 2`、`previous = 1 - current`（前提是 `frameSlots == 2`，与 `FrameLoop` 现状一致；参数块分槽仍按 `frameSlots()` 取模，两者互不影响）。
6. **钳制强度语义**：AABB 以 3×3 邻域中轴为准、半幅按 `clampScale` 缩放（>1 放松、<1 收紧）——不是对 min/max 直接缩放，避免整体偏移。
7. **着色器采样器分工**：场景色 NEAREST（1:1 取点 + 3×3 纹素偏移）、历史 LINEAR（重投影 UV 非整数）、世界位置沿用引擎 `gbufferSampler()`（NEAREST）。
8. **锐化字段本批未落**（避免「设置面板有开关但无实现」的死开关），归步骤二 `P4-11`。
9. **描述符集按 ping-pong 代常驻（随实机崩溃修正，2026-11-15）**：首版按「纹理身份变了就重建 set」的通用缓存形态（照 `SsaoPass.descSetFor`）——但 TAA 的历史/源纹理**逐帧交替身份**（t1→t0→t1…），于是每帧 `createDescriptorSet` + `dispose` 旧的，描述符池内存持续累积，运行一段时间即 `VkResult=-1000069000`（`VK_ERROR_OUT_OF_POOL_MEMORY`，报在 `copySetFor` → `VulkanDevice.createDescriptorSet`）。修法 = **按 ping-pong 代（`current`）各常驻一份 set（每视口 resolve×2 + copy×2），稳态零分配**；仅当纹理对象真的换代（resize / G-Buffer 换代 / 链输入因 SSAO 开关变化）时才原地 `set.update` 重绑采样 binding，不 create/dispose。附带收益：两代 set 交替使用，避免「update 正在被在飞命令引用的同一 set」。
10. **copy Pass 去抖动（随实机「整屏跳动」修正，2026-11-15）**：首版整条链只做「抖动渲染 + 重投影取历史」，从未把**本帧抖动从输出里去掉——resolve 的重投影只对齐**上一帧（`prevViewProj` 把世界点投回上帧抖动栅格取历史，混合结果写回的像素网格语义仍是**本帧抖动栅格**），copy 又是 1:1 直拷 → 最终输出 = 理想画面整体平移 j本帧，j 随 Halton 8 相逐帧变（相邻相位差峰值 ~0.9px）= **整屏跳动**（静态相机即复现，与历史有效性无关）。修法 = copy Pass 采样 `+j` 去抖（`理想[uv] = 历史[uv + j·texel]`，即画面平移 −j 锚回未抖动网格）：① `RenderingSystem.taaJitterFor(frameIndex, out)` 访问器暴露本帧抖动像素（关闭时恒 0，copy 退化直拷）；② copy push 扩 32B（uvRect + vec4 jitter）；③ `taa_copy.frag` 按 `textureSize` 换算 UV 偏移采样 + 夹回 uvRect（边缘半像素防漏采样）。**resolve/重投影/钳制零改动**（它们互相本就是对齐的）；该位置还能让背景/天空/历史失效帧（resolve 内直出当前帧、同锚抖动栅格）被统一去抖。教训归档：TAA 的抖动必须「哪里进哪里出」——注入在 UBO1 的 viewProj，去除就必须落在消费该栅格的输出点上。
11. **历史重采样换 Catmull-Rom 9-tap（随实机「整体偏糊（含纹理）」修正，2026-11-15，用户拍板 CR 先行、锐化仍留步骤二）**：去抖修完后画面稳定但整体偏糊——主糊源 = **历史累积链的低通复合**：resolve 每帧把历史按亚像素偏移（相邻相位差最大 ~0.6 纹素）**双线性**重采样一遍、0.88 权重混入，静态场景下三角核逐帧复合成 ~1 纹素等效低通，纹理高频被洗掉。修法 = `taa.frag` 历史采样从单次双线性换 **CR 9 双线性取数**（tap1+2 权重恒正且和 ≥1 合并为一次取数；4 个角落乘积一并取，**非近似**——公式从 CR 核（B=0,C=0.5）自推，并对 16-tap 分离卷积真值做 2 万点数值验证逐点一致，不抄网上流传半纹素歧义版本）；负瓣把被平均掉的高频部分抵回来，累积核从低通变带通锐化型。附带边界：CR 取数可越出 uvRect 至 2 纹素（双线性时 ≤1），多视口接缝处跨视口渗色宽度 1px→2px，接受（CLAMP_TO_EDGE 兜底）。**copy 去抖仍用双线性**（不回馈历史、不复合，单次 ≤半纹素，换 CR 反而负瓣在最终画面上易可见振铃）——若实机仍嫌软，下一档就是锐化 pass（P4-11，步骤二原计划）。
12. **口径对齐 Babylon thinTAA（实机「抖 / 糊」两轮后的定型，2026-11-15）**：核 `postprocess/taa.fragment.fx`（全文只有 min/max clamp + `mix(h, c, factor)`）与 `thinTAAPostProcess.ts`（`factor` 默认 0.05、`disableOnCameraMove` 默认 true、`clampHistory` 默认 **false**、抖动幅度 `_hs.setDimensions(W/2, H/2)` 即**半像素**），据此改四点：
    - **钳制默认关闭**：`clampScale` 默认 0，shader 里 `params.y > 0` 才做 3×3 AABB。实机已坐实——低对比区 AABB 极差极小 → 历史被钳成当前帧 → 退化为直出，亚像素抖动直接现形（整屏抖的主因）。
    - **相机移动帧不累积、不抖动**：`TaaHistory` 增 `curRawViewProj`（上一帧未抖动 viewProj）+ `cameraMoved`（逐元素容差 `TAA_MOVE_EPS=1e-5` 比较，首帧视为 moved）；移动帧 `taaHistoryValid=false`（直出）且 `writeCameraBlock` 不抖（对应 `disableOnCameraMove`）。
    - **抖动幅度默认 0.5 像素**（`TaaSettings.jitterScale` 由 1.0 改 0.5），与 thinTAA 同量级，满像素会把画面拉糊。
    - **历史权重默认对齐 thinTAA**（`blendWeight` 0.88 → **0.95**；Babylon `factor` 0.05 是**当前帧权重**，本项目字段是历史权重，故 `blendWeight = 1 - 0.05`）。
    - **copy 去抖按「实际施加」的抖动**：新增 `RenderingSystem.taaAppliedJitter`，`taaJitterFor` 返回它而非按相位现算——移动帧/关闭 TAA 时恒 0，避免 copy 平移一张本来没抖的画面（凭空糊一层）。
    - 速度自适应降权阈值收紧到 `smoothstep(0.5, 2.0, disp)`（位移稍大就偏信当前帧，压运动拖影糊）。

### 9.3 验收口径与结论（口径见 §5.2 十三条）

当前状态 = **✅ 全件已验收（2026-11-15 用户实机）**：步骤一经四轮实机反馈修正（描述符池耗尽 §9.2-9；整屏跳动 §9.2-10；累积糊→CR 历史采样 §9.2-11；thinTAA 口径对齐 §9.2-12）后通过；步骤二锐化（§9.4）与相机级迁移（§9.5）、Inspector 参数组形态（§9.6）同日通过。可选剩余三项（YCoCg 方差钳制 / 静景收敛加速 / 天空无穷远重投影）留在 `Engine_Backlog.md` P4-11 尾注，随后续排障按需接入。

### 9.4 步骤二：锐化 pass（P4-11，2026-11-15 落码并验收）

**口径来源**：`D:\Workspaces\java\Babylon.js` 的 `Shaders/sharpen.fragment.fx`（4-tap 十字 unsharp，`edgeAmount` 默认 0.3）与 `Shaders/fsr1Sharpen.fragment.fx`（FSR1 **RCAS**）；位置取自 `defaultRenderingPipeline.pure.ts` 的 `_buildPipeline` 顺序——`Bloom → imageProcessing（tonemap）→ **sharpen** → grain → chromaticAberration → FXAA`，即**锐化在 Tonemap 之后（LDR）、FXAA 之前**。

**选 RCAS 不选 unsharp 的理由**：lobe 由环极值定向求取并限幅（`FSR_RCAS_LIMIT=0.1875`），只会往「本来更锐的方向」推且受局部对比度约束——高对比边缘不振铃、暗部少放噪；成本与 unsharp 同为单 pass 3×3。

**形态**：
- `SharpenPass` 为**终节点**（`isTerminal()=true`）→ 恒写显示目标，**接管链尾清屏**（`isRegionClear` + CLEAR + scissor，语义自原 `TonemapPass` 平移）；
- `TonemapPass` **降级为普通节点**（`isTerminal()=false` + `order()=40`）→ 锐化在链上时它写 `postRtFor` 中间 RT（RGBA16F 存 LDR 值）。`isTerminal` 全项目仅 `orderedChain` 消费，降级零外溢；
- 链序解耦注册顺序：`expandPostProcess` 按「链内最后一个节点写 colorTarget」判定，故**锐化关闭时链尾自动回落 Tonemap 直出**（此时 Tonemap 仍是最后节点，直出语义与降级前一致）；
- 参数走 push `vec4 params`（x = strength，0~1 直接作 `con.x`；Babylon CPU 侧 `con = 2^(-sharpness stop)` 的换算在本实现中被「直接暴露归一化系数」取代，1 = 最强）；
- set0 单纹理布局 + NEAREST/CLAMP 采样器（RCAS 按整像素 `texelFetch` 邻域，1:1 取数）；PSO 按输出格式缓存；输入描述符集按视口 + 纹理身份缓存（中间 RT 只在 resize 换代，非逐帧交替——无 TAA 那个池爆坑）；
- `SharpenSettings`（`enabled` 默认 false / `strength` 默认 **0.87** = Babylon FSR1 `sharpnessStops` 0.2 的换算 `con = 2^-0.2 ≈ 0.871`）+ `GraphSetting`「锐化」组 + `PostProcessSetup.SHARPEN` + `JavelinEngine.applyRenderSettings` 链成员 + i18n 双语 3 键 + 设置往返/缺键默认两条单测；
- **两档通用**：锐化不读 G-Buffer，FORWARD 档同样生效（与 SSAO/TAA 不同，无 warn 分支）。

**已知边界**：Tonemap 关闭而锐化开启时，锐化读到的是未压高光的 HDR 值 → 画面过曝（与 Babylon 关 postprocess 同类的配置责任，不做强制联动）。

### 9.5 相机级迁移（Unity HDRP 口径，2026-11-15 落码并验收）

> **验收**：2026-11-15 用户实机——选中相机改「抗锯齿 = TAA」即生效、切回 OFF 后处理链回落，
> 不同相机各自一套参数成立（per-camera 后处理确认）。

**拍板**（用户：「项目设置里的 TAA 组和锐化组直接删除，搬到相机上。也就是说不同相机可以有不同的后处理」）：抗锯齿是**相机**的事，项目设置不再有 TAA / 锐化两组。

**参数集 = Unity HDRP TAA 面板四项**（用户给定），与现有实现的映射：

| Unity 参数 | 实现落点 | 默认（等值于已验收行为） |
|---|---|---|
| Sharpen Strength | `SharpenPass` 的 RCAS strength（per-view push） | 0.87 |
| History Sharpening | `taa.frag` 历史重采样核：0 = 双线性，1 = 全 Catmull-Rom（`params.z`） | 1.0 |
| Anti-flickering | `taa.frag` 3×3 AABB 钳制强度（`params.y`）0→2 映射 | 0（关） |
| Speed Rejection | `taa.frag` 位移降权起始阈值（`params.w`，纹素） | 1.0（= 0.5 纹素起降权，等价 `smoothstep(0.5, 2.0)`） |

**不再暴露的两项**：历史权重固定 0.95、抖动幅度固定 0.5 像素（`RenderingSystem` 内部常量）——Unity 面板里也没有这两个旋钮，且它们已随实机定型。

**载体与透传链**：
- `Camera` 组件（场景内、Inspector 可编辑、序列化）：`antialiasing`（`AntialiasingMode`：`OFF` / `TAA`，默认 `OFF`，旧场景缺键保默认）+ 条件子组 `taaSetting`（`TaaSetting`：`sharpenStrength` 0.87 / `historySharpening` 1.0 / `antiFlickering` 0 / `speedRejection` 1.0，四个 `@InsFloatRange(0~1)`）——**选 TAA 才展开**，见 §9.6；
- `SceneRenderBridge.sync` 每帧把上述五项同步进 `RenderCamera`（与 fov/clearFlags 同一处）；
- `RenderingSystem.writeCameraBlock`：按**本相机**写参数快照（`TaaHistory.taaEnabled/…`）并决定抖动；`taaJitterFor(cameraName, out)` 按相机取实际抖动（多相机各视口不同，不再共用全局暂存）；`taaHistoryValid` 增加「**上一帧也启用 TAA**」判据（关闭期间历史 RT 不更新，帧号连续也不能用，`TaaHistory.prevTaaEnabled`）；
- `TaaPass`：本视口未启用 → **只挂一次直拷**（source = 链输入、NEAREST、抖动 0），不抖动、不累积、不建历史 RT；启用时照旧 resolve + copy；
- `SharpenPass`：强度按视口取（`rs.sharpenStrengthFor(view)`），未启用时 0；`sharpen.frag` 在强度 0 时显式短路直出中心像素（避免 RCAS 近似倒数的极色偏）；
- **链成员每帧同步**（`JavelinEngine.syncCameraAntialiasing`，在 `collectFrame` 之后调）：任一相机启用 TAA → 挂 `TAA` + `SHARPEN` 两节点（锐化是抗锯齿配套，同生同灭），否则摘掉；`setTaaJitterEnabled(anyTaa && !forward)` 仍作全局闸，FORWARD 档 warn 一次。

**删掉的东西**：`TaaSettings` / `SharpenSettings` 两个类文件、`GraphSetting` 的 `taaSettings` / `sharpenSettings` 字段与读写、`PostProcessSetup.setTaaParams/setSharpenParams`、`RenderingSystem.setTaaJitterScale`、i18n 六键（`projectSettings.section.taa|taa|taaBlendWeight|taaJitterScale|taaClampScale|section.sharpen|sharpen|sharpenStrength`）、`JavelinSettingTest` 四条 TAA/锐化往返与缺键用例。旧设置文件里的这两个键读时静默忽略（`GraphSetting.read` 不再请求）。

**新增 i18n**：`ins.camera.antialiasing` / `ins.camera.sharpenStrength` / `ins.camera.historySharpening` / `ins.camera.antiFlickering` / `ins.camera.speedRejection` + `ins.enum.antialiasing.off|taa`（双语）。

**单测**：`CameraTest.antialiasingDefaultsMatchAcceptedBehavior`（默认值与 setter 往返、null 归一 `OFF`）。

**验收（2026-11-15 通过）**：相机切到「时域抗锯齿（TAA）」后画面与既有效果一致；切回「关闭」完全无抖动/无累积/无锐化且不建历史 RT；两台相机一开一关各自正确（多视口）。

### 9.6 参数组的 Inspector 形态（`@InsFieldGroup` 条件子组 + 折叠组头 + 滑条，2026-11-15 落码并验收）

**诉求**（用户给的形态）：抗锯齿一行下拉，选了 TAA 才在其下展开四项参数，选 Off 时整组收起：

```java
@InsField(label = "ins.camera.antialiasing")
@InsFieldGroup(keys = {"OFF", "TAA"}, value = {"", "taaSetting"})
private AntialiasingMode antialiasing = AntialiasingMode.OFF;

private TaaSetting taaSetting = new TaaSetting();
```

**语义**：`keys[i]` = 控制字段的 wire 值，`value[i]` = 命中时显示的同宿内容器字段名，空串 = 不显示。容器字段**不贴 `@InsField`**（它不单独成行，只作为条件子组内联）。keys/value 必须等长，嵌套深度上限 3。

**实现**（编辑器侧，`Inspector_Design.md` v0.27）：
- `InspectorMeta` 递归把 `TaaSetting` 的四个 `@InsField` 字段并入 `Camera` 的字段清单，`fieldName = "taaSetting.sharpenStrength"`（回写定位同一串），`hostPath` 记容器路径，`parentField` / `showKeys` 记显示条件；
- `PropMeta.get/set` 先解引用容器实例（`readPath`）再读写；容器为 null 抛异常（红线 15，不静默兜底）；
- `ComponentSection` 按父 `JeSelect` 的选中值成对显隐组内 label 与控件（GridBag 忽略不可见组件 → 整行塌缩），父值一变即时生效。

**序列化**：`Camera.write` 写 `taaSetting`（`TaaSetting implements Serializable` + `@Reflective`，四标量直传）；`read` 缺键保默认，并兼容相机级首版把四项平铺在本组件上的档（键在则迁移进组）。

**面板形态**：四项均标 `@InsSlider` → 「滑条 + 数字框」（`Inspector_Design.md` v0.27 的 `JeSliderField`），
刻度按 `@InsFloatRange(0~1, step 0.01)` 换算成 100 格，拖动即提交（等于 Unity 的滑块手感）。
组头 `label = "ins.camera.taaSetting"` → 四项上方一行可点击的「▾ TAA 设置」折叠头（`Inspector_Design.md`
v0.28），点一下即收起/展开；抗锯齿选 OFF 时整组（含组头）隐去。

---

> （注：部分内容可能由 AI 生成）
