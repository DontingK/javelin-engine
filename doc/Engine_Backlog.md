# 引擎待办总账（Engine Backlog）

> 版本：v0.1（2026-11-11 建档）
> 定位：**「已计划、尚未完成」的唯一登记处**——未开工 / 半成品 / 后置项 / 待拍板 / 已知问题（bug 类）全部在此收敛，一项一行。
> 只写「要做什么 + 落点 + 归类 + 依赖」；设计论证、拍板依据、实现细节一律留在各设计档，本文不做转述。

## 0. 与邻档的分工（防止两处维护）

| 文档 | 管什么 | 与本文的关系 |
|------|--------|--------------|
| **本文** | **还没做的**（计划态） | 入口 |
| `Engine_Features.md` §6 | 已落地、等用户实测（待验收挂账） | **不是待办**，本文不收录 |
| `Engine_Features.md` §7 | 已知问题（bug 类） | 已并入本文 §13，原处留指针 |
| `doc/Engine_Roadmap.md` §4 | 四期的范围与排序（立项口径） | 本文 §3 只登记条目，范围以路线档为准 |
| `doc/Engine_Roadmap.md` §6 | 跨期遗留（简版） | 已改为指向本文 |
| `doc/Engine_Roadmap_Progress.md` | **已做完**的批次索引 | 本文条目完成即移入该卷 |

**维护纪律**（红线 12「文档先行」/ 红线 16「台账 ≤50KB」适用）：

1. **开工**：状态列由 `未开工`/`后置项` 改 `进行中`；
2. **完成并验收**：整行**移出**本文 → 进 `Engine_Roadmap_Progress.md` 批次索引 + `Engine_Features.md` 验收状态（编号留空档，**不复用**）；
3. **新增**：设计档新写「后置项 / 挂账 / 已知边界」段时，**同批**在此登记一行（落点写明档名 + 小节号）；
4. **待拍板项拍板后**：归类去掉 `待拍板`，按拍板结论改 `未开工` 或移出。

**归类图例**：`未开工`（方案已定、没动手）｜`半成品`（一部分已落）｜`后置项`(主动推迟)｜`待拍板`（连方案都没定）｜`已知问题`（bug 类，有明确症状）｜`进行中`

**编号**：`域-序号`，建档时一次分配，永久稳定，条目移出后编号作废不复用。

## 0.1 落点简写对照

| 简写 | 文档 |
|------|------|
| 路线 / 进度卷 | `doc/Engine_Roadmap.md` / `doc/Engine_Roadmap_Progress.md` |
| 功能清单 | `Engine_Features.md`（根目录） |
| 实现计划 | `doc/Engine_Implementation_Plan.md` |
| RHI 计划 / RHI 进度 | `doc/RHI_Implementation_Plan.md` / `doc/RHI_Implementation_Progress.md` |
| 管线 / 光照 / 天空盒 / 后处理 | `doc/Engine_RenderPipeline_Design.md` / `doc/Engine_Lighting_Design.md` / `doc/Engine_Skybox_Design.md` / `doc/Engine_PostProcess_Design.md` |
| 贴图 / 资产 / 材质 / 热重载 | `doc/Engine_Texture_Design.md` / `doc/Engine_Assets_Design.md` / `doc/Engine_Material_Design.md` / `doc/Engine_AssetReload_Design.md` |
| 场景 / 碰撞 / 拾取 / 输入 | `doc/Engine_Scene_Design.md` / `doc/Engine_Collision_Design.md` / `doc/Engine_Picking_Design.md` / `doc/Engine_Input_Design.md` |
| 编辑器 / Swing / 菜单 | `doc/Editor_Design.md` / `doc/Editor_Swing_Design.md` / `doc/Editor_Menu_Design.md` |
| 插件 / Inspector / 调试线 | `doc/Editor_Plugin_Design.md` / `doc/Inspector_Design.md` / `doc/Editor_DebugLine_Design.md` |
| 调试工具 / 坑 | `doc/Engine_DebugTools_Plan.md` / `doc/Vulkan_Pitfalls.md` |

---

## 1. 建议近期候选（供拍板，非承诺）

> 建档时的排序建议，依据 = 量级小 / 解除他项阻塞 / 收益直接。**是否开工由用户拍板**，本节随拍板滚动。
>
> **2026-11-14 用户拍板：四期 = 当前最高优先级主线**（跨期散件让位）——四期条目按 §3 施工序 ①~⑦ 推进，本节其余候选（REND-01 / RHI-01 / BUG / HOT-02）排在四期之后；四期未满档前不开其他线。

| 序 | 候选 | 理由 |
|----|------|------|
| 1 | **四期施工序**（§3：P4-10 TAA → P4-04 环境光 → P4-08 语义契约 → ⓖ P4-05 探针 GI → ⓗ P4-06 光追） | **2026-11-14 用户拍板 = 最高优先级主线**；①~⑤ 零新底座、画面收益直接（后处理链 + SSAO + 阴影质量分级 + **TAA 已验收 2026-11-15（含相机级迁移 + 检查器条件子组/折叠组头/滑条）**）；**④ P4-04 步骤一 + ⑤ P4-08 2026-11-16 均已用户实机验收；当前项 = ⑥ P4-05 探针 GI（前置 RHI 纹理底座 2026-11-18 已验收，⑥ 前置轻档 P4-17 体素化 AO 待开工）**；**⑥ 前置 RHI 纹理底座 + 跨帧布局记忆 RHI-13 2026-11-18 用户实机验收通过**（`RHI_Texture_Types_Design.md` v0.3 §5.1；代码 `195244d`） |
| 2 | **REND-01 视锥剔除** | Aabb/Mesh.bounds 地基已备，收益直接（当前每帧全量送渲染）——**让位四期，四期满档后再议** |
| 3 | **RHI-01 Buffer staging** | RHI 地基；动态 Mesh/纹理专项（AST-05）的前置 |
| ~~4~~ | ~~**P4-01 后处理链（Bloom/Tonemap + HDR）**~~ | **✅ 2026-11-14 完成并验收**（步骤一 HDR+Tonemap 2026-11-13、步骤二 Bloom 同日落码，用户实机运行通过）→ 已移出本表，见 §3 移出记录 |
| 5 | **BUG 余项** | §13 余 `updateIndex` 热点（BUG-02）、`MainFrame.width()` 语义（BUG-05）（Layer 下拉空 BUG-08 已于 2026-11-15 随设置投影清账移出） |
| 6 | **HOT-02 `.mat`/`.texture`/`.smesh` 文件级 reload 恢复** | 外部手改资产自动生效；**须先拍板真值源优先序**（内存即真值 vs 盘面改动），拍板前不动 |

> DBG-01（dbg-1 校验层回调 + engineDebug 开关）2026-11-11 建档时列本表首位，**2026-11-12 落地并验收**，已移出本表。
> HOT-01（运行期 `setImage` 后 GPU 像素重上传）建档时同列首位，**2026-11-12 实机复核确认早已生效**（2026-10-30 随「Texture2D 冻结废除 + revision」落地），已移出本表（详见 §7 移出记录）。
> 本批（2026-11-12 零散债务小批）移出：**BUG 打包小修**与 **ED-01 耗时指示器接线**——BUG-01/03/04 两条过期两条已修、ED-01 落两处调用点（见 §8/§13 移出记录）。
> 原表 P4-01 条目（建档时序号 3）**P4-01 后处理链** 2026-11-13 落码、**2026-11-14 用户实机验收**，已移出本表（见 §3 移出记录）。

---

