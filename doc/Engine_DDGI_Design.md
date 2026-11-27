# DDGI（探针实时 GI）设计（Engine_DDGI_Design）

> **⛔ 本档已归档（2026-11-27）：P4-05 DDGI 整体退役。**
> 退役拍板 = 用户「DDGI 全部退役。该删的都删了」「不用考虑历史数据问题」（推翻 2026-11-27 晨间的「暂时停用、枚举保留」口径）——环境光的 GI 供给收敛到光追 RTGI 一条（`AmbientMode` 收 `OFF/CONSTANT/RTGI` 三档，`RTGI` 即原 P4-06 B7 的 1-bounce 光追辐照度图）。
> **整链删除**：`render/ddgi` 包整删（`DdgiProbeGrid` / `DdgiAtlases` / `DdgiVoxelField` / `DdgiVoxelizer` / `DdgiProbePass` / `StaticSetSignature`）+ `MeshVoxelGeometry` + `DdgiSettings`（项目设置组与场景条件子组两处承载均删）+ `InsEnumSkip` 注解与检查器支持 + set1 binding8/9/10 + `DDGI_BLOCKS` 注入与 `javelinLighting`/`javelinLightingPbr` 环境项 DDGI 分支 + `AmbientMode.DDGI` 枚举值 + `Environment.ddgiSetting` 与 `rtgiPercent` 字段 + 编辑器探针视图三件套（`DdgiProbeVizAppState` / `DdgiProbeViewPass` / `DdgiProbeVizMenuCommand`、菜单组、F5）+ 内置资产 `voxel/`（`voxelize` / `ddgi_trace` / `ddgi_update`）与 `debug/ddgiView.frag` + 单测 `DdgiProbeGridTest` / `DdgiAtlasTest` + i18n 全部键。DebugText 行号收敛（阴影图 12→11、光照 gizmo 13→12、光追直显 14→13）。注入器改动 ⇒ 重导全部 `.shader` 与内置 `lighting.frag`（与既有待重导批次合并）。
> **保留价值**：本档 §13 的排障实录（VVL 00344/09600/03047 一族、atlas 布局转换、描述符池归还、探针逃逸、盒外冻结）与 §3 的 atlas/探针网格纯数学，是后续任何探针类方案（含光追侧未来若做降噪/缓存）的直接输入；代码可从 git 历史取回（最后入库版本见 `Engine_Roadmap_Progress_P4.md` 对应批次行）。
> 卫星档同步退役：`Engine_DDGI_ProbeViz_Design.md`（探针视图，随链删除）、`Engine_DDGI_NearField_Design.md`（原已冻结，随之作废）。

> v0.8（2026-11-26）｜**可见性矩采样改双线性（落码待实机验收）**：圆柱在方向光背侧出现等间距竖向条带——根因 = `javelinProbeVisibility` 取最近 texel（辐照度侧才是双线性），曲面法线周向连续变化扫过 16 档深度 texel 成可见性阶跃，背光面恰在 0/1 临界带。修法 = 矩 `E(d)/E(d²)` 四角双线性后再算 Chebyshev。注入器改动 ⇒ 强制重导全部 `.shader` + 内置 `lighting.frag`。见 §13.10。
>
> v0.7（2026-11-26）｜**定位收口：DDGI = 有界场景 GI + 场外冻结网格（落码待实机验收）**：相机走到探针盒外时网格仍跳一格、整屏间接光换版——机制 = `fitOrigin` 步数被 clamp 到边界（跳一次即停）+ 跳格只走 hysteresis 洗错位（无位移重投影）+ 盒外采样全退化成边界常量（影响面放大到整屏）。**拍板**：DDGI 只交付有界场景 GI（关卡/室内），**相机在体素场外时冻结网格**（沿用上一帧 grid，不跳格/不重扫/不换代，进出各一条日志）；无界 GI（自由飞行、大世界）交给光追——体素场是「整世界静态一张」，网格跟出去也没体素可打，正解是 trace 走 AS 求交。见 §13.9。
>
> v0.6（2026-11-23）｜**移动闪烁止血 + 阴影区反弹修复（落码待实机验收）**：用户实跑反馈两症状——①移动物体时闪烁；②阴影/背光墙的颜色反弹看不到。**①根因**：体素场 AABB = 集合包围盒，物体一停（两帧稳定门控放行）就重建 → 场盒随物体变 → 探针网格 `fittedToField` 跟着变（探针数/原点）→ atlas 换代或整圈 hysteresis=0 硬覆盖，每挪一下闪一下（相机跟随走时每 2m 再叠一发）。**修法两件（止血 a+b，跳过 atlas 双缓冲——留给近景档的位移重投影一并做）**：a=跳格重扫保持设置 hysteresis（不清 firstSweepDone，错位一格的旧值混几圈收敛，hysteresis=0 只留给 atlas 换代/真首圈）；b=场盒只增不减（`grownMin/grownMax` 历次并集，`requestRebuild`/`releaseField` 重置）。**②根因 = §13.7 档位门控的推理错误**：消费面 DDGI 档是「整项替换」不是叠加（`result = javelinDDGI` 直接顶掉常量环境项），不存在「弹两次」；门控成 0 时阴影/背光面命中辐射 = 0 → 阴影区环境项被替换成 ≈0（比不开 DDGI 还黑）。**修法**：`ambientInHit` 恒 1——ambient 进命中辐射参与一次反弹（被命中面 albedo 着色），阴影里的墙也能弹出自己的颜色。反弹淡的其余构成（单弹无反馈、点光不进 GI、体素基色只取 `_Color` 不采贴图）为已知边界不入本批。见 §13.8。
>
> v0.5（2026-11-22）｜**直射项终态：体素 sunVisible → CSM 阴影图（落码待实机验收）**：trace 段命中点直射项的遮挡改采主视口 CSM 阴影图（binding6 深度比较采样 + binding7 352B 阴影参数 UBO，`packDdgiShadowParams` 每帧打包）——与主画面同一张图同一套矩阵，阴影边界对齐、两套影子系统不一致的渗光销账；同批把 §13.6 遗留第 2 条一并收口（DDGI 档下命中辐射不再含常量 ambient，档位开关经 Light UBO `lightingFlags.x` 下发）。无阴影回落（`shadowMapOrNull` 非抛出口 + 占位图 + 级联数 0 恒受光）。见 §13.7。
>
> v0.4（2026-11-21）｜**B3 消费面落码（待实跑）**：环境项由探针辐照度接管——`AmbientMode` 扩 `DDGI` 档、桥每帧推模式；set1 新占 binding8（辐照度 atlas）/ binding9（DDGI 参数 UBO 96B）/ binding10（深度矩 atlas）（P4-17 退役腾出的槽）；注入器新增 `DDGI_BLOCKS`（八面体编码 + tile 内区手写双线性 + 深度矩 Chebyshev 可见性 + 网格三线性），`javelinLighting` 环境项改为「DDGI 档且网格内 → 探针辐照度，否则常量项」。见 §13。
>
> v0.3（2026-11-21）｜**B2 落码 + B3 前置（探针网格按体素场收缩）**：两段 compute（`ddgi_trace.compute` 体素 DDA / `ddgi_update.compute` 余弦加权毯 + 深度矩 + hysteresis）+ 宿主 `DdgiProbePass`（切片派发、首圈不混合、atlas 换代重建临时 tile、诊断 SSBO 读回）。实跑两轮修三处（`ivec2` 隐式缩窄、`SAMPLED_IMAGE`→`COMBINED_IMAGE_SAMPLER`、探针全在场外）。**第三轮实测暴露尺度问题**：配置 16×4×16@2m = 30×6×30 m，体素场仅 ≈10.8×5.8×7.9 m ⇒ 只约 60/1024 个探针在场内，其余全走环境色兜底（命中 32~48 m）。**修法**：新增 `DdgiProbeGrid.fittedToField`（三向数量收缩到 `1+floor(场边长/spacing)`、上限仍取配置值；原点锚在场 min 的 spacing 格点、余量不足一格即居中于场），宿主改用收缩铺法；单测补 3 例（11 例全绿）。见 §12。
>
> v0.2（2026-11-21）｜**B1 数据面落码（未编译、待验收）**：`DdgiSettings`（探针数三向 / 间距 / 每探针射线数 / 每帧探针预算 / hysteresis / 强度 / 法线偏置，接项目设置「图形」页新组）+ `DdgiProbeGrid`（纯数学：相机居中按 spacing 吸附的原点、线性索引 ↔ 世界坐标、网格内判定与归一化、atlas 排版与字节账；辐照度 tile 10×10 / 深度 18×18，`cols = ceil(sqrt(N))`）+ 单测 `DdgiProbeGridTest`（7 例）+ `DdgiAtlases`（两张 atlas 的 GPU 资源与换代生命周期，验证件 `DdgiAtlasTest`）+ `DdgiVoxelField` / `DdgiVoxelizer` / `StaticSetSignature`（体素场与体素化重建，`voxelize.compute` 改为 albedo 通道 + 签名门控）。**尚未做**：追踪/更新着色器（B2）与消费面（B3）。§10 四点按推荐默认执行（单张 2D atlas / 纯相机滚动体积 / 体素 DDA / SSAO 0.25+0.45）。
>
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
- 命中 → `radiance = albedo × (ambient? + 主光 × ndl × CSM 阴影因子)`；未命中 → 天空盒颜色（无天空盒回落 `ambient`）。直射项遮挡采 **CSM 阴影图**（2026-11-22 修正，§13.7）；环境项由档位开关门控（DDGI 档 = 0，其他档保留保诊断可读）
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

