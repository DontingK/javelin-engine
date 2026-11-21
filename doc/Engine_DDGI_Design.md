# DDGI（探针实时 GI）设计（Engine_DDGI_Design）

> v0.1（2026-11-20）｜工程台账 P4-05｜四期施工序 ⑥
> 状态：**方案件（未落码）**。本档按「先方案后码」红线（AGENTS.md）出稿，§10 为待拍板四点，拍板后再动代码。
> 前置：RHI 纹理底座 + RHI-13 跨帧布局记忆 2026-11-18 已验收；**RHI-15（格式能力查询）未开工**、RHI-14（子资源面）按 §3 选型可暂不阻塞。

## 1. 定位与命名（先纠一处常见混淆）

| | DDGI | RTXGI |
|---|---|---|
| 是什么 | 论文算法：*Dynamic Diffuse Global Illumination with Ray-Traced Irradiance Fields*（Majercik 等，JCGT 2019） | NVIDIA 的 SDK（`NVIDIAGameWorks/RTXGI`，DDGI 专版 `RTXGI-DDGI`） |
| 依赖 | 求交后端任意：体素 DDA / SDF / rayQuery / RT 核心 | DXR 或 `VK_KHR_ray_tracing`（AS + SBT） |
| 本引擎采用 | ✅ 算法本身 | ❌ 不引 |

**不引 RTXGI 的三条理由**：①绑定 C++ / DXR / AS+BDA 编译链，我方 P4-06 才做；②许可为 `GI_RT_OpenSource_Reference.md` §15 记的**黄档**（源码可见不可抄、不可再分发）；③首版不需要——投射线走 compute 软光追（体素 DDA）。

**参考件（许可分级见参考档）**：G3D 的 DDGI reference GLSL（BSD-2，算法圣经）｜`helenl9098/DDGI-Minecraft`（Apache-2.0，Vulkan compute 无 RT 硬件复现，descriptor/dispatch/barrier 编排模板）｜O3DE `DiffuseProbeGrid`（Apache-2.0，「准备 → 追踪 → 混辐照度 → 混距离」四段骨架）｜nvpro `vk_raytracing_tutorial_KHR`（精确版 rayQuery 备用，P4-06）。

**目标**：把 ④ 的常量 `ambient` 升级为「按位置/方向查询的探针辐照度」，补掉当前引擎**完全没有颜色反弹**这条最大缺口（`Engine_Backlog.md` REND-03/16 是更上层的 PBR/IBL 面，本档只管 GI）。

**明确不做**：烘焙 GI / lightmap（已拍板否决）｜SSGI 与「体素 albedo 一次弹射」两个中间档（2026-11-20 拍板跳过）｜手摆探针组（首版只自动铺网格）。

## 2. 算法形态（三段 compute）

1. **追踪**（每探针 × 每方向一条射线）：八面体方向图定方向 → 投射线求交 → 命中点 radiance + 距离
2. **更新**（每探针一次积分）：方向图 → 辐照度（余弦加权）+ 深度矩（`E(d)`、`E(d²)`）+ 时域混合（hysteresis）
3. **着色**（每像素一次查询）：世界位置 → 8 探针三线性权重 → 每探针按 `worldNormal` 查八面体辐照度 → 可见性加权 → 环境项

探针数据 = 两张 atlas（辐照度 / 深度-可见性），**分帧刷新**（每帧只更新 N 个探针 + 时域累积），显存与每帧成本恒定。

## 3. 数据面：atlas 选型（首版 = 单张 2D atlas 拼 tile）

**选「单张 2D atlas」而非 2D 数组 / 3D 纹理**，理由 = 避开分层/子资源屏障（RHI-14 可暂不做），storage 写 + 采样读都在现有通道内。

| 资源 | tile | 格式 | 用途 |
|---|---|---|---|
| 辐照度 atlas | `8×8` + 1px border = `10×10` | `RGBA16F` | 八面体辐照度；border 用于 oct 接缝 wrap |
| 深度 atlas | `16×16` + 2px border = `18×18` | `RG16F` | `E(d)` / `E(d²)` 矩，供可见性加权 |

