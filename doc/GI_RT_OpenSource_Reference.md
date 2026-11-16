# GI / 体素 / 光追 开源参考索引（GI & RT Open-Source Reference）

> 版本：v0.1（2026-11-16 建档）
> 定位：四期 ⑥（P4-05 探针 GI）/ ⑥ 前置（P4-17 体素化环境光遮蔽）/ ⑦（P4-06 光追 rayQuery）的**外部算法与工程实现速查**——按项目拆解「它用什么路线、源码在哪、许可能不能抄、与我方差在哪」，补 `Babylon_Rendering_Reference.md`（只管 Babylon）在 GI / 体素 / 光追三段上的空白。
> 来源：全部为**外部仓库 / 官方文档**（`godotengine/godot`、`casual-effects` G3D、`helenl9098/DDGI-Minecraft`、`microsoft/o3de`、`nvpro-samples`、`KhronosGroup/Vulkan-Samples`、`The-Forge`、`EmbarkStudios/kajiya`、NVIDIA RTXGI、`FlaxEngine`），**不随本库版本管理**；本档只记「在哪、能抄哪层」，不复制其代码。
> 取材口径：**许可状态逐项标注核实方式**（本次检索到官方 LICENSE / 文档正文 = 已核；仅凭公开常识 = 待核，见 §10）；机制描述只写能由官方文档/文件清单支撑的部分，细节推断一律进 §10 待核实清单，不写成结论。
> 边界：只讲 GI / 体素化 / 光追的算法与 GPU 组织；不讲编辑器 GUI、资产管线、序列化、平台层。

---

## 0. 总览：先记住的八条

1. **与唯一同构的第一参考不是 Babylon，是 Godot 4**：它跟我方一样是 **Vulkan + GLSL compute + 无硬件光追假设** 的渲染架构（RenderingDevice 后端），SDFGI 是「级联 + 探针 + 方向追踪」的完整落地，正好覆盖 P4-17 与 P4-05 两端。
2. **DDGI 的算法圣经是 G3D**：DDGI 论文作者把 **reference GLSL** 直接发布在 G3D 里，而 G3D core 与 `data-files/shader` 是 **BSD-2**——这是全清单里唯一「论文官方参考实现 + 可自由抄」的组合。
3. **首版「软件求交 + 纯 compute」的工程模板是 DDGI-Minecraft**：Apache-2.0，Vulkan，团队明确说因硬件限制没用 Vulkan 光追扩展、改用 compute 光追（RVPT），等于替我方先跑了一遍「无 RT 硬件做 DDGI」。
4. **体素 AO 两件套分工清楚**：Babylon IBLShadows = 只出**遮挡**（DDA 命中即遮挡 + 双边模糊 + 时域累积）；Godot SDFGI = 出**级联 GI**（含探针辐照度）。P4-17 抄前者形态，P4-05 抄后者骨架。
5. **rayQuery 的直接模板是 nvpro-samples**：sample 16（ray query 基础）与 sample 17（**compute 内 rayQuery 做屏幕空间 AO**）——后者几乎就是 P4-17 走硬件光追口径的形态。
6. **AS 进渲染图 / 跨后端的抽象，看 The-Forge**：它把 BLAS/TLAS 管理与双后端（Vulkan/DX12）封装成一层，对应我方「AS 作为新资源种类进图 + build 写 / trace 读图状态」的设计语言。
7. **许可分三级**：绿（BSD-2 / MIT / Apache-2.0）可抄码并保留署名；黄（Flax EULA、NVIDIA RTXGI 许可）源码可见但**不可抄码**；红（Unity HDRP APV / Unreal Lumen 等）不开放。
8. **我方三处缺口在参考里都有对照件**：3D 纹理 + compute 写（Godot RD 的 3D 资源用法）、体素化（Godot/IBLShadows）、AS/BDA/`GL_EXT_ray_query` 编译链（nvpro 样本）；但**没有一件是为 LWJGL/Java 写的**，移植要过「GLSL 方言 + 描述符组织 + 扩展启用」三道。

