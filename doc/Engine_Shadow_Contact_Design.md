# 接触阴影（REND-21-C2）落码方案

> 版本：v0.5（2026-11-25 三轮反馈后**降级为实验项**：默认强度改 0、加 4m 近景门控；平地/凸面屏幕空间条带伪影属路线固有，完整画质版挂 Backlog 重做，见 §7.3）
> v0.4（2026-11-25 二轮实机反馈：强度 0.1 就整片变暗、球/圆柱上出现明暗梯度环且集中在受光过渡带——判定为凸曲面「沿光向步出后投影回自身更靠前表面」的系统性自命中 + `strength × 2` 放大 + 步进距离过长。修法 = 法线起点抬半格、dz 区间收到 `[0.03, tMax]`、`tMax 0.35→0.2` / 步数 10→8、去掉 2× 放大，见 §7.2；未编译待验收）
> v0.3（2026-11-25 实机反馈修正：强度 1.0 时整片受光区压暗——屏幕空间步进在掠射/背光面几乎全命中，加「法线-光向」门控与命中深度差上限，见 §7.1；未编译待验收）
> v0.2（2026-11-25，落码件——§4 拍板全默认通过、§7 落码记录）
> v0.1（2026-11-25，方案件，待拍板）
> 定位：`Engine_Shadow_Quality_Design.md` §9.2 挂账项的落码方案；Backlog `REND-21-C2`。
> 关联：`Engine_Shadow_PCSS_Design.md`（C1 已完成）、`Engine_Uniform_Keys.md`（set1 槽位表）。

---

## 1. 目标与缺口

C1（PCSS）解决的是「影子边缘硬 / 随遮挡距离不变软」；它管不到另一类体感问题：**大尺度阴影贴图分辨率不够时的近接遮挡丢失**——物体与地面贴合处的细窄暗缝、桌腿落地的一圈接触暗部、墙角缝隙，在 shadow map 里要么被 bias 抹掉，要么根本小于一个纹素。

接触阴影（contact shadow）补的正是这一层：**屏幕空间、短距离（厘米～分米级）、逐像素**的遮挡判定，与 CSM 的「远距离大范围阴影」互补，两者相乘。

验收体感目标：物体与地面/墙面贴合处出现短而实的暗缝，不再是「浮在光里」；关掉该项时逐像素回退到 C1 状态。

## 2. 关键事实（已勘察）

| 事实 | 出处 |
|------|------|
| **相机深度附件没有 `SAMPLED`**，`depthFor` 只声明 `DEPTH_STENCIL_ATTACHMENT` | `RenderingSystem.depthFor`（~710 行）；此前 C1 方案已记录为前置缺口 |
| **G-Buffer 世界位置已可读**：set1 `binding7 = gbufferWorldPos`（w = 有效标志），LightingPass 与 Forward 两路都已绑定 | `frameStorageLayoutDesc`、`updateLightingSet`、`lighting.frag` 第 12~18 行 |
| SSAO 走的是同一思路：**用世界位置反投影**（`cam.viewProj * vec4(samplePos,1)`），完全不碰深度附件 | `ssao.frag` 第 109 行；`SsaoPass` set0 相机块 + set1 normal/worldPos |
| 相机块 `CameraBlock { mat4 viewProj; mat4 view; }` 在 HYBRID 与 Forward 两路着色器里都可用 | `ShaderInjector` 第 43 行；`lighting.frag` 已用 `cam.view` |
| `LightingParamsBlock` 实需 **656B**，`LIGHTING_STRIDE = 768` → **尾部还有 112B 空闲** | `RenderingSystem` 第 144/148 行 |
| `javelinLighting(normal, viewPos, worldPos)` 是**注入段共享函数**，Forward 与 LightingPass 共用 | `ShaderInjector`、`lighting.frag` 第 24 行 |
| `set1` 现用到 binding 11，下一个空槽 = 12 | `Engine_Uniform_Keys.md` §4 |

**结论：本批不需要给深度附件加 `SAMPLED`，不需要新 RHI 能力，也不需要新描述符槽位**——世界位置在屏幕空间已经够用。这与 C1 方案里「C2 硬前置 = 深度附件加 SAMPLED」的预判相反，属于勘察后的方案改进（原预判基于「屏幕空间必须读深度」，实测 SSAO 已证明可用世界位置替代）。

## 3. 方案：世界位置屏幕空间短距步进

对每个可见像素（`worldPos.w > 0.5`）：

