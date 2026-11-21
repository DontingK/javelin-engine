# DDGI（探针实时 GI）设计（Engine_DDGI_Design）

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

**B2 未收口项（转 B3 一起看）**：①命中点直射项只算 `ndl`、未接 CSM 阴影；②未命中回落常量 `ambient`、不接天空盒；③tile 1px 边框不填（消费面按内区 clamp）；④`MAX_RAY_DISTANCE` 固定 60 m（B5 再按场景尺寸自适应）；⑤`vkDestroyDevice has 101 leaked objects` 是 shutdown 期既有泄漏，与本批无关。

### 12.5 诊断口径 v2（同日，第三轮读数被判为不可用后）

收缩后第三轮实跑：网格 `6x3x4 probes=72`、atlas `90x80` / `162x144`（147 KB）、origin `(-5.0, 0.4416, -1.8201)`——收缩与锚点全部按预期。但中心块命中距离仍出现 `31~54 m`：**这是诊断方式的问题，不是 DDA 的问题**。旧口径取内区**单个角 texel**，其余弦权重锥近乎半个球，一条射线可能命中 2 m、其余全部走满 `MAX_RAY_DISTANCE`，加权均值落成 45 m 这种既不表示真命中也不表示纯兜底的中间值。

v2 改为 update 段线程 `(0,0)` 直接统计内区 64 条射线（读 trace 段产出的 radiance / distance tile，不读刚写入的 atlas，避免 image barrier）：

- `[0..2]` 平均入射辐射 rgb（64 条射线算术平均）
- `[3]` 命中率（`distance < MAX_RAY_DISTANCE − 0.5` 的射线占比；未命中距离从 grid UBO 的 `reservedGrid.y` 取，与 trace 段同源）
- `[4]` 平均命中距离（只统计命中射线；`0` = 一条都没命中）

SSBO 每探针 6 float（24B，8 探针 192B）。**判据**：命中率应显著非零（空场景朝天的方向自然低，属正常）、平均命中距离落在 0.3~4 m 量级、相邻探针之间的命中率应有差异（说明方向/位置真的影响到了求交）。

**实跑结论（2026-11-21 21:19，用户确认通过）**：`#0` 100%（64/64）1.78 m、`#1` 31%（20/64）1.03 m、`#2` 100% 1.47 m、`#3` 27% 1.08 m、`#4` 13% 2.82 m、`#5` 8% 3.91 m、`#6` 11% 3.35 m、`#7` 8% 4.05 m；平均辐射 0.50~0.81。下层探针被几何包住（全命中、1.5 m 量级），上层探针朝外/朝上（大部分射线走环境色、命中距离变长），相邻探针命中率与辐射均有差异 ⇒ DDA 求交与遮蔽真在起作用。**B2 数据面关账**；下一段 B3 消费面。

