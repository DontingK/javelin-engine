# 引擎 SSAO 设计（Engine SSAO Design）

> 版本：v0.6（**2026-11-21 噪点修复（BUG-12）用户实机收口**；v0.5 = 该修复落码待验收；v0.4 = 步骤一/二 2026-11-14 用户实机验收通过——整项关账）
> 日期：2026-11-21
> v0.6 变更（2026-11-21）：**§10 降噪重做验收收口**——用户实机反馈「看起来比之前好，先这样」：斜视面/墙角颗粒明显减轻、接触遮蔽保留；按「先这样」收口，不再追加调参。**BUG-12 从 `Engine_Backlog.md` 移出**；未做项（采样数可配 16/32/64、深度感知上采样）转 `Engine_Backlog.md` **REND-22** 后置（DDGI 落地后 SSAO 降为「接触 AO」时再评估）。
> v0.5 变更（2026-11-21）：**降噪重做（BUG-12，见 §10）**——①模糊改**可分离两遍**（同一支 `ssao_blur.frag` 录水平/竖直两遍，各 9 tap，支撑面由 5×5 扩到 9×9，新增第三个半分辨率 AO RT `index 2`）；②双边保边容差 `sigmaZ` 改**自适应**（`max(radius×0.5, 1.5×本轴中心差分视 z 步进)`）；③AO 的深度偏置改**自适应**（`max(bias, 1.5×邻域视 z 步进)`，倍率为内部常量经 push 传参、不占设置面）；④AO RT 由 `R8_UNORM` 升 **`R16_SFLOAT`**（消 8 位量化台阶）。**覆盖本档原口径**：§3.3 的「单 Pass 5×5 双边」（改为两遍）、§3.4 的格式（`R8_UNORM` → `R16_SFLOAT`）、§7 拍板点 4/10（同一结论，形态已变更）。未做：采样数可配 16/32/64（按实机反馈决定）。

> v0.4 变更（2026-11-14）：**用户实机验收通过**（「看起来没问题了」）——步骤一（骨架 + AO 计算）+ 步骤二（双边模糊消噪 + 三参数接设置窗口）同日验收关账；已入 `Engine_Roadmap_Progress.md` §7 批次索引 + `Engine_Features.md` §2.4 验收状态，Backlog P4-02 移出。
> v0.3 变更（2026-11-14）：**步骤二落码**（同日）——双边模糊 Pass（消半球核噪声，写 `ssaoRtFor(view,1)`）+ SSAO 参数接设置（新 `SsaoSettings` 嵌套对象 + 项目设置窗口三行）；保边权改**视空间 z 差**（§9.2 第 3 条，掠射面下世界位置距离会拒绝平滑）。
> v0.2 变更（2026-11-14）：用户「继续」= 按 §7 建议默认开工 → **步骤一落码**（`order()` 排序声明 + AO 资源族 + `SsaoPass` AO/合成两 Pass + 组合根/开关/项目设置勾选），落点与两处步骤划分差异见 §9。未编译、未提交（红线 1/13）。
> 说明：四期第 2 项（`Engine_Roadmap.md` §4-2「SSAO——GI 第一步，零新底座」）的设计方案；施工序 **①**（四期当前最高优先级主线首项）。
> 职责边界：
> - 本档管 **SSAO 域**（算法形态、Pass 结构、AO 资源、参数面、节点在链中的位置与排序）；
> - `Engine_PostProcess_Design.md` 管链的承载与展开机制（`PostProcessNode`/`PostProcessChain`/`PostProcessSetup`/展开点）——本档只**复用**，不改机制（新增的 `order()` 排序声明是本档唯一增量契约，理由见 §3.5）；
> - G-Buffer 格式与写入契约归 `Engine_RenderPipeline_Design.md` §4.2 / `Engine_Material_Design.md` §4.2（`Pass GBuffer`）；
> - 参数设置面（`PostProcessSettings` / 项目设置窗口）归 `Engine_PostProcess_Design.md` §9.8 / `Editor_ProjectSettings_Design.md`，本档只登记新增字段；
> - 天空盒环境光（P4-04）、探针 GI（P4-05）不是本档范围——SSAO 是它们之前的第一步，二者将来替换/叠加 AO 的方式归各自档。

---

## 1. 范围与目标

**目标**：让物体接触处、凹角、缝隙出现**接触阴影（AO）**，把「只靠直射光 + 常量 ambient」的平贴画面做出空间层次感；这也是 GI 路线的第一步（`Engine_Lighting_Design.md` §10：SSAO 为 GI 第一步，探针 GI 后续接管环境项真值）。

**本期做**：

1. **`SsaoPass`（链中节点，`implements PostProcessNode`）**——单节点内录制 3 个覆盖制全屏 Pass（AO 计算 → 双边模糊 → 合成）；
2. **AO 资源族**——`RenderingSystem.ssaoRtFor(cameraName, index)`（per-view、半分辨率、惰性创建、resize 换绑、`removeCamera` 回收），照 `bloomRtFor` 同模式；
3. **输入面**——读 G-Buffer 的**世界法线 + 世界位置**（不读深度，理由见 §3.1）；
4. **参数面**——独立开关（`JavelinSetting.ssao`，默认关）+ 三参数（`radius`/`bias`/`intensity`）接 push 常量与编辑器「项目设置」窗口（照 Bloom 先例）；
5. **节点排序声明**——`PostProcessNode.order()`（默认 0，稳定升序；`isTerminal()` 仍恒置链尾）——保证 SSAO 恒在 Bloom 之前（理由见 §3.5）。

**本期不做**：

- **深度采样路径**（RHI 缺口三条，§3.1）——不做，也不新增/修改 RHI；
- HBAO / GTAO / 地平线搜索类算法（本篇是经典半球核 SSAO，升级另立项）；
- AO 只作用不透明物体的严格版（需深度采样；v1 全屏乘的边界见 §4.2）；
- AO 与探针 GI 的融合策略（归 P4-05）；
- AO 中间 RT 的内存别名（RHI-04 后置）。

---

## 2. 现状（代码实证，2026-11-14）

方案立在既有结构上，先固化事实（均可在代码核到；括号内为落点，供落码时对齐）：