## 2. 排障基建（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| DBG-03 | shadowMap 可视化（深度比较采样需专 shader 分支） | 调试工具 §3.6 | 已落码（2026-11-24），待实机验收 | 实际改「非比较采样直读深度」，见 §3.6 |
| DBG-04 | 校验层 VERBOSE 严重度分支（对象命名/生命周期追踪用） | 调试工具 §2.2 | 后置项 | DBG-01 的一行改动 |
| DBG-05 | 编辑器「首选项」面板接入 engineDebug 开关（现仅能手改设置文件） | 调试工具 §2.4 | 后置项 | DBG-01 |
| DBG-06 | SPIR-V 静态自检（无 SDK 环境下的引擎侧校验） | 调试工具 §4 | 待拍板 | 需另立项 |
| DBG-07 | DDGI 探针可视化（网格线框 / atlas 直显 / 数值行） | `Engine_DDGI_ProbeViz_Design.md` §3 | 已落码（2026-11-24），待实机验收 | 路线 L3 验收依赖件；与 DBG-03 同族但对象不同 |

## 3. 四期：高质量渲染（**当前最高优先级主线**，施工前逐项拍板方案）

> 范围与排序口径以 `路线 §4` 为准；本节只登记条目。
>
> **施工序（2026-11-14 用户拍板「四期 = 最高优先级」；序同 `路线 §4`）**：**① P4-02 SSAO → ② P4-03 阴影质量分级（PCF 起步）→ ③ P4-10 TAA → ④ P4-04 天空盒环境光照（+ P4-09）→ ⑤ P4-08 非光照材质语义契约 → ⑥ P4-05 探针 GI（DDGI）→ ⑦ P4-06 光追 rayQuery**。①~⑤ 零新底座（逐项出设计档 → 拍板 → 落码）；⑥⑦ 需先补 RHI 底座前置批（3D 纹理 / 纹理数组；AS / BDA / `GL_EXT_ray_query` 编译链）。**下表按施工序排列**。