1. 起点 `p0 = worldPos`，沿**主光方向**（`-lighting.directional[0].xyz` 归一后的行进方向）步进 N 步，总距离 `tMax`（世界单位，厘米～分米级）。
2. 每步点 `pi = p0 + dir * tMax * (i + 1) / N`，用 `cam.viewProj` 投影到屏幕 uv（越界即丢）。
3. 在 `gbufferWorldPos` 上采该 uv 的 `w1 = worldPosAt(uv).w` 与位置 `q`；若 `w1 > 0.5` 且 **q 比 pi 更靠近相机**（相机空间 z 更小，容差 `bias`）→ 记一次命中。
4. `occlusion = hits / N`；接触阴影系数 `cs = 1 - clamp(occlusion * strength, 0, 1)`。
5. 施加位置：**只乘主光直射项**（与环境项、点光/聚光无关），在 C1 的 shadow 系数之后相乘。

代价：每像素 N 次 `texture()`（N = 8~12）；HYBRID 档限定的额外带宽 ≈ N × 视口像素 × 16B。放在 `lighting.frag` 内可用 `if (contactParams.x > 0.5)` 早退，关档零成本（只多一次 uniform 读）。

**不放进注入段 `javelinLighting`**：Forward 路在写颜色时同帧读 G-Buffer 世界位置，语义上不可靠（G-Buffer 只保证 HYBRID 路本帧有效）。因此落点固定在 `lighting.frag`，属于「HYBRID 档专属画质项」——与 SSAO 的档位口径一致。

## 4. 五点拍板（默认值已给，回「嗯」= 全按默认）

**拍板结论（2026-11-25，用户回「嗯」= 五点全按默认）**：P1 = 只在 `lighting.frag`（HYBRID 限定）；P2 = `contactParams` 挂 `LightingParamsBlock` @656，stride 768 不变；P3 = 复用 `gbufferWorldPos` 反投影，bias 0.02；P4 = 10 步 / tMax 0.35；P5 = `ShadowSettings.contactShadows` 单滑块，缺省 0.5。

| # | 决策点 | 默认（推荐） | 备选 |
|---|--------|--------------|------|
| **P1** | 落点与门控 | 只在 `lighting.frag` 内做，**仅 HYBRID 档生效**；不进注入段共享函数，Forward 路无此项 | 进注入段共享函数（Forward 也吃，语义不可靠） |
| **P2** | 参数载体 | `LightingParamsBlock` 尾部新增 `vec4 contactParams`（@656，x=开关 0/1、y=最大距离 tMax、z=强度、w=步数 N）；`LIGHTING_STRIDE` 768 **不变** | 新增独立 UBO（多一个绑定，收益为零） |
| **P3** | 数据来源 | `gbufferWorldPos`（binding7）反投影 + 相机空间 z 比较，`bias` 取 `0.02` 世界单位 | 给深度附件加 `SAMPLED` + 新 binding12 + 逆投影重建（多一套 RHI 前置，本批理由不成立） |
| **P4** | 步数与距离 | N = 10 步、tMax = 0.35 世界单位（约 35cm）、步距线性均匀 | 对数步距 / 自适应按像素深度（先简单，不足再改） |
| **P5** | 设置项与缺省 | `ShadowSettings` 新增 `contactShadows`（`@InsSlider` 0~1，缺省 **0.5**，0 = 关；语义 = 强度） | 独立布尔开关 + 强度两项（设置面板多一行） |

## 5. 风险与后置

- **R1 自遮挡（暗斑/条纹）**：世界位置采样用 NEAREST + 全屏 1:1，同像素采到自身即为「距离 0」——需要「相机 z 差 < bias 视为不遮挡」，bias 与 tMax 的比值决定效果；拍板后按实机调。
- **R2 后方几何误判**：步进点投影到屏幕后，采到的可能是**被遮挡体前面的另一表面**（典型屏幕空间缺陷，物体边缘出现拉长阴影）。缓解：只接受「比步进点更靠近相机且距离 < tMax」的命中。
- **R3 画面外裁切**：步进点出屏即丢弃，边缘少量缺失，属可接受。
- **后置（不在本批）**：多光源接触阴影（点光/聚光维度的世界位置步进方向需改成「指向灯位」，与 `lights[]` 结合，量级翻倍）；TAA 下接触阴影的抖动采样（当前 10 步固定方向，会有带状感，需要时再上黄金角抖步 + 时间复用）。

## 6. 自验口径（未编译，交用户）

1. HYBRID 档、`contactShadows = 0`：画面与 C1 状态**逐像素一致**（关档必须零残留）。
2. `contactShadows = 1`：箱子/桌腿与地面贴合处出现短暗缝；暗缝长度约等于 tMax 投影，不延伸到整片地面。
3. 把 tMax 调到 1.0：暗缝明显变长、能看出「影子被拉向屏幕方向」的屏幕空间特征（这是该项的固有性质，属预期）。
4. 相机贴近物体时接触阴影增强、远处物体自动消失（受 tMax 与屏外裁切共同作用）。
5. `error.log` 零新 VVL；关档下帧时间与 C1 无差异。