- **阴影**：DDGI 不替代 CSM，只替代环境项；命中点的直射项采 CSM 阴影图（**已落码 2026-11-22**，§13.7：移植 `javelinCascadeShadow` 单 tap 降档版、逐级联试投影选级联；中间过渡件体素 DDA `sunVisible` 同日退役）
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

## 10. 拍板（2026-11-21 用户「按计划推进」= 按推荐默认执行）

1. **atlas 选型**：✅ **单张 2D atlas**（RHI-14 不阻塞）
2. **探针体积**：✅ **纯相机滚动体积**（缺省；`Environment.giVolume` 覆盖留后置）
3. **首版投射线后端**：✅ **体素 DDA**（体素化在 P4-05 内部按「静态集 + 签名门控 + albedo 通道」重建）
4. **SSAO 分工参数**：✅ `radius` 0.25 / `intensity` 0.45 作为 DDGI 档缺省（「关掉」不作缺省）

---

## 11. B1 实装记录（数据面，2026-11-21 落码）

| 件 | 落点 | 形态 |
|----|------|------|
| 参数对象 | `cn.donting.javelin.core.settings.DdgiSettings` | 三向探针数（16/4/16 缺省）、间距（2m）、每探针射线数（64）、每帧预算（64）、hysteresis（0.85）、强度（1.0）、法线偏置（0.4×spacing）；`@Reflective` + `@InsField`/`@InsFloatRange`/`@InsIntRange`/`@InsSlider`；`read/write` 缺键保默认；派生 `probeCount()` / `framesPerSweep()` |
| 面板接入 | `GraphSetting` 新组 `@SettingGroup("projectSettings.section.ddgi")` | 声明序在阴影之后；`write/read` 各一行（缺键走 `readSerializable` 的 null 判定保默认）；i18n 中英各 10 键 |
| 网格与 atlas 数学 | `cn.donting.javelin.core.render.ddgi.DdgiProbeGrid` | 相机居中：`origin = floor((cam − extent/2)/spacing) × spacing`（吸附防探针游动）；`probeIndex` / `probePosition` / `contains` / `normalized`；tile 10×10（内区 8）与 18×18（内区 16），`cols = ceil(sqrt(N))`；`irradianceAtlasBytes()` / `depthAtlasBytes()` 供显存账 |
| 单测 | `javelin-core/src/test/java/.../DdgiProbeGridTest.java` | 7 例纯逻辑：原点吸附、atlas 尺寸与字节、索引往返、越界抛错、contains/归一化、参数钳制与一圈帧数、最小网格末行留空 tile |
| atlas GPU 资源 | `cn.donting.javelin.core.render.ddgi.DdgiAtlases` | 辐照度 `RGBA16F` / 深度矩 `RG16F`，用途 `SAMPLED｜STORAGE｜TRANSFER_SRC｜TRANSFER_DST`（STORAGE 写、SAMPLED 读、DST 首帧清零、SRC 供 debug/验收读回；不用 COLOR_ATTACHMENT）；尺寸全体取自 `DdgiProbeGrid`；建前先查 RHI-15 快照的 STORAGE/SAMPLED 位，缺位抛带设备名与快照的 `IllegalStateException`（DDGI 口径，不落到驱动通用报错）；`matches(grid)` = 探针数 + 两张图宽高全等 |
| atlas 生命周期 | `RenderingSystem.ddgiAtlasesFor(cameraName, grid)` / `retireDdgiAtlases` | per-view 惰性创建；版面不符即换代（旧两张图进两帧退役队列，新一对按新尺寸建）；**与交换链尺寸无关**（resize 不换绑，探针网格是相机居中滚动体）；`removeCamera` 回收；创建/换代各打一条日志（尺寸/格式/显存 KB） |
| GPU 验证件 | `javelin-core/src/test/java/.../DdgiAtlasTest.java` | `runTest -DmainClass=...DdgiAtlasTest`：两张图 STORAGE/SAMPLED 能力、缺省网格尺寸对账（320×320 / 576×576）、`matches` 复用与换代判据、第二组网格实建并释放 |
| 体素场资源 | `cn.donting.javelin.core.render.ddgi.DdgiVoxelField` | **单张 N³ `RGBA8`**（`rgb` = 命中面基色、`a` = 占用位），用途 `STORAGE｜SAMPLED｜TRANSFER_DST`，创建时全零上传；参数块 64B（`originResolution`/`voxelSize`/`gridBounds`/`reserved`，与 `voxelize.compute` 同布局）；线性 + 最近邻采样器各一；建前查 RHI-15 的 STORAGE/SAMPLED 位 |
| 体素化 compute | `builtin-asset-core/voxel/voxelize.compute`（+.meta，新 GUID） | 从 `dc1e9e0` 取回后改三处：写 `rgba8` 而非 `r32ui`（**`imageStore` 取代 `imageAtomicOr`**，UNORM 不能原子化，并发「后写者胜」对 GI 无害）、push 区加 `vec4 albedo`（112B，vec4 前补 12B 对齐）、参数块第四行改 `reserved`。体素粒度/保守光栅/主轴投影沿用原口径 |
| 静态集签名 | `cn.donting.javelin.core.render.ddgi.StaticSetSignature` | 集合身份 = 网格实例（identity hash）+ 世界矩阵（**1e-4 量化**，矩阵末位抖动不算变动）+ 基色，拌成 64 位摘要。**取代「裸 renderer 计数」门控**（BUG-13 幽灵暗角） |
| 体素化 Pass | `cn.donting.javelin.core.render.ddgi.DdgiVoxelizer` | 集合口径 = 不透明（`!isTransparent()`）+ `castShadow` + 网格可体素化（`MeshVoxelGeometry`）；**两帧稳定门控**（候选签名连续两帧相同才重建，挡住「移动中每帧全量重刷」）；一个网格实例一个 dispatch（三角形数/64），**只在本帧第一个视口挂载**；重建日志含体素图 MB、实例数、包围盒、收集 ms、建资源 ms + 签名；`MeshVoxelGeometry`（同包桥接）同批取回未改口径 |