---

## 1. Godot 4（SDFGI / VoxelGI）——第一参考

> 仓库：`https://github.com/godotengine/godot`（MIT）

### 1.1 为什么是第一参考

- 同为 **Vulkan-first** 引擎（RenderingDevice 后端，`renderer_rd`），着色器是 **GLSL compute/fragment**，不是 HLSL；
- 全局光照两条路都在无硬件光追前提下跑：**SDFGI**（实时、屏幕外有效、Forward+ 专用）与 **VoxelGI**（体素探针式）；
- 实现文件按 pass 切成独立 `.glsl`，便于逐个对照抄数学，而不是吞一个大 uber shader。

### 1.2 SDFGI 的文件切分（官方文档已列）

`servers/rendering/renderer_rd/shaders/environment/` 下：

| 文件 | 职责（按文件名与官方渲染架构文档） |
|---|---|
| `sdfgi_preprocess.glsl` | 场景到 SDF / 体素的预处理（写基础距离场数据） |
| `sdfgi_direct_light.glsl` | 把直接光写入级联（光照作为 GI 传输的源） |
| `sdfgi_integrate.glsl` | 级联的传播 / 合成（GI 的「多级弹射近似」在这一层） |
| `sdfgi_debug.glsl` / `sdfgi_debug_probes.glsl` | 可视化（级联与探针），对我方 DBG 件可直接借形态 |

C++ 侧调度在 `renderer_rd/environment/` 下（级联轮转、探针网格、每帧更新哪一个级联）——**具体文件名与函数粒度未逐行核实，见 §10**。

### 1.3 已被官方文档确认的行为口径（可直接写进设计档的约束）

- **仅 Forward+ 渲染器可用**（移动/兼容渲染器不可用）——与我方「仅 HYBRID 档」的取舍同型；
- **不是屏幕空间效果**，屏幕外元素也能被照亮（对比 SSIL 的局限）——这正是 P4-17 相对 SSAO 的收益点；
- **收敛期约 30 帧**（官方使用文档原文），需要过渡遮掩——对应我方「时域累积 + 首帧/切换后衰减」的参数面；
- 锐利反射只在**不透明**材质可见，透明材质只用粗糙反射——与我方 HYBRID 档的不透明/透明分桶同构。

### 1.4 与我方的差异 / 移植点

- 它把 GI 做成**环境级开关 + 环境级参数**（挂在 Environment 上），与我方 ④ 已落地的 `Environment.ambientMode` 条件子组同套路，**可以直接复用我方的组件形态**（P4-05 首版口径 ② 已这么定）；
- 它用 RenderingDevice 的资源对象，**没有 VkImage/VkImageView 一层**——抄 dispatch 与格式选择，别抄资源封装；
- 它默认把 SDFGI 与反射探针/SSAO 的交互也处理了（如 SSIL 与 SDFGI 的取舍），我方没有 SSIL，这块不抄。

### 1.5 可抄 / 不可抄

- **可抄**：级联切分与轮转更新的调度思路、SDF/体素写入与遍历的数学、收敛期衰减、调试可视化形态；
- **不可抄**：RenderingDevice 封装、Godot 的 shader 参数块（uniform set）布局、它的场景/节点耦合。

---

## 2. G3D Innovation Engine（DDGI 官方参考 GLSL，BSD-2）

> 获取：**无官方 GitHub 仓库**——官方站 `https://casual-effects.com/g3d`；源码分发 SourceForge `https://g3d.sourceforge.net`；论文页附件 `https://jcgt.org/published/0008/02/01/`

### 2.1 关系

