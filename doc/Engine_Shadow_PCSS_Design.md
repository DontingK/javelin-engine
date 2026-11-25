# 阴影软化设计（PCSS + 接触阴影，REND-21-C）

> v0.3（2026-11-25 实机反馈修正：`softness ≥ 1.05` 时阴影边缘出现多条梯度带——根因是软化核复用了固定偏移的 MJP 4/9 tap，核半径被 PCSS 放大到最多 `pcfRadius × 4` 后每个 tap 变成一条可见亮度带（切「软影高」9 tap 带更多）。改为 PCSS 专用连续螺旋核 `javelinPcssSoftShadow`（黄金角分布 16/24 tap 随品质档），`softness = 0` 不进该段、原 MJP 分支零改动，见 §9.1；**2026-11-25 用户实机通过——「现在看起来柔和多了」，边缘梯度带消失**）｜v0.2（2026-11-25 落码：P1~P5 按推荐默认拍板，**C1 PCSS 已实装**、C2 接触阴影挂后置，见 §9 落码记录；未编译待验收）｜v0.1（2026-11-25 方案件）。路线坐标 = `Engine_Roadmap.md` §4.1 **L4 第二笔**（与 REND-21-B 逐级联剔除对调：C 是画质项、B 是性能项且要与 REND-01 联动，用户 2026-11-25 认可对调）。
>
> 一句话目标：现在影子边缘「一刀切、核大小固定」，远了也硬、近了也不软；接触处（物体贴地、细缝）没有那层暗。本笔把「半影随遮挡距离变化」和「屏幕空间接触暗」补上。

## 1. 现状核实（2026-11-25）

| 事实 | 出处 |
|---|---|
| 阴影采样 = `sampler2DShadow`（binding4）+ **硬件比较采样器**（LINEAR / CLAMP / compare LESS） | `RenderingSystem`：`shadowSampler` 构造（~421 行）、`frameStorageLayoutDesc` binding4 |
| PCF 两档核固定：软低 4 tap（复现 3×3）/ 软高 9 tap（复现 5×5），核半径 `shadowParams.y = pcfRadius`（缺省 0.6 纹素） | `ShaderInjector.javelinCascadeShadow`（`pcfMode = shadowParams.x` 编码品质档） |
| 核半径与**遮挡距离无关**——同一 `pcfRadius` 在所有级联、所有远近统一 | 同上，函数内无 blocker 概念 |
| 比较采样只返回 0/1 可见性：**取不到原始深度值**，无法做 blocker 搜索 | 现有 binding4 是 comparison sampler |
| 阴影 atlas 创建时已带 `SAMPLED` usage | `RenderingSystem` ~1272 行（`DEPTH_STENCIL_ATTACHMENT, SAMPLED`） |
| 相机的**主深度附件只带 `DEPTH_STENCIL_ATTACHMENT`，未开 SAMPLED**（`depthFor`） | `RenderingSystem` ~700 行 → 屏幕空间接触阴影的硬前置 |
| set1 布局现占 binding 0~10，**下一个空槽 = 11** | `frameStorageLayoutDesc` |
| HYBRID 档有 G-Buffer 世界位置（binding7，RGBA32F + w 有效标志），FORWARD 档没有 | `GBUFFER_BLOCKS` / LightingPass |
| `ShadowSettings` 现字段：quality / strength / pcfRadius / refDepthOffset / biasConstant / biasSlope / cascadeCount / shadowDistance / cascadeLambda | `ShadowSettings` |

## 2. 目标与判据

- 判据 A（PCSS）：同一盏主光下，**遮挡体离接收面越远，半影越宽**；贴地物体的影子边缘比悬空物体的更锐；远处级联不出现「半影一刀切」。
- 判据 B（接触阴影）：物体与地面/墙角接触线出现**随视点一致的短距离暗带**（长度 ≤ 约 20cm 世界尺度），不再依赖 PCF 的死硬接触线。
- 判据 C（性能）：HYBRID 1080p 全屏、单主光，两档合计帧时间增量**目标 ≤ 1.5ms**（PCSS 优先、接触阴影按预算裁采样数）；`error.log` 零新增 VVL。
- 判据 D（不回退）：关 PCSS 开关时画面与现 PCF 逐像素等价。

## 3. PCSS 方案（本笔主体）

### 3.1 两段结构（Babylon `PCSS` / Unity 同口径）