| # | 事实 | 落点 |
|---|------|------|
| 1 | **后处理链契约已就绪**：`PostProcessNode extends RenderPass`（四参 `addToGraph(graph, ctx, input, output)`、`isTerminal()`、`setClearColor()`）、`PostProcessChain`（`addLast/remove/clear/isEmpty/nodes`）、组合根 `PostProcessSetup`（按 id 惰性创建 + `setEnabled`/`isEnabled`，**`isEnabled` 不触发创建**） | `core/render/pipeline/PostProcessNode.java`、`PostProcessChain.java`、`PostProcessSetup.java` |
| 2 | **链展开在管线基类**：`AbstractRenderPipeline`——链首 input = `ctx.sceneColorTarget()`、链尾 output = `ctx.colorTarget()`、中间节点 output = `rs.postRtFor(view, i)`；`orderedChain()` = 非终节点保持**注册序** + 终节点置尾。**新增后处理节点只改组合根，管线基类零改动** | `AbstractRenderPipeline.java:109-140` |
| 3 | **多 Pass 单节点先例**：`BloomPass` 单节点内录 8 个覆盖制全屏 Pass，中间级全写自管 RT（`bloomRtFor`），对链只暴露「input→output」 | `core/render/pipeline/pass/BloomPass.java` |
| 4 | **跨视口/多数尺寸坐标必须走 push `uvRect` + 顶点 `vUV`**（`gl_FragCoord` 只在 1:1 全屏成立）：`uvRect = (vp.x/fullW, vp.y/fullH, vp.width/fullW, vp.height/fullH)`，片元 `uv = uvRect.xy + vUV*uvRect.zw`；各 Pass 的 viewport/scissor 随级数缩放 | `BloomPass.java:170-178`、`bloom_composite.frag` |
| 5 | **G-Buffer 可采样面**：`gbufferNormalFor`（`R16G16B16A16_SFLOAT`，**世界空间**归一法线）、`gbufferWorldPosFor`（`R32G32B32A32_SFLOAT`，xyz=世界位置、**w=有效标志：几何 1.0 / 清屏 0.0**）；`gbufferSampler()`（NEAREST/CLAMP）；三张 usage 含 `SAMPLED` | `RenderingSystem.java:551-578`；写入侧 `Lit.shader`、`GBufferPass.java:53-55` |
| 6 | **自建采样描述符集的先例**：`GBufferViewPass`（编辑器 overlay）自建单 set 三采样器 + push `int mode` + `addColorOutput(target, LOAD, null)`，**不用 set1**；LightingPass 则读 set1 binding5/6/7 | `GBufferViewPass.java:128-134/198-221`、`LightingPass.java:145-166` |
| 7 | **set0 相机块**：`CameraBlock { mat4 viewProj; mat4 view; }`（偏移 0/64，描述符 range/动态偏移步长 256B）；**stageFlags = `ALL_GRAPHICS`**（坑 #35 修正后，fs 可读）；**只有 viewProj 与 view——无 proj / invProj / invView / near / far** | `Engine_Uniform_Keys.md` §3；`RenderingSystem.frameSetLayoutDesc()`；绑定先例 `LightingPass.java:163` |
| 8 | **相机 UBO 缓冲句柄不对外**：只暴露 `frameSet()` 与 `frameSetLayoutDesc()`（`uboBuffer` 私有）→ **自建「含 cam 的 set0」不可行**，只能把引擎 `frameSet` 绑 set0、自己的采样器另放一个 set | `RenderingSystem.java:149-150/274-278/513-514` |
| 9 | **push 常量 128B/Pass 已被实践验证**（`ShadowPass.PUSH_SIZE = 128`）；Bloom 用 32B（`uvRect + params`）、Tonemap 16B | `ShadowPass.java:69`、`BloomPass.java:92` |
| 10 | **参数设置面链路完整**：`PostProcessSettings`（core `@Reflective Serializable`；**`read` 缺键恒 0 → 新增字段必须走「对象整体 null 判定」保默认**）挂 `JavelinSetting`；`PostProcessSetup.setXxx` 已创建即时/后创建补发；`JavelinEngine.createRenderPipeline` 启动期下发；编辑器 `ProjectSettingsWindow` 勾选 + 数字行 + 关窗 load-modify-save | §9.8；`PostProcessSettings.java`、`ProjectSettingsWindow.java:123-190/307-337/344-365` |
| 11 | **内置着色器链**：`builtin-asset-core/postprocess/*.vert|.frag` + 伴生 `.meta`，走 `SpvShaderImporter`（扩展名定 stage、**零注入**——含 `LIGHTING_STAGE_MARKER` 才会被注入光照段，注释里写标记字面量也会误触）；loadAsset 路径 = `"core/postprocess/xxx.frag"`；索引由 Gradle 扫目录自动再生成（零登记） | `SpvShaderImporter.java`、`TonemapPass.java:74-75` |
| 12 | **FORWARD 档无 G-Buffer**：`GBufferPass` 只在 HYBRID 档装配（前向管线不写三张 MRT） | `DefaultHybridRenderPipeline` / `ForwardRenderPipeline` |

### 2.1 关键缺口：深度采样（本方案绕开，不做）

SSAO 的经典实现读**深度图**。本引擎当前**没有可用的深度采样路径**（三条缺口，均实证）：

| # | 缺口 | 证据 |
|---|------|------|
| 1 | 图内所有 `p.reads` 的屏障目标布局**硬编码 `SHADER_READ_ONLY`**，屏障生成器 switch 不产出 `DEPTH_STENCIL_READ_ONLY` | `RenderGraphImpl.java:248-252`、`VulkanBarrierGenerator.java:82-106` |
| 2 | 描述符的 `SAMPLED_TEXTURE` imageLayout **硬编码 `SHADER_READ_ONLY_OPTIMAL`**（无「按格式选深度只读布局」分支） | `VulkanDescriptorSet.java:58-60` |
| 3 | `depthFor` 的 usage **只有 `DEPTH_STENCIL_ATTACHMENT`**（无 `SAMPLED`；对比 `shadowMap` 是 `DEPTH_STENCIL_ATTACHMENT + SAMPLED`） | `RenderingSystem.java:518-522` vs `:296-304` |

**现状唯一可用的深度采样是「比较采样器读 shadowMap」那条路**（`DEPTH_STENCIL_ATTACHMENT → SHADER_READ_ONLY` + `sampler2DShadow`，已随批次④-A 实机验收）——它服务阴影比较，**不是**「读原始深度值」，且这套布局转换对非比较采样是否被 VVL 接受**无法从代码判定**。