**B1 残留（下一段）**：`ddgi_trace.comp` / `ddgi_update.comp` 与消费面（B2/B3）。**STORAGE 实写**归 B2 验收。

**B1 数据面验收**：①`gradlew :javelin-core:test --tests cn.donting.javelin.core.DdgiProbeGridTest` 全绿（**2026-11-21 用户实跑通过**，7 例）；②`gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.DdgiAtlasTest` 打印两组 atlas 尺寸 +「断言全部通过」、ESC 退出不崩（**2026-11-21 用户实跑通过**：`RGBA16F`/`RG16F` 的 STORAGE 与 SAMPLED 四位全 true、缺省网格 320×320 / 576×576（2096 KB）、20×4×16 换代后 360×360 / 648×648（2652 KB）、9 条断言全过；同批编译通过，`DdgiVoxelizer`/`DdgiVoxelField`/`StaticSetSignature` 随模块一起过了编译）；③编辑器跑场景 → 控制台出现一条「体素化重建完成：体素图 128³（8 MB）、实例 N 个、包围盒 [...]、收集 X ms、建资源 Y ms」；④项目设置「图形」页「探针 GI（DDGI）」组 11 项可改可存（含「启用探针 GI」与「体素场分辨率」）、打开旧工程不报错（缺键保默认）。③④ 待实跑。

> **③ 实跑结论（2026-11-21 20:32）**：`体素化重建完成：体素图 128³（8 MB）、实例 7 个、包围盒 [(-5.269, -0.436, -2.769), (5.269, 5.319, 5.129)]、收集 0 ms、建资源 11 ms（StaticSetSignature{entries=7, digest=8e9a01c71f80d5e6}）`——包围盒 ≈ 10.5×5.8×7.9 m ⇒ 单体素 ≈ 8.2 cm；`voxelize.compute` 资产（GUID `d41c8b27…`）加载成功、全程无 ERROR。**视觉无变化属预期**：B1 只建资源，消费面（`javelinDDGI` 注入 + `ambientMode=DDGI`）是 B3，`启用探针 GI` 当前只控制「建/不建体素场与 atlas」，不是画面开关。

**B1 未定（待用户表态）**：引擎目前**没有「静态」标志**（`RenderObject` 只有 `castShadow`/`receiveShadow`），故首版「静态集」只能取「不透明 + 投射阴影」这个近似。若要精确的静态语义（例如场景里加入真正的 `staticGeometry` 标志、由场景桥下发），属下一批口径调整，不阻塞 B2。

---

## 12. B2 落码 + B3 前置：探针网格按体素场收缩（2026-11-21）

### 12.1 B2 两段 compute 与宿主

| 件 | 落点 | 要点 |
|----|------|------|
| 追踪段 | `builtin-asset-core/voxel/ddgi_trace.compute` | 一 workgroup = 一探针、64 线程 = 八面体 8×8 方向；Amanatides-Woo 体素 DDA 走 `RGBA8` 体素场；命中取 `albedo×(ambient+主光×ndl)`、未命中回落环境色；写 radiance / distance 临时 tile 内区 |
| 更新段 | `builtin-asset-core/voxel/ddgi_update.compute` | 余弦加权积辐照度 ×π、深度矩 `E(d)/E(d²)`、`mix(新, 旧, hysteresis)`；**首圈不 `imageLoad`**（atlas 未上传零值若为 NaN，`NaN×0` 仍是 NaN，会把整片辐照度污染） |
| 宿主 | `DdgiProbePass` | 每帧算网格 → 网格/相机变即 atlas 换代并重建临时 tile 与游标；trace/update 各一套 layout+PSO+描述符集；切片派发 `probesPerFrame`；首圈（含首圈最后一刀）hysteresis 传 0；体素图换代后重指描述符；首圈完成后一次性读回诊断 SSBO |
| 诊断通道 | `binding=5` host-visible SSBO | 不走纹理读回（`VulkanTexture.readback()` 仍 `UnsupportedOperationException`）；update 段把中心块统计写入（**v2 口径**：内区 64 条射线的平均入射辐射 rgb + 命中率 + 平均命中距离），宿主 map 读回打印 |

**实跑两轮修掉的三处**：①update 段 `ivec2 ij = gl_LocalInvocationID.xy` 的 `uvec2→ivec2` 隐式缩窄被 shaderc 拒（整段 B2 不跑，落 stub）→ 显式构造；②`VUID-VkComputePipelineCreateInfo-layout-07990` + `VUID-VkWriteDescriptorSet-descriptorType-00319`——GLSL `uniform sampler3D/2D` 是 **COMBINED_IMAGE_SAMPLER**，layout 却写成 `SAMPLED_IMAGE`，三处（trace b0、update b0/b1）改后 VVL ERROR 清零；③探针体积远大于体素场 → 全部场外（详见 12.2）。

### 12.2 第三轮实测：尺度不匹配

中心块 8 探针实测：

```
#0 1.5708  命中 2.22 m       #4 1.7310  命中 45.58 m
#1 1.6393  命中 1.92 m       #5 1.6550  命中 48.93 m
#2 1.8932  命中 3.73 m       #6 1.5708  命中 32.36 m
#3 1.8252  命中 3.04 m       #7 1.5708  命中 36.34 m
```

`#0~#3` 命中 1.9~3.7 m = DDA 真的求到交（链路活）；`#4~#7` 命中 32~48 m = 场外走环境色兜底。根因：配置 16×4×16 × 2 m = 30×6×30 m，体素场 AABB 仅 ≈10.8×5.8×7.9 m ⇒ 场内只装得下约 `5×3×4 ≈ 60` 个探针，其余 960 多个几乎全是兜底值。**B3 上屏后的两个实际隐患**：外围探针把恒定环境色当间接光（漏光式错误光照）；真正带信息的探针只占 6%，画面增益远低于预期。

另注：诊断值取内区**单个角像素**（一条方向），只能证明链路活性，不能当辐照度正确性判据；`r=g=b` 是测试场景灰白单色所致。

### 12.3 修法：`fittedToField`（缺省铺法）