```
阻挡搜索：在 uv 空间一个「搜索半径」圆盘上取 N 个 tap 的【原始深度】，
          统计 refZ 以下的平均遮挡深度 blockerZ（无遮挡 → 直接返回受光）
半影估算：penumbraTexels = softness * (refZ - blockerZ) / blockerZ * atlasTexels
变半径 PCF：以半影为核半径，用现有比较采样器取 M taps（核夹在象限内）
```

- 现有 `javelinCascadeShadow` 保留为**「PCSS 关闭」分支**（`shadowParams.x` 新增档位编码，或新字段门控），不删旧路径——判据 D 靠它兜底。
- 阴影强度 / 级联混合 / 接触漏光半纹素外扩三条既有逻辑全部沿用，只在「核半径」处被 penumbra 替换。

### 3.2 数据面（需要新增两样）

| 需要 | 做法 |
|---|---|
| **原始深度**（阻挡搜索） | set1 **binding 11** = `sampler2D shadowMapRaw`，与 binding4 绑**同一张 atlas**（不同采样器：NEAREST 或 LINEAR、CLAMP、**无 compare**）；布局 `frameStorageLayoutDesc` + 每视口 `set.update` 各加一条 |
| **软化参数** | `ShadowSettings` 新增 `softness`（0 = 关 PCSS，缺省建议 1.0）+ 复用 `pcfRadius` 作上限钳制；进 UBO 需扩 `shadowParams2` 的 w 或新增 vec4（**LIGHTING 步长 768 会变 → 所有相机块重排**，见 §6 风险 2） |

### 3.3 开销账（先算清楚）

| 档 | 现 tap | PCSS 后 tap |
|---|---|---|
| 软低（4 tap） | 4 | 阻挡搜索 16 + PCF 8 = **24** |
| 软高（9 tap） | 9 | 阻挡搜索 24 + PCF 16 = **40** |
| 级联混合带 | ×2 | ×2（只在相邻级联的混合区，比例很小） |

每 tap 是一次深度纹理取样，全屏逐像素；主光 ndl>0 且 receiveShadow 才走。追加成本主要在填充率，`pcss` 关闭时归零。

## 4. 接触阴影方案（第二件，建议单独一笔）

- **仅 HYBRID 档**（FORWARD 无世界位置可重建；FORWARD 侧忽略并 warn，与 SSAO 同口径）。
- 逐像素沿**世界空间光线**（指向主光反方向）步进 8~16 步，每步把世界位置投到屏幕，与**主深度附件**比值判遮挡；命中则压暗该像素的方向光项（τ 上限 0.2 世界单位）。
- 硬前置：`depthFor` 的纹理 usage 加 `SAMPLED`，LightingPass 图声明 `addTextureRead(depth)`，set1 **binding 12** = `sampler2D sceneDepth`（NEAREST / CLAMP）。
- 在 `javelinLighting` 的**主光方向项**乘以 `contactFactor`（不是乘整个结果，避免把环境/点光一起压暗）——与 PCSS 的 `shadow` 相乘叠加。

## 5. 待拍板五点

**v0.2 拍板结论（2026-11-25，用户「嗯」= 全默认）**：P1 = **两笔**（本笔只做 C1 PCSS，C2 接触阴影挂 `Engine_Backlog.md`）；P2 = **`ShadowSettings.softness`（缺省 1.0）** 走 `shadowParams2.w`；P3 = **16/24 tap 黄金角螺旋，搜索半径 `pcfRadius × 8` 纹素、penumbra 钳制 `[pcfRadius, pcfRadius × 4]`**；P4 = **不同批**；P5 = **PCSS 两档都生效（注入段同源）**。
| 编号 | 决策点 | 推荐默认 | 备选 |
|---|---|---|---|
| **P1** | 拆分粒度 | **两笔**：`REND-21-C1` PCSS 先落（判据 A/C/D），`REND-21-C2` 接触阴影后落（需动深度附件 usage） | 一笔全做（回滚面大、验证混在一起） |
| **P2** | 软化参数载体 | **`ShadowSettings.softness`（缺省 1.0，0 = 关 PCSS）**，进 UBO 用 `shadowParams2.w`——**已核实该位恒写 0、无消费方**（`packDdgiShadowParams` 注释同口径；DDGI 追踪 compute 读同一 ShadowParams 块但不消费 w，无影响），故 `LIGHTING_STRIDE` 768 不变 | 新增 vec4 → 步长变、全体相机块重排（风险 2） |
| **P3** | 阻挡搜索核 | **16 tap 固定圆盘（软低）/ 24 tap（软高）**，搜索半径 = `pcfRadius × 8` 纹素；penumbra 上限钳制到 `pcfRadius × 4` | 32 tap 更平滑但贵；按级联距离自适应核大小 |
| **P4** | 接触阴影是否同批 | **不同批**（C2 单列） | 同批（多一处深度 usage + 布局改动，验证面翻倍） |
| **P5** | FORWARD 档口径 | **PCSS 两档都生效（注入段同源）、接触阴影仅 HYBRID** | PCSS 也只 HYBRID（与「两路一致」原则冲突） |