**结论（本方案的取舍）**：**v1 不碰深度**，改用 G-Buffer 的 `worldPos`（含有效标志）+ 世界法线——两者已含「重建视空间位置」所需的全部信息（`cam.view × worldPos`），且既不新增 RHI 能力、也不碰布局转换风险。代价与边界：**仅 HYBRID 档可用**（§4.1）；每样本多读一次 16B 世界位置（§3.2 的深度比较开销）。深度采样路径作为**后续独立议题**（可与 TAA 的运动矢量、REND-08 世界位置改深度重建一并议），记 Backlog。

---

## 3. 结构设计

### 3.1 输入面与坐标口径

AO Pass 每次着色需要：

| 输入 | 来源 | 用途 |
|------|------|------|
| 世界位置 + 有效标志 | `gbufferWorldPos`（RGBA32F，w<0.5 = 背景） | 当前像素的世界坐标；`w<0.5` 直接 `AO=1`（天空/背景不参与遮挡） |
| 世界法线 | `gbufferNormal`（RGBA16F，世界空间归一） | 半球定向（TBN 的 N 轴） |
| 相机矩阵 | set0 `cam.view` / `cam.viewProj` | 视空间换算（视空间 z 用于遮挡判定/距离衰减）与样本点投影 |

**坐标口径（三条，落码须实测核对）**：

1. **半球采样在世界空间做**——因为 set0 **只有 `viewProj` 与 `view`、没有 `proj`**（§2 表第 7 条）：视空间样本点无法直接投影到屏幕，只能「世界空间采样 → `viewProj` 投影」。世界空间半球 + 世界法线在数学上与视空间等价（AO 与朝向无关）。
2. **遮挡判定在视空间 z 上做**：`viewZ = (cam.view × worldPos).z`；**本引擎视空间前向 = −Z**（`Engine_Coordinate_Conventions`），故「采样点被更近的表面挡住」= `sceneViewZ > sampleViewZ + bias`。**符号口径实装时必核对**（同帧打印两个 z 判向，不靠推演）。
3. **样本点的屏幕 uv**：`clip = cam.viewProj × vec4(sampleWorldPos,1)` → `snorm = clip.xy/clip.w * 0.5 + 0.5`（帧缓冲归一化，Vulkan 左上原点；Y 翻转已入投影，无需再翻）→ 采样纹理 uv = `uvRect.xy + snorm * uvRect.zw`（**§2 表第 4 条的同一条换算**，对半分辨率/分屏视口一致成立）。

**样本可视性**：`clip.w <= 0`、`snorm` 越界、或采样到的 `worldPos.w < 0.5`（背景）→ 该样本不参与遮挡（避免边缘拉黑）。

### 3.2 算法形态（经典半球核 SSAO，参考 Babylon SSAO2 简化）

**核**：着色器内 `const vec3 KERNEL[16]`（预生成的半球向量，长度按 `(i/16)²` 缩放偏向中心，照 Babylon `scales[16]` 口径）——**不放 UBO/SSBO**（16 个常量，编译期固定，省一个资源面）。

**逐像素旋转噪声**：**着色器内哈希**（`fract(sin(dot(pixel, vec2(12.9898,78.233)))*43758.5453)` 一类的确定性伪随机，或用 4×4 角噪声表）——**不引噪声纹理资产**（避免新资产类型 + 采样器；Babylon 用 128×128 随机纹理是它的资源模型，我方 v1 用哈希即可）。噪声带来的带状图案由 §3.3 的模糊 Pass 消除。

**单像素流程**（AO Pass，半分辨率）：

```
worldPos = texture(gbufferWorldPos, uv);  N = normalize(texture(gbufferNormal, uv).xyz)
if (worldPos.w < 0.5) { out = 1.0; return; }            // 背景
vec3 rnd = hash3(gl_FragCoord.xy);
vec3 T = normalize(rnd - N * dot(rnd, N));              // Gram-Schmidt
mat3 TBN = mat3(T, cross(N, T), N);
float occ = 0.0;
for (int i = 0; i < 16; i++) {
    vec3 dir = normalize(TBN * KERNEL[i]);
    vec3 samplePos = worldPos.xyz + dir * radius * scale(i);   // 半径内
    vec3 clip = (cam.viewProj * vec4(samplePos, 1.0)).xyz;
    // 投影 → 屏幕 uv（§3.1 第 3 条）→ 越界/背景跳过
    float sceneZ  = (cam.view * vec4(texture(gbufferWorldPos, suv).xyz, 1.0)).z;
    float sampleZ = (cam.view * vec4(samplePos, 1.0)).z;
    float range   = smoothstep(0.0, 1.0, radius / max(abs(sceneZ - sampleZ), 1e-4));
    occ += (sceneZ > sampleZ + bias ? 1.0 : 0.0) * range;      // 距离衰减，防「远处表面误遮挡」
}
ao = clamp(1.0 - occ / 16.0, 0.0, 1.0);                  // 强度在合成 Pass 生效
```

**距离淡出**：视空间距离超过 `FADE_END`（着色器常量，起步 40 世界单位）时 AO 渐变回 1.0——照 Babylon `maxZ` 口径，避免远处画面过暗（常量起步，接设置后置）。

### 3.3 三 Pass 结构（单节点内录制，照 `BloomPass` 形态）

> **2026-11-21 更新**：下文的三 Pass（单个 5×5 双边）已被 §10 的两遍分离结构取代——现为**四 Pass**（AO / 水平模糊 / 竖直模糊 / 合成），AO RT 增至三个索引。本节保留原始方案记录。

```
场景色（lightingRT）──────────────────────────────────────────────┐
                                                                  ▼
① SSAO Pass（半分辨率）   读 gbufferNormal + gbufferWorldPos → AO 原始值 → ssaoRt(view,0)
② Blur Pass（半分辨率）   读 ssaoRt(view,0) + gbufferWorldPos（保边权）→ ssaoRt(view,1)
③ Composite Pass（全分辨率）读 链输入(场景色) + ssaoRt(view,1) → 链输出（场景色 × mix(1, AO, intensity)）
```