- **数量收缩**：`count = clamp(1 + floor(场边长/spacing), 2, 配置值)`。取整必须用 **floor**——`ceil` 会得到 12 m 跨度去套 10.8 m 的场，边界探针照样在场外。收缩后实测场景为 `6×3×4 = 72` 探针，全部落在场内；atlas 排版 `cols = ceil(sqrt(72)) = 9`、8 行，由 320×320 / 576×576（2096 KB）缩到 90×80 / 162×144（约 150 KB）。
- **原点锚点改用场 `min`**：原实现按世界零点 spacing 吸附，场边界不整除时会把整块网格顶出界（场 x 向 10.538 m / 间距 2 m → 世界格点 −6 起，最左探针 −6 < −5.269）。锚在 `min` 上仍保持「只整格跳、不游动」。
- **滑移余量 `slack = 场边长 − 网格跨度`**：`slack < spacing` → 居中于场（此时网格永远不动，无需吸附）；`slack ≥ spacing` → 按整格跟随相机（步数夹到 `[0, floor(slack/spacing)]`）。
- **旧口径保留**：`cameraCentered` / `cameraCenteredWithin` 不删（单测对照 + 体素场远大于配置跨度时等价），宿主只走 `fittedToField`。
- **设置面语义**：`DdgiSettings` 三向探针数由「实际值」改为「**上限**」（面板文案不变，javadoc 与 `probeCount()`/`framesPerSweep()` 注释改为「上限口径，实际以收缩后为准」）。

### 12.4 落码与验收

改动文件：`DdgiProbeGrid`（新增 `fittedToField` / `fitCount` / `fitOrigin`）、`DdgiProbePass`（`ensureAtlases` 改走收缩铺法）、`DdgiSettings`（三向字段与派生方法改「上限」口径）、`DdgiProbeGridTest`（+3 例：收缩到 72 探针且逐探针判越界、场比网格大保留 1024 且原点整格跟相机、某轴薄于 spacing 退化为 2）。**已编译 + `gradlew :javelin-core:test --tests ...DdgiProbeGridTest` 11 例全绿**（GraalVM 25 / Gradle 9.2.0）。

**实跑验收（✅ 2026-11-21，用户确认）**：①日志 `DDGI 探针数据面就绪` 网格 `6x3x4 probes=72`、atlas `90x80` / `162x144`（147 KB）、origin `(-5.0, 0.4416, -1.8201)`；②中心块统计见 §12.5（命中率 8%~100%、命中距离 1.03~4.05 m、相邻有差异）；③「体素化重建完成」日志不变（体素化未动）。

**B2 未收口项（转 B3 一起看）**：①~~命中点直射项只算 `ndl`、未接 CSM 阴影~~（中间经体素 sunVisible 过渡，2026-11-22 改采 CSM 阴影图销账，§13.7）；②未命中回落常量 `ambient`、不接天空盒；③tile 1px 边框不填（消费面按内区 clamp）；④`MAX_RAY_DISTANCE` 固定 60 m（B5 再按场景尺寸自适应）；⑤`vkDestroyDevice has 101 leaked objects` 是 shutdown 期既有泄漏，与本批无关。

### 12.5 诊断口径 v2（同日，第三轮读数被判为不可用后）

收缩后第三轮实跑：网格 `6x3x4 probes=72`、atlas `90x80` / `162x144`（147 KB）、origin `(-5.0, 0.4416, -1.8201)`——收缩与锚点全部按预期。但中心块命中距离仍出现 `31~54 m`：**这是诊断方式的问题，不是 DDA 的问题**。旧口径取内区**单个角 texel**，其余弦权重锥近乎半个球，一条射线可能命中 2 m、其余全部走满 `MAX_RAY_DISTANCE`，加权均值落成 45 m 这种既不表示真命中也不表示纯兜底的中间值。

v2 改为 update 段线程 `(0,0)` 直接统计内区 64 条射线（读 trace 段产出的 radiance / distance tile，不读刚写入的 atlas，避免 image barrier）：

- `[0..2]` 平均入射辐射 rgb（64 条射线算术平均）
- `[3]` 命中率（`distance < MAX_RAY_DISTANCE − 0.5` 的射线占比；未命中距离从 grid UBO 的 `reservedGrid.y` 取，与 trace 段同源）
- `[4]` 平均命中距离（只统计命中射线；`0` = 一条都没命中）

SSBO 每探针 6 float（24B，8 探针 192B）。**判据**：命中率应显著非零（空场景朝天的方向自然低，属正常）、平均命中距离落在 0.3~4 m 量级、相邻探针之间的命中率应有差异（说明方向/位置真的影响到了求交）。

**实跑结论（2026-11-21 21:19，用户确认通过）**：`#0` 100%（64/64）1.78 m、`#1` 31%（20/64）1.03 m、`#2` 100% 1.47 m、`#3` 27% 1.08 m、`#4` 13% 2.82 m、`#5` 8% 3.91 m、`#6` 11% 3.35 m、`#7` 8% 4.05 m；平均辐射 0.50~0.81。下层探针被几何包住（全命中、1.5 m 量级），上层探针朝外/朝上（大部分射线走环境色、命中距离变长），相邻探针命中率与辐射均有差异 ⇒ DDA 求交与遮蔽真在起作用。**B2 数据面关账**；下一段 B3 消费面。

---

## 13. B3 消费面（环境项接管，2026-11-21 落码）

### 13.1 通道与布局

| 槽 | 内容 | 归属 |
|---|---|---|
| set1 `binding8` | `sampler2D` 辐照度 atlas | 本视口 per-view；`DdgiProbePass` 经 `RenderingSystem.syncDdgi` 重指 |
| set1 `binding9` | DDGI 参数 UBO（96B，非动态） | 本视口 `ViewLighting.ddgiParams`，每帧重写 |
| set1 `binding10` | `sampler2D` 深度矩 atlas | 同上（可见性加权用） |

参数 UBO（std140，与 `ShaderInjector.DDGI_BLOCKS` 的 `DdgiParamsBlock` 同源）：`gridOriginSpacing`（xyz 原点 / w 间距）、`counts`（xyz 三向数量 / w tilesPerRow）、`tileParams`（x 辐照度 tile 边长 10 / y 内区 8 / z 深度 tile 18 / w 深度内区 16）、`ddgiParams`（**x mode 0/1** / y 强度 / z 法线偏置（世界单位 = `normalBiasScale × spacing`）/ w 预留）。**`LightingParamsBlock` 一字未改**（656B/768B 步长不变），故已验收的阴影/簇通道零风险。

### 13.2 场景开关

`AmbientMode` 扩三档：`OFF` / `CONSTANT` / **`DDGI`**（i18n `ins.enum.ambientmode.ddgi` 中英各一）。`Environment` 的 `@InsFieldGroup` 加 `DDGI`（对应空串 = 不展开子组，探针参数在项目设置「探针 GI（DDGI）」组）。`SceneRenderBridge` 每帧推 `setAmbientMode`；`DDGI` 档同时把常量环境项置黑（着色端由探针接管）。

mode 的实际取值 = `ambientMode == DDGI && DdgiSettings.enabled` → 1，否则 0。**0 时着色端完全回落常量项**，所以关掉探针 GI 或没建体素场都是安全的旧行为。

### 13.3 着色端（`ShaderInjector.DDGI_BLOCKS`）