## 7. 落码记录（2026-11-25，全部已提交前状态）

| 落点 | 改动 |
|------|------|
| `ShadowSettings` | 新增 `contactShadows`（`@InsSlider` 0~1 step 0.05，缺省 0.5，注解 `projectSettings.shadowContact` + Hint）+ `getContactShadows/setContactShadows`（钳 0~1）+ `write/read`（缺键回退 0.5） |
| `RenderingSystem` | `LIGHTING_BLOCK_BYTES` 656→**672**；新增常量 `CONTACT_SHADOW_MAX_DISTANCE = 0.35f` / `CONTACT_SHADOW_STEPS = 10.0f`；`packLightingParams` 尾部写 `contactParams`（x=强度>0 ? 1 : 0、y=tMax、z=强度、w=步数）；`LIGHTING_STRIDE` 768 不变（尾部 96B 仍为对齐填充） |
| `ShaderInjector.LIGHTING_BLOCKS` | `LightingParamsBlock` 尾加 `vec4 contactParams`；`javelinLighting` 改为三参转调四参 `javelinLightingEx(..., 1.0)`，四参版把系数只乘在 `i == 0`（主光）直射项；重载前加前向声明（GLSL 先声明后调用） |
| `ShaderInjector.CONTACT_SHADOW` | 新增常量块，**只在 `injectLightingStage` 里 `GBUFFER_BLOCKS` 之后拼入**（Forward 的 `inject()` 路径不含它 → 天然 HYBRID 限定）：`javelinContactShadow` 朝主光 10 步、`cam.viewProj` 投影、越界/背景跳过、`sceneZ > stepZ + 0.02` 记命中，返回 `1 - min(hits/steps * strength * 2, 1)` |
| `lighting.frag`（core 内置 + test-project 副本） | 取 `worldPos` 后算 `contact = javelinContactShadow(worldPos, -normalize(lighting.directional[0].xyz))`，改调 `javelinLightingEx(..., contact)`；副本 SHA256 已核（`184F3DC7…FC3F`） |
| 语言包 | zh/en 各补 `projectSettings.shadowContact` / `.shadowContactHint`（CRLF、无 BOM） |

**自验口径**：同 §6 五条。**不做的**（明确后置）：多光源接触阴影、抖动步进 + 时间复用、深度重建路线（若屏幕空间效果在实机上被否，再回到「`depthFor` 加 `SAMPLED` + binding12」这条完整路线）。

## 7.1 实机反馈修正（2026-11-25 v0.3，误命中整片压暗）

**现象**：强度 `0.05` 时"有点效果"，拉到 `1.0` 后**光照区域成片变暗**，不是只在接触线一条窄带。

**根因**：屏幕空间短距步进只做了「比步进点更近相机即命中」一条判据，缺两道必要门控：
1. **掠射/背光面**——主光方向接近水平或指向相机时，沿光向的步进点投影后普遍落在比自己更近的几何上（地面、近处墙面），`hits/steps` 接近 1；再乘 `strength × 2`，强度 1 直接把整片乘到约 0。
2. **无深度差上限**——投影落到远处墙面/背景附近时，深度差可以很大，仍被判命中；接触阴影本应只管 `tMax` 内的近邻。

**修法（已落码）**：

| 落点 | 改动 |
|---|---|
| `ShaderInjector.CONTACT_SHADOW` | 签名加 `worldNormal`；开头 `dot(normalize(worldNormal), normalize(towardLight)) < 0.2` 直接返回 1.0（掠射/背光不参与——主光项本身已弱，这类像素也不该被屏幕空间步进污染）；命中判定改为 `dz = sceneZ - stepZ`，要求 `dz > 0.02 && dz < tMax × 1.5`（下限容差不变，新增上限滤远处误命中） |
| `lighting.frag`（core 内置） | 调用改 `javelinContactShadow(worldPos, worldNormal, -normalize(lighting.directional[0].xyz))` |

**保留不动**：`tMax = 0.35` / 步数 10 / `strength × 2` 放大 / `contactParams` 打包，全部不变——先把误命中压掉再谈调参。

**验证**：新函数体已用 `glslc --target-env=vulkan1.3` 单独编译通过（临时件，非仓库产物）。**仍需重导全部 `.shader` 与内置 `lighting.frag`**。验收：① 强度 0 与改前一致；② 强度 0.5 时贴地/墙角有窄暗带，光照明亮区域不整体变暗；③ 强度 1.0 只让接触带更深，不出现整屏压暗；④ `error.log` 零新增 VVL。