| Pass | 名（图内） | 分辨率 | set0 | set1（自建，采样器 `gbufferSampler()`） | push（32B） | 输出 |
|------|-----------|--------|------|------------------------------------------|-------------|------|
| ① AO | `{view}_SSAO_AO` | 视口 `>>1` | 引擎 `frameSet`（动态偏移 `view.uboOffset()`） | b0 = `gbufferNormal`、b1 = `gbufferWorldPos` | `uvRect` + `(radius, bias, 0, 0)` | `ssaoRt(view,0)`，CLEAR |
| ② Blur | `{view}_SSAO_Blur` | 视口 `>>1` | 同上（声明但不消费） | b0 = `ssaoRt(view,0)`、b1 = `gbufferWorldPos` | `uvRect` + `(0,0,0,0)` | `ssaoRt(view,1)`，CLEAR |
| ③ Composite | `{view}_SSAO_Composite` | 全视口 | 同上（不消费） | b0 = 链输入（场景色）、b1 = `ssaoRt(view,1)` | `uvRect` + `(intensity, 0, 0, 0)` | 链 `output`，CLEAR（含区域清屏语义，照 `BloomPass.addCompositePass`） |

- **为什么 set0 恒为引擎 `frameSet`**：自建「含 cam 的 set0」需要相机 UBO 缓冲句柄，而它是私有的（§2 表第 8 条）——故统一「set0 = 引擎 frameSet（不消费也声明）+ set1 = 本 Pass 输入集」。三个 Pass 的 PSO 布局 = `[frameSetLayoutDesc(), ssaoSetLayout]`，绑定一律 `bindDescriptorSet(0, rs.frameSet(), 1, {view.uboOffset()})` + `bindDescriptorSet(1, set, 0, null)`。
- **② 模糊**：**单 Pass 2D 双边核**（半径 2，即 5×5）——权重 = 世界位置差（保边，跨物体边界不串色）+ 空间核；半分辨率下 25 tap 成本可接受，省「H/V 两 Pass + 一张 RT」。将来要更省再拆可分离（Babylon 是 H/V 两 pass）。
- **中间 RT 归 `RenderingSystem`**（引擎无「节点自持 RT」先例，照 `BloomPass`）：节点只向链暴露「input → output」，两个半分辨率 RT 由 `ssaoRtFor` 提供。
- **③ 合成的 AO 施加方式**：`sceneColor * mix(1.0, ao, intensity)`（`intensity=0` 即关；只乘不除，暗部单调变暗，无「AO 反转」风险）。**背景/天空**处 `ao=1`（§3.1）→ 天空盒不受影响。

### 3.4 AO 资源族（`RenderingSystem.ssaoRtFor`）

照 `bloomRtFor` 全套模式（`RenderingSystem.java:588-650`）：

| 项 | 值 |
|----|-----|
| 签名 | `public Texture ssaoRtFor(String cameraName, int index)`（index 0 = AO 原始、1 = 模糊后） |
| 尺寸 | `max(1, depthWidth >> 1)` × `max(1, depthHeight >> 1)`（半分辨率，随视口） |
| 格式 | **`R8_UNORM`**（单通道 8 位；RHI `Format` 已有该枚举）。若实机出现带状 → 换 `R16_SFLOAT`（一行改动，枚举已有） |
| usage | `COLOR_ATTACHMENT + SAMPLED` |
| key | `cameraName + "#s" + index` |
| 生命周期 | `computeIfAbsent` 惰性（SSAO 关时不创建）→ `ensureDepthSize` 的 `disposeAll` 链换绑 → `removeCamera` 走 `retireSsaoRts(prefix)` 两帧退役 → `close()` 全域 dispose |

> 注意 `removeCamera` 现有一处**已知不对称**：G-Buffer 三张没被回收（`RenderingSystem.java:332-337` 无对应行，`ensureDepthSize`/`close` 有）。落码时按 Bloom 模式补齐 SSAO 的回收，**不顺手改 G-Buffer 那笔账**（另记 Backlog）。

### 3.5 节点排序声明（`PostProcessNode.order()`）

**问题（实证）**：`orderedChain()` = 非终节点**注册序** + 终节点置尾（`AbstractRenderPipeline.java:126-140`）。`PostProcessSetup.setEnabled` 走 `addLast` —— 于是「先开 Bloom、后开 SSAO」会让 SSAO 落在 Bloom **之后**：AO 会把泛光也压暗、且 AO 的输入已含 bloom，与设计意图相反（正确序 = SSAO → Bloom → Tonemap）。用户两次点击的先后顺序不该决定画面语义。

**做法**：`PostProcessNode` 增 `default int order() { return 0; }`；`orderedChain()` 对非终节点按 `(order, 注册序)` **稳定升序**，终节点（`isTerminal()`）仍恒置尾。取值：**SSAO = 10、Bloom = 20、Tonemap = 终节点（不看 order）**。

- 这是本档**唯一**的链契约增量，动机是「链上节点自带排序声明」（`Engine_PostProcess_Design.md` §9.7 解耦时已朝此方向：`instanceof` 特例 → 节点声明）。
- 兼容性：`order()` 默认 0、稳定排序 → 现有 Bloom/Tonemap 行为**零变化**；后续节点（TAA 等）按同一机制声明位置。
- 备选（不采纳）：给 `PostProcessChain` 加「按序插入」API，由组合根算插入位——链要多一个排序概念，且插入位判断散在组合根。

### 3.6 参数与设置面

| 项 | 落点 | 内容 |
|----|------|------|
| 开关 | `JavelinSetting.ssao`（原生 `boolean`，默认 **false**）+ write/read | 照 `bloom` 先例（缺键保默认 = 关，无迁移坑） |
| 参数 | `PostProcessSettings` 增 `ssaoRadius`（世界单位，起步 0.5）、`ssaoBias`（0.02）、`ssaoIntensity`（1.0）+ getter/setter + write/read | 照 §9.8 口径；**新增字段的 read 必须走对象整体 `null` 判定**（`InputCapsule` 无「键存在」，浮点缺键恒 0） |
| 组合根 | `PostProcessSetup`：`SSAO = "ssao"` id + `setSsaoParams(...)`（已创建即时、后创建补发）+ `node("ssao")` 分派 | 照 `setBloomParams` |
| 启动期 | `JavelinEngine.createRenderPipeline`：按 `JavelinSetting` 装配链 + 下发参数 | 照 Bloom |
| 编辑器 | `ProjectSettingsWindow`「后处理」页：SSAO 勾选 + 三数字行 + 关窗 persist 字段拷贝 + i18n 双语键 | 照 Bloom 四参数先例 |
| 菜单 | **不加工具菜单项**（项目设置窗口即入口；Bloom/Tonemap 的菜单项是历史包袱，不为 SSAO 复制） | —— |