- `javelinOctaEncode(vec3)` —— 与更新段 `octDecode` 对偶（八面体，uv∈[0,1]）。
- `javelinProbeIrradiance(probe, dir)` —— 定 tile（`tx = probe % cols`、`ty = probe / cols`）→ 内区手写双线性（4 次 `texelFetch`，**clamp 在内区**）。不用硬件 LINEAR 的原因：B2 未填 1px 边框，硬件插值会跨 tile 借到邻探针 texel。
- `javelinProbeVisibility(probe, dir, dist)` —— 深度矩 Chebyshev 上界 `var/(var+d²)`；`E(d)<=0`（该方向一条都没命中）返回 1.0 不降权。
- `javelinDDGI(worldPos, worldNormal)` —— 法线偏置后的位置 → 网格三线性 8 探针（`base` 夹到 `cnt-2`，避免越界取邻）→ 每探针辐射 × 可见性 × 权重 → 除以权重和 × 强度。
- `javelinLighting` 开头：`result = ambient.rgb*ambient.a`；若 `ddgiParams.x>0.5 && javelinDDGIInside(worldPos)`，则世界法线 = `transpose(mat3(cam.view)) * viewNormal`，`result = javelinDDGI(...)`。网格判定含**半格外扩**（外沿仍借边界探针，避免硬切边）。

**注入顺序**：`CAMERA_BLOCK → DDGI_BLOCKS → LIGHTING_BLOCKS`（DDGI 函数须先于 `javelinLighting` 定义）。两处注入点（材质 fs 的 `LAMBERT` 分支、全屏光照段的 `injectLightingStage`）同源，故 FORWARD / HYBRID 两路一致。

**开销账**：每像素 8 探针 ×（4 次辐照度 + 1 次深度矩）= **40 次 `texelFetch`**。首批要正确性；集显帧率不足再降探针数或改单 texel（转 Backlog）。

### 13.4 落码与验证

改动：`AmbientMode`（+DDGI）、`Environment`（分组键）、`SceneRenderBridge`（推模式）、`RenderingSystem`（`ambientMode` + `setAmbientMode`/`ambientMode()` + set1 三槽 + `ViewLighting.ddgiParams` + `syncDdgi`/`rebuildLightingSet`/`writeDdgiParams` + 1×1 占位图 + `close()` 释放）、`DdgiProbePass`（每帧 `syncDdgi`）、`ShaderInjector`（`DDGI_BLOCKS` + 环境项接管 + 保留字）、i18n 两条。

**已做**：全模块编译通过；`javelin-editor:test --tests ...importer.shader.*` 全绿（其中 `litShaderEndToEnd` 是**真实 shaderc 编译注入后的 fs**，等于替用户先验了 GLSL 语法/布局）；`DdgiProbeGridTest` 11 例仍全绿。

**待实跑**：①项目设置里把环境光模式切到「探针 GI（DDGI）」；②场景出现彩色环境渗色（灰白测试场景应见到墙面/地板颜色进阴影）；③`error.log` 无新 `[VVL]`；④关掉「启用探针 GI」应回到旧常量环境项（不崩）。

**必做前置**：注入器改动 → **重导全部 `.shader` 与内置 `lighting.frag` 产物**（项目面板右键「重新导入」，或 `AssetImportPipeline.reimportAll`），否则运行的是旧注入、DDGI 静默不生效。

**B3 残留（待后续）**：①只有首视口绑 DDGI 数据面，第二视口 mode 恒 0（回落常量项）；②深度矩按单 texel 取、未做方向插值；③~~命中点直射项未接 CSM 阴影~~（已销账 2026-11-22，§13.7；未命中仍未接天空盒）；④tile 1px 边框仍未填；⑤40 次 `texelFetch` 的性能未实测。

### 13.5 首跑排障（2026-11-21 21:52 用户实跑：过亮 / 闪烁 / 画面不对）

实跑现象 = 环境项偏亮、画面成片闪、整体普亮。`error.log` 直接坐实第一条原因（每帧两组 VVL 刷屏，到 duplicate 上限：

- `VUID-vkCmdDispatch-imageLayout-00344`：更新段以 `STORAGE_IMAGE`（`GENERAL`）写 atlas，但图里这张图当前是 `SHADER_READ_ONLY_OPTIMAL`；
- `VUID-vkCmdDraw-None-09600`：`vkQueueSubmit` 时 Forward/Lighting 采样 atlas，期望 `SHADER_READ_ONLY_OPTIMAL`，实际是 `GENERAL`。

**根因**：DDGI atlas 只被 compute Pass 声明了 `addTextureStorageWrite`，**着色 Pass 一侧没有 `addTextureRead`**——图里没有「写→读」这条边，于是既没有 `GENERAL ↔ SHADER_READ_ONLY` 的布局转换，也没有跨段屏障：着色端读到的是**本帧正在被 compute 改写的图**（闪、亮斑、错色全由此而来）。

**四条修法**（同日落到工作区）：

1. **补齐依赖声明**（治闪/错色，主因）：`RenderingSystem` 新增 `ddgiAtlasesOf(cameraName)`（只读现成绑定，不触发建图）；`OpaqueForwardPass` / `TransparentForwardPass` / `LightingPass` 在 `addToGraph` 里对辐照度/深度矩两张图 `addTextureRead`（与 `SsaoPass` 对 AO 图「compute 写 + 图形读」同构）。图据此插转换与屏障。
2. **亮度除回 π**（治过亮）：更新段存的是辐照度 `E = ∫L·cosθ·dω ≈ π·L̄`，而 Lambert 出射辐射度是 `albedo·E/π`——调用方只乘 albedo，所以消费端必须除 π。`javelinDDGI` 返回值改 `… * 0.31830989`（实跑前实测约亮 3 倍，正是这个 π）。
3. **八面体解码改正**（治方向错/渗色）：`ddgi_trace` / `ddgi_update` 的 `octDecode` 原写法是「先改 `n.x` 再用新 `n.x` 算 `n.y`」，下半球方向被扭歪，与消费端 `javelinOctaEncode` 的标准式不是互逆 → 朝下法线采到的是另一条方向的 texel。两处改成标准式 `n.xy = (1 - abs(n.yx)) * sign(n.xy)`（同时读未改写的 `n.yx`），与 `javelinOctaEncode` 严格对偶。
4. **常量环境项不再置黑**（治网格外黑洞 / 天空无光）：`SceneRenderBridge` 对 `DDGI` 档也把 `AmbientSetting` 推给 `RenderingSystem`（原来只有 `CONSTANT` 才推、其余置黑）。它一身三用：网格外像素回落常量项、追踪段未命中射线的天空色、与 ambient 相乘的后处理不至于归零；网格内仍由探针接管，不重复累加。

**验证**：`glslc -fshader-stage=compute --target-env=vulkan1.2` 两段 compute 均通过（13.6 KB / 16.0 KB SPIR-V）；`:javelin-core:compileJava :javelin-editor:compileJava` 通过；`--tests ...importer.shader.*` 全绿（`litShaderEndToEnd` 真 shaderc 编注入 fs）。**生效前提**：这次改的不只是注入器，`ddgi_trace.compute` / `ddgi_update.compute` 也变了 → **重导要连 `.compute` 一起**（`.shader` / `lighting.frag` / 两个 `.compute` 全部重导），漏一个就会出现「着色端除 π 了、图里还是 π 值」这种半生效。

### 13.6 第二、三轮实跑（2026-11-21 23:45 / 11-22 09:38）

**23:45 轮**：`error.log` 从「两组 VUID 每帧刷屏」降到只剩 1 条 `VUID-vkCmdDraw-None-09600`（首帧采样一张还是 `UNDEFINED` 的 atlas）；`Lit.shader` 产物里已能查到 `javelinDDGI` / `DdgiParamsBlock` / `ddgiIrradiance` 符号（注入确实生效）；但画面「开了跟没开一样」。判定 = **消费面没接管**（`ddgiParams.x==0`，`Environment.ambientMode` 没切到 DDGI）。补两处：①`DdgiAtlases.create` 改成建图时上传一张全零图（不再从 UNDEFINED 起，顺手消掉那条 VVL，边界 tile 也不会是垃圾值）；②`RenderingSystem.writeDdgiParams` 加状态日志——接管时报「DDGI 消费面接管：camera[...] 网格… 强度… 法线偏置…」，`启用探针 GI` 开着但模式不对时报 WARN 明确点出「环境光模式 = CONSTANT，要选 DDGI」，只在状态变化时落一条。