| ID | 待办 | 落点 | 归类 | 依赖/备注（加粗数字 = 施工序） |
|----|------|------|------|-----------|
| P4-10 | **TAA（AA 路线取代 MSAA，2026-11-13 用户拍板）**：相机投影抖动（Halton）+ 逐像素运动矢量（混合实现由 G-Buffer 世界位置 + 上帧 viewProj 反投影重建；前向实现无 G-Buffer 需另定）+ 历史缓冲 ping-pong + 重投影/邻域钳制防拖影 + 历史失效触发（切场景/相机跳变/热重载/resize）+ 锐化。**MSAA 已删（2026-11-13），空窗期**：在 TAA 落地前画面无抗锯齿。**方案件已出（2026-11-15）：`Engine_TAA_Design.md`——HYBRID-only、链序 SSAO 后(15)、Halton 8 相、RGBA16F per-view ping-pong、两 Pass 形态、10 条拍板点；步骤一已落码并验收（同日，经三轮实机反馈修正后用户确认「看起来没啥问题了」，v0.4 §9 实装记录），锐化等步骤二项归 P4-11** | `Engine_TAA_Design.md` v0.4 / 后处理档 §4.2 / 原 P4-07 | ✅（2026-11-15 用户实机验收） | **③**｜挂后处理链（SSAO 之后、Bloom/Tonemap 之前）；参考 `Babylon_Rendering_Reference` TAA 段 |
| P4-04 | 天空盒环境光照（作 ambient 源：SH9 球谐 / mipmap 降采样，替换 ambient 常量） | 路线 §4-4 / 光照 §10 / 天空盒 §8 | **✅ 步骤一（常量环境光）2026-11-16 用户实机验收**；**步骤二 SH9 → 后置候选（2026-11-16 用户拍板「先不做」）** | **④**｜步骤一 = `Environment.ambientMode`（OFF/CONSTANT）+ 颜色/强度，2026-11-16；步骤二重启触发 = 需要无主光照明氛围（阴天/黄昏/室内）或出现多通道共享受益（镜面/反射）——落法已定：CPU 投影天空盒得 27 float、std140 打包 7 vec4 正好填满 656→768 余量（零 RHI 改动、注入段改求值函数须强制重导），常量档保留为 fallback/调色旋钮，叠加形态可选 |
| P4-09 | HDR→LDR 高亮截白真解（`.hdr` float 产物） | 天空盒 §1.2/§5 | 后置项 | 随 **④**｜随 P4-04 环境光批 |
| P4-08 | 非光照材质语义契约（`default.shader` HYBRID 档 albedo 仍乘光照 → 无灯即黑；「直读 albedo」语义待定） | 光照 v0.10 遗留 / 管线 v0.6 / **`Engine_Material_LightingModel_Design.md` v0.3** | **✅ 2026-11-16 用户实机验收**：`.shader` 顶层 `LightingModel`（**单一封闭轴**：`Lambert` 缺省 / `Unlit`）+ `Shader.lightingModel` + `RenderList.unlitOpaque` + 新 `UnlitForwardPass`（Lighting 之后/Skybox 之后/Transparent 之前、深度 LOAD+ZWrite）+ 注入器按模型取块集（`Unlit` 跳光照段）+ `default.shader` 改 Unlit；验收口径档 §9 | **⑤**｜余项：`.shader` 诊断台「光照模型」只读行、Unlit 示例资产（登记后置） |
| P4-05 | 探针实时 GI（DDGI 路线；**烘焙 GI 管线明确不建**） | 路线 §4-5 / 光照 §10 / `Babylon_Rendering_Reference.md` §4.5/§10 / **`GI_RT_OpenSource_Reference.md` §2~§5** / **`RHI_Texture_Types_Design.md`** / **`Engine_DDGI_Design.md` v0.4** | **B2 数据面已关账（2026-11-21 用户实跑确认）**；**B3 消费面已落码待实跑**（`javelinDDGI` 注入 + `ambientMode=DDGI` + set1 binding8/9/10，见档 §13）；B1 数据面同日关账（`DdgiProbeGridTest` + `DdgiAtlasTest` + 体素化重建日志全过）；网格收缩 `6×3×4=72` 探针全在场内 | **⑥（RHI 底座已备）**｜**B1 数据面 2026-11-21 落码**（`DdgiSettings` + `DdgiProbeGrid` + `DdgiProbeGridTest` + **`DdgiAtlases`（辐照度 `RGBA16F` / 深度矩 `RG16F`，`SAMPLED｜STORAGE｜TRANSFER_SRC｜TRANSFER_DST`，按网格版面换代，per-view 惰性生命周期接 `RenderingSystem.ddgiAtlasesFor`）** + **`DdgiAtlasTest`**；档 v0.2 §11），**§10 四点已按推荐默认拍板**（单张 2D atlas / 纯相机滚动体积 / 体素 DDA / SSAO 0.25+0.45）；残留 = 追踪/更新着色器（STORAGE 实写归 B2）。**体素化重建同批落码（残留②）**：`DdgiVoxelField`（128³ `RGBA8`：rgb=基色、a=占用，`STORAGE｜SAMPLED｜TRANSFER_DST`）+ `DdgiVoxelizer`（集合 = 不透明 + `castShadow` + 可体素化；`StaticSetSignature` 两帧稳定门控，代掉 BUG-13 的裸计数；首视口挂载）+ 取回并改写的 `voxelize.compute`（albedo 通道）+ `MeshVoxelGeometry`；`DdgiSettings` 加「启用」与「体素场分辨率」。**纹理底座 + 跨帧布局记忆 RHI-13 2026-11-18 用户实机验收通过**（`RHI_Texture_Types_Design.md` v0.3 §5.1：3D 纹理 + 2D 数组 + storage image 写口全链读回一致）；**仍缺** mip 链上传（RHI-14，若走 mip 采样）与格式能力查询（RHI-15）。**首版口径（2026-11-16 讨论记录）**：①探针**自动铺网格**（相机滚动体积、间距可配，显存恒定），**不做手摆探针组**（手摆如需则后置）；②网格来源 = 相机滚动体积为默认，场景可用 `Environment` 条件子组声明「GI 体积 + 间距」覆盖（与 ④ `ambientMode` 同套路）；③**网格外像素回落 `ambient` 常量**（④ 常量档天然 fallback）；④投射线两选——**体素 DDA 追踪**（学 Babylon IBLShadows，无需光追硬件，建议首版）/ ⑦ rayQuery（精确）；⑤探针合法性分类（墙内/地下探针防黑斑）首版用简单版、实测有黑斑再加分类 pass；⑥编辑器探针 gizmo 后置。**2026-11-20 拍板（三条，方案件须带入）**：①**跳过中间档**——不做 SSGI（屏幕空间 GI）与「体素加 albedo 的一次弹射」，直接上 DDGI（中间档方案讨论记录见档 `Engine_VoxelAO_Design.md` §11）；②**与 SSAO 分工（2026-11-20 定稿，替代原「三层收敛」口径）**——体素 AO 已退役、代码已删；`ambientMode = DDGI` 档下 **SSAO 保留、改配成「接触 AO」**：`radius` 0.5→**0.25**、`intensity` 1.0→**0.4~0.5**，**「关掉」不作缺省**（会丢物体贴地的接触线，画面明显「悬浮」）。分工 = **DDGI 管低频**（颜色反弹、墙角/桌底柔和压暗、方向性环境光）、**SSAO 管高频**（接触线、细缝、小物件根部）。依据三条：探针间距 1~2m + 八面体 8×8（角分辨率≈22°）表达不出 5~10cm 接触遮蔽；探针网格外回落 `ambient` 的区域完全无遮蔽；动态物体分帧刷新天然滞后。③**SSAO 降噪优先级提升**——DDGI 抹平低频后 SSAO 颗粒更扎眼（BUG-12 必修，不再是「只做到能用」）；设计档 = `Engine_DDGI_Design.md` §6 |
| P4-17 | **体素化环境光遮蔽**（**DDGI 前置底座 + 无 GI / 低端档兜底**；原定位「P4-05 前置轻档」于 2026-11-20 扩展）：把 ④ 的常量 `ambient` 由「全程无遮挡」升级为「按几何遮挡的环境光」——场景体素化进 3D 纹理 → 每像素沿环境方向 DDA 追踪 → 双边模糊 → 时域累积。**不需要探针辐照度与探针网格**（= DDGI 的遮挡层单独成批，不含渗色）。形态参考 `Rendering/IBLShadows/**`（R8 3D 纹理、WebGPU storage 3D / WebGL2 三轴 slab + combine、`resolutionExp`=2^n、triPlanar 可选、透明体素限穿透） | `Babylon_Rendering_Reference.md` §4.5/§10-11 / **`GI_RT_OpenSource_Reference.md` §4** / **`RHI_Texture_Types_Design.md`** / **`Engine_VoxelAO_Design.md`** | **批 A~D 曾落码入库**（`dc1e9e0`，2026-11-19）；**已退役（2026-11-20 用户拍板整体删除——「有 SSAO 目前够了」）**：批 E（SSAO 共存 / TAA 无抖动 / FORWARD 档 / 重建耗时 / 「整体均匀变暗」断案）全部作废，代码从工作区删除、可从 `dc1e9e0` 取回 | **已退役**（2026-11-20 用户拍板「有 SSAO 目前够了」→ 整链删除；下列备注为历史记录）｜**RHI 3D 纹理 + compute 写通道已验收**（RHI_Texture_Types_Design v0.3 §5.1：`TextureTypesTest` 读回全一致、`TextureDescShapeTest` 形状校验；跨帧布局记忆 RHI-13 同批验收）。若先做，DDGI 只再加「辐照度 atlas + 探针网格 + 分帧刷新」。**收益**：补掉「缝隙/桌底/墙角均匀亮」这个 ④ 常量档最大漏洞；**不含**颜色反弹（渗色仍需 DDGI）。**2026-11-20 定位拍板**：①**不随 DDGI 落地而撤**——DDGI 是低频探针辐照度（间距 ≥1~2m，表达不出接触遮蔽）、覆盖只在相机滚动体积内、动态物体滞后；体素 AO 是「重建时算一次、每帧近零成本」的最便宜兜底，且体素化/追踪通道本就是 DDGI 的投射线底座（DDGI 届时在同套体素表示上加 albedo 通道，AO 图保持 `R8_UNORM` 不受影响）；②**DDGI/光追档不乘 AO**（环境项来源互斥，见 P4-05 备注②），届时 AO 只用于「网格外回落 ambient 的区域 + 低端/无光追档 + 动态滞后补强」；③**真正的删除时机** = DDGI 在目标硬件稳定跑住且性能可接受之后，不是 DDGI 落码当天 |
| P4-06 | 光线追踪 rayQuery（AS 资源种类/图状态、BDA、`GL_EXT_ray_query` 编译链、蒙皮 BLAS 重构；首范围=静态几何） | 路线 §4-6 / 光照 §10 / **`GI_RT_OpenSource_Reference.md` §6** | **B0/B1/B2a/B2b/B3 已全部落码（2026-11-25，待验收）**；后续 = 主光方向求交 + CSM 替换/混合拍板 | **⑦（需 RHI 底座）**｜2026-11-25 用户拍板主线转光追、验收显卡为 RTX；批次与判据见 `Engine_RayTracing_Plan.md` v0.5 |
| P4-07 | ~~HYBRID 档 MSAA-GBuffer（现强制 1x）~~ **作废（2026-11-13）**：AA 路线改 TAA，不做 MSAA-GBuffer | 光照 §9 批次④-B | 作废 | 由 P4-10 取代 |
| P4-11 | **TAA 锐化 pass**（LDR 后、强度可调——TAA 会糊的标准配套）；**已验收（2026-11-15，`SharpenPass` 链尾终节点 + RCAS 照 Babylon `fsr1Sharpen` 移植 + Tonemap 降级为 order 40；参数由 `Camera.sharpenStrength` 驱动、项目设置「锐化」组删除，检查器滑条即时生效）**；可选剩余：YCoCg 方差钳制升级 / 静景收敛加速 / 天空无穷远重投影（不再单列批次，随 TAA 后续排障按需接入） | `Engine_TAA_Design.md` §5.3/§9.4/§9.5 | ✅（2026-11-15 用户实机验收） | P4-10 步骤二 |
| P4-12 | **逐物体运动矢量**（速度附件 / 上帧世界矩阵通道）——运动物体拖影根治 | `Engine_TAA_Design.md` §4-1/§8 | 后置项 | 独立项，需新通道 |
| P4-13 | **FORWARD 档 TAA**（无 G-Buffer，需深度重建世界位置或速度通道） | `Engine_TAA_Design.md` §8 | 后置项 | 前向档本为「先不管」 |
| P4-14 | **天空「无穷远」重投影**（相机旋转时天空盒边缘改善） | `Engine_TAA_Design.md` §8 | 后置项 | — |
| P4-15 | **TAA 钳制升级**（YCoCg / 方差钳制，高对比边缘稳定性） | `Engine_TAA_Design.md` §8 | 后置项 | — |
| P4-16 | **TAA 静景收敛加速**（静止提高历史权重） | `Engine_TAA_Design.md` §8 | 后置项 | 手感优化 |