### 3.7 内置着色器

`javelin-core/src/main/resources/builtin-asset-core/postprocess/` 新增（各伴生 `.meta`，Gradle 扫目录零登记）：

| 文件 | 内容 |
|------|------|
| `ssao.vert` | 全屏三角形（照 `bloom.vert`，输出 `vUV`） |
| `ssao.frag` | ① AO 计算（§3.2）；自建 `CameraBlock` 声明（**零注入，必须手写** `layout(set=0,binding=0) uniform CameraBlock { mat4 viewProj; mat4 view; } cam;`，std140 与 256B 步长对齐）+ set1 两采样器 + push 32B |
| `ssao_blur.frag` | ② 5×5 双边（世界位置差权） |
| `ssao_composite.frag` | ③ `mix(1, ao, intensity)` 乘到场景色 |

> **两条易踩**（照既有坑）：① **注释里也不要写 `LIGHTING_STAGE_MARKER` 的字面量**（会误触注入撞名，坑已在 G-Buffer 视图档记）；② 顶点/片元坐标一律 `uvRect + vUV`，**不要用 `gl_FragCoord/textureSize`**（半分辨率 + 分屏视口下不成立）。

### 3.8 FORWARD 档边界

SSAO 依赖 G-Buffer，**仅 HYBRID 档可用**（FORWARD 档无 G-Buffer，`§2 表第 12 条`）。

- 组合根：`renderPipeline == FORWARD` 时**不创建、不挂** SSAO 节点（`setEnabled(SSAO)` 在 FORWARD 档直接不生效）；
- 编辑器：项目设置窗口的 SSAO 勾选在 FORWARD 档**置灰 + 提示行**（照 dbg-2「FORWARD 档菜单项置灰 + F4 提示」先例）；
- 编辑器固定 HYBRID（`ProjectLoadingFrame` 显式设），故本边界只影响打包运行时的 FORWARD 档——**前向先不管**（2026-11-13 用户方向），此处只保证「不报错、不黑屏」。

---

## 4. 边界与交互

### 4.1 与管线档位

SSAO 是链上节点，而链对两档**正交**——但 SSAO 的**输入**不是两档共有（HYBRID 才有 G-Buffer）。故这是后处理链里**第一个「依赖档位能力」的节点**，边界按 §3.8 收口：节点自身不判断档位，**由组合根按档位决定挂不挂**（管线基类仍不认识 SSAO）。

### 4.2 SSAO 作用于透明物体（v1 已知边界，不静默）

合成是「全屏乘 AO」，而**透明物体不写 G-Buffer**（`Pass GBuffer` 只收不透明）——透明像素读到的 AO 是**它背后不透明表面的 AO**，于是透明面片也会被压暗（Unity 后处理栈的 DepthNormals AO 同样有这类边缘问题，它靠深度比较限制在不透明像素）。

- **v1 口径**：接受并记录（编辑器场景透明物少、影响轻）；**要严格修就必须引深度采样**（§2.1 缺口），归后续议题，不塞进本批。
- 天空/背景不受影响（`worldPos.w<0.5 → AO=1`）。

### 4.3 多相机 / 预览视口 / 分屏

- 每视口独立 `ssaoRt`（key 含 cameraName）、独立录制（照 Bloom）；
- 分屏区域清屏与 scissor：合成 Pass 走 `BloomPass.addCompositePass` 同款分支（`AbstractForwardPass.isRegionClear(view, output)` → 区域版 `addColorOutput(output, CLEAR, clear, view.scissor())`）；
- 预览视口（`targetOverride`）：链尾仍是 Tonemap/合成链的输出，尺寸口径与 `depthFor` 一致，无新增边界。

### 4.4 与 overlay 制 Pass（Gizmo / 调试线 / G-Buffer 视图 / 拾取）

链展开点在**最后一个场景 Pass 之后、overlay 之前**（后处理档 §9.2 第 1 条）——SSAO 只在链内，故 Gizmo/调试线/调试视图**不受 AO 影响**（照 Tonemap/Bloom 的既有验收面）。**G-Buffer 调试视图**若同时开着 AO：调试视图直贴 G-Buffer 原图、不经链，同样不受影响。

### 4.5 图缓存与性能

- 节点增删 / 开关 → 链组成变 → `structureHash` 变 → 图缓存正常失效（后处理档 §4.5 口径），不做特殊优化；
- 参数改（radius/bias/intensity）走 push 常量 → **不改 Pass 组成、无图缓存失效**（§9.8 同口径）；
- 成本 = ① 半分辨率 16 tap（每 tap 一次 16B 世界位置读）+ ② 半分辨率 25 tap + ③ 全屏 1 tap；起步实现不追极限，先看画质与帧率。

---

## 5. 实施步骤

> 逐步可验收、每步独立可跑（先方案后码；本档拍板后按用户派发的增量步走）。

**步骤一：链内骨架 + AO 计算（可视但粗糙）**

1. `PostProcessNode.order()` + `orderedChain()` 稳定排序（`AbstractRenderPipeline`），Bloom/Tonemap 行为零变化；
2. `RenderingSystem.ssaoRtFor` + 生命周期四处（创建/resize/removeCamera/close）；
3. `SsaoPass` 骨架 + `ssao.vert/ssao.frag`（AO 计算）+ 合成 Pass（先不做模糊，直接乘原始 AO，看得到接触阴影与噪声）；
4. `PostProcessSetup` 注册 `ssao` id + `JavelinSetting.ssao` + 启动期装配。

**验收（步骤一）**：开关后接触处出现遮挡、转角变暗；天空与背景不受影响；双视口不串；`logs/error` 零 ERROR。

**步骤二：模糊 + 参数接设置**

5. `ssao_blur.frag` + 第二个半分辨率 RT（消噪声、保边）；
6. `PostProcessSettings` 三参数 + `PostProcessSetup.setSsaoParams` + 编辑器项目设置窗口三行 + i18n。

**验收（步骤二）**：噪声消除、边不糊；三参数在窗口内即时生效、关窗重开保持、重启保持；关 SSAO 时画面**逐像素不变**（默认关 = 零变化）。

---

## 6. 验收口径（汇总）