DDGI 论文 *Dynamic Diffuse Global Illumination with Ray-Traced Irradiance Fields*（JCGT 2019）明确写「open source reference shaders for implementing DDGI, taken directly from and compatible with the open source G3D Innovation Engine」，并提供 **reference GLSL code**。G3D 官网写明 core（`G3D-base.lib`/`G3D-gfx.lib`/`G3D-app.lib`/**`data-files/shader`**）为 **OSI 认可的 2-Clause BSD**——这是本清单许可最干净的一份。

### 2.2 能拿到的算法部件（论文级）

- **探针布局与显存布局**：一张大 atlas 存所有探针的辐照度与深度（避免逐探针一张纹理）；
- **octahedral 编码**：每探针 8×8 octahedral 贴图存方向性辐照度；
- **可见性感知的矩插值**：论文贡献点，用深度矩做 probe 间可见性加权的插值（明确命名为 moment-based interpolant）；
- **更新轮转**：每帧只更新一部分探针（时域均摊）；
- C++ 侧入口：`G3D::DefaultRenderer::createProbeVolumes(...)`（官方类参考已列）。

### 2.3 移植坑

- G3D 是 **OpenGL 4.x + G3D 自有 shader 宏体系**（`<G3D/shader>` 注入、`uniform` 命名空间），GLSL 版本较老，`imageLoad/imageStore`、`layout(binding)` 等 Vulkan 习惯写法要重排；
- 它的求交走 **G3D 自己的 GPU ray tracing（RayGrid/UMat）**，我方首版没有硬件光追，**求交后端必须换成体素 DDA**（即 P4-05 首版口径 ④ 的第一选项），抄的是「probe 数据面 + 更新与插值」，不是求交；
- BSD-2 义务：**保留版权声明与免责声明**（进文件头，形如现有 `builtin-asset-core/postprocess` 那批 Babylon Apache 注释的做法）。

---

## 3. helenl9098 / DDGI-Minecraft（Vulkan compute 工程模板，Apache-2.0）

> 仓库：`https://github.com/helenl9098/Dynamic-Diffuse-Global-Illumination-Minecraft`

### 3.1 技术路线

- 一个 **SIGGRAPH 2019 DDGI 论文的 Vulkan 复现**，Minecraft 风格场景；
- README 自述：因硬件限制**无法使用 Vulkan 光追扩展**，代码基于 **Realtime Vulkan Path Tracer（RVPT）**，用 **compute shader 做光线求交**——即「软件光追 + compute」的完整工程；
- 许可为 Apache-2.0（仓库 LICENSE 为 Apache 系列，本次检索已见）。

### 3.2 可抄的工程组织（这是它相对 G3D 的独有价值）

- **DDGI 在 Vulkan 里的 descriptor / dispatch 编排**：probe 更新 pass 与 irradiance / distance blend pass 的分工、临时资源与 barrier 的放置；
- **compute 软光追的 BVH 遍历与命中着色**（替代硬件 RT 的首版求交后端形态，可与体素 DDA 方案对比取舍）；
- **Vulkan 侧资源格式选择**（atlas 存储格式、mip 与采样器配置）。

### 3.3 边界（别照抄过头）

- 场景是 Minecraft 风格（大量轴对齐方块），**BVH/体素效率结论对我方复杂网格不成立**；
- 学术课程项目体量，**无工业级优化**（无 bindless、无 GPU 侧调度、无多队列）；
- 它的 RHI 是自建最小层，**不适合作为我方 RHI 的参考**，只抄算法与 pass 编排。

---

## 4. Babylon IBLShadows（体素遮挡单件；指针）

> 仓库：`https://github.com/BabylonJS/Babylon.js`；IBLShadows 目录 `packages/dev/core/src/Rendering/IBLShadows/**`

已在 `Babylon_Rendering_Reference.md` §4.5 / §10-11 与本仓 `doc/Babylon_Rendering_Reference.md` 的源码索引表中登记，**此处只留与 P4-17 的对应要点**，不重复其细节：

- 3D 纹理 R8 + NEAREST，`resolutionExp` = 2^n 控分辨率；WebGPU 走 storage 3D 直写，WebGL2 退化为三轴 slab + `iblCombineVoxelGrids` 合成 pass；
- 追踪 = `iblShadowsVoxelTracingPass` 沿方向 DDA 命中即遮挡（透明体素轮盘赌限穿透次数）；
- 收尾 = 双边模糊压噪 + 时域累积（场景变化 `resetAccumulation()`）；
- **适用边界**：只产「环境光遮蔽」，不产辐照度/颜色反弹——**即 P4-17 的定位**，渗色仍归 P4-05。

---

## 5. O3DE DiffuseProbeGrid（DDGI 的 pass 划分范式，Apache-2.0）

> 仓库：`https://github.com/o3de/o3de`；落点 `Gems/DiffuseProbeGrid/Code/Source/Render/**`

- 位于 `Gems/DiffuseProbeGrid`，官方 API 文档列出四条 compute pass：**PreparePass**（更新网格数据）、**RaytracePass**（探针追踪）、**BlendIrradiancePass**（由追踪结果生成辐照度贴图）、**BlendDistancePass**；
- 价值在**章节骨架**：把 DDGI 讲成「准备 → 追踪 → 混辐照度 → 混距离」四段，正好是我方设计档该有的结构；
- 许可 Apache-2.0（**本次未逐字核对其 LICENSE 文本，见 §10**）；
- 它依赖硬件光追，**求交层不可抄**，只抄 pass 划分与参数面（间距、自阴影 bias、分帧刷新）。

---

## 6. 光追 rayQuery 线（P4-06）

> 仓库：nvpro `https://github.com/nvpro-samples/vk_raytracing_tutorial_KHR`｜Khronos `https://github.com/KhronosGroup/Vulkan-Samples`｜The-Forge `https://github.com/ConfettiFX/The-Forge`

### 6.1 nvpro-samples / vk_raytracing_tutorial_KHR（首选，Apache-2.0）

- **sample 16 Ray Query**：`rayQueryInitializeEXT` / `rayQueryProceedEXT` / `CommittedStatus` / `CommittedRayT` 的标准循环；
- **sample 17 Ray Query Screenspace**：官方描述为「compute shader ray queries，用于 AO 一类效果，结合 rasterization 与 G-buffer，实时单射线效果」——**P4-17 若改走 rayQuery，形态直接照这份**；
- 教程同时给 **AS 构建（BLAS/TLAS）/ descriptor 组织**的渐进式讲解（`02_basic_nvvk` 与直连 Vulkan 两套写法）。

### 6.2 Khronos Vulkan-Samples（Apache-2.0）

- ray query / ray tracing 样本，写法更「原生」（不带 nvpro 辅助库），适合核对 **stageFlags、AS 布局、shader 里的 `GL_EXT_ray_query` 前置声明**。

### 6.3 The-Forge（Apache-2.0）

- 跨后端（Vulkan / DX12）的 AS 管理与 RT 封装层——类比我方「AS 作为新资源种类进图」时，**哪些状态属于资源、哪些属于 pass** 的划分可直接借鉴；
- 只抄**抽象划分**，别抄它的 DescriptorManagement / ResourceLoader（那是它自己的体系）。

### 6.4 我方前置缺口（与本档关系）

AS / BDA / `GL_EXT_ray_query` 编译链是我方 ⑦ 的三个底座缺口（`Engine_Backlog.md` P4-06）。参考件给的是**用法形态**；具体 LWJGL 侧的 API 签名（`vkCmdBuildAccelerationStructuresKHR` 一族、`MemoryRequirements` 结构体、shader 侧扩展名）**一律按 AGENTS 红线 4 走 IDEA MCP 查证**，不从网页或本档猜。

---

## 7. Kajiya（Apache-2.0）——后置档

> 仓库：`https://github.com/EmbarkStudios/kajiya`

- Rust + Vulkan 的实验性实时全局光照渲染器，ReSTIR DI/GI 路线；
- **不在首版范围内**：ReSTIR 依赖采样蓄水池 + 重投影 + 去噪体系，我方 ⑦ 首版要的是「静态几何 + 单次求交」的保守形态；
- 定位为 **⑦ 之后的升级参考**，与 `Engine_TAA_Design.md` 的运动矢量/历史通道有交集（ReSTIR 的时间复用与 TAA 历史是同源问题）。

---

## 8. 仓库地址与许可分级

### 8.1 仓库地址（取件用）

| 项目 | 仓库 / 获取地址 | 备注 |
|---|---|---|
| Godot 4 | `https://github.com/godotengine/godot` | SDFGI 着色器 = `servers/rendering/renderer_rd/shaders/environment/sdfgi_*.glsl` |
| G3D Innovation Engine | **无官方 GitHub 仓库**——官方站 `https://casual-effects.com/g3d`，源码分发在 SourceForge `https://g3d.sourceforge.net` | DDGI 论文页注明「reference code is available online at JCGT」：`https://jcgt.org/published/0008/02/01/`（取件优先看该页附件） |
| DDGI-Minecraft | `https://github.com/helenl9098/Dynamic-Diffuse-Global-Illumination-Minecraft` | Apache-2.0，Vulkan compute 软光追 |
| Babylon.js | `https://github.com/BabylonJS/Babylon.js` | IBLShadows = `packages/dev/core/src/Rendering/IBLShadows/**` |
| O3DE | `https://github.com/o3de/o3de` | DiffuseProbeGrid = `Gems/DiffuseProbeGrid/Code/Source/Render/**` |
| nvpro-samples | `https://github.com/nvpro-samples/vk_raytracing_tutorial_KHR` | sample 16 = `16-ray-query`；sample 17 = Ray Query Screenspace（目录名以仓库为准） |
| Khronos Vulkan-Samples | `https://github.com/KhronosGroup/Vulkan-Samples` | — |
| The-Forge | `https://github.com/ConfettiFX/The-Forge` | — |
| Kajiya | `https://github.com/EmbarkStudios/kajiya` | 后置档（ReSTIR） |
| NVIDIA RTXGI | `https://github.com/NVIDIAGameWorks/RTXGI`；DDGI 专版 `https://github.com/NVIDIAGameWorks/RTXGI-DDGI` | **黄档**：源码可得但许可不可再分发、D3D12 |
| Flax Engine | `https://github.com/FlaxEngine/FlaxEngine` | **黄档**：Flax EULA，只读不抄 |
| Unreal Engine 5（Lumen / DFAO） | 私有仓库 `https://github.com/EpicGames/UnrealEngine`（需 Epic 账号绑定 GitHub 后访问）；公开文档 `https://dev.epicgames.com/documentation/unreal-engine/lumen-technical-details-in-unreal-engine`、`.../mesh-distance-fields-in-unreal-engine`、`.../distance-field-ambient-occlusion-in-unreal-engine` | **红档（UE EULA）**：源码可见 ≠ 开源，只读不抄（详见附录 A） |
| Unity HDRP APV | 不开放 | **红档** |

> 取件口径：上表地址仅供查阅。**黄档（Flax / RTXGI）禁止抄码入库**；绿档抄码须按 §8.2 履行署名。

### 8.2 许可分级与署名义务

| 项目 | 许可 | 抄码 | 核实状态 | 义务 |
|---|---|---|---|---|
| G3D（core + `data-files/shader`） | **BSD-2** | ✅ | 已核（官网正文） | 保留版权声明与免责声明 |
| Godot 4 | **MIT** | ✅ | 已核（项目通例 + 文档） | 保留版权与许可声明（引擎整体 COPYRIGHT.txt 有第三方声明清单） |
| Babylon.js IBLShadows | Apache-2.0 | ✅ | 已核（同 Babylon 档既有结论） | 文件头注明来源 + NOTICE（我方已有先例） |
| helenl9098/DDGI-Minecraft | **Apache-2.0** | ✅ | 已核（仓库 LICENSE） | 同上 |
| O3DE DiffuseProbeGrid | Apache-2.0 | ✅ | **待核**（未逐字读 LICENSE） | 同上 |
| nvpro-samples / Vulkan-Samples | Apache-2.0 | ✅ | **待核**（未逐字读 LICENSE） | 同上 |
| The-Forge | Apache-2.0 | ✅ | 已核（公开许可信息） | 同上 |
| Kajiya | Apache-2.0 | ✅ | 已核（仓库元数据） | 同上 |
| Flax Engine | **Flax EULA**（源码可见、收入分成） | ❌ 只读 | 已核（官方 FAQ 明示） | 不得抄码入库 |
| NVIDIA RTXGI SDK | **NVIDIA 许可**（申请制、不可再分发、D3D12） | ❌ 只读 | 已核（官方许可文本） | 不得抄码、不得分发 |
| Unreal Engine 5 | **UE EULA**（源码仅对签约许可持有者可见；私有仓库） | ❌ 只读 | **待核**（EULA 条款本次未逐字读；「私有仓库 + 账号绑定」口径已核） | 不得抄码入库、不得用于其他引擎 |
| Unity HDRP APV | 商业许可 / 不开放 | ❌ | — | 只读文档与论文 |

> **抄码口径**：只抄**算法数学 + GPU 组织**（dispatch 尺寸、格式、pass 顺序、纯函数），不抄框架抽象层——这与三期批次①「通用性红线」（禁止光照特化窄接口）是一致的。

---

## 9. 与本引擎的适配清单（按四期落点）

### P4-17 体素化环境光遮蔽

| 抄哪层 | 来源 | 我方差异 / 前置 |
|---|---|---|
| 体素化写入 + DDA 追踪 + 双边模糊 + 累积 | Babylon IBLShadows（§4） | 我方无 `TEXTURE_3D`，**需先补 RHI 3D 纹理 + compute 写通道** |
| 级联/多分辨率体素与轮转更新 | Godot `sdfgi_preprocess.glsl` / `sdfgi_integrate.glsl` | 抄轮转策略；不抄 RenderingDevice |
| 只做遮挡、不含反弹的定位 | 与 P4-05 的分工 | 与 ④ 常量 `ambient` 组合：网格外/无体素覆盖回落常量 |
| 硬件光追口径（可选后置） | nvpro sample 17 | 依赖 ⑦ 的 AS 底座 |
| 跟随相机的体积（clipmap）+ 只更新脏区 | **UE DFAO / Global Distance Field（只读）** | 正是 P4-05 口径 ②「相机滚动体积」的原型；只抄结构 |
| 锥体两级混合（近处对象 SDF + 远处全局低分辨率 SDF） | UE DFAO（只读） | **我方无 per-object SDF 生产链**，首版单一分辨率体素网格，两级留后置 |

### P4-05 探针 GI（DDGI）

| 抄哪层 | 来源 | 我方差异 / 前置 |
|---|---|---|
| probe atlas / octahedral 编码 / 矩插值 / 自阴影 bias | **G3D（BSD-2）** | 求交后端换成体素 DDA（首版） |
| compute 软光追 + DDGI pass 编排（Vulkan） | **DDGI-Minecraft** | 场景尺度与性能结论不可照搬 |
| pass 划分与参数面 | O3DE DiffuseProbeGrid | 求交层不可抄（它用硬件 RT） |
| 探针网格来源与场景覆盖 | Godot SDFGI 的「级联 + 收敛期」口径 | 我方首版 = 相机滚动体积 + `Environment` 条件子组覆盖（P4-05 口径 ②） |
| 收敛期衰减 / 调试可视化 | Godot SDFGI（30 帧收敛）+ `sdfgi_debug*.glsl` | 对应我方「首帧/切换衰减 + DBG 件」 |
| 「表面缓存 + 屏幕探针 + 屏幕追踪」三段分离（远期路线） | **UE Lumen（只读）** | 复杂度来自场景捕获/Nanite 耦合，**首版不照抄**，仅留作远期对照 |

### P4-06 光追 rayQuery

| 抄哪层 | 来源 | 我方差异 / 前置 |
|---|---|---|
| rayQuery 循环与命中数据提取 | nvpro sample 16 | LWJGL 签名走 IDEA MCP |
| compute 内 rayQuery 做 AO | nvpro sample 17 | 与 P4-17 共享消费者 |
| 原生（无辅助库）写法与 stageFlags | Khronos Vulkan-Samples | — |
| AS 资源种类与图状态划分 | The-Forge | 不抄其描述符体系 |
| 后置：ReSTIR | Kajiya | 首版不做 |

---

## 10. 待核实清单（禁当结论使用）

以下条目**本次未取得一手源码/许可文本**，落地前须检出仓库或走 IDEA MCP 核实：

1. **G3D DDGI 的具体 shader 文件名与路径**（`data-files/shader` 下的 DDGI 目录结构）；本次只确认「论文参考 GLSL 发布在 G3D + core/data shader 为 BSD-2」。
2. **Godot SDFGI 的 C++ 调度文件路径与函数粒度**（`renderer_rd/environment/` 下具体文件）；本次只确认五个 `.glsl` 与三条「仅 Forward+ / 非屏幕空间 / 约 30 帧收敛」口径。
3. **Godot SDFGI 的级联数量、探针网格分辨率、每探针面数**等具体常量（官方使用文档只给可配项，未给工程默认值明细）。
4. **DDGI-Minecraft 的文件级结构**（哪些 shader 对应 raycast / blend 两 pass）；本次确认其 Apache-2.0、Vulkan、compute 软光追（RVPT）路线与「未用 RT 扩展」自述。
5. **O3DE 与 nvpro-samples 的 LICENSE 文本**（按公开常识应为 Apache-2.0，未逐字核对）。
6. **Flax DDGI 的算法细节**（`Source/Engine/Renderer/GI/DDGI` 结构）——**只允许读思路，不得抄码**（Flax EULA）。
7. **LWJGL 侧全部光追 API 签名**——`vkCmdBuildAccelerationStructuresKHR`、`VkAccelerationStructureGeometryKHR`、`MemoryRequirements`、`GL_EXT_ray_query` 在 shaderc/glslang 的启用方式：**一律 IDEA MCP，不网页搜索**（AGENTS 红线 4）。
8. **UE 侧的可读边界与实现细节**：①UE EULA 关于「阅读源码 / 派生」的具体条款本次未逐字读（`EpicGames/UnrealEngine` 为私有仓库、需账号绑定已核）；②Lumen 五件（Surface Cache / Screen Traces / Radiance Cache / Screen Probe Gather / HWRT 模式）只有**概念级文档**，GPU 侧实现细节未核；③DFAO 的**两级 SDF 锥体混合**与 GDF clipmap 的更新策略未核到代码级——**均按"只读思路、不抄码"使用，不得据此写实现代码**。

---

## 11. 引用与落点

- 本档服务的条目：`Engine_Backlog.md` P4-17 / P4-05 / P4-06；对应设计档在落地时新建（P4-17 可并入 `Engine_Lighting_Design.md` 或独立成档，P4-05 需专档，P4-06 需 RHI 底座批）。
- 与既有参考档的关系：`Babylon_Rendering_Reference.md` 承载 Babylon 全局；本档只补 GI / 体素 / 光追三段的外部来源，**不重复 Babylon §4.5 的细节**。

---

## 附录 A：Unreal Engine 5（Lumen / DFAO）——只读参照，不抄码

> 仓库：`https://github.com/EpicGames/UnrealEngine`（**私有仓库**，需 Epic 账号绑定 GitHub 才能访问；非公开可克隆）
> 公开文档：Lumen 技术细节 `https://dev.epicgames.com/documentation/unreal-engine/lumen-technical-details-in-unreal-engine`｜Mesh Distance Fields `https://dev.epicgames.com/documentation/unreal-engine/mesh-distance-fields-in-unreal-engine`｜DFAO `https://dev.epicgames.com/documentation/unreal-engine/distance-field-ambient-occlusion-in-unreal-engine`
> 许可：**UE EULA**——源码对许可持有者可见，可读以理解机制，**不得把代码搬进其他引擎或其他产品**。故本附录只记「它的思路与我方哪个条目对应」，**不给文件级移植路径**。

### A.1 Lumen 的链路（官方文档已确认的五件）

1. **Surface Cache**：对邻近场景表面做自动参数化，为每网格从多个角度捕获材质属性（捕获位置 = **Cards**，离线生成）进 atlas；玩家移动时持续重捕附近网格。作用是「射线命中点快速查光照」。
2. **Screen Traces**：屏幕空间追踪（采样 Scene Depth / Color），提高质量；局限是屏幕外不可见（与 SSGI 同性质）。
3. **Radiance Cache**：屏幕探针的中间缓存，探针分辨率与追踪数可配（`r.Lumen.ScreenProbeGather.RadianceCache.ProbeResolution`）。
4. **Screen Probe Gather**：从像素发射射线并积分入射光 = final gather；主性能杠杆是探针间距（`r.Lumen.ScreenProbeGather.DownsampleFactor`）。
5. **软件 / 硬件光追双模式**：HWRT 模式用加速结构，软件模式面对的是与我方 ⑥ 首版同一条约束（无 RT 硬件的近似求交）。

**对我方的结论（明确写死不跑偏）**：
- Lumen 的复杂度大头是**场景捕获 + Nanite 耦合 + 表面缓存参数化**，这三件我方都没有、也不打算建；
- **首版参考仍是 DDGI（§2/§3）**，Lumen 只作远期对照：若将来做「屏幕空间探针 + 表面缓存」路线，再回来看这三段分离；
- 唯一可直接借的**结构判断**：探针收集（gather）与表面表示（cache）是两件事，不要在一开始就耦合。

### A.2 DFAO 与全局距离场（对 P4-17 最直接的一段）

- **Global Distance Field（GDF）**：低分辨率 SDF，把各网格的**对象 SDF** 合成到**围绕相机跟随的若干 clipmap 体纹理**里；只更新新进入视野或被修改的区域——**这正是 P4-05 首版口径 ②「相机滚动体积、间距可配、显存恒定」的原型**。
- **锥体追踪两级混合**：锥体**起点附近用对象 SDF**（自遮挡准确），**其余段用 GDF**（低分辨率但便宜）；官方口径称天空遮蔽因此约 5 倍性能提升。
- 参数面：`Occlusion Max Distance`（遮挡源最远距离，随距离增加开销）、`Distance Field Resolution Scale`（逐 actor 的 SDF 分辨率，影响体纹理尺寸）。

**对我方的取舍**：
- 两级混合的**前提是「每网格对象 SDF 生产链」**（导入期烘焙 + 存盘 + 合成），我方目前没有 → **首版不抄两级**，只用单一分辨率体素网格 + 相机滚动体积；两级留作后置（实测缝隙精度不够再加）。
- 可抄的是**结构**：跟随相机的 clipmap、只更新脏区域、以**少量锥体/方向射线**代替逐像素多射线。
- 表征形式差异：UE 用**距离场**（可做软遮挡过渡），Babylon/IBLShadows 用**占据体素 + DDA 命中**（更简单）。**首版按占据体素走**，SDF 只在"需要软过渡/精确自遮挡"时才值回成本。
</content>