> **移出记录（2026-11-14）**：**P4-01 后处理链（Bloom/Tonemap + HDR 切换点）** 完成并用户实机验收——步骤一（HDR 切换点 + Tonemap，含管线拆两实现类 + MSAA 移除）2026-11-13 落码、步骤二（Bloom，独立开关）2026-11-13 落码，2026-11-14 用户实机运行通过 → 已入 `Engine_Roadmap_Progress.md` §7 批次索引 + `Engine_Features.md` §2 验收状态（细节见 `Engine_PostProcess_Design.md` v0.4 §9/§9.6）。
> **移出记录（2026-11-14，同日第二批）**：**P4-02 SSAO（四期施工序 ① = GI 第一步）** 步骤一/二同日落码、2026-11-14 用户实机验收通过（「看起来没问题了」）→ 已入 `Engine_Roadmap_Progress.md` §7 + `Engine_Features.md` §2.4（细节见 `Engine_SSAO_Design.md` v0.4 §9）。
> **移出记录（2026-11-16）**：**P4-04 步骤一（常量环境光）+ ⑤ P4-08（非光照材质语义）** 同日用户实机验收通过（原话「环境光没问题」「非光照材质 看起来也没问题」）→ 明细见 `Engine_Roadmap_Progress.md` §7（2026-11-16 两条）；**P4-04 步骤二 SH9 仍挂后置候选**（不占施工序），P4-08 余项（`.shader` 诊断台「光照模型」只读行 / Unlit 示例资产）登记后置。
> 
> **移出记录（2026-11-14，同日第三批）**：**P4-03 阴影质量分级（四期施工序 ②）** 步骤一全件（品质档 PCF / bias 调参面 / per-object 开关 / 设置窗口）+ 步骤二 CSM 同日落码、2026-11-14 用户实机验收通过（步骤一「看起来没问题，我验收了」、一-C 与 CSM「看起来没问题了」）；随验两修（坑 #39 `RenderObject` 换代缓存、#40 阴影盒只收 caster）→ 已入 `Engine_Roadmap_Progress.md` §7 + `Engine_Features.md` §2.4（细节见 `Engine_Shadow_Quality_Design.md` v0.5 §9）。**余步骤三可选收尾**（D16/PCSS/逐级联剔除/DBG-03/多灯阴影，未单列条目）。四期余 P4-04/05/06/08/09/10。

## 4. RHI 遗留（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| RHI-01 | **Buffer staging 上传路径**（deviceLocal 顶点/索引/纹理；现仅 hostVisible，deviceLocal 直接 `vkMapMemory` 会失败） | 路线 §6 / RHI 进度 待办 / 实现计划 §4 | 未开工 | FrameArena（RHI-05）是其赎回路径 |
| RHI-02 | 描述符池按「布局 × 帧数」环形复用（替代设备级单池 2048 sets，RHI 15.8 正式方案） | 路线 §6 / RHI 进度 阶段⑥ | 未开工 | — |
| RHI-03 | backbuffer 句柄改以「槽位身份」参与 structureHash（现 image(idx) 帧间交替 → 单槽图缓存每帧全量重编译） | 路线 §6 / RHI 进度 已知问题 | 已知问题 | 性能 |
| RHI-04 | RT 内存别名（RHI 17.4；现所有附件独立分配） | RHI 进度 阶段④ / 管线 §9 | 后置项 | P1；后处理中间 RT 别名 |
| RHI-05 | FrameArena 收口：`VulkanDevice.frameArena()` per-call `new`（帧内误调即每帧 1MB 泄漏）+ 陈旧注释 + 容量配置化（设计默认 64MB，现 1MB/块） | RHI 进度 现状存档 / 坑 #25 | 半成品 | 与 RHI-01 同源 |
| RHI-06 | `VulkanQueue.submit` 等待阶段硬编码 COLOR_ATTACHMENT_OUTPUT（compute 提交须按用途分派） | RHI 进度 已知问题 | 已知问题 | — |
| RHI-07 | RHI 内部封装视口 Y 翻转（吸收测试里的负高度手法，上层不再处理） | RHI 进度 待办 | 未开工 | — |
| RHI-09 | P2 包：多线程并行录制/异步加载（27.5）、QueryPool 性能回读、多队列 compute、DebugUtils 对象命名/帧 region、采样器缓存去重、Bindless | RHI 计划 §4 | 后置项 | 明确「不做（P2，实现后置）」 |
| RHI-10 | 图依赖缺「材质贴图读」：前向/光照 Pass 只声明 `shadowMap`，`_MainTex` 等走材质描述符集绑定 → 采样贴图无屏障可插（坑 #37 修后已不报错，但跨帧布局仍是猜的） | 管线 §4.2 / 坑 #37 | 未开工 | 需把材质贴图句柄纳入 PassDef 读声明（structureHash 随之变） |
| RHI-11 | 非 `VulkanTexture` 的纹理绑描述符走 `texture().handle()` 当 imageView（`VulkanDescriptorSet.update` else 分支）——`SwapChainTexture` 等会把 VkImage 当 view 传进去 | RHI 进度 已知问题 / 坑 #37 | 已知问题 | 当前无消费方（无人绑交换链图），属潜伏 |
| RHI-12 | 交换链 per-image `SwapChainTexture` 未释放：`VulkanSwapChain.create` 直接覆盖 `images`、`dispose` 不遍历 → 每次 resize 重建漏一批 VkImageView（且 view 须先于 `vkDestroySwapchainKHR` 销毁，Cleaner 的两帧延迟不适用，需即时销毁口） | 坑 #37 / RHI 进度 已知问题 | 未开工 | 与 BUG-09 泄漏账同源 |
| RHI-14 | 纹理子资源面：自定义视图 `Texture.createView(TextureViewDesc)`（现全项目零引用的死代码）+ 屏障仅全范围（无逐层/逐 mip 转换）+ `UploadData.mipLevels` 未被 `uploadTexture` 消费（只拷 mip 0） | `RHI_Texture_Types_Design.md` §2-5/6 / §6 | 后置项 | 消费方：点光 cube 阴影、CSM 单张分层 atlas（`Engine_Shadow_Quality_Design` §B）、探针 atlas 分块、DDGI mip 采样；**DDGI 首版走单张 2D atlas，故不阻塞 P4-05**（2026-11-21 定） |

> **移出记录（2026-11-21）**：**RHI-15 格式能力查询**与 **RHI-08 R32_UINT 附件查询**用户实机验收通过——`FormatCapabilityTest` 八行能力表 + 7 条必支持断言全通过、负例创建期抛错（设备 AMD 780M，快照见 `RHI_Texture_Types_Design.md` v0.5 §7 验收记录）。


> **移出记录**：**RHI-13（跨帧布局记忆）2026-11-18 用户实机验收通过**——形态落地为「布局状态随资源持有」（`VulkanTexture.layout` 跨帧沿用、`pipelineBarrier` 以真实布局为准并回写、`LayoutTracker` 删除、交换链图像本帧局部记账）；验收 = 编辑器常规画面与 TAA 零视觉差异 + `[VVL]` 无 `oldLayout` 不匹配（`RHI_Texture_Types_Design.md` v0.3 §5.1）。它是 ⑥ P4-05 / P4-17 的前置硬项，已随纹理底座同批关账。