**09:38 轮**：`DDGI 消费面接管：camera[Editor Camera Node] DdgiProbeGrid{8x4x6 probes=192, spacing=1.5 ...}，强度 1.0`——接管成立（探针网格随设置里的间距 1.5 收缩到 192 个）。用户反馈「**阴影太亮**」。读数 = 中心块平均入射辐射 0.52~0.83、命中率 13%~95%。0.78 radiance 意味着阴影区 ≈ 照亮区 78%，看着像没阴影。**根因**（两条，都不是强度数值）：

1. **追踪段命中点没有直射遮挡测试**：原来只算 `ndl`，被别的几何挡住的墙面也按「正对太阳」出射全亮度 → 间接光整体偏高（这就是 B2 遗留项③「未接 CSM」的真正代价）；
2. 常量 ambient 现在也进了追踪段的命中辐射（§13.5 第 4 条所需），而它本身代表天空环境——在封闭场景里等于把假环境光再弹一次。

**修法（两条均已收口，见 §13.7 终态）**：第 1 条先落体素 DDA `sunVisible(hitPos, normal)`——从命中点沿太阳方向再走一次短程 DDA（起点沿面法线退 1.2 倍体素对角线避免撞自己），打出网格才算见太阳，被挡则直射项归零。代价 = 每条射线多一次 DDA（探针追踪成本约翻倍，性能账归 B5）。强度 1.0 是默认值、也是**线性**放大系数，等遮挡补上后仍偏亮再调它（或按场景调 0.6~0.8）。

### 13.7 直射项终态：体素 sunVisible → CSM 阴影图（2026-11-22 落码，待实机验收）

§13.6 两条根因的终态修法。**为什么换掉 sunVisible**：它是当天早些时候的过渡件——体素遮挡与主画面 CSM 是两套影子系统，边界不一致（体素判不出的遮挡在 CSM 里是影子）→ 阴影区仍渗「体素级假直射」；且每条射线多一次 DDA，探针追踪成本翻倍。换成采 CSM 阴影图后：同一张图、同一套级联矩阵，阴影边界与主画面对齐，还把翻倍的 DDA 省回来。

**落码形态**（改动三件：`ddgi_trace.compute` / `DdgiProbePass` / `RenderingSystem`）：
- **trace 段新增 binding6/7**：binding6 = 主视口 CSM atlas（`sampler2DShadow` 深度比较采样）；binding7 = 352B 阴影参数 UBO（`cascadeHead` + `sunViewProj[4]` + `cascadeSplits` + `shadowParams` + `shadowParams2` + `cascadeInvZ`，与 set1 的 CSM 段同源布局，由 `RenderingSystem.packDdgiShadowParams` 每帧打包（新公开口，与 `packDdgiLighting` 对称）。
- **级联选择 = 逐级联试投影**（与主路径按视 z 选不同）：探针射线命中点散布全场、不经主相机视锥，按视 z 选没有意义；第一个把命中点投进 [-1,1]³ 的级联即用之，全出界 = 盒外恒受光。命中点远离主相机时落远级联（精度低）或恒受光——探针低频数据可接受，后续要提质再评估。
- **PCF 降档单 tap**：探针数据有 hysteresis 时域平滑 + 消费面三线性两级平滑，单 tap 锯齿不会直接上屏；升 4 tap 挂 §13 残留。refZ 补偿（refDepthOffset × cascadeInvZ + 半纹素外扩）与主路径同源。
- **环境项档位门控（第 2 条根因收口）**：Light UBO 的 `lightingFlags.x` = 命中辐射是否含 ambient 项——DDGI 档（消费面接管）= 0（常量环境项已由消费面负责，探针里再弹一次等于假环境光弹两次）；其他档保留（否则探针全黑、诊断读数失真）。`writeLightUbo` 从 `ambientMode()` 取值。
- **无阴影回落**：主光未激活/关阴影时视口没有 ViewShadow——`shadowMapFor` 会抛 IllegalStateException，新增非抛出口 `shadowMapOrNull` + `packDdgiShadowParams` 空表回落全零（级联数 0 → compute 侧恒受光，天然回落不炸描述符）；binding6 挂 1×1 占位深度图（`placeholderShadowTexture` 懒建）。
- **图声明**：trace compute pass 增 `addTextureRead(主视口 shadowMap)`——显式依赖 + 句柄进 structureHash（CSM 换代自动失效图缓存）；Pass 顺序前置事实已满足（DdgiVoxelizer after=Shadow、DdgiProbePass after=DdgiVoxelizer）。

**随验排障（2026-11-22 晚实跑，已修）**：画面生效但报两类 VVL：
- **00309（vkFreeDescriptorSets 在用即 free，10+ 条）**：同日早些时候给 `VulkanDescriptorSet.dispose` 补的立即 free 撞的雷——VUID 00309 要求所有引用该 set 的已提交命令缓冲**执行完毕**，而引擎的 retire+2 帧窗口在 collectFrame 帧首冲（早于上一帧的 submit 栅栏 await），窗口差半帧。**修法**：descriptor set 注册进 `CleanerRegistry` 两帧延迟销毁队列（新增 `ResourceType.DESCRIPTOR_SET`；`dispose` 只入队，`onFrameEnd` 帧末 submit 之后推进才 `vkFreeDescriptorSets`）——与 BUFFER/TEXTURE 同一窗口且推进时点在 submit 之后，语义严严覆盖。
- **09600（首帧采样 UNDEFINED 颜色图，3 条）**：全部落在启动首帧 resize（737x515→513）重建前的首次提交，之后整场零复发——是 §13.5 已录过的 legacy 首帧现象，与本批改动无关。但排查中发现一个真隐患：**占位深度图**（无阴影时 binding6 的 1×1 D32）创建时传 null、又不进图声明（`addTextureRead` 只挂真 shadowMap）→ 永不转布局、采样即同款 09600。**修法**：改带数据创建（D32 + SAMPLED + TRANSFER_DST，深度值 1.0 = 远平面恒未遮挡）——上传路径把布局走到 SHADER_READ_ONLY，与 binding6 描述符声明一致（`DdgiAtlases.createAtlas` 同款思路，Pitfalls #37）。

**验收清单**（待实机）：①重导 `ddgi_trace.compute`（项目面板右键「重新导入」；只动这一个，update 段无改动）；②阴影边界摄影验证阴影区间接光变暗、过渡与主画面 CSM 对齐；③诊断行「平均入射辐射」应比修前下降（阴影贡献不再虚高）；④`--debug=true` 跑一次 VVL（重点看 compute 里采 sampler2DShadow 有无校验层抱怨）；⑤无主光场景不崩（占位图 + 恒受光回落）。

### 13.8 移动闪烁止血 + 阴影区反弹修复（2026-11-23 落码，待实机验收）

用户实跑报两症状：**移动物体时间接光闪烁**、**阴影/背光墙的颜色反弹看不到（疑似太淡）**。全链路静态排查（体素化 → trace → update → 消费面注入段）定位根因，用户拍板止血组合 a+b（atlas 双缓冲 c 不做）；反弹修复随诊断坐实。

