# 接触阴影（REND-21-C2）落码方案

> 版本：v0.2（2026-11-25，落码件——§4 拍板全默认通过、§7 落码记录）
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