## 6. 风险

1. **深度精度/自遮挡**：raw 深度无 compare，阻挡搜索必须按 `refZ - biasInUV` 起算，否则会把自己算成 blocker → 影子整体发灰。要用与比较采样相同的 `refDepthOffset` 折算，不能另起一套 bias。
2. **UBO 步长**：`LIGHTING_STRIDE` 现 768（`lightingBuffer` 按 `frameSlots × MAX_CAMERAS` 分配）。**2026-11-25 核实 `shadowParams2.w` 恒为 0 且无消费方** → 推荐路径不扩块、步长不变；只有选备选（新增 vec4）时才需要同步改所有相机的 offset 与 buffer 尺寸，改错即整屏亮度错乱。
3. **象限越界**：penumbra 核变大后更易跨象限，现有 `qmin/qmax` 夹取必须继续生效（夹取后核被压扁，边缘会轻微变硬——可接受）。
4. **接触阴影自遮挡条纹**：屏幕空间步进的经典问题是「斜坡自阴影」与「屏幕外无效」，需要 `步进起点偏置 + 深度比较容差` 两个旋钮；先按固定容差上线，看实机再调。
5. **天空/背景像素**：接触阴影必须由 G-Buffer `worldPos.w < 0.5` 直接跳过（与 LightingPass 背景剔除同源）。

## 7. 不做

- 不做 VSM/ESM/PCSSS 变体；
- 不做光源尺寸物理量（`lightSize` 用相对 softness 表达，避免引入光源半径端点）；
- 不做 RT 接触阴影（属 L6）；
- 不动 CSM 切分与 bias 口径。

## 8. 落档与关联

- 本档：`doc/Engine_Shadow_PCSS_Design.md`（v0.2 落码件，C1）
- 关联：`Engine_Shadow_Quality_Design.md` §4.5/§4.7（PCF 两档、阴影 atlas 版面）、`Engine_Shadow_Cutout_Design.md`（cutout 与 PCSS 在 fs 里先后 discard/shadow 相乘，互不冲突）、`Engine_Uniform_Keys.md`（set1 binding11 新增槽）、`Engine_Roadmap.md` §4.1 L4 行

## 9. 落码记录（2026-11-25，C1 PCSS，未编译待验收）

| 落点 | 改动 |
|---|---|
| `ShadowSettings` | 新增 `softness`（`@InsFloatRange(0..4, 0.05)` + 滑块 + `@SettingHint`，缺省 **1.0**；0 = 关 PCSS）+ `getSoftness/setSoftness` + `write/read("softness")` |
| 语言包 | `zh_CN/en_US.properties` 追加 `projectSettings.shadowSoftness` / `.shadowSoftnessHint` |
| `RenderingSystem` | 新增 `shadowRawSampler`（NEAREST/NEAREST/NEAREST + CLAMP，**无 compare**；理由：深度格式的线性过滤支持因设备而异，NEAREST 全平台安全）；`frameStorageLayoutDesc` 加 **binding11**；`updateLightingSet` 与「atlas 换代重指」两处都同步写 binding11 = 同一张 shadow atlas；`packDdgiShadowParams` 的 **`shadowParams2.w` = `shadow.getSoftness()`**（原恒 0）；`close()` 释放新采样器 |
| `ShaderInjector` | `LIGHTING_BLOCKS` 加 `binding11 uniform sampler2D shadowMapRaw` 声明；新增注入函数 `javelinPcssBlockerDepth(uv, refZ, qmin, qmax, radiusTexels, texelSize, taps)`（黄金角螺旋、24 上限 + `taps` 截断、无遮挡返回 -1）；`javelinCascadeShadow` 在算出 `texelSize/qmin/qmax/base` 后按 `shadowParams2.w > 0` 做阻挡搜索 → `penumbra = (refZ-blockerZ) * softness * atlasSize * 0.05` → `kernelRadius = clamp(penumbra, pcfRadius, pcfRadius*4)`，两档 PCF 的偏移量由 `pcfRadius` 改乘 `kernelRadius` |