**症状①机制链（两条复合）**：

1. **场盒随集合即时缩放**：移动物体（不透明 + castShadow，属体素化集合）停顿 ≥2 帧 → 签名两帧稳定 → `DdgiVoxelizer.prepare` 整场重建。场 AABB = 集合包围盒随物体位置变 → 探针网格 `fittedToField` 跟着变：探针数跨 spacing 整数倍 → **atlas 换代（全零新图）**，重扫完成前（探针数 ÷ 每帧预算 ≈ 1~2 帧）探针值全零 → 掉黑再爬升；只是原点滑移步数变 → `moved` 分支清 `firstSweepDone` → **整圈 hysteresis=0 硬覆盖**，收敛值瞬间换生值。编辑器拖拽中手常有 ≥2 帧微停顿 → 每次微停顿都重建一发 → 「移动全程闪烁」。
2. **网格原点跟随相机整格跳**：`fitOrigin` 在场盒余量 ≥ spacing 时按相机滑动，每约 spacing（2m）跳一格 → 同样触发整圈 hysteresis=0 重扫。相机跟物走（第三人称/编辑器跟随）时与 1 叠加。

**修法（两件，均纯 Java 宿主侧，`.compute`/`.shader` 零改动、无需重导）**：

- **止血 a（`DdgiProbePass.ensureAtlases` 的 `moved` 分支）**：只把 `sliceCursor` 归零重扫，**不清 `firstSweepDone`**。旧探针值只错位一格（探针本是低频数据），按设置 hysteresis 混几圈收敛（0.85 时 ≈ 1/(1−0.85) ≈ 7 圈），远好于硬切；hysteresis=0 只留给 atlas 换代/真首圈——新图全零，混历史等于从黑往上爬。
- **止血 b（`DdgiVoxelizer` 场盒只增不减）**：新增 `grownMin/grownMax` 历史盒 = 历次集合包围盒的并集，`prepare()` 并入再建场。物体在场内移动不再动场盒 → 网格与探针数稳定 → 不触发换代/重扫，重建只静默换体素内容（探针按 hysteresis 平滑过渡到新体素图）。重置口：`requestRebuild()`（删大件回收场地）与 `releaseField()`（场景清空/关开关）。
- **遗留（近景档预告内）**：跳格的完美过渡要 atlas 位移重投影（原点平移一格 = tile 平移搬运）或探针 relocation；atlas 换代双缓冲（新图后台扫完再切，消掉黑窗）= 本次评估的方案 c，与重投影一并归近景档。

**症状②根因 = §13.7 档位门控的推理不成立（本批推翻）**：

- §13.7 把 DDGI 档命中辐射的 ambient 门控成 0，理由「常量环境项已由消费面负责，探针里再弹一次等于假环境光弹两次」。逐字核对消费面（`ShaderInjector` 的 `javelinLighting`）：DDGI 档是 **`result = javelinDDGI(...)` 整项替换**常量环境项、不是叠加——「弹两次」不存在。
- 反向代价：命中辐射 = `albedo × (0 + 直射)`，墙在阴影/背光时直射项（ndl×CSM）= 0 → 命中辐射 = 0 → 封闭/阴影区探针辐照度 ≈ 0 → **环境项被替换成 ≈0**（比不开 DDGI 还黑），反弹整体消失——即用户报的「墙壁颜色反射看不到」。
- **修法（`DdgiProbePass.writeLightUbo`）**：`ambientInHit` 恒 1（`lightingFlags.x` 通道与 `.compute` 侧读取保留，无需重导）。ambient 进命中辐射 = 环境光参与一次反弹（被命中面 albedo 着色），阴影里的墙也弹出自己的颜色。与 §13.6「阴影太亮」不冲突——那轮 0.78 是「无遮挡下阴影墙出射全直射」；现在阴影墙出射的是 ambient 量级（远低于直射）。
- **使用口径**：ambient 从此是 GI 的**环境源光**——DDGI 档下 `Environment` 环境光仍要设非零（设 0 且全场阴影 → 反弹也归 0）；反弹总量 = 命中面 albedo × (ambient + 直射)，嫌淡先拉环境光强度或 `ddgiIntensity`。已知边界（不入本批）：反弹色只来自材质 `_Color`（贴图 albedo 不进体素化）；单弹、无原版 DDGI 的多弹时域反馈；点光/聚光不进探针光照。

**改动面**：`DdgiProbePass`（moved 分支 + `writeLightUbo` + 删 `AmbientMode` import）/ `DdgiVoxelizer`（`grownMin/grownMax` + `prepare`/`releaseField`/`requestRebuild` + 类注释）。IDEA inspections 零 error。

**验收清单（待实机）**：①编辑器拖拽物体（含微停顿）不再闪烁——`体素化重建完成` 日志可照刷，但 `DDGI 探针数据面就绪` 不应跟着刷（场盒稳定 = 不换代）；②相机走动每 ~2m 跳格处过渡平滑（hysteresis 混合、无硬切）；③阴影/背光墙能看出反弹色（墙 `_Color` 设彩色验证），室内不再比不开 DDGI 暗；④诊断行「平均入射辐射」阴影区探针不再 ≈0；⑤环境光强度 / `ddgiIntensity` 调反弹量即时生效。

### 13.9 定位收口：DDGI = 有界场景 GI + 场外冻结网格（2026-11-26）

用户实跑发现「相机走到探针盒外，网格还会跳一格，屏幕上光照/阴影跟着变一点」。追到机制后拍板定位：**DDGI 只交付「有界场景 GI」（关卡内/室内），无界（自由飞行、大世界）GI 交给光追**，本批做场外冻结。

**症状机制（三层，均为既有设计代价，非新 bug）**：

1. **场外原点被 clamp**：`DdgiProbeGrid.fitOrigin` 把步数夹在 `[0, floor(slack/spacing)]`（`DdgiProbeGrid.java:176-177`）。相机往外走时 `desired` 一直变大，但网格滑到边界就停 → 只在经过某格边界时跳一次，之后不动。
2. **跳一格 = 边界探针值换血**：`moved` 只归零 `sliceCursor` 按 hysteresis 收敛（§13.8 止血 a），没有位移重投影（跳格时 tile 序号不变、探针世界位置平移一格 → 值错位）。
3. **影响面被放大到整屏**：消费面 `javelinDDGI` 对归一化坐标 clamp，盒外所有采样点都退化成边界那圈探针 → 边界值一换，整个可见区域的间接光同时换一版。这才是「只跳一格却看着一大片都变了」的原因。

**修法（`DdgiProbePass.ensureAtlases`，纯宿主侧）**：先算相机是否落在体素场 AABB 内（`cameraInField`）。**在内**照旧 `fittedToField` 跟随（整格跳 + hysteresis 收敛）；**在外**直接把 `newGrid = grid`（沿用上一帧网格）→ `sameGrid` 成立 → 不跳格、不重扫、不换代。进出各打一条日志：`DDGI 网格冻结：相机已离开体素场…` / `DDGI 网格恢复跟随：相机回到体素场内`（每个状态转换一条，不刷屏）。

> **后续（2026-11-26，§13.11）**：本节的「场内跟随 / 场外冻结」整体退场——网格改为**锚定场内、绝不跟相机**，`cameraInField` 判定与两条冻结日志一并删除（`anchoredToField` 下不存在跳格，也就不需要冻结）。本节保留为决策历史。