## 5. 渲染 / 管线后置（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| REND-01 | **视锥剔除落地**（RenderingSystem 收集侧；Aabb/Mesh.bounds 地基已备） | 路线 §6 / 功能清单 §2.4 | 未开工 | 一期即后置；**做时必须拆两套集合**（2026-11-19 记录）——可见性桶吃「相机视锥 + layerMask」，`shadowCasters` 独立按级联盒/主光视锥剔除，否则视锥外 caster 被剔 → 屏幕边缘影子整块消失、随相机转动忽闪；距离门限用 `shadowDistance` 而非 `farZ`，防「影子先消、物体后消」错位；体素 AO 收集侧同理不吃相机（细目见 `Engine_Shadow_Quality_Design.md` §4.7-1） |
| REND-02 | 遮挡剔除 / GPU Instancing / LOD | 路线 §6 / 场景 §8 / 管线 §9 | 后置项 | P2 |
| REND-03 | 着色模型升级 **Lambert → BRDF**（实体 = PBR：GGX + metallic/roughness + 能量守恒 + 法线贴图；现 `javelinLighting` 只有 Lambert + 环境项 + 点/聚光距离窗，连「半 PBR」都不到） | 路线 §4 尾注 / 光照 §10 / 材质档 | 后置项 | 不设期，归材质/着色模型专档；**画质对标族追赶序第 1 项**（§5 尾 2026-11-19 登记）——光追打在 Lambert 上无收益，PBR + IBL（REND-16）必须先于 RT 精度项 |
| REND-04 | cluster 构建整链 GPU 化（count/scan/write 三步 compute + 每帧 grid 清零；CPU 版作迁移 oracle） | 光照 §10 / §6 | 后置项 | 三期有意先落 CPU |
| REND-05 | 脏标记跳过 cluster 重建（相机/灯集合不变时不重跑） | 光照 §10 | 后置项 | — |
| REND-06 | cluster cell 线性索引 **Morton 化**（局部性） | 光照 §5/§10 | 后置项 | — |
| REND-07 | decals / 反射探针并入同一 cluster 索引系统（索引池语义泛化） | 光照 §10 | 后置项 | — |
| REND-08 | 世界位置改**深度重建**（现 G-Buffer 直存 worldPos） | 管线 §4.2 / 光照 §10 | 后置项 | P2 优化 |
| REND-09 | 单 Pass 多视口（RHI 8 节备选） | 管线 §9 | 后置项 | — |
| REND-10 | 按视口分档（预览视口 `FORWARD_SIMPLE`） | 管线 §5.1 | 未开工 | **前向实现非重点（2026-11-13 主口径混合），优先级下调** |
| REND-14 | 前向管线独立类的落地面（`ForwardRenderPipeline` 屏外无人消费：demo/测试现仍直用 `RenderingSystem` 不经管线类；编辑器固定混合、仅打包运行时按 `javelinSettings.renderPipeline` 选用） | 管线 §6.3 | 后置项 | 2026-11-13 拆分产物；需要时再补 demo/测试接线 |
| REND-11 | ~~MSAA 后置族：stacking/预览/分屏区域清屏的 MSAA 路径、运行期切档（PSO/附件失效协议）、FXAA 档、末 pass storeOp DONT_CARE~~ **作废（2026-11-13）**：MSAA 业务面已整体删除（AA 改 TAA，P4-10）；**仅存「末 pass storeOp DONT_CARE 带宽优化」** | 管线 §9 | 后置项 | AA 部分由 P4-10 接管；MSAA 路径族不再做 |
| REND-12 | UI Pass 组 + 环境雾（`Environment` 加雾字段；天空盒已转正） | 管线 §9 / 场景 §8 | 后置项 | — |
| REND-13 | 光照 gizmo/调试显示（编辑器侧 DebugLine 光球线框 + 灯图标） | 光照 §10 / 调试线 | ✅ 已落码待验收（2026-11-25，`Engine_DebugTools_Plan.md` §3.7） | — |
| REND-16 | **IBL 图像式环境光照**：天空盒/HDRI → 漫反射 SH9 + **镜面预滤波 mip 链 + BRDF LUT**（现环境项仅常量 `ambient`；P4-04 步骤二 SH9 只覆盖漫反射半球，镜面半未立） | 光照 §10 / 天空盒 §8 / 材质档 | 后置项 | **画质对标族序 2**｜依赖 AST-01 mip 生成链（AST-09 天空盒 mip 同源）、AST-12 equirect→cubemap；消费方 = REND-03 的镜面项 + REND-17 反射 |
| REND-17 | **反射能力**（现**无 SSR、无反射探针**，金属/水面全假）：SSR（屏内步进 + 粗糙度多级 + 半分辨率 + 降噪）→ 反射探针（cubemap 捕获 + 盒投影）→ RT 反射 | 光照 §10 / 管线 §4.2 / 材质档 | 后置项 | **画质对标族序 3**｜RT 反射归 P4-06 首批；探针并入 cluster 索引见 REND-07；SSR 可先做（无 RT 依赖） |
| REND-18 | **后处理栈扩容**：曝光/测光（EV 自动曝光）、景深 DOF、运动模糊、体积雾/体积光、色彩分级（LUT）、镜头效果（色差/暗角/畸变/光晕）；现链仅 Tonemap + Bloom + Sharpen(RCAS) + TAA | 后处理档 §4 / §9 | 后置项 | **画质对标族序 4**｜HDRP「电影感」一半来自这一栈；体积雾与 REND-12 环境雾同源（REND-12 先落字段） |
| REND-19 | **色彩与曝光管理**：线性工作流全链统一（sRGB 口径随 AST-03）+ ACES 等 tonemap 曲线族 + 物理相机参数（EV/ISO/快门/白平衡） | 后处理档 §4 / 贴图 AST-03 | 后置项 | **画质对标族序 4**｜与 REND-18 曝光项耦合（自动测光依赖统一曝光口径） |
| REND-20 | **材质与工具链长期面**：Shader Graph 等价物（`.shader` 可视化编排）、材质库/模板、GPU 抓帧集成（RenderDoc 内嵌）、材质特性覆盖（清漆/次表面/各向异性/毛发/贴花）、贴图压缩与流送 | 材质档 / 编辑器 §6 / AST-02 | 后置项 | **画质对标族序 6**｜与光追无关，是「全面追上 HDRP」的真实长期差距 |
| REND-21 | **阴影质量延伸**：PCSS（软影随距离变糊）、接触阴影（屏幕空间或 RT）、多灯阴影、逐级联剔除；现仅 CSM + PCF 三档。分支：**REND-21-A cutout/alpha-test 阴影**已于 2026-11-24 落码（材质 `_AlphaCutoff` + 阴影专 set0 采样 `_MainTex` + push 144B + 主渲染同口径 discard，`Engine_Shadow_Cutout_Design.md` v0.2），**未编译待验收**；其后置项 = `DdgiVoxelizer` 体素化不吃 alpha（镂空物体素仍是实心，GI 会「透不过去」）。**REND-21-C1 PCSS** 已于 2026-11-25 落码（`softness` + set1 binding11 原始深度 + 变半径 PCF，`Engine_Shadow_PCSS_Design.md` v0.2）；**REND-21-C2 接触阴影** 已于 2026-11-25 落码（世界位置屏幕空间短距步进 + `contactParams` @656，`Engine_Shadow_Contact_Design.md` v0.2；勘察后推翻「必须先给深度附件加 `SAMPLED`」的旧前置——改用 G-Buffer 世界位置反投影，零 RHI 改动、零新槽位，仅 HYBRID 档）；**v0.4/v0.5 三轮实机反馈后降级为实验项**——默认强度改 0 + 只让 4m 内近景参与；平地/凸曲面的屏幕空间条带伪影未收敛（移动相机随采样网格变化），彻底修需「半分辨率 + 抖动 + 时间累积」或法线/深度不连续门控，**挂后置重做**（`Engine_Shadow_Contact_Design.md` §7.1~§7.3）；**REND-21-B 逐级联剔除** 已于 2026-11-25 落码（caster 世界盒 → 光裁剪盒 AABB 粗筛，近平面侧不裁；`Engine_Shadow_CascadeCull_Design.md` v0.1），**同日用户实机验收通过**（距离 80 末级 `9/9` / 临时 1m `0/9` 证判定生效 / 回 80 画面无缺块，档 v0.2 §7；遗留 = 近级联 `k/total` 未落日志）；完整形态（含主视图剔除 / 静态动态分层）仍与 REND-01 联动；**REND-21-D 多灯阴影**硬前置 = RHI-14 | `Engine_Shadow_Quality_Design.md` §B（步骤三） | 后置项 | **画质对标族序 4**｜P4-03 步骤三余项，此前未单列，随本族补登记；逐级联剔除与 REND-01 联动 |
| REND-22 | **SSAO 精细化后置**：采样数可配（16/32/64，需多套核常量 + 设置项 + i18n）；半分辨率 AO 的**深度感知上采样**（替代 LINEAR 2×2 块状，需读深度或世界位置做权重） | `Engine_SSAO_Design.md` §10.4 | 后置项 | BUG-12 收口时留项（2026-11-21）——先看实机是否仍有颗粒；DDGI 落地后 SSAO 降为「接触 AO」，届时按降档口径再评估是否需要 |