| 项 | 口径 |
|----|------|
| 默认关 | 画面与现基线**逐像素一致**；链空、零新增 RT |
| AO 正确 | 角落/接触处变暗、平面不脏、天空不暗、无「表面自遮挡」条纹 |
| 无噪声 | 模糊后无可辨噪声图案（近看远景均无） |
| 保边 | 物体交界、轮廓处不出现 AO 渗色（模糊权重正确） |
| 参数 | 三参数即时生效，`intensity=0` = 视觉等同关闭 |
| 多视口 | Scene + Game 双视口各自正确、不串 |
| overlay | Gizmo / 调试线 / 拾取高亮 / G-Buffer 视图不受 AO 影响 |
| 校验层 | `logs/error/error.log` 零 ERROR（dbg-1 已就绪） |
| 性能 | 帧率达标（半分辨率 AO + 模糊 + 全屏合成） |
| 排序 | 先开 Bloom 再开 SSAO，AO 仍在 Bloom **之前**（`order()` 生效） |

---

## 7. 拍板点（开工前逐项确认）

> 每项给建议，取默认则按建议落码。

| 序 | 拍板点 | 建议 |
|----|--------|------|
| 1 | 深度来源 | **v1 不用深度**，读 G-Buffer `worldPos`(含有效标志) + 世界法线（零 RHI 改动、仅 HYBRID 可用）；深度采样留后续独立议题（§2.1 三条缺口） |
| 2 | 算法档位 | **经典半球核 SSAO（16 采样 + 哈希旋转噪声 + 距离衰减）**，参考 Babylon SSAO2 简化；HBAO/GTAO 另立项 |
| 3 | 分辨率 | **AO 与模糊半分辨率（`depth>>1`）+ 全分辨率合成** |
| 4 | 模糊 | **单 Pass 5×5 双边**（世界位置差保边）；可分离 H/V 留后续 |
| 5 | 噪声 | **着色器内哈希，不引噪声纹理资产** |
| 6 | 节点排序 | **加 `PostProcessNode.order()`**（SSAO=10 / Bloom=20 / 终节点仍置尾），稳定升序；链机制其余不动 |
| 7 | 合成范围 | **全屏乘**（透明物也吃 AO，记边界 §4.2）；严格「只作用不透明」需深度采样，另议 |
| 8 | 开关与参数 | **独立开关 `JavelinSetting.ssao`（默认关）** + `radius/bias/intensity` 三参数接 push 与项目设置窗口；**不加工具菜单项** |
| 9 | FORWARD 档 | **不创建不挂**（组合根按档位判）+ 项目设置项置灰提示（照 dbg-2 先例） |
| 10 | AO RT 格式 | **`R8_UNORM`**（若实机带状 → `R16_SFLOAT`，一行改动） |

---

## 8. 与邻档衔接

| 概念 | 出处 | 关系 |
|------|------|------|
| 链承载与展开机制 | `Engine_PostProcess_Design.md` §3.4/§3.7/§9.7/§9.8 | 本档复用其机制与参数面；**唯一增量 = `PostProcessNode.order()`**（§3.5） |
| 场景颜色终点 / HDR | 同上 §3.1~§3.3 | SSAO 的链输入即 `sceneColorTarget()`（HDR 生效时为 `lightingRT`）——**AO 在 HDR 空间施加**（正确） |
| G-Buffer 格式与写入契约 | `Engine_RenderPipeline_Design.md` §4.2 / `Engine_Material_Design.md` §4.2 | 本档只**读**，不改 G-Buffer（worldPos 的 w 有效标志语义是本档的背景判定依据） |
| set0 相机块字段 | `Engine_Uniform_Keys.md` §3 | 只消费 `view`/`viewProj`；若将来 SSAO 需要 `proj/invProj`，须按「改通道 = 改契约」流程先改该表（本档不需要） |
| 深度采样缺口 | `Vulkan_Pitfalls.md` / `RHI_Implementation_Progress.md` | §2.1 三条缺口为实证；**本档不修**，留后续议题（可与 TAA 运动矢量、REND-08 合并议） |
| 光照环境项 | `Engine_Lighting_Design.md` §10 | SSAO 为 GI 第一步；`lighting.ambient`（现纯黑）真值源归 P4-04 天空盒环境光；AO 与探针 GI 的融合归 P4-05 |
| 编辑器设置入口 | `Editor_ProjectSettings_Design.md` | 「后处理」页新增 SSAO 开关与三参数行 |
| 四期排序 | `Engine_Roadmap.md` §4-2 | 本档 = 施工序 ① |
| 待办登记 | `Engine_Backlog.md` §3（P4-02） | 状态随本档落地更新 |

---

## 9. 实装记录（步骤一/二，2026-11-14）

> 用户「继续」= 按 §7 建议默认开工；步骤一（骨架 + AO 计算 + 合成）与步骤二（双边模糊 + 参数接设置）同日落码。**✅ 2026-11-14 用户实机验收通过**（先报「噪点/角落」，补模糊后「看起来没问题了」）。

### 9.1 落点（代码实证）