**行为契约（是设计口径，不是残缺）**：相机越界后画面退化为「边界常量间接光」（不抖、不变），数据侧保持最后一份有效 GI；回到场内自动恢复跟随。**要真正的无界 GI，不是把网格跟出去，而是换求交后端**——体素场是「整世界静态一张」，探针飞出去没有体素可打，只有 ray query 的 AS 求交不受此限（见 `Engine_RayTracing_Plan.md`）。

**边界**：体素化未就绪（`field == null`）时仍走 `cameraCentered`，此时无界判定不可用（此时也还没有 GI 数据可抖）。

**关联**：§13.8 止血 a/b（跳格过渡与场盒只增不减）、`Engine_DDGI_NearField_Design.md`（自本批起冻结、不排期，待光追 AS 求交走通后整档作废）。

### 13.10 可见性矩采样改双线性（2026-11-26，治"圆柱背光面竖条带"）

用户报：圆柱在方向光的背面，阴影区内出现**明显的等间距竖向条带/梯度**。根因 = 消费段 `javelinProbeVisibility` 对深度矩取的是**最近 texel**（`ivec2(octaEncode(dir) * depthInner)`），而辐照度侧是手写双线性。圆柱侧面的法线沿周向连续旋转 → 八面体 uv 连续扫过 16 档深度 texel（`DEPTH_INNER=16`，每档 ≈22.5°）→ 可见性成为**阶跃**；背光面恰好是「探针到像素的距离 > 该方向命中距离」的临界带，相邻 texel 直接 0/1 翻转，于是 16 条等间距硬边，叠加辐照度侧较平滑的 8 档插值，看起来就是"竖条 + 梯度"。

**修法**：`javelinProbeVisibility` 的矩采样与 `javelinProbeIrradiance` 同口径——`st = octaEncode(dir) * depthInner - 0.5`、四角 `texelFetch` 后双线性混合 `E(d)` / `E(d²)`，再算 Chebyshev。阶跃变连续梯度，条带消失；成本 = 每探针多 3 次 `texelFetch`（每像素 8 探针，共 24 次额外深度采样），`m1 <= 0`（未扫/朝天）仍直接回 1.0。

**注入器改动 ⇒ 强制重导全部 `.shader` 与内置 `lighting.frag` 产物**（否则跑的还是旧最近邻版本）。

**边界**：①双线性矩是近似（均值方差都线性插值后再算 Chebyshev 上界），只影响可见性的平滑度、不改降权语义；②八面体映射在 `n.z<0` 处的翻转缝仍可能留**一条**竖线（clamp 而非 wrap），若复验后只剩单条等角位置硬线，那是 seam，需按 uv 邻域 wrap 采样单独处理（未在本批做）；③真实方向分辨率不足（16 档）导致的辐照度方向性粗糙仍在，属"探针低频"的已知边界。

### 13.11 网格锚定 + 探针逃逸（2026-11-26，治"旋转相机盒内暗面闪"与"物体贴探针出光斑"）

用户实测两条现象，均在**光追三档全 0**（纯 DDGI）下复现：①盒内（前开口、斜射入光）那面暗墙，旋转/平移编辑器相机时**周期性闪变**；②把一面墙拖到**刚好压在探针上**时，墙附近冒出**亮斑**。

**现象①根因 = 网格跟相机整格吸附 + 跳格时游标归零**：`fitOrigin` 用 `cam − extent/2` 取 `round(spacing)`，相机每跨半格（≈1 m）就把整个 `origin` 平移一格；探针 atlas 是「世界位置 → tile」的定值缓存，origin 一动，**每个 tile 的读数整体错位一格**。而 `moved` 分支又把 `sliceCursor = 0`，于是每帧只重算开头 `probesPerFrame`（默认 64）个探针，网格 72 探针时尾部 8 个**永不刷新**；错位的旧值、部分新值、加上 hysteresis 0.85 的慢收敛混在三线性插值里 → 暗面间接光一档一档地跳。

**修法 ①（`DdgiProbeGrid.anchoredToField` + `DdgiProbePass.ensureAtlases`）**：网格原点改为**只居中于场的滑移余量**（`min + slack/2`），与相机彻底解耦；`moved` 分支**不再归零游标**（原点变只可能来自体素场 AABB 变化，让游标自然推进、一圈内全量重算）。DDGI 是场景光照缓存，覆盖范围本就按场 AABB 收缩，在余量里滑动没有任何收益。`fittedToField` 保留（单测仍覆盖旧口径），不再是宿主口径。

**现象②根因 = 探针落在实心体素内**：`ddgi_trace.compute` 的探针起点原样取网格节点，没有 probe relocation。墙/地板穿过节点时，`traceVoxel` 起始格就是实心体素，第一格即命中（`t ≈ 0`），命中法线由步进方向随手取，`radiance = albedo × (ambient + 主光×ndl×shadow)` 常为满亮；这个"墙内面的读数"被写成该 tile 的辐照度，消费面按世界位置三线性插值，就当成**空气中的间接光** → 墙附近亮斑（本质是漏光，量级比正常间接光高一档）。

**修法 ②（`ddgi_trace.compute` 的 `relocateProbe`）**：探针起点若是实心体素，沿 6 个轴向逐步外推（最多 `PROBE_ESCAPE_STEPS=6` 体素；128 分辨率、10.8 m 场下单体素 ≈0.084 m，上限约 0.5 m ≪ spacing 2 m），取第一个空气体素中心为实际起点；6 步内全实心（厚墙内部）则 `trapped`，该探针按「未命中环境色」处理，不产爆亮。幅度远小于 spacing，对消费面三线性假设的影响可忽略（与经典 DDGI probe relocation 同口径）。

**改动面**：`DdgiProbeGrid`（新增 `anchoredToField`）、`DdgiProbePass`（改口径 + 删 `cameraOutsideField` + `moved` 不归零 + `gridMoveLogged` 一次性日志）、`ddgi_trace.compute`（`relocateProbe` / `solidCell` / `PROBE_ESCAPE_STEPS`）。`DdgiProbeGridTest` 11 例仍全绿。

**注入/资产面**：`ddgi_trace.compute` 是内置资产的**项目副本**（`test/test-project/.javelin/assets/core/voxel/ddgi_trace.compute`），改源文件后必须同步副本（本批已用源覆盖副本、`.meta` guid 不动），否则跑的是旧 SPIRV。

**验收口径**：①光追三档全 0、环境光 DDGI，旋转/平移相机绕场景一圈，盒内暗面亮度**无周期跳变**（`DDGI 网格原点变更` 日志不应在相机移动时出现）；②拖动墙到任意探针位置停留，墙附近**无亮斑**、暗面不漏光；③墙停下 ≥2 帧后 GI 按 hysteresis 平滑过渡到新体素图（`体素化重建完成` 可刷，`DDGI 探针数据面就绪` 只在探针数变时出现）。

**边界**：①锚定后相机跑出体素场，画面退化为边界常量间接光（与 §13.9 结论一致，不抖不变）；②拖大件改变场 AABB → 探针数变 → atlas 换代从零重扫 1~2 帧（黑窗仍在，双缓冲未做）；③逃逸只处理「起点在实心体素」，不处理「探针贴墙 1 体素内」的漏光（那属于 normal bias / 法线偏置，`DdgiSettings.normalBias` 已定义但尚未接进 compute push）。