> ~~REND-15 体素 AO 手动重建命令~~ **已随 P4-17 退役作废（2026-11-20）**：原 2026-11-19 落码的 `tools.voxelAoRebuild` 命令与 `JavelinEngine.requestVoxelRebuild()` 同批删除，编号留空档。

> ~~**P4-17 排障未完事项（2026-11-19 挂账，随批 E 收口）**~~ **已随 P4-17 退役作废（2026-11-20）**：遗留的「整体均匀变暗」口径问题、`VoxelAoDebugReadback` 临时诊断件去留，均不再处理（代码可从 `dc1e9e0` 取回）。若 P4-05 首版投射线改走体素 DDA 而重现同类现象，参考 Babylon IBLShadows 的「表面点求值 + 二值命中」口径（`GI_RT_OpenSource_Reference.md` §4）与 `Engine_VoxelAO_Design.md` §9 排障实录（该档已归档）。

> **画质对标族（后期能力，2026-11-19 登记）**：HDRP 画质对标的分项与追赶序在此收敛。**不占四期施工序 ①~⑦，也不进 §1 近期候选**——四期满档（P4-06 光追验收）之后再排期。
> **追赶序（按性价比）**：1 REND-03 PBR → 2 REND-16 IBL → 3 REND-17 反射 → 4 REND-18 后处理栈 + REND-19 色彩曝光 + REND-21 阴影延伸 → 5 P4-06 光追精度（RTAO / RT 阴影 / RT 反射）→ 6 REND-20 工具链与材质特性覆盖。P4-05 DDGI 仍按四期 ⑥ 走，不在本族内。
> **成绩口径**：1+2+3 补齐后，单场景静态/半动态画面 ≈ HDRP **无 RT 默认档**；再叠 5 可在接触阴影 / 反射 / 屏外遮蔽三项上超过它；「全面追上」还要 6，与光追无关。
> **硬约束**：光追 + Lambert = 浪费（反射与间接光需要正确 BRDF 与粗糙度），故 1、2 是 5 的前置，不许反序；RT 全档还需同步建降噪体系（工程量大过「发射线」本身）。

## 6. 资产 / 贴图 / 网格 后置（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| AST-01 | **mip 生成链**（RHI 未实现，导入恒 `mipLevels=1`） | 贴图 §5 | 未开工 | 天空盒 mip（AST-09）同源 |
| AST-02 | 块压缩 BC/ASTC（现 RGBA8 直读；落 Texture2D 层派生多份 GPU image） | 贴图 §5 | 后置项 | — |
| AST-03 | sRGB 口径（现 `R8G8B8A8_UNORM`；线性工作流接交换链 sRGB 变体需视图格式分支 + MUTABLE_FORMAT 规则查证） | 贴图 §5 | 后置项 | 与 P4-01 HDR 相关 |
| AST-04 | 「一图多设置」的 UI 消费（模型已支持多 Texture2D 引同一 Image，设置入口待落） | 贴图 §5 | 未开工 | — |
| AST-05 | **动态 Mesh / 动态纹理专项**（hostVisible 常驻 + 每帧映射写入 / staging 环形；`rhi.Texture.update(Region,ByteBuffer)` 运行期口已有未接） | 路线 §6 / 功能清单 §2.5 / 材质 §6 / 资产 §2.10 | 未开工 | 依赖 RHI-01 |
| AST-06 | `.mesh` 二进制格式（快加载；`.obj` 仅作导入源） | 资产 §2.10 | 后置项 | — |
| AST-07 | 顶点格式/布局扩展：多绑定点（分离布局）、显式 location、U8/U16/HALF | 资产 §2.10 | 后置项 | — |
| AST-08 | 骨骼网格 SkinnedMesh 的对称设计 | 资产 §2.10 | 后置项 | 无动画系统 |
| AST-09 | 天空盒 mip 链（环境项 mipmap） | 天空盒 §3/§8 | 后置项 | 依赖 AST-01 |
| AST-10 | 程序化天空（大气散射，远期） | 天空盒 §8 | 后置项 | — |
| AST-11 | per-camera 天空盒（现多相机统一读环境组件同一 skybox） | 天空盒 §5/§8 | 后置项 | — |
| AST-12 | equirect→cubemap 转换工具（给用户把全景图转六面） | 天空盒 §1/§8 | 后置项 | — |
| AST-13 | cubemap 资产六面缩略面板（现只读行） | 天空盒 §5 / 功能清单 §2.3 | 后置项 | 检查器侧 |
| AST-14 | Cubemap ImportSettings `faceSize` 可调（现固定 512） | 天空盒 §1.2 | 后置项 | — |
| AST-15 | 内置资源同名索引 fat-jar 合并边界（editor+core 若合 fat jar 须改按模块原名或改扫描协议） | 天空盒 §8 | 后置项 | 构建侧 |

## 7. 热重载与资产管线挂账（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| HOT-02 | `.mat`/`.texture`/`.smesh` 文件级 reload 摘除的恢复（收窄只 shader；`targetClass` 加回分支即恢复 → 外部手改这些资产自动生效） | 热重载 §8/§9 | 半成品 | walker/reload/reconcile 基建全保留 |
| HOT-03 | Scene 根豁免（外部改 `.scene` 重导 Library 但场景对象图 warn 跳过、画面不刷新）——批次②剩余 | 热重载 §5.1/§8 | 半成品 | 二期 |
| HOT-04 | Scene 脏时警示形式（日志 warn → 顶部弹条 / 对话框，Unity 式） | 热重载 §10 | 待拍板 | 批次②实测定 |
| HOT-05 | 换 shader 删 Pass 的报错形态（维持渲染侧显式报错 vs 重载前 Pass 兼容预校验、不兼容材质置灰） | 热重载 §6/§10 | 待拍板 | 批次④ |
| HOT-06 | Inspector 材质编辑通道接入 + 内容 hash 替代 mtime（防「时间戳变内容没变」误重导）+ Pass 兼容预校验 | 热重载 §8 批次④/§5.2 | 未开工 | 批次④ |
| HOT-07 | WatchService 后台监听（Windows 多段保存误触发坑；焦点扫描已覆盖主用例） | 热重载 §1/§5.1 | 后置项 | — |
| HOT-08 | 删除资产源文件联动清理（Library 产物残留不失效，Unity 同口径） | 热重载 §7 | 后置项 | — |
| HOT-09 | Walker 递归深度再评估（v0.5 起不再递归；出现「仅经 Asset 内部字段可达的非注册表持有者」时） | 热重载 §10 | 待拍板 | 条件触发 |
| HOT-10 | 旧格式 `.smesh` 产物自动检测（2026-10-04 前产物 guid null，现仅能删产物重导） | 编辑器 §6.3 | 待拍板 | — |
| HOT-11 | 同文件导入任务去重（连续快速保存同文件多 worker 交叠写同一产物） | 编辑器 §6.3 | 未开工 | — |
| HOT-12 | 脚本导入器落地（`.java` 代码树不产 meta、meta 无消费方；头 GUID 脚手架保留） | 编辑器 §6.2 | 待拍板 | 待脚本功能真落地 |
| HOT-13 | std140 排布生成器独立化（Engine_Material §3 P2）——**疑已随 .mat/DefaultMaterial 落地，待复核** | 材质 §3 | 待复核 | 低置信 |