尺寸预算示例（间距 2m、体积 32×8×32m → 17×5×17 = **1445 探针**）：辐照度 atlas `400×400` ≈ 4MB，深度 atlas `720×720` ≈ 2MB，合计 ~6MB（5060 8G 无压力）。

## 4. 三个 Pass 细则

### 4.1 `ddgi_trace.comp`（每探针一个 workgroup）
- 探针位置 = 网格（间距可配）；射线数 `raysPerProbe`（首版 64，可升 128）
- **求交后端 = 静态体素 DDA**：体素化在 P4-05 内部重建（`voxelize.compute` 可从 `dc1e9e0` 取回），**过滤口径改为「静态 + 不透明」**（不再复用 `castShadow`，`castShadow` 一标志两用是 P4-17 的老坑）；体素增加 **albedo 通道**（原 AO 链没有）
- 重建门控 = **静态集签名**（数量 + 每个静态物体 world matrix / mesh 的 hash），不是裸计数（BUG-13 的结论）
- 命中 → `radiance = albedo(BaseColor) × (环境项 + 主光 × ndl × 阴影项)`；未命中 → 天空盒颜色（无天空盒回落 `ambient`）
- 输出中间图：radiance + hit distance（每探针一张临时 2D tile，用完即弃）

### 4.2 `ddgi_update.comp`（每探针一个 workgroup）
- 方向图 → 辐照度：对每个八面体 texel 做半球余弦加权平均（含法线偏置）
- 深度图 → 矩：`E(d)` 与 `E(d²)`，位置方差供可见性比较（薄墙光渗的主要抑制手段）
- **时域混合**：`hysteresis` 缺省 0.85~0.95；探针移动 / 重建 / 场景切换 → 历史重置
- 刷新调度：每帧 N 个探针（缺省 64）→ 1445 探针约 23 帧一圈；首帧全量（`time-slice` 可配）

### 4.3 `ddgi_relocate.comp`（可选，实测有黑斑再加）
探针落到墙内/地下 → 沿表面法线梯度推出网格。首版先用「占用体素判定 + 沿六轴最小代价推出」的简化版。

## 5. 消费面

**注入段新增 `vec3 javelinDDGI(vec3 worldPos, vec3 worldNormal)`**，接在 `ShaderInjector` 的 `javelinLighting` 内：

```glsl
vec3 result = (lighting.ambientMode == 1 && insideProbeVolume(worldPos))
        ? javelinDDGI(worldPos, worldNormal)     // DDGI 档
        : lighting.ambient.rgb * lighting.ambient.a;   // CONSTANT / OFF / 网格外回落
```

采样链：8 探针三线性权重 → 每探针 `worldNormal` 转八面体 uv → 双线性查辐照度 → **可见性加权**（目标像素方向深度矩比较，墙后探针降权）→ 法线偏置 `normalBias ≈ 0.3~0.5 × spacing`。

**绑定复用（P4-17 退役刚好腾出的两个槽）**：set1 `binding8` = `sampler2D` 辐照度 atlas；`binding9` = DDGI 参数 UBO（网格原点/间距/尺寸/强度/`ambientMode` 标志/`hysteresis` 运行态）。UBO 余量 656→768 足够，**布局零改动**。

**`Environment.ambientMode` 扩三档**：`OFF` / `CONSTANT`（现状）/ `DDGI`（新增）。

## 6. 与 SSAO 分工（2026-11-20 用户确认）

| 层 | 负责频段 | 参数 |
|---|---|---|
| DDGI | 低频：颜色反弹、墙角/桌底柔和压暗、方向性环境光 | 间距 1~2m、8×8 oct |
| SSAO | 高频：接触线、细缝、小物件根部 | **`radius` 0.25、`intensity` 0.4~0.5**（原 0.5 / 1.0） |

**「DDGI 档关掉 SSAO」不作缺省**：探针间距与 8×8 角分辨率（≈22°）表达不出 5~10cm 接触遮蔽、探针网格外回落 `ambient` 区完全无遮蔽、动态物体分帧滞后——三条都只能由屏幕空间 AO 补。**SSAO 降噪（BUG-12）从「只做到能用」升级为必修**：DDGI 抹平低频后，SSAO 残留颗粒反而更显眼。