**自验口径（待用户执行）**
1. 重启编辑器 reimport 全部 `.shader` 与内置 `lighting.frag`（注入段改动 = 强制重导，否则旧 SPIR-V 不认 binding11）；
2. 项目设置「阴影」组出现「PCSS 软化系数」；调到 **0** 时画面应与改前一致（判据 D）；
3. 调到 1.0：悬空物体影子边缘明显比贴地物体软，近级联比远级联软；
4. `error.log` 零新增 VVL；1080p 全屏看帧时间增量（判据 C ≤ 1.5ms，超了就说，我下调 tap 数）。

**未做项（挂账）**：C2 接触阴影已于 2026-11-25 单独落码（`Engine_Shadow_Contact_Design.md` v0.2）——**勘察确认原「需 `depthFor` 加 `SAMPLED` + binding12」的前置不成立**，改走 G-Buffer 世界位置反投影，零 RHI 改动、零新槽位，仅 HYBRID 档。

## 9.1 实机反馈修正（2026-11-25，软化核改连续螺旋）

**现象**：`softness` 从 1.0 调到 1.05，阴影边缘出现很多梯度带；按建议把品质档从「软影低」切到「软影高」，**梯度反而变多但依然明显**。

**根因（确认）**：软化核直接复用既有 PCF 的 MJP 固定偏移核——软影低 4 tap 复现 3×3、软影高 9 tap 复现 5×5。这 4/9 个采样点在 `pcfRadius = 0.6` 的小半径下还能靠亚纹素权重凑成平滑过渡，但 PCSS 把半径放大到 `clamp(penumbra, 0.6, 2.4)` 纹素后（直径最多 4.8 纹素），固定 tap 的间距被同比拉开，每个 tap 不再是"插值点"而是"一条独立亮度带"。tap 越多带越细越多——所以切高档后梯度"变多"。`softness` 越大，越多像素被推到 4× 上限同半径，那一片成为宽平台带。

**修法（已落码）**：

| 落点 | 改动 |
|---|---|
| `ShaderInjector` | 新增注入函数 `javelinPcssSoftShadow(uv, refZ, qmin, qmax, radiusTexels, texelSize, taps)`——黄金角螺旋（与 `javelinPcssBlockerDepth` 同一 2.39996323 常量与 `sqrt((i+0.5)/taps)` 面积分布，保证搜索与软化方向一致）、24 循环上限 + `taps` 截断、逐 tap 走 `javelinShadowTapC`（内部已 clamp 象限界）；`javelinCascadeShadow` 在 `softness > 0` 分支算出 `kernelRadius` 后**直接 return 螺旋核**，不再落进下方 MJP 分支 |

**口径三条**：
1. `softness = 0` 完全不进本段，仍走原 4/9 tap MJP 分支 → 判据 D（关档逐像素一致）不受影响；
2. 半径 clamp `[pcfRadius, pcfRadius × 4]` **本次不动**（只换采样分布，把变量降到最小）；
3. 品质档只决定 tap 数（低 16 / 高 24），不再决定核形状——16 tap 在 4.8 纹素直径上间隔约 1.2 纹素，级差 ≈ 6%；24 tap 级差 ≈ 4%。

**开销**：PCSS 像素从「24 raw + 4~9 compare」变成「24 raw + 16~24 compare」，约 2 倍采样；仅阴影像素走、四级联只有命中级生效。若 1080p 帧时间不达标，退路 = 半径 < 1.5 纹素用 12 tap、否则 24 tap 的阶梯，或把搜索 tap 从 24 降到 16。

**验证**：改动后注入段 GLSL 已用 `glslc --target-env=vulkan1.3` 单独编译通过（临时件，非仓库产物）。**仍需重启编辑器 reimport 全部 `.shader` 与内置 `lighting.frag`**（注入段改动 = 强制重导）。验收判据：① 边缘是连续灰度过渡、无分层带；② `softness = 0` 与改前逐像素一致；③ 1080p 帧时间增量。