| 件 | 落点 | 形态 |
|----|------|------|
| 链排序声明 | `PostProcessNode.order()`（默认 0）+ `AbstractRenderPipeline.orderedChain` | 非终节点按 `order()` 升序（`List.sort` 稳定 → 同序保持注册序），终节点仍恒置链尾；`SsaoPass.order()=10`、`BloomPass.order()=20`。**现有 Tonemap/Bloom 行为零变化**（默认 0 + 终节点不看 order） |
| `SsaoPass` | `core/render/pipeline/pass/SsaoPass` | `implements PostProcessNode`；单节点内录 2 个覆盖制全屏 Pass（`{view}_SSAO_AO` 半分辨率 / `{view}_SSAO_Composite` 全分辨率）；PSO 缓存 key=(kind,输出格式)、描述符集 per-view 按纹理身份换代重建；push = `vec4 uvRect + vec4 params(radius,bias,intensity)` 32B |
| AO 资源族 | `RenderingSystem.ssaoRtFor(cameraName, index)` + `createSsaoViewTexture` | 半分辨率（`>>1`）、`SSAO_AO_FORMAT = R8_UNORM`、`COLOR_ATTACHMENT\|SAMPLED`；key = `cameraName#s索引`（0 = AO 原始；1 留给步骤二模糊后）。生命周期八处照 `bloomRtFor`：字段 / 访问器 / 工厂 / `retireSsaoRts` / `removeCamera` / `ensureDepthSize` 换绑 / `close` / 常量 |
| 内置着色器 | `builtin-asset-core/postprocess/ssao.vert` + `ssao.frag` + `ssao_composite.frag`（各伴生 `.meta`，GUID 新分配） | 裸 GLSL 走 `SpvShaderImporter`（Gradle 扫目录零登记、零注入）；`ssao.frag` **手写** `CameraBlock` 声明（set0 binding0）+ set1 双采样（法线/世界位置），16 样本半球核为 `const vec3 KERNEL[16]`（面积均匀 × 由内向外长度梯度，脚本生成保证单位化与朝向）、切向基**退化防护**（参考轴与法线近平行时换轴，避免 NaN）、逐像素哈希旋转、背景（`worldPos.w<0.5`）跳过、`smoothstep` 距离衰减；`ssao_composite.frag` = `scene × mix(1, AO, intensity)` |
| 采样器 | AO 采样用本 Pass 自建 **LINEAR**；G-Buffer 采样用引擎 `gbufferSampler()`（NEAREST） | 半分辨率 AO 上采样平滑；世界位置不做跨轮廓插值 |
| 组合根 | `PostProcessSetup.SSAO = "ssao"` + `node()` 分支 + `setSsaoParams` + `setClearColor` + `isEnabled`（if 链，仍不触发创建） | 参数缺省 0.5 / 0.02 / 1.0（已创建即时、后创建补发） |
| 开关 | `JavelinSetting.ssao`（原生 boolean，默认 false）+ write/read（缺键=false = 默认语义，无迁移坑） | 照 `bloom` 先例 |
| 启动期装配 | `JavelinEngine.createRenderPipeline` | `setting.isSsao()` 且非 FORWARD 档 → 挂链；**FORWARD 档 warn 并忽略**（§3.8，不静默） |
| 编辑器械 | `ProjectLoadingFrame.loadEditorSetting` 读 `ssao`；`ProjectSettingsWindow`「后处理」页 SSAO 勾选 + `applyToggle` 分支 + `persist` 字段拷贝；i18n 双语键 `projectSettings.ssao` | 见 §9.2 第 2 条（步骤一即接，理由是验收可行性） |
| **模糊 Pass（步骤二）** | `SsaoPass.addBlurPass` + `builtin-asset-core/postprocess/ssao_blur.frag`（+ 伴生 `.meta`） | 半分辨率 5×5 双边：读 `ssaoRtFor(view,0)` + 世界位置（保边）→ 写 `ssaoRtFor(view,1)`；set0 = 引擎 frameSet（视空间 z 用）、set1 = 双采样；**区域边界守卫**（越出本视口区域即跳过，分屏不读邻视口）；合成改读 index 1 |
| **参数设置面（步骤二）** | 新 `SsaoSettings`（core `@Reflective Serializable`：radius/bias/intensity）+ 挂 `PostProcessSettings.ssao`（`readSerializable` null 判定保默认） | **嵌套对象而非直接加字段**：`InputCapsule` 缺键浮点恒 0（半径 0 = 无 AO），须对象级 null 判定（红线段注 §9.8 同源口径）；`JavelinEngine` 启动期 `setSsaoParams` 下发 |
| **窗口三行（步骤二）** | `ProjectSettingsWindow`：SSAO 半径/深度偏移/强度三数字行 + `applySsaoParams`（即时下发）+ i18n 三键 | 数字行照 Bloom 四参数先例（键入即提交 → 即时生效，写盘收口在关窗） |

### 9.2 实装期与方案步骤划分的两处差异（记明不静默）

1. **参数在步骤一即走 push 常量**（着色器内无 `const` 半径/偏移/强度；Java 侧缺省 0.5/0.02/1.0）。
   方案把「三参数接设置」归步骤二——实装为避免「先硬编码、再改 push」的往返，push 通道（`uvRect + params`，与 Bloom 同形）在步骤一即建；**步骤二只剩 `PostProcessSettings` 字段 + 项目设置窗口三行 + `setSsaoParams` 接线**，着色器与 Pass 侧不再改动。
2. **编辑器项目设置窗口的 SSAO 勾选在步骤一即接**（方案把窗口接线归步骤二）。
   起因：`Game/javelinSettings.settings` 是二进制文件，用户无法手改开关——不接勾选则**本批无法实机验收**。故步骤一收敛为「开关可用」（勾选 + 读设置 + 写盘），步骤二只补三个参数行。
3. **模糊的保边权用「视空间 z 差」而非方案字面的「世界位置差」**（步骤二实装修正，起因 = 实机噪声反馈）。
   方案 §3.3 写「权重 = 世界位置差（保边）」；实装改为 `tz - cz`（均经 `cam.view` 折算）指数衰减。原因：**世界位置逐像素跨度随视角放大**——掠射视角的同一表面（地面、墙角斜看）相邻像素世界距离可达半径量级，用世界距离会把同一表面判成不同面而拒绝平滑，噪声正好留在用户最容易看到的地面/墙角。视空间 z 的逐像素梯度是常量（平面），无此问题；语义上两者同为「几何差保边」，故归为**实现细化**而非设计改向。深度容差取 `radius × 0.5`（与半径口径联动，不另加参数）。

### 9.3 验收口径（用户实机，步骤一 + 步骤二）

> 重点管线 = 混合实现（编辑器固定）；前向档只要求不报错（FORWARD 档本次 warn 忽略 SSAO）。

- 默认关：画面与现基线**逐像素一致**（零新增 RT、零行为变化）；
- 项目设置窗口勾选 SSAO 后：角落/接触处出现遮挡变暗、平面不脏、**天空与天空盒不暗**；关掉即时还原；
- **噪声**：步骤二双边模糊后**不应再有可辨噪点**（尤其斜视地面/墙角）；若仍有 → 先调「SSAO 半径/深度偏移」，仍不净则报我（模糊扩核或加大半分辨率半径）；
- 三参数即时生效：半径（遮蔽范围）、深度偏移（消表面自遮挡暗斑/条纹）、强度（0 = 视觉等同关闭）；
- 已知（设计内）：透明物体也吃 AO（§4.2 边界，严格版需深度采样）；
- Scene + Game 双视口各自正确、不串；Gizmo / 调试线 / G-Buffer 视图不受 AO 影响；
- 排序：先开 Bloom 再开 SSAO，AO 仍作用在 Bloom **之前**（`order()` 生效）；
- 关窗重开 / 重启编辑器开关与三参数保持（写盘链）；`logs/error/error.log` 零 ERROR（dbg-1 已就绪）。