> **移出记录**：HOT-01（运行期 `setImage` 后 GPU 像素重上传）2026-11-12 复核**确认早已生效**——`setImage` 置空句柄 + `revision` 自增 → materialSet 退役重建重传，2026-10-30「Texture2D 冻结废除」时即落地；用户实机面板换图当帧上屏、旧 TEXTURE/IMAGE_VIEW 经 Cleaner 回收。原「半成品 / 红线 17 唯一残留项」归类系 2026-10-25 面板初版注释（「`TextureUpload` 幂等返回旧句柄」）未随改动回收。外部手改 `.texture`/`.png` 的 file-level 重载仍摘除 → 见 HOT-02。

## 8. 编辑器（二期 / 跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| ED-02 | Swing 深色主题（2026-10-17 拍板先改亮色，深色随后置） | Swing | 后置项 | — |
| ED-03 | Dock 拖拽停靠（复杂交互分期后置） | Swing | 后置项 | — |
| ED-04 | 多视口（各自相机各自 swapchain，RHI 侧扩展） | Swing | 后置项 | RHI 联动 |
| ED-05 | Game 视图工具区（显示比例/缩放/Stats 一族，Unity 口径） | 编辑器 / Swing | 后置项 | — |
| ED-06 | Inspector 字段贡献者（插件侧） | Swing | 后置项 | 随插件体系 |
| ED-07 | Inspector 暂缓族：Collider 字段、多 tag 展示与编辑、同节点多实例组件收集形态、可空值「清回 null」UI、订阅 nodeRenamed（设置管线合流已随 Layer/Tag 投影于 2026-11-15 清账） | Inspector §10 | 后置项 | — |
| ED-08 | Inspector 遗留：project 面板高亮跨域同步（订阅 ProjectSelectEvent）、`.smesh` 等其余资产检查器按需注册、注入后 GLSL 查看（Unity "Show compiled code" 等价物） | Inspector §11 | 后置项 | — |
| ED-09 | Inspector 细节：折叠态重建复位（无记忆）、label 超 96px 截断、候选分组/分类/按类名搜索/顶栏 Component 菜单、AddComponent 菜单项扩容 | Inspector §11/§13 | 后置项 | — |
| ED-10 | 脚本资产检查器 + 脚本功能本体 | Inspector §11 | 后置项 | 与 HOT-12 同域 |
| ED-11 | Gizmo 后置族：双轴平面手柄、环上角度扇形、拖拽箭头变长动画、角度/位移吸附、pivot/center 切换、环不可见 collider、W/E/R 快捷键 | 编辑器 | 后置项 | — |
| ED-12 | 视口聚焦已知边界（F 键依赖渲染窗口 OS 焦点；极窄竖视口按垂直 FOV 口径可能侧向裁边） | 编辑器（视口聚焦段） | 已知问题 | 二期 |

> **移出记录**：ED-01（耗时指示器调用点接入）2026-11-12 落地——场景切换（`SceneFileType.openScene`）+ 焦点扫描（`AssetReloadService.scanAndShow`）两处；原另两候选（`ProjectLoadingFrame` 引导链、`PackDialog` 构建线程）复核后不接（各有常驻指示器，叠浮层是重复）。

## 9. 插件与 i18n（二期 / 跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| PLG-01 | 命令 id 前缀化（现第三方 id 撞核心域 fail-fast 拒绝；规则 + 菜单结构注入位未定） | 插件 §7.1 / 路线 §6 | 待拍板 | — |
| PLG-02 | FileType 扩展名冲突策略（现 fail-fast；第三方接管 `.scene` 该拒还是先注册者胜） | 插件 §7.1 / 路线 §6 | 待拍板 | — |
| PLG-03 | 装载期注解层启动校验（§4.5-2） | 插件 §7.2 | 后置项 | — |
| PLG-04 | i18n 报告导出命令（Tools 域 miss/used 分桶工单） | 插件 §7.2 / 功能清单 §3.8 | 后置项 | — |
| PLG-05 | 插件管理界面 v2（列表 name/description/version/author、启用禁用、i18n 报告按插件分桶） | 插件 §7.2 | 未开工 | — |
| PLG-06 | AssetImporter SPI 收编 `PluginContext` | 插件 §7.2 | 未开工 | — |
| PLG-07 | SDK 模块提取（§5.2） | 插件 §7.2 | 未开工 | — |
| PLG-08 | 第三方 jar 实测（现静态骨架） | 功能清单 §3.8 | 后置项 | — |

## 10. 场景 / 物理 / 输入 / 窗口（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| SYS-01 | **L2 物理**（Rigidbody 组件、MeshCollider、物理库选型；开放项「只做窄相查询 vs 连带选型」） | 功能清单 §2.7 / 碰撞 §2/§6 / 路线 §2.2 | 待拍板 | 选型拍板后立项；对应实现计划阶段⑥ |
| SYS-02 | 物理/查询后置族：broadphase 加速、overlap 族、raycast 命中排序选项、触发器语义 | 碰撞 §10.5/§5 | 后置项 | — |
| SYS-03 | 三角网格拾取 / MeshCollider（编辑器已改 GPU 拾取，归 L2 需求再定） | 碰撞 §7/§10.5 | 后置项 | 依赖 SYS-01 |
| SYS-04 | 音频子系统（音频上下文 + 3D 音源） | 实现计划 阶段⑥ / 路线 §5 | 未开工 | 选型后立项 |
| SYS-05 | Prefab 资产（节点树模板 → 实例化） | 场景 §8 / 实现计划 §5 | 后置项 | — |
| SYS-06 | 组件 Update 顺序优先级 / 依赖排序（P2，对齐 Unity Script Execution Order） | 场景（组件段） | 后置项 | — |
| SYS-07 | 自定义组件分派优化（按接口分派 / 回调缓存；组件量上升后再做） | 场景 §8 | 后置项 | 性能 |
| SYS-08 | 独占全屏 | 功能清单 §2.6 | 未开工 | 窗口 |
| SYS-09 | 输入后置族：文本输入（char 回调 + IME）、剪贴板、鼠标锁/游标模式（编辑器飞行 raw motion 时做）、事件订阅 API、方括号/小键盘回车 AWT 映射（待真机验证）、手柄/触摸 | 输入 §6 | 后置项 | — |

## 11. GPU 拾取边界（二期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| PICK-01 | 多视口拾取（分屏 viewport 偏移换算） | 拾取 §8 | 后置项 | — |
| PICK-02 | hover 变更驱动失效（现仅指针移动时重拾；场景动/鼠标不动则 hover 不刷新） | 拾取 §8 | 后置项 | — |
| PICK-03 | 水平视角下网格平面之下（y<0）被挡物体的拾取为已知边缘 | 拾取 §8 | 已知问题 | 二期 |

## 12. 构建 / 开源（跨期）

| ID | 待办 | 落点 | 归类 | 依赖/备注 |
|----|------|------|------|-----------|
| BLD-01 | `./gradlew` 对 Windows cmd/PowerShell 不可用（根缺 `gradlew.bat`）；`distributionUrl` 仍走腾讯镜像 | 进度卷 §6 开源前置批 | 已知问题 | — |

## 13. 已知问题（bug 类）

> `Engine_Features.md` §7 原表并入本节，原处留指针。