## 7.2 二轮修正（2026-11-25 v0.4，凸曲面自命中）

**现象**：v0.3 后强度拉到 **0.1** 就明显整片变暗；**球体/圆柱体表面出现明显明暗梯度带**，集中在直射光过渡带与暗侧。

**根因（三条叠加）**：
1. **凸曲面系统性自命中**——起点就在表面外侧 2.5~3.5cm，沿光向走出后投影回屏幕，往往落在**同一凸面更靠近相机的部分**（球顶、圆柱上部）。深度差为正且不大，被 `dz < tMax × 1.5` 放行 → `occ` 在曲面上接近 1。曲率随位置变化 → `occ` 呈环状梯度，10 步离散量化（0.1/步）让过渡带呈阶梯条纹。
2. **`strength × 2` 放大**——`occ ≈ 1` 时强度 0.1 也压 20%，与「接触阴影该是极窄一条」的直觉不符。
3. **步进距离过长**——`tMax = 0.35` 让单步 3.5cm、误命中可接受的深度差上限到 0.525，凸面绕出几乎必进区间。

**修法（已落码）**：

| 落点 | 改动 |
|---|---|
| `RenderingSystem` | `CONTACT_SHADOW_MAX_DISTANCE` **0.35 → 0.2**、`CONTACT_SHADOW_STEPS` **10 → 8**（单步保持 2.5cm，误命中窗口整体缩小） |
| `ShaderInjector.CONTACT_SHADOW` | ① `ndl` 门控 `0.2 → 0.35`（过渡带不再参与）；② 起点 `worldPos + n * (tMax/steps * 0.5)` 沿法线抬半格；③ 命中带 `dz > 0.03 && dz < tMax`（下限 3cm 与单步 2.5cm 匹配，上限按步进距离收，凸面绕出处被剔）；④ 返回 `1 - min(occ * strength, 1)`，去掉 2× |

**已知残留（路线固有）**：屏幕空间短距步进在强凸曲面上仍会有弱环状 `occ` 起伏；彻底压平需要「半分辨率 + 抖动 + 时间累积」或「法线变化/曲率门控」，均属后置（§5）。若 v0.4 后球体上仍有可见条纹，先按「只在平面/凹角启用」的方向做曲率门控，不再靠调参。

**验证**：函数体 `glslc --target-env=vulkan1.3` 通过（临时件）。需重导 `.shader` 与 `lighting.frag`；判据同 §7.1 四条，另加「强度 0.1 不再整片暗」「球/圆柱明暗过渡带无环形条纹」。

## 7.3 三轮反馈后降级（2026-11-25 v0.5，实验项 + 默认关）

**现象（v0.4 之后）**：圆柱体背光侧仍变暗、地面也变暗；**移动相机时地面上的暗带跟着变**（呈条状游走）。

**判定**：v0.4 的四处门控压不掉根因——这是「单帧、全分辨率、8 步、无抖动」屏幕空间短距步进的采样式伪影：
- 大面积平地上，步进点投影到屏幕后与邻域地面点比较深度，**屏幕压缩越强（远距离/低视角）命中率越不连续**，形成暗带；
- 暗带位置绑定像素采样网格，相机一动网格动，故「跟着变」；
- 圆柱背光侧 `ndl` 很小的过渡带仍能通过 `dot(n,dir) < 0.35` 的边缘，主光项本身已弱，再压一点就成了明显暗区。

这类伪影**不是参数问题**，业界处理靠「半分辨率 + 抖动 + 时间累积 + 双面厚度」一整套，属新的工作量。

**处置（v0.5）**：

| 落点 | 改动 |
|---|---|
| `ShadowSettings` | `contactShadows` 缺省 **0.5 → 0**（`read` 回退同改 0）——默认关，老项目已保存的 0.5 仍生效，需手动调 0 |
| `ShaderInjector.CONTACT_SHADOW` | 签名加 `viewPos`；新增近景门控 `-viewPos.z > 4.0` 直接返回 1.0（中远景平地不再参与，条带大幅收窄） |
| `lighting.frag`（core + test 副本） | 调用改四参 `(worldPos, viewPos, worldNormal, lightDir)` |

**结论**：REND-21-C2 降级为**实验项**（默认关、可手动开 0.3~0.5 在近景观察），完整画质版挂 `Engine_Backlog.md` REND-21 后置——重做方向 = 半分辨率 + 抖动 + 时间累积（或法线/深度不连续门控）。本轮验收按「强度 0 时不参与、无副作用」判过即可。