## 7. 与其它子系统

- **阴影**：DDGI 不替代 CSM，只替代环境项；命中点的直射项复用 `javelinCascadeShadow`（体素 DDA 命中的表面点取阴影因子）
- **TAA**：分帧刷新会带来探针切换的离散噪声，TAA 天然摊平；不做额外时域抖动也可，实测跳变明显再加每帧旋转相位
- **透明物体**：不进体素；透明材质环境项按 DDGI 结果照常乘（不做折射/透射 GI）
- **FORWARD 档**：与 HYBRID 同口径（注入段同源，只依赖 `worldPos`/`worldNormal`）；FORWARD 没有 G-Buffer 不影响 DDGI

## 8. 批次与验收口径

| 批次 | 内容 | 验收 |
|---|---|---|
| B0 | RHI-15 格式能力查询（并 RHI-08） | 非法 `STORAGE` 组合创建期 fail-fast |
| B1 | `DdgiSettings` + 探针网格 + 两 atlas + 体素化重建（静态集 + albedo 通道） | 探针数/atlas 尺寸/重建耗时读得到 |
| B2 | `ddgi_trace.comp` + `ddgi_update.comp` | 探针辐照度可视化正确（单色墙验证） |
| B3 | 注入段 `javelinDDGI` + `ambientMode=DDGI` + 网格外回落 | **单色房间白球旁有渗色**；关主光后墙角仍有方向性；无黑斑 |
| B4 | 探针 debug 视图（位置 / 辐照度 / 可见性） | 调参可见 |
| B5 | hysteresis + 分帧预算 + 重定位 | 相机移动无跳变、一圈 N 帧 |
| B6 | 与 SSAO 分工参数 + TAA 协同 + 文档/验收关账 | 两层不叠乘、画面稳定 |

工时估算：B0 一天级；B1 2~3 天；B2 3~4 天；B3 3~4 天；B4 1 天；B5/B6 各 2~3 天。**首版（B0~B3）约两周，可看效果约三周。**

## 9. 风险与坑

1. **薄墙光渗**：靠 B3 的深度矩可见性加权，B3 必须有（不能挪到后面）
2. **八面体接缝**：tile 留 border + 采样 wrap 到邻图；border 内容在 update pass 内填（copy 邻图边缘）
3. **探针落在墙内 → 黑斑**：B5 重定位；首版接受少量
4. **相机滚动体积边界**：边界探针降权 + 网格外回落 `ambient`
5. **动态物体滞后**：DDGI 固有（探针分帧）；靠 SSAO 接触层补，不追求零滞后
6. **成本**：1445 探针 × 64 射线 ≈ 9 万射线/整圈；64 探针/帧 ≈ 4k 射线/帧，体素 DDA 下 <1ms；首帧全量 2~4ms
7. **GraalVM AOT 禁反射**：`DdgiSettings` 字段/枚举静态注册（与 `ShadowSettings` 同套路）
8. **体素化开销**：只做静态集 + 签名门控 → 场景不动时零重建（P4-17 的动态物体问题是设计输入，见 `Engine_Backlog.md` BUG-13）
9. **`.shader` 重导**：注入段改动（新增 `javelinDDGI` + binding8/9 声明）→ 必须重导全部 `.shader` 与内置 `lighting.frag`，否则静默不生效

## 10. 待拍板（B0 前须定）

1. **atlas 选型**：单张 2D atlas（本档推荐，RHI-14 不阻塞）vs 2D 数组分层（需先做 RHI-14）
2. **探针体积**：纯相机滚动体积（默认，显存恒定）vs 允许 `Environment.giVolume` 覆盖（灵活但可能显存/探针数暴涨）
3. **首版投射线后端**：体素 DDA（本档推荐）vs 先做屏幕空间追踪应急（质量差、但零体素化成本）
4. **SSAO 分工参数**：`radius` 0.25 / `intensity` 0.45 是否照本档 §6 作为缺省（已按用户 2026-11-20 确认写入 `Engine_Backlog.md` P4-05 备注②，此条待最终定数值）