| ID | 症状 | 落点 | 归类 | 备注 |
|----|------|------|------|------|
| BUG-02 | 导入成功即全量 `updateIndex()` 热点（每任务成功都重扫监听目录重写索引） | 编辑器 §9 | 已知问题 | 性能 |
| BUG-05 | `MainFrame.width()` 语义与 GLFW 不同（逻辑像素 vs framebuffer 像素；按窗口宽高的逻辑须留意 dpi） | 编辑器 §9 | 已知问题 | — |
| BUG-06 | 窗口拖拽 resize 期画面非等比拉伸、松手即愈（2026-10-17 拍板接受） | 编辑器 §9 / 原 功能清单 §7 | 已拍板接受 | 不修，记录 |
| BUG-07 | Gizmo 拖拽中按 Alt 相机环绕与拖拽并动（已知边缘） | 原 功能清单 §7 | 已知问题 | 后调 |
| BUG-09 | 退出期 `vkDestroyDevice` 报 46 个对象未销毁（VVL 05137；首 10 个为 VkDeviceMemory），`CleanerRegistry.flush()` 已跑仍剩 → 逐笔账未查 | 坑 #37 | 已知问题 | 已确认子项 = RHI-12 交换链 view；查账手法 = 抬高 `duplicate_message_limit` 看全列表 + 按 handle 反查创建点 |
| BUG-13 | ~~**体素 AO 重建门控只看 renderer 数量**~~ **作废（2026-11-20）**：随 P4-17 整体退役，代码已删，问题不复存在。历史内容保留：`VoxelizationPass.ensureReady` 仅 `rendererCount != lastRendererCount` 触发重建 → 移动/同数量替换都不刷新（幽灵暗角），且每帧全重建成本不可接受（4.2M 条 DDA + `prepare()` 逐 mesh 描述符集） | ~~`VoxelizationPass.java:157-167`~~ | 作废 | 修法 A/B/C（静态集签名 / 增量 tile + AO 分片 / 降档全重建）作为 **P4-05 首版投射线**的设计输入保留——DDGI 若走体素 DDA，必须一开始就按「静态集 + 签名门控」做 |
| BUG-14 | 调试功能键（F6 / F4 / F5 / F7 / F9 同族）**时好时坏**：①引擎 `Input` 只在 Vulkan 画布持焦点时收键，焦点在检查器/树即失灵；②F6 更会**被焦点遍历吃掉**——按下去焦点在项目 / 层级面板间循环选中而不切视图 | `Editor_DebugTools_Plan.md` §3.6 / `MainFrame` | 已知问题 | 2026-11-25 试修两轮**均无效已回退**（rootPane `WHEN_IN_FOCUSED_WINDOW` 全局绑定 + 窗口级从四类 `*_TRAVERSAL_KEYS` 摘 `VK_F6`）→ 拦键方不在 `JFrame` 遍历键集合上；**修方向改为菜单命令层统一分发快捷键**（`MenuRegistry` accelerator 现只做显示、无全局触发），一批解决；当前靠菜单项切换，不影响验收 |

> 另有一批存量问题（描述符池 / FrameArena / backbuffer 哈希等）登记在 §4 RHI 遗留，性质同属 bug 类（RHI-03 / RHI-05 / RHI-06），不在此重复登记。

> **移出记录（2026-11-21，BUG-12）**：**SSAO 噪点用户实机收口**（「看起来比之前好，先这样」）——修法 = 可分离两遍双边（各 9 tap，支撑 9×9）+ `sigmaZ` 自适应 + AO 深度偏置自适应 + AO RT `R16_SFLOAT`；口径与验收记录归 `doc/Engine_SSAO_Design.md` v0.6 §10。未做项（采样数可配、深度感知上采样）已转 **REND-22** 后置。

> **移出记录（2026-11-21，BUG-11）**：**物体与受影面接触处漏光****用户实机验收通过，已移出**——修法 = 注入段 `javelinCascadeShadow` 的 `refZ` 加固定半纹素外扩 `0.5/(atlasSize/grid)` + `ShadowSettings` 五项缺省重标（`pcfRadius`0.6 / `refDepthOffset`0.02 / `biasSlope`0.6 / 级联 4 / 距离 80）；前置 = 重导全部 `.shader` 与内置 `lighting.frag` + 项目设置「阴影」组恢复默认。机理（含 `D32_SFLOAT` 下 `depthBiasConstantFactor` 实际只按 `2⁻²³` ULP 生效）归 `doc/Engine_Shadow_Quality_Design.md` v0.7 §9.6 + 坑 #45；环境光侧接触漏光随体素 AO 退役一并消失，PCSS/SSCT 仍属步骤三可选收尾。

> **移出记录（2026-11-18）**：**BUG-10**（检查器颜色行被压成一条线）**用户实机复测通过**——相机 `backgroundColor` 与环境光 `AmbientSetting.color` 两行色块均恢复正常高度。两轮修法沿革与机理（首轮 validate root 误判、第二轮 root 定案 + 竖直 minimum=pref 兜底 + 压扁自检）归 `doc/Editor_Swing_Design.md` §10.8；回归钩子 `InspectorRowHeightTest`（首轮 4 条、第二轮 +2 条）。

> **移出记录（2026-11-15）**：**BUG-08**（Layer 下拉空）——根因是设置 → 注册表断链（编辑器启动不读 Layer/Tag 页、检查器另存两份静态缓存）；`LayerAndTagManger` 收口 + `JavelinEngine` 构造期投影 + 两份缓存删除后，下拉读的是注册表现值；随 Layer/Tag 体系批次验收。
> **移出记录（2026-11-12 零散债务小批）**：**BUG-01**（`loadScene` 失败路径 NPE）**复核为过期条目**——四处 null 守卫早已落地（`JavelinEditor.onInitialize` 场景未就绪跳过装配 + `EditGridAppState` / `TransformGizmoAppState` / `DebugLineAppState`）；**BUG-04**（`SettingsManager` javadoc 悬空引用）同属过期——该引用全仓零命中、现引 `PreferencesDialog`；**BUG-03**（加载页假等待）已修——`ProjectLoadingFrame` 改「补足最短可见时长」（原为在引导链耗时之上额外 +2s）。

## 14. 待复核（疑文档残留，非真待办）

> 建档时发现两处文档残留，**2026-11-11 同批已就地更正**（`路线 §6` 改写为指向本文并标注原条目已落定 / 「插件域待拍板」改 2 项），本节暂空，留位给后续清点发现的存量不一致。

（当前无）

---

## 15. 建档说明

- 本表 v0.1 由一次全量文档清点生成（2026-11-11），扫描范围 = `doc/*.md` + `Engine_Features.md` + `AGENTS.md`，关键词 = 后置/挂账/遗留/待办/未开工/待拍板/已知边界/暂缓等；
- 条目数：**106 条**（DBG 6 / P4 9 / RHI 9 / REND 13 / AST 15 / HOT 13 / ED 12 / PLG 8 / SYS 9 / PICK 3 / BLD 1 / BUG 8），去重后按域归入 §2~§13；
- 建档口径：**只登记「还没做」**；已落地待实测（`功能清单 §6`）与已完成批次（`进度卷`）不收；
- 优先级与「建议下一步」为建档时建议，非承诺。

> **移出记录**：DBG-01（dbg-1 校验层回调 + `engineDebug` 开关）2026-11-12 落地并**验收**（`Engine_Features.md` §6 挂账清零）；DBG-02（dbg-2 G-Buffer 可视化）同日落地并验收（2026-11-12 用户实测）→ 已入 `Engine_Features.md` §3.5 并销账（原归 §6 待验收挂账，当日清）；HOT-01（贴图换绑像素重上传）同日复核确认早已生效（非待办）→ HOT 域余 12 条；同日零散债务小批移出 **ED-01**（耗时指示器接线）与 **BUG-01 / BUG-03 / BUG-04**（两条过期条目 + 一条已修）→ ED 域余 11 条、BUG 域余 6 条。**在建条目 = 99**（建档 106 − DBG-01/02 − HOT-01 − ED-01 − BUG-01/03/04 + 首跑排障新增 RHI-10/11/12、BUG-09 共 4 条 − **P4-01 2026-11-14 验收移出** − **P4-02 SSAO 2026-11-14 验收移出** − **RHI-13 2026-11-18 验收移出** − **BUG-10 2026-11-18 复测通过移出**）。

> （注：部分内容可能由 AI 生成）