### 9.4 未做（步骤一/二范围外）

深度采样路径（§2.1，另议）；HBAO/GTAO（另立项）；AO 与探针 GI 的融合（P4-05）；半分辨率模糊的可分离 H/V 两 Pass（现为单 Pass 5×5，够用则不拆）；AO 中间 RT 内存别名（RHI-04）；多视口 AO RT 尺寸随视口而非交换链（现与 depth 同尺码，与既有族一致）。

---

## 10. 降噪重做（BUG-12，2026-11-21 落码，同日用户实机收口）

> **验收（2026-11-21）**：用户重导两支 spv 后实机看效果，回「看起来比之前好，先这样」——按此收口，不再追加调参；`BIAS_SLOPE_SCALE` 1.5 与 `sigmaZ` 下限 `radius×0.5` 保持现值为基线。若日后 DDGI 落地、SSAO 改「接触 AO」（radius 0.25 / intensity 0.4~0.5）时再现颗粒，从 REND-22 的采样数可配与深度感知上采样两条里取。

> 起因：2026-11-20 用户报「开 SSAO 后画面明显颗粒」，取证与根因排序登记在 `Engine_Backlog.md` BUG-12（五条：半球核 + 过紧的保边容差 + 过小的固定 bias + 8 位量化 + 半分辨率上采样）。体素 AO 退役后 SSAO 是**唯一环境遮蔽层**，且 DDGI 会抹平低频、让 SSAO 的颗粒更扎眼 → 本轮按「必修」投入。

### 10.1 落点

| 件 | 落点 | 形态 |
|----|------|------|
| 两遍分离模糊 | `SsaoPass.addBlurPass(..., horizontal)` 录两次 + `ssao_blur.frag` 单轴 9 tap | Pass 名 `{view}_SSAO_BlurH` / `_BlurV`；水平读 `ssaoRt(0)` 写 `1`、竖直读 `1` 写 `2`；同一 PSO（`blur:格式`）两遍共用；push `params.y` = 轴（1 水平 / 0 竖直） |
| 自适应保边容差 | `ssao_blur.frag` | `slope` = 沿本轴 ±2 纹素中心差分的视 z 步进 ÷4（每纹素），`sigmaZ = max(radius×0.5, 1.5×slope)`——掠射面自动放宽、平面仍按半径口径收紧 |
| 自适应深度偏置 | `ssao.frag` | 取 ±1 AO 纹素（= 2 全分辨率像素）四向视 z 步进的最大值，`biasEff = max(bias, 1.5×slope)`；倍率 `BIAS_SLOPE_SCALE` 为 Java 内部常量，走 push `params.z`（设置面仍只有半径/偏移/强度三项） |
| 邻域取样安全 | 两支着色器均加 `clampRegion()` / 回落 | 邻域 uv 夹回本视口区域（分屏不读邻视口）；背景/无效处回落中心 z，避免轮廓外把偏移放大成亮边 |
| AO RT 格式 | `RenderingSystem.SSAO_AO_FORMAT` | `R8_UNORM` → `R16_SFLOAT`（值域仍是 [0,1]，清屏白不变；半分辨率下带宽可忽略） |
| AO RT 索引 | `ssaoRtFor(camera, 0/1/2)` | 0 = AO 原始、1 = 水平模糊中间、2 = 竖直模糊结果（合成消费 2）；资源族与生命周期代码无需改动（索引泛化） |
| push 槽语义 | `SsaoPass.pushParams(cmd, uvRect, px, py, pz, pw)` | 同一 32B 布局，AO = (radius, bias, biasSlopeScale, 0)、模糊 = (radius, axis, 0, 0)、合成 = (0, 0, intensity, 0) |

### 10.2 为什么这样分（而不是单遍扩核）

- 单遍扩到 7×7/9×9 是 O(n²) tap：9×9 = 81 tap 半分辨率全屏，且大核在掠射面上仍会被同一个过紧的 `sigmaZ` 全拒；
- 两遍分离是 O(n) ×2 = 18 tap，且**每遍按自己轴的局部位 z 步进算容差**——水平遍只跟水平邻域的斜率，竖直遍只跟竖直的，避免一个方向的大梯度把另一方向也放开；
- 支撑面 9×9 已覆盖原设计「扩核」的意图，且保留「单个节点内多 Pass、对链只暴露 input→output」的形态。

### 10.3 验收口径（用户实机）

1. **斜视地面与墙角**：开 SSAO 后无颗粒/条纹，近看远景均干净（这是本轮的主判据）；
2. **接触遮蔽不丢**：物体贴地处仍有暗线，`intensity` 0.5/1.0 两档都应有可见接触阴影（若被磨平 → 报我，降 `sigmaZ` 下限或缩 tap）；
3. **无自遮挡暗斑**：曲面（球、圆柱）表面无「斑点/条纹」；若有 → 升 `bias` 或报我调 `BIAS_SLOPE_SCALE`；
4. **保边**：物体交界/轮廓不出现 AO 渗色，背景（天空盒）不被压暗；
5. **边界不变**：透明物吃背后 AO（§4.2 设计内）；双视口不串；Gizmo/调试线/G-Buffer 视图不受影响；先开 Bloom 再开 SSAO 时 AO 仍在 Bloom 之前；
6. 三参数即时生效、关窗/重启保持；`logs/error/error.log` 零 ERROR；帧率不劣于旧版（18 tap vs 25 tap，理论更省）。
7. 前置：**着色器改动须重导** `ssao.frag` / `ssao_blur.frag` 的 `.spv` 产物（内置资产走导入链，改源码不重导 = 仍跑旧产物）。

### 10.4 未做 / 边界

- **采样数可配（16/32/64）**：需多套核常量 + 设置项 + i18n，按实机是否仍有颗粒决定，不在本批；
- 半分辨率 AO 的**深度感知上采样**（替代 LINEAR 2×2 块状）：要读深度或世界位置做权重，另议；
- HBAO / GTAO 换算法：仍属另立项（§1 本期不做）。

---

> （注：部分内容可能由 AI 生成）
