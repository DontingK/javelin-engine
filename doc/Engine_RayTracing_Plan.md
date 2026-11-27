# 光追首范围（P4-06）推进计划

> 版本：v1.8（2026-11-28，**第二轮实机：GI 天光断供修复 + B8 降噪链两前置定案（§3.15 补记）**：⑩RTGI 档「阴影不对」根因 = 桥清零常量环境项而 GI 天风输入恰读该字段——`Environment.giSkySetting` 独立来源（`giSkyRadiance()`，`ambientRadiance` 删除）+ RTReflectPass 天光回退同修；⑪`sunVisible` tMax 借 GI 采样距离 20m 与 RT 阴影 150m 打架——`lightDir.w` 传 150（RenderDoc push 导出核对生效）；**剩余「换视角 uGi 明暗整幅重排」定性为屏幕反查设计缺陷非 bug**——根治归 REND-23-D 材质代理表、时域平滑归 B8 修复（`historyValid` 关掉整套重投影 + 采样相位只重排不换样，两前置已定案待落码））
> v1.7（2026-11-28，**B0~B9 首轮实机排障九连修**：①Mesh BLAS 三处判据读已释放的 CPU 副本 → TLAS 实例恒 0；②缓冲缺 `ACCELERATION_STRUCTURE_BUILD_INPUT`（VVL 03673）；③TLAS 重复构建缺 BUILD→BUILD 屏障；④rtdenoise 半分辨率 texelFetch 采全分辨率 G-Buffer；⑤REND-23-B 首步提前落（命中点朝阳补主光可见性射线，B 方案）；⑥屏幕反查补可见性比对；⑦降噪链 ping-pong 单代静默退化 + 读代交替（Pitfalls #46）；⑧段描述符集分代变体缓存（Pitfalls #47）；⑨TLAS 实例掩码口径对齐 CSM（透明/castShadow=false 不挡光，反射/调试 0x03）+ `--rtDenoiseBypass` 旁路开关——见 §3.15）
> v1.6（2026-11-27，**RTGI 质量补全族立项（REND-23，§3.14）**：用户拍板「后期都是需要的，这样才能算完整的 RTGI」——十子项按性价比排序：A 天光 SH9 / B 二次可见性（复用 RT 阴影图）/ C 簇灯进间接光 / D 屏外真材质 / E 自发光 / F 双边上采样 / G 透明镂空 / H 第二 bounce / I 变差引导降噪 / J 动态 BLAS；前置 = P4-06 编译 + 实机验收）
> v1.5（2026-11-27，**B8 时域累积 + ATrous 降噪落码**：GI 与反射共用一支 `rtdenoise.compute`（temporal 重投影累积 + 年龄自适应 α / atrous 5×5 十字保边两迭代），`RTDenoiser` 共享执行器 + 每视口 ping-pong 历史两代 + 中转一张，重投影矩阵与失效判据同 TAA 源——见 §3.13；Java 侧待编译、`rtdenoise.compute` 已过 glslc）
> v1.4（2026-11-27，**DDGI 整体退役 → RTGI 即引擎唯一实时 GI**：`AmbientMode` 收 `OFF/CONSTANT/RTGI` 三档，RTGI 档由 `SceneRenderBridge` 派生 `rtgi=100/0`（原 `Environment.rtgiPercent` 字段删除）；B7 的「与 DDGI 是 mix 关系、100% 顶掉 DDGI」残留随之作废——现仅剩常量基线与 RTGI 的混合。B8 时域降噪优先级上升（GI 噪点现在没有别的层兜底）。见 `Engine_DDGI_Design.md` 头部退役横幅）
> v1.3（2026-11-26，B9 镜面反射落码：半分辨率 1 spp 反射射线 + 粗糙度 lobe 抖动 + 命中点屏幕反查，PBR 按 Fresnel 计入环境镜面）
> v1.0（2026-11-26，B6 质量修补落码：混合档百分比 + 阴影强度统一 + 法线偏移起点）
> v0.9（2026-11-26，B5 主链消费落码：半分辨率 RT 阴影可见性图 + 材质 shader 采样替换）
> v0.8（2026-11-26，B4 CSM 对照落码：`csmcompare.frag` + 第四态 + 四绑定 set0）
> v0.7（2026-11-25，B3 前置：级联求值抽为共享核心 `javelinCsmCore` + 对照参数快照）
> v0.5（2026-11-25，B3 落码：加速结构描述符 + 半分辨率相机主射线求交直显 F9）
> v0.4（2026-11-25，B2b 落码：TLAS + 构建后屏障 + 光追场景装配；B3 待做）
> v0.3（2026-11-25，B2a 已落码：AS 设备地址分配 + BLAS 构建 + Mesh 缓存；B2b = TLAS 待做）
> v0.2（2026-11-25，B0 + B1 均已落码；B2 起待拍板）
> v0.1（2026-11-25，B0 已落码；B1 起待拍板）
> 定位：`Engine_Roadmap.md` §4.1 **L6**；`Engine_Backlog.md` P4-06。
> 范围口径（路线已定）：**只做阴影与 GI 求交，不做镜面反射消费**（镜面项归 L7 BRDF 之后）。

---

## 1. 为什么先做「能力探针」

2026-11-25 用户拍板：曝光/测光、色彩分级 LUT、DOF、体积雾都属后期，**主线转光追**。但 L5 那四项的阻塞点（RHI-14 mip 生成链）与光追无关——光追的阻塞点完全不同，是**驱动能力**：

| 需要 | 现状 |
|------|------|
| `VK_KHR_ray_query` 扩展 + `rayQuery` feature 位 | RHI **无任何枚举**：`VulkanDevice` 建设备时只开 swapchain，`Device` 接口没有扩展/特性查询口 |
| `VK_KHR_acceleration_structure` / `VK_KHR_buffer_device_address` | 同上，且 `BufferUsage` 没有 `SHADER_DEVICE_ADDRESS` / AS 存储位 |
| 光追着色器编译 | shaderc（glslang）已在用，`GL_EXT_ray_query` 可直接编；**不需要换工具链** |
| 计算 Pass 基建 | 已有（三支 compute 落码先例：体素化/SSAO/cluster；DDGI 三支已于 2026-11-27 随退役删除），B3 可直接照搬 |

本机日常开发机显卡 = **AMD Radeon 780M（RDNA3 iGPU）**，驱动 32.0.11039.6002；但**实际光追验收显卡是 RTX**（2026-11-25 用户明确），780M 不作为光追判据——B0 探针仍保留，用于在任意机器上先看到「这台能不能开工」，而不是等 B2 建 AS 时才炸。

## 2. 批次划分

| 批 | 内容 | 产出 | 状态 |
|----|------|------|------|
| **B0** | 能力探针：`VulkanPhysicalDevice` 枚举设备扩展（懒算一次）、`Device.hasDeviceExtension(String)` / `Device.supportsRayQuery()`（扩展位 + feature 位双判）、启动期一行日志 | 能否开工的判据 | ✅ 2026-11-25 落码（未编译） |
| **B1** | 能力启用：`VkDeviceCreateInfo` 按「驱动真的上报」拼装扩展列表（`VK_KHR_deferred_host_operations` → `VK_KHR_acceleration_structure` → `VK_KHR_ray_query` / `VK_KHR_buffer_device_address`）+ 对应特性链；`BufferUsage` 加 `SHADER_DEVICE_ADDRESS` / AS 存储 / AS 构建输入三档；**能力可选不阻断启动**，但 `Device.rayQueryEnabled()` 报「这一台设备真的能用」，B2 建 AS 前查它、false 就显性抛错 | 设备能创建 AS | ✅ 2026-11-25 落码（未编译） |
| **B2** | AS 构建：每 `Mesh` 一条 BLAS（静态、`MeshUpload` 后一次性建）、每帧一条 TLAS（instances = `RenderObject` 世界矩阵 + 实例自定义索引）；AS 缓冲走 device-local，构建用一次性 command buffer + fence | `rayQuery` 可求交 | ✅ B2a + B2b 2026-11-25 落码（未编译） |
| **B3** | 最小消费：半分辨率 compute Pass 里对每个像素投一条主光方向射线，输出 `命中距离 / miss` 可视化（先验证数值与 shadow map 一致），再决定是「替换 CSM」还是「与 CSM 混合」 | 可验收的求交画面 | ✅ 2026-11-25 落码（未编译）；首批用**相机主射线**，主光方向求交后置 |
| **B4** | CSM 对照：RT 遮挡与 CSM 可见性逐像素比对，红绿差异图（`csmcompare.frag` + 第四态 + 四绑定 set0） | 「替换 / 混合」拍板的判据 | ✅ 2026-11-26 落码（着色器过 glslc，未实跑） |
| **B5** | 主链消费：半分辨率 RT 阴影可见性图（独立 compute）+ 主渲染 shader 采样替换 CSM（`rtParams.x` 开关） | RT 阴影进主画面 | ✅ 2026-11-26 落码（Java 编译过、compute 过 glslc；**须重导全部 `.shader`**） |
| **B6** | 质量修补：`rtParams.x` 变混合百分比（0/100/中间）、RT 档统一乘 `shadowParams.z` 强度、compute 起点改「法线偏移 + 光方向偏移」（set0 加法线） | 可 A/B 的 RT 阴影 | ✅ 2026-11-26 落码（Java 编译过、compute + 注入后光照块均过 glslc） |
| **B7** | 漫反射 GI：半分辨率 4 spp 余弦半球求交（Hammersley + 帧相位），命中点投影回屏幕取 G-Buffer 反照率/法线，输出 RGBA16F 辐照度图，环境项按 `rtgiParams.x` 混合 | 有方向的间接光 | ✅ 2026-11-26 落码（Java 编译过、`rtgi.compute` + 注入块均过 glslc；**须重导 `.shader` 与新资产**） |
| **B7.5** | 前置 PBR（REND-03）：G-Buffer material 通道（r=roughness/g=metallic）+ GGX/Fresnel/Smith 光照，详见 §3.11 | 反射所需的粗糙度/金属度数据面 | ✅ 2026-11-26 落码（`compileJava` 过、三条 shader 路径 glslc 过；**须重导 `.shader`**） |
| **B8** | 时域累积 + 空间降噪：GI（B7）与反射（B9）共用降噪链——重投影采上帧历史（`prevJitteredViewProj` 同 TAA 源）+ 年龄自适应 α 累积，ATrous 5×5 十字保边核两迭代收尾，详见 §3.13 | 噪点可控的 GI 与反射 | ✅ 2026-11-27 落码（`rtdenoise.compute` 过 glslc；Java 侧待编译；**须导入新资产**） |
| **B9** | 镜面反射：半分辨率逐像素 1 条反射射线（相机→表面→法线镜像），粗糙度决定 lobe 抖动角，命中点投影回屏幕取反照率着色，输出 RGBA16F 反射图；PBR 段按粗糙度修正的 Fresnel 份额计入环境镜面 | 完整 RT 的第三类消费 | ✅ 2026-11-26 落码（`compileJava` 过、`rtreflect.compute` 与三条注入路径均过 glslc；**须重导 + 导入新资产**） |
| **REND-23** | **RTGI 质量补全族**（§3.14，2026-11-27 立项）：A 天光 SH9 / B 二次可见性 / C 簇灯间接光 / D 屏外真材质 / E 自发光 / F 双边上采样 / G 透明镂空 / H 二 bounce / I 变差引导降噪 / J 动态 BLAS——B0~B9 链路闭环之后的质量闭环。**B 首步已于 2026-11-28 提前落**（B 方案：朝阳命中补主光可见性射线，见 §3.15-5） | 完整的 RTGI | 部分开工（B 首步；其余前置 = 编译 + 实机验收 + B3 三阻塞修复） |

**刻意的顺序**：B3 先做**可视化**而不是直接接阴影——光追一旦接进主光，跑出来的画面异常（偏移、漏光、自交）很难归因；先出「命中距离直显」图，能和 shadow map 的深度图逐像素对照，错误一眼可见。

## 3. B0 落码点

| 落点 | 改动 |
|------|------|
| `VulkanPhysicalDevice` | 新增 `Set<String> extensions()`（`vkEnumerateDeviceExtensionProperties` 懒算一次）+ `hasExtension(String)` |
| `Device`（接口） | 新增 `hasDeviceExtension(String)` / `supportsRayQuery()`；`supportsRayQuery` = 扩展在列 **且** `VkPhysicalDeviceRayQueryFeaturesKHR.rayQuery == true`（部分驱动上报扩展却把位关掉） |
| `VulkanDevice` | 实现上述两方法；`supportsRayQuery` 用 `vkGetPhysicalDeviceFeatures2` + `pNext` 链查位 |
| `JavelinEngine` 启动日志 | 新增一行 `光追能力探针: rayQuery=? accelerationStructure=? bufferDeviceAddress=?` |

**没做的**：不启用任何扩展、不建 AS、不改 `BufferUsage`、不动着色器。B0 是纯探测，跑起来对现有渲染零影响。

## 3.1 B1 落码点（2026-11-25）

| 落点 | 改动 |
|------|------|
| `VulkanDevice` 构造器 | 建 device 前先 `vkGetPhysicalDeviceFeatures2` 探三组特性（AS / rayQuery / BDA）；按「扩展在列 **且** 特性位为 true」决定是否加入启用列表——顺序 `deferred_host_operations` → `acceleration_structure` → `ray_query` / `buffer_device_address`，AS 缺失即整组降级（rayQuery 结构上依赖 AS）；再另建一套特性结构串 `pNext` 交给 `features13.pNext(...)`（探测那套用完即弃，避免 pNext 复用） |
| `VulkanDevice` 字段/方法 | `rayQueryEnabled` 字段 + `rayQueryEnabled()` 实现；`supportsRayQuery()` 保持「只报驱动能力」语义 |
| `Device` 接口 | 新增 `rayQueryEnabled()`，javadoc 写明与 `supportsRayQuery()` 的区别 |
| `BufferUsage` + `VulkanBuffer` | 新增 `SHADER_DEVICE_ADDRESS` / `ACCELERATION_STRUCTURE_STORAGE` / `ACCELERATION_STRUCTURE_BUILD_INPUT` 三档并映射 VK 标志（`VK12` BDA 位 + `KHRAccelerationStructure` 两档） |
| `JavelinEngine` 启动日志 | 一行报四态：`supported(rayQuery)` / `enabled(rayQuery)` / `accelExt` / `bdaExt` |

**B1 未做（B2 必须补）**：带 `SHADER_DEVICE_ADDRESS` 的缓冲必须在**分配显存时**带 `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT`（`VkMemoryAllocateFlagsInfo` 挂 `pNext`）——当前 `VulkanMemory.allocate` 没有这条路径，B2 建 AS 缓冲时一并加，否则 `vkGetBufferDeviceAddress` 校验失败。

---

## 3.2 B2a 落码点（2026-11-25）

| 落点 | 改动 |
|------|------|
| `VulkanMemory.allocate(..., boolean deviceAddress)` | 带 `VkMemoryAllocateFlagsInfo` + `VK_MEMORY_ALLOCATE_DEVICE_ADDRESS_BIT` 重载；无地址需求的调用走旧三参（内部转 false） |
| `Buffer.deviceAddress()` + `VulkanBuffer` | `needsAddress = usage 含 SHADER_DEVICE_ADDRESS` → 分配带址标志；bind 后 `vkGetBufferDeviceAddress` 取值，否则 0 |
| `CommandBuffer.handle()` / `buildBlas(as, desc, scratchAddress)` | `handle()` 是扩展命令逃生口；`buildBlas` 只发 `vkCmdBuildAccelerationStructuresKHR`（几何/构建信息由 `VkBlasGeometry` 装配，尺寸查询与构建共用一份声明） |
| `VulkanPhysicalDevice.accelerationStructureScratchAlignment()` | `VkPhysicalDeviceProperties2` + `VkPhysicalDeviceAccelerationStructurePropertiesKHR` 取 `minAccelerationStructureScratchOffsetAlignment`，懒算缓存，下限 1 |
| `AccelerationStructure` / `BlasDesc` | AS 抽象（`handle/deviceAddress/size`，extends `GPUResource`）；`BlasDesc` 构造期校验索引数是 3 的整数倍、两缓冲设备地址非零 |
| `VulkanAccelerationStructure` | 完整 BLAS：尺寸查询 → device-local 存储缓冲（STORAGE + BDA + AS_STORAGE）→ 建 AS → scratch（多分配 alignment、取对齐后地址）→ 一次性提交构建 + fence → 取设备地址；失败路径回收 AS 与存储；`dispose()` 显式销毁（不进 CleanerRegistry，顺序由持有者控） |
| `Device.createBlas` / `VulkanDevice` | 接口声明 + 实现；`rayQueryEnabled()` 为 false 时 `IllegalStateException`（红线 15，不做软件回退） |
| `Mesh.blas(device)` / `disposeBlas()` | 每网格一条 BLAS，惰性构建（整网格单几何，子网格不拆）；校验 location0 = `R32G32B32_SFLOAT`、索引数 %3；重新上传后须先 `disposeBlas` |
| `MeshUpload` | 顶点/索引缓冲用途补 `SHADER_DEVICE_ADDRESS`（否则 `BlasDesc` 构造即抛） |

**B2a 未做（B2b 已补）**：TLAS、AS 构建后的 GPU 侧屏障、RHI 外接线——见 §3.3。

---

## 3.3 B2b 落码点（2026-11-25）

| 落点 | 改动 |
|------|------|
| `VkAsGeometry` | 原 `VkBlasGeometry` 更名并扩 TLAS：实例几何（`VK_GEOMETRY_TYPE_INSTANCES_KHR`、`arrayOfPointers=false`、`data` = 实例缓冲设备地址）、TLAS 构建信息（`TOP_LEVEL` + `PREFER_FAST_TRACE`）、共用 `range(primitiveCount)`；新增 `buildToReadBarrier`（`ACCELERATION_STRUCTURE_BUILD` 写 → 顶点/片元/计算读） |
| `TlasInstance` | 实例描述：世界变换（3×4 行主序，占 64B 记录的前 48B）+ BLAS 设备地址 + 24 位自定义索引 + 8 位掩码 + `FORCE_OPAQUE`（默认开） |
| `Tlas` / `VulkanTlas` | 固定容量 TLAS：实例缓冲（host-visible 直写 + `SHADER_DEVICE_ADDRESS` + AS 构建输入用途）、AS 存储、预分配 scratch（对齐后地址）；`build(cmd, instances)` 写实例缓冲 + 记录一次重建 + 屏障；`capacity()` / `deviceAddress()` / `handle()` |
| `CommandBuffer.buildTlas` | 接口与实现（占位 `vkCmdBuildAccelerationStructuresKHR` + 屏障）；`buildBlas` 同批补同样屏障 |
| `Device.createTlas(int capacity)` | 接口声明 + `VulkanDevice` 实现；`rayQueryEnabled()` false 即抛错 |
| `RayTracingScene`（core） | 光追场景装配：遍历 `RenderObject` → `Mesh.hasTriangles()` 筛 → `mesh.blas(device)`（BLAS 缓存在 Mesh 上，共享网格只建一次）→ 按身份表复用 `TlasInstance`（每帧只刷世界矩阵）→ 容量不够（`max(16, 2^ceil)`）重建 TLAS → `tlas.build(cmd, …)`；`customIndex` 首次分配后稳定，`objectAt(int)` 供 B3 反查 |
| `RenderingSystem` | 设备支持时构造 `RayTracingScene`（否则 null）、只读访问器 `rayTracingScene()`、`close()` 释放 |

**B2b 未做 / 风险**：①实例缓冲单份不双缓冲，多帧飞行会让 GPU 读到半截数据（调用方须在上一帧 fence 之后 build，B3 接 Pass 时按此约束，或后补双缓冲）；②BLAS 无重建策略（网格重新上传必须先 `Mesh.disposeBlas()`，目前没有自动失效钩子）；③`RayTracingScene.build` **尚无调用方**——B3 以 RenderGraph Pass 形式接入（AS 作为图资源进依赖图）。

---

## 3.4 B3 落码点（2026-11-25）

| 落点 | 改动 |
|------|------|
| `DescriptorType.ACCELERATION_STRUCTURE` + 映射 | 新枚举 + `VulkanDescriptorSetLayout` 映射 `VK_DESCRIPTOR_TYPE_ACCELERATION_STRUCTURE_KHR` |
| `DescriptorUpdate.accelerationStructure(binding, Tlas)` | 新工厂 + `Tlas` 字段/访问器；`VulkanDescriptorSet.update` 走 `VkWriteDescriptorSetAccelerationStructureKHR` 挂 `pNext`（不占 pImageInfo） |
| 描述符池 | `VulkanDevice` 池尺寸表补 AS 档 64 |
| `RayTracingScene.tlasDescriptorSet()` | set0 binding0 = 加速结构（COMPUTE 阶段），TLAS 换代自动重建并重指；`dispose()` 一并释放集与布局 |
| `raytrace.compute`（新资产 + `.meta`） | 半分辨率每像素：`invViewProj` 反投影 far 平面点求方向 → `rayQueryInitializeEXT`（`Opaque`、mask 0xFF、tMin 0.05、tMax = farZ）→ `while(rayQueryProceedEXT)` → 写 `r32f` 存储图（命中 = t/tMax，miss = -1） |
| `raytraceview.frag`（新资产 + `.meta`） | 全屏采样距离图：命中近蓝远橙渐变、miss 深蓝 |
| `RayTraceViewPass`（editor/debug） | 两段图：compute（回调内先 `scene.build(cmd, renderers)` 再绑集派发，8×8 workgroup）+ 全屏大三角直显；距离图尺寸变经 `RenderingSystem.retireResource` 延迟换代 |
| `RayTraceDebugAppState` + `JavelinEditor` | F9 开关、第 14 行状态（实例数 / TLAS 容量）；设备无 ray query 或场景未建时写「未就绪」且不进图；语言包 4 键 |

**B3 未做**：①**主光方向求交**（需 G-Buffer 世界位置 + 与 shadow map 逐像素对照）——首批特意先用相机主射线，几何/自交/漏检问题更直观；②菜单项（当前只有 F9）；③半分辨率上采样（现在最近邻直接放大，边缘有块状）；④「替换 CSM 还是混合」的决策留待可视化验收后再拍。

---

## 3.5 B3 补：主光方向射线（2026-11-25）

| 落点 | 改动 |
|------|------|
| `raytrace.compute` | 加 `mode` 分支：mode 2 = 半分辨率像素 → UV → 采 G-Buffer 世界位置（`w < 0.5` 视为背景写 −1）→ 沿「指向光源」方向以 `worldPos + dir*0.02` 为起点求交，命中写 `t/tMax`。新增 binding2 `sampler2D uWorldPos`；push 扩到 112B（加 `vec4 lightDir`） |
| `RenderingSystem.sunDirectionOrNull()` | 暴露主光**行进方向**（与 `lighting.directional` 同源；无主光返回 null），调用方取负得「指向光源」 |
| `RayTraceViewPass` | 三态 mode（1 相机 / 2 主光）；mode 2 需 G-Buffer 世界位置与主光，缺任一则不进图（AppState 侧提示）；compute 集固定挂 binding2，mode 1 用距离图自身占位避免空绑定 |
| `RayTraceDebugAppState` | F9 三态循环 OFF → 相机主射线 → 主光方向 → OFF，状态行报实例数/TLAS 容量 |

**与 CSM 的对照口径**：mode 2 的遮挡轮廓应与现有 CSM 阴影轮廓重合；数值逐像素比对（在 compute 里同算 CSM 可见性、R/G 双通道叠显）**未做**——需要把 `javelinCascadeShadow` 的级联求值抽成共享函数再复用，留待「替换/混合」决策前的一批。

## 3.6 B3 前置：共享级联求值抽离（2026-11-25）

对照要想成立，必须让「光栅路的 CSM」与「对照路的 CSM」是同一份代码，否则两份实现漂移会给出假结论。本批只做抽离与管道，不出图。

| 落点 | 改动 |
|------|------|
| `ShaderInjector.CSM_CORE_GLSL` | 新增公开常量：`javelinCsmCore(cascade, worldPos, sunViewProj, invZ, shadowParams, shadowParams2) → vec4(uv, refZ, valid)`，承载级联矩阵投影、盒外判定、atlas 象限 uv、参考深度（半纹素外扩 + 用户偏移）。**PCF/PCSS 不进核心**（只影响半影过渡，不影响遮挡真值） |
| `LIGHTING_BLOCKS` | 改为 `CSM_CORE_GLSL + 原块`；`javelinCascadeShadow` 删掉自算前段，改调核心，取 `uv/refZ/grid/atlasSize/quadOrigin` 后原样走 PCF/PCSS——**逐像素结果与改前一致**（仅代码位置变化） |
| `SpvShaderImporter` | 新增 `//@javelinCsmCore` 标记分支：只内联该核心（UBO/采样器由对照 Pass 自声明），与 `//@javelinLighting` 互斥 |
| `RenderingSystem.packCsmCompareParams(cameraName, out)` | 新增只读快照：`sunViewProj[4] → shadowParams → shadowParams2 → cascadeInvZ`，304B，与 `lighting` UBO 同名同序；配套 `CSM_COMPARE_BYTES` 常量 |

**待做（下一批）**：`csmcompare.frag`（RT 遮挡 + CSM 可见性 → 红绿上色）、`RayTraceViewPass` 第四态与 set0 捆绑（RT 距离图 / G-Buffer 世界位置 / shadow atlas 比较采样 / CSM 参数 UBO）、`RayTraceDebugAppState` F9 四态。级联选择需相机视空间 z 与 `cascadeSplits`——快照里暂无，届时从 `RenderCamera` 侧补 view 矩阵或把 splits 并入块。

---

## 3.7 B4 落码点（2026-11-26）

| 落点 | 改动 |
|------|------|
| `csmcompare.frag`（新资产 + `.meta`） | 片元着色器带 `//@javelinCsmCore` 标记（只内联级联核心，不注光照块）；set0 四绑定 = 距离图 / 世界位置 / 阴影 atlas（`sampler2DShadow`）/ CSM 参数 UBO（std140，304B）；配色：两端一致阴影=绿、一致受光=白、**CSM 漏影=红**、CSM 过度=青绿、级联外/背景=灰 |
| `RayTraceViewPass` | 新增 `MODE_COMPARE = 3`：compute 复用主光方向求交（`computeMode = MODE_LIGHT`），展示段换对照 PSO；新增 `compareSetDesc`（4 绑定）、`compareSetFor`（绑定变化即换新集 + `retireResource`）、`compareShadowSampler`（LINEAR + LESS + CLAMP，与 `RenderingSystem.shadowSampler` 同参）、`csmCompareUbo`（304B，每帧 `packCsmCompareParams` 直写）；`pipelineFor` 抽出 `buildPipeline` 供两套 PSO 共用 |
| `RayTraceDebugAppState` | F9 四态 `OFF → 相机 → 主光 → 对照`；`requestMode` 上限放宽到 `MODE_COMPARE` |
| 级联选择 | **不另传 splits**：逐级跑 `javelinCsmCore` 取第一个 `valid`（与实渲染同源判定，重叠区取近级联），省掉快照扩 4B 的改动 |

**未做**：对照模式下阴影关/无 G-Buffer 时 Pass 不进图（AppState 只报就绪行，不给假结论）；四色对照的画面验收待实跑（判据 = 红/青绿应集中在半影与几何边缘，不应成片）。着色器（注入后）已过 `glslc --target-env=vulkan1.3`。

---

## 3.8 B5 落码点（2026-11-26）

**关键设计选择：RT 求交不进材质 shader。** 材质 shader 保持 `#version 450`（不加 `GL_EXT_ray_query`），求交集中在独立 compute 里产半分辨率可见性图，主渲染只多一次纹理采样和一次开关判断。理由三条：①避免全量 shader 升 460 + 扩展带来的编译链风险；②半分辨率 + 后续降噪有余量；③与 B3/B4 的射线构造口径一份代码。

| 落点 | 改动 |
|------|------|
| `core/shadow/rtshadow.compute`（新资产 + `.meta`） | 460 + `GL_EXT_ray_query`：G-Buffer 世界位置为起点、沿指向光源方向求交，输出 `r8` 可见性（1 受光 / 0 遮挡；背景写 1）。push 32B（vec4 光方向 + vec4 params：tMax / 起点偏移） |
| `RTShadowPass`（core，新类） | 半分辨率 compute Pass：回调内 `scene.build` 重建 TLAS → 绑 set0（AS / 可见性图 storage / 世界位置 sampled）→ dispatch。`rtShadowMode=0` 或设备无 ray query 时整段不进图；TLAS 空时跳过并只 warn 一次 |
| `RenderingSystem` | `rtShadowFor(cameraName)`（半分辨率 `R8_UNORM`：STORAGE｜SAMPLED，resize 换绑）；`rtShadowSampler`（LINEAR + CLAMP）；`setRtShadowMode/rtShadowMode`（默认 0，由组合根开）；`LIGHTING_BLOCK_BYTES` 672→**688**（`rtParams` @672：x 模式 / y tMax / z,w 全 backbuffer 尺寸）；set1 布局加 **binding12** 并写入描述符 |
| `JavelinEngine.createRenderPipeline` | 仅「混合管线 + ray query 设备」时 `setRtShadowMode(1)` 并 `addPass(rtShadowPass, "GBuffer")`（Forward 档没有 G-Buffer，误开会让着色端采未渲染图 → 全黑阴影，故默认 0） |
| `LightingPass / OpaqueForwardPass / TransparentForwardPass` | `addTextureRead(rtShadowFor)`（仅 mode≠0）——建 RTShadow→消费 Pass 的图依赖 |
| `ShaderInjector.LIGHTING_BLOCKS` | UBO 加 `vec4 rtParams`；set1 加 `binding12 sampler2D rtShadowMap`；`javelinLightingEx` 主光阴影段：`rtParams.x > 0.5` 走「`gl_FragCoord.xy / rtParams.zw` 采可见性图」，else 保持原 CSM/PCSS 整段 |

**必须重导**：`LIGHTING_BLOCKS` 改了 UBO 结构与 set1 声明 ⇒ 全部 `.shader` 与内置 `lighting.frag` 都要重导，否则结构错位（光照全乱）。

**已知残留（待验收后再定）**：①单采样无降噪，边缘锯齿/噪点，后续接 TAA/时空复用；②`RayTraceViewPass` 与 `RTShadowPass` 都每帧 build TLAS（调试视图开着时重复一次，功能无碍）。

---

## 3.9 B6 落码点（2026-11-26）

**一项到位收掉 B5 的三条残留：混合档、强度口径、起点偏移。**

| 落点 | 改动 |
|------|------|
| `rtshadow.compute` | `rtParams` 语义改为 **y = 法线偏移 / z = 光方向偏移**；新增 `binding3 sampler2D uNormal`；起点 = `worldPos + normalize(n) * y + dir * z`。只沿光方向推，薄面/掠射仍自交；只沿法线推，法线背光时把起点推进几何。两者叠加最稳 |
| `RTShadowPass` | set0 加 binding3（世界法线，`gbufferSampler`）；`boundNormal` 参与换集判定；`NORMAL_BIAS = 0.05` / `DIRECTION_BIAS = 0.01`（世界单位） |
| `RenderingSystem.setRtShadowMode(int percent)` | 语义变**百分比**：0 关 / 100 全替换 / 1~99 与 CSM 混合；`rtParams.x = percent / 100`。新增 `RT_SHADOW_DEFAULT_PERCENT = 100`，`JavelinEngine` 用它开档 |
| `ShaderInjector` 主光段 | 重排为「两路可选 + 统一乘强度」：`rtMix < 0.999` 才算 CSM（全替换档省掉级联选择与 PCF），`rtMix > 0.001` 才采 RT 图，`shadowFactor` 按 0 / 中间 / 1 三态取 `csmShadow` / `mix` / `rtShadow`，最后统一 `shadow = mix(1.0, shadowFactor, shadowParams.z)`。顺带修掉 `rtParams.x=1.0 > 0.5` 恒真带来的强度被绕过问题 |

**验证**：`:javelin-editor:compileJava` 通过；`rtshadow.compute` 过 `glslc --target-env=vulkan1.3`；把 `LIGHTING_BLOCKS`（CSM 核心 + lightList/cluster/atlas/rtShadowMap + `javelinLightingEx`）按真实注入顺序拼成最小 frag，同样过 `glslc`——主光段新分支的语法与类型都在编译期验过。

**实跑开关**：`RayTraceDebugAppState` 加 **F10**，循环 `0 → 25 → 50 → 75 → 100 %` 并打一行日志。不用改代码重编就能在同一场景里 A/B：100 看 RT 全替换的瑕疵，0 回到 CSM 基线，25/50 看混合是否吃掉偏差。

**为什么混合档值得做**：B4 的对照图如果显示 RT 与 CSM 在远距离/薄物体的偏差明显，直接全替换会把「CSM 其实对的地方」也换掉。`percent` 让 A/B 变成一次参数改动，而不是再改一遍 shader。降噪仍后置——单采样硬遮挡不会有射线噪声，真正的瑕疵是半分辨率上采样的像素化，需要深度感知双边上采样（G-Buffer 世界位置在 set1 binding7 现成可用），等实拍噪点分布出来再定 tap 数与权重。

---

## 3.10 B7 落码点（2026-11-26）

**光追的第二类消费：1-bounce 漫反射 GI。** 与 B5 的阴影不是两条技术线，而是同一套 AS + ray query 的两种用法：阴影是「1 条射线问可见性」，GI 是「N 条半球射线问间接光」。区别在于 **GI 有采样方差**，单帧必然噪，所以 B7 只保证「方向和量级对」，降噪留给 B8。

| 落点 | 改动 |
|------|------|
| `core/gi/rtgi.compute`（新资产 + `.meta`） | 460 + ray_query：半分辨率逐像素，在世界位置上做 4 spp **余弦加权半球采样**（Hammersley 2D + 帧序号相位错开，跨帧样本不重复）；命中后把命中点投影回屏幕（`push 的未抖动 viewProj`），取该像素 G-Buffer 反照率与法线；屏外/背面回退「反照率 0.5、法线 = -rayDir」。miss 按天光计，命中按「环境 + 主光 ndl」计（**不含二次可见性**，见残留） |
| `RTGIPass`（core，新类） | set0 五项：0 AS / 1 GI 图（storage）/ 2 世界位置 / 3 法线 / 4 反照率；push **128B**（mat4 viewProj + params(spp,tMax,frame,bias) + sunRad + ambientRad + lightDir）；TLAS 空时跳过并只 warn 一次；`rtgiMode=0` 或相机未收集时整段不进图 |
| `RenderingSystem` | `rtgiFor(cameraName)`（半分辨率 `R16G16B16A16_SFLOAT`：STORAGE｜SAMPLED，resize 换绑）；`rtgiSampler`（LINEAR + CLAMP）；`setRtgiMode/rtgiMode`（百分比，默认 0）；新增 `cameraViewProjFor`（本帧未抖动 viewProj，取自 TaaHistory）、`sunRadiance()`、`ambientRadiance()`；`LIGHTING_BLOCK_BYTES` 688→**704**（`rtgiParams` @688：x 混合 / y tMax / z,w 全 backbuffer 尺寸）；set1 加 **binding13** 并写入描述符 |
| `JavelinEngine` | 与 RTShadowPass **同锚点**（`after="GBuffer"`）注册，互不依赖；`RTGI_DEFAULT_PERCENT = 100` |
| `LightingPass / OpaqueForwardPass / TransparentForwardPass` | `addTextureRead(rtgiFor)`（仅 mode≠0）——建 RTGI→消费 Pass 依赖 |
| `ShaderInjector.LIGHTING_BLOCKS` | UBO 加 `vec4 rtgiParams`；set1 加 `binding13 sampler2D rtgiMap`；`javelinLightingEx` 环境项两段：`ambientBase`（常量）→ `rtgiParams.x > 0` 时按 `gl_FragCoord / rtgiParams.zw` 采 GI 图并 `mix(ambientBase, gi, x)`（原「DDGI 接管」中间段已随 DDGI 退役删除2026-11-27 删除） |

**为什么采样用 `gl_FragCoord` 而不是投影世界位置**：GI 图与 G-Buffer 逐像素对齐（同一个半分辨率网格），直接按屏幕坐标 1:1 采样即正确，且多视口天然成立；投影世界位置反而要在 shader 里多带一次 viewProj，还会被 TAA 抖动污染。

**实跑开关**：F11 循环 `0 / 25 / 50 / 75 / 100 %`。100 看纯 RTGI 的形状与噪点，0 回常量基线，中间看混合。

**验证**：`:javelin-editor:compileJava` 通过；`rtgi.compute` 过 `glslc -fshader-stage=compute --target-env=vulkan1.3`；`LIGHTING_BLOCKS` 按真实注入顺序拼最小 frag 过 `glslc`（新 ambient 分支语法与类型编译期验过）。

**已知残留**：①**无二次阴影**——命中点用 ndl 近似判断受光，暗角落会漏光，若要收需要第二条射线（成本翻倍，等 B8 后看占比）；②屏外命中的反照率退 0.5，间接光偏灰；③4 spp 单帧噪点明显，B8 时域累积前不建议拿它当验收画面；④半分辨率 + LINEAR 放大，几何边缘有 1~2 像素软化；⑤~~RTGI 与 DDGI 同开时是 `mix` 关系~~（**2026-11-27 DDGI 退役后作废**：`AmbientMode.RTGI` 档位即开关，`SceneRenderBridge` 派生 100/0，不存在两源并存）。

---

## 3.11 前置：PBR 光照模型（REND-03，2026-11-26）

**为什么在反射之前先做**：镜面反射必须按 roughness 分射线的 lobe（光滑面 1 条窄锥、粗糙面多 samp 宽锥），而 Lambert 模型里根本没有粗糙度概念，G-Buffer 也没有地方存。所以「RT 反射」的真正阻塞项不是 AS 或 compute，而是**材质参数进不了着色**。B7 之后插这一批，等于把 `roughness/metallic` 从 Properties 一直铺到 G-Buffer 与光照函数，B9 反射落码时只差一个消费端。

改动面（五处固定点 + 两处新数据面）：

1. `LightingModel` 末尾追加 `PBR("ins.enum.lightingmodel.pbr")`（不动既有 ordinal）；`ShaderParser.parseLightingModel` 加 `case "pbr"`；`ShaderInjector.lightingStageBlocks` 加 `case PBR`（该 switch 无 default，漏值即编译失败，是刻意保护）。
2. `PBR_LIGHTING_BLOCKS` 注入：`javelinD_GGX` / `javelinG_Smith`（Smith 联合）/ `javelinF_Schlick` / `javelinPbrDirect`（单灯响应，`kd=(1-F)*(1-metal)` 做能量守恒拆分）/ `javelinLightingPbr`（主光 + 簇灯 + 环境项，阴影段与 Lambert 同口径复制）。签名 `(viewNormal, viewPos, worldPos, contactShadow, albedo, vec2 roughMetal)`——rough/metal **作参数传入**，因为 `material` UBO 由注入末尾生成，块内引用会「声明在后」编译失败。
3. G-Buffer 第四附件 `R8G8B8A8_UNORM`（r=roughness / g=metallic），清屏 `CLEAR_MATERIAL=(1,0,0,1)` **恰好是 Lambert 等价值**——存量 `.shader` 不写 location 3 就保持全粗糙非金属，因此加通道不强制改造旧材质。
4. `RenderingSystem`：`gbufferMaterialFor` / set1 binding14 / `LightingPass.addTextureRead`（缺这条声明则 GBuffer 写与 Lighting 读之间没有布局转换，采到脏值）。
5. `Standard.shader`（原 `Lit.shader`，2026-11-26 改名）顶层 `LightingModel PBR`，Forward fs 调 `javelinLightingPbr(..., vec2(material._Roughness, material._Metallic))`，GBuffer fs 写 location 3；`lighting.frag` 改调 `javelinLightingPbr(..., javelinRoughMetal())`（全屏路径从 binding14 读）。

亮度口径：`F0 = mix(0.04, albedo, metal)`、`diffuse = albedo*(1-metal)`、漫反射**不除 π**（沿用既有亮度，避免全场景变暗重调光）；环境项只乘漫反射份额，**环境镜面（IBL）明确留给 REND-16**。

校验：`:javelin-editor:compileJava` BUILD SUCCESSFUL；三条 shader 路径（Forward / GBuffer / 全屏 `lighting.frag`）按注入块手工拼装后均过 `glslc --target-env=vulkan1.3`。遗留：`ins.enum.lightingmodel.pbr` 的 i18n label 未核实、全部 `.shader` 需重导。

---

## 3.12 B9 落码点（2026-11-26）

**光追的第三类消费：镜面反射。** 三类消费的分界由此齐了——遮挡（1 射线问 0/1，无状态）、漫反射 GI（N 射线半球，有方差）、镜面反射（**1 射线，方向唯一确定**）。反射方向 = `reflect(worldPos - camPos, N)`，不依赖任何随机数：粗糙度只决定 lobe 宽度，光滑面（rough ≤ 0.1）抖动角近乎为零 = 精确镜面。

**资产与 Pass**：`core/rt/rtreflect.compute`（460 + `GL_EXT_ray_query`）半分辨率逐像素 1 spp，`perturbAround(R, N, rough, jitter)` 把方向绕切线偏 `rough²·π/2·lobe`，抖动相位由 `radicalInverse(px + frame)` 驱动（跨帧不重复，B8 累积时样本均匀）。命中点投影回屏幕取 `gbufferAlbedo`、`gbufferNormal`，用「环境 + 主光 ndl」近似其辐射亮度；打空 = 通向天空，直接输出环境辐照度（无环境源时即黑，与「无天光」口径一致）。`RTReflectPass`（core，set0 六绑定：AS / 反射图 storage / 世界位置 / 法线 / **材质** / 反照率），**push 144B**——比 B5/B7 多的 16B 是相机世界位置，这是我为「反射必须知道眼睛在哪」付的唯一额外成本；Vulkan 保证下限 128B，桌面 GPU 普遍 256B。

**数据面**：`RenderingSystem` 加 `rtreflectFor`（半分辨率 RGBA16F STORAGE|SAMPLED）/ `rtreflectSampler` / `setRtReflectMode` / `cameraPositionFor`（按视口名查注册相机）/ `RT_REFLECT_MAX_DISTANCE=100`（远大于 GI 的 20——镜子里能看到走廊尽头）/ `RT_REFLECT_DEFAULT_PERCENT=100`；`LIGHTING_BLOCK_BYTES` 704→**720**（`reflectParams`@704，步长 768 余量 48B）；set1 加 **binding15**；resize 与 close 两处 dispose；`LightingPass` / `OpaqueForwardPass` / `TransparentForwardPass` 各加 `addTextureRead(rtreflectFor)`。

**着色端消费**（`PBR_LIGHTING_BLOCKS`，Lambert 不受影响）：
```glsl
if (lighting.reflectParams.x > 0.001) {
    float NoV = max(dot(normalize(viewNormal), V), 1e-4);
    vec3 fEnv = f0 + (max(vec3(1.0 - rough), f0) - f0) * pow(1.0 - NoV, 5.0);
    vec2 reflUV = gl_FragCoord.xy / max(lighting.reflectParams.zw, vec2(1.0));
    vec3 refl = texture(rtReflectMap, reflUV).rgb;
    result += refl * fEnv * (1.0 - 0.5 * rough * rough) * lighting.reflectParams.x;
}
```
`fEnv` 的 `max(1-rough, f0)` 让粗糙面 Fresnel 向 1 收敛（能量摊进宽 lobe），再乘 `(1 - 0.5·rough²)` 压强度——单射线近似下这是唯一能避免「粗糙面出现镜面锐利反射」的压制项。反射是**环境镜面**（间接光部分），与主光 GGX 高光（`javelinPbrDirect`）并存，两者物理上各自成立。

**已知残留**：①命中点只用 ndl 着色、不含二次可见性，暗角落反射偏亮（与 B7 同源取舍）；②屏外命中反照率退 0.5、法线退 `-dir`；③粗糙面单帧有斑点（等 B8 时域）；④半分辨率放大，镜面边缘 1~2px 软化（真要锐利可给全分辨率 + 只对 smooth 材质双分辨率派发，未做）；⑤**SSR 未做**——屏幕内命中目前也是"打 RT 再回屏取色"，本质等价于 RT 版 SSR，但比屏幕空间步进贵、比它稳（能拿到背面与屏外几何的遮挡关系）。

---

## 3.13 B8 落码点（2026-11-27）

**时域累积 + 空间降噪，GI 与反射共用一条链。** DDGI 退役后 RTGI 是引擎唯一实时 GI，噪点没有别的层兜底——B8 优先级因此前置。两类消费的降噪数学同构（颜色图 + 世界位置/法线保边 + 重投影累积），只有输入图不同：共享一支着色器（push mode 分派）与一个执行器实例，省一套 SPIR-V 与管线。

**链形态**（每视口、半分辨率 RGBA16F、五段各占一个 RenderGraph compute Pass——storage 写→采样读的布局转换与屏障由图执行器按声明自动插入）：

```
rtgi/rtreflect.compute ──raw──▶ temporal ──写另一代历史──▶ atrous(step1) ──▶ scratch ──▶ atrous(step2) ──▶ 本帧 GI/反射图（着色端消费点不变）
```

| 落点 | 改动 |
|------|------|
| `core/rt/rtdenoise.compute`（新资产 + `.meta`） | 两模式：**temporal**（重投影找历史 + 离散度判据 + 年龄自适应 α 累积，历史 alpha 通道存累积年龄）与 **atrous**（5×5 十字 12 tap、step 1/2 两迭代，保边权 = 世界位置距离指数衰减 × 法线夹角 pow 8，深度容差按邻域世界位置步进自适应）。push 80B（prevViewProj 64 + mode/valid/step/保留 16） |
| `RTDenoiser`（core，新类，包私有） | 共享执行器：管线/段描述符集缓存（key = 视口+段，纹理身份换代退役重建）、push 打包与 dispatch、`addTemporalPass`/`addAtrousPass`、`ready()`（着色器缺失整链不进图、直出 raw）、`close()` |
| `RTGIPass` / `RTReflectPass` | 构造器加 `RTDenoiser`（同一实例）；GI/反射图只作 raw，temporal 写另一代历史、ATrous 两迭代回写本帧图——**着色端消费点与 set1 绑定零改动**；每 Pass 记 `currentViewName` 供历史代取键 |
| `RenderingSystem` | 新纹理族：`rtDenoiseHistFor(camera, usage, index)`（ping-pong 两代，usage = gi/reflect 子键隔离）、`rtDenoiseHistOtherFor`（拿本代反查另一代，identityHashCode 缓存）、`rtDenoiseScratchFor`（ATrous 中转）；访问器 `rtPrevViewProjFor`（拷贝 `TaaHistory.prevJitteredViewProj`）、`rtCameraMoved`、`rtPrevFrameFor`；resize（`ensureDepthSize`）与 close 两处 dispose |
| `JavelinEngine` | `createRenderPipeline` 建共享 `RTDenoiser` 传入两 Pass（同 `rayQueryEnabled` 守卫内）；shutdown 显式 `close()`（waitIdle 之后） |

**重投影方向与 TAA 相反**：TAA 是「上帧这个屏幕像素的表面在哪」（历史采样用 prevViewProj 反推 uv），B8 是「本帧这个表面在上帧屏幕的哪」（本帧 G-Buffer 世界位置用 prevViewProj 投回上帧 uv 取历史）——两个方向同一份矩阵，互不冲突。历史图半分辨率、重投影 uv 直取（textureSize 即历史图自身尺寸）。

**失效判据**（直出当前帧、年龄归 1）：断帧（`rtPrevFrameFor != frameIndex - 1`）/ 相机移动（`rtCameraMoved`，与 TAA `isTaaCameraMoved` 同源）/ prevViewProj 缺失（首帧）/ 纹理换代（resize 后历史即新空图，天然失效）。着色器内另有**离散度判据**：重投影点采到的世界位置与本像素差 > 0.15（遮挡边界/新入屏表面）即弃历史。

**年龄自适应 α**：`α = clamp(1/(age+1), 1/64, 1)`——新表面收敛快、静帧渐近收敛；下限 1/64 保留当前帧修正份额（光源/材质改动不滞后一整屏）。**不做**色度钳制（TAA 的 3×3 AABB 钳制）：GI 辐照度方差大，钳制会把收敛上限压低，离散度判据已挡住主要误混源。

**RTShadow 不接降噪**：阴影是 1 射线问 0/1 的硬遮挡，无采样方差；其噪点来自半分辨率上采样像素化，属深度感知双边上采样范畴（等实拍分布再定 tap），不在 B8 范围。

**已知残留**：①temporal 段读上帧历史存在跨帧窗口——`maxFramesInFlight=2` 帧闸门下，本帧录制时上帧提交已 await 完成才 acquire（renderFrame 串行结构），读其写出口安全；若将来帧并行化须按 TAA ping-pong 同款奇偶约束复查；②ATrous 对完全屏外的命中（反照率退 0.5 的灰）会糊成一团中性灰斑——反射图里「屏外走廊」的间接色偏灰是 B9 已登记残留，B8 不改；③历史年龄无上限（半分辨率 RGBA16F 的 a 通道 float，静帧会积到很大——α 已被下限钳住，年龄只是分母，无溢出风险）；④失效帧的 raw 直出（无 temporal 无 ATrous）——着色端档位不变，单帧噪点回落到 B7 水平。

---

## 3.14 RTGI 质量补全族（REND-23，2026-11-27 立项）

**B0~B9 是链路闭环，本节是质量闭环。** 用户拍板（2026-11-27）：「把这些都列入计划，后期都是需要的，这样才能算完整的 RTGI」。十个子项按性价比排序，收敛登记于 `Engine_Backlog.md` REND-23；**前置 = P4-06 编译 + 实机验收 + B3 三阻塞修复**——先看 B8 后的真实噪声分布与漏光占比再动工，避免在错误的噪声分布上调参。

| 批 | 内容 | 为什么是它 | 依赖 |
|----|------|-----------|------|
| **A 天光 SH9** | skybox 烘 SH9 系数，miss 射线按方向求值替代常量环境色；同一份系数喂常量档（`AmbientMode.CONSTANT` 升级为「天空盒环境光」） | 现在朝天与朝墙缝的表面拿同一环境色，RTGI 方向感缺一大块；一处接线两处受益 | 与 REND-16 漫反射半同源（SH9 烘焙件共享） |
| **B 命中点二次可见性** | 命中点投屏后采现成 RT 阴影可见性图（set1 binding12），替代纯 ndl 近似。**首步（2026-11-28）改为 B 方案直接补射线**：仅朝阳（ndl>0）命中发一条主光方向 rayQuery，遮挡太阳项归零——不采阴影图（图是按 `rtShadowMode` 门控产出的，GI 不应依赖阴影档位开），成本 ≈ 4spp×(1+朝阳比例) 而非翻倍 | 暗角落漏光是当前最扎眼失真；复用阴影图只多一次纹理采样，不做二次求交 | RT 阴影开启（`rtShadowMode>0`）；阴影关时退回 ndl |
| **C 簇灯进间接光** | 命中点投屏反查该像素 cluster 灯表，按距离衰减近似点光/聚光的反弹贡献 | 点光照亮的墙不向环境反弹——夜晚点灯周围全黑，比漏光更显眼 | cluster 三件套现成；compute 侧要接 cluster 索引池读 |
| **D 屏外命中真材质** | 按 `customIndex` 建 CPU 上传材质参数表（storage buffer：反照率色/法线/emissive），命中查表替代 0.5 灰兜底 | 相机背后的表面全部「灰色反弹」，间接光偏灰；贴图级细节做不到但色相不再失真 | `RayTracingScene.objectAt` 反查面已备 |
| **E 自发光面** | 材质 emissive 属性 + 命中点着色加自发光项 | 灯罩/霓虹不向环境发光，是 GI 最直观的「假」 | 与 D 同批（同一张材质表） |
| **F 深度感知双边上采样** | 半分辨率 GI/反射图按 G-Buffer 世界位置加权升全分辨率，替代 LINEAR | 1~2px 边缘软化是半分辨率的固定税 | 与 REND-22（SSAO 上采样）同 kernel 模式 |
| **G 透明/镂空进 AS** | 镂空：BLAS 构建接 `_AlphaCutoff`（procedural triangle alpha? 或分段裁剪）；透明：命中按 Fresnel 权重衰减 | 玻璃/树叶在 GI 与反射里是实心的 | 镂空与 REND-21-A 阈值同源；透明项做前需先拍板口径 |
| **H 第二 bounce** | 命中点采上一帧 GI 历史图当它的间接入射（历史图 ping-pong 现成），能量钳制防自反馈发散 | 单 bounce 深走廊偏暗；「历史即二弹」是零额外射线的廉价路线 | B8 历史链已备；发散钳制参数要实机调 |
| **I 变差引导降噪** | ATrous 固定两迭代改逐像素方差驱动（迭代数/核宽自适应） | 同等开销把角落噪点压得更干净 | B8 落地后看实机收敛表现再定 |
| **J 蒙皮/动态 BLAS** | 动态几何重建/复用 BLAS，物体运动才进 AS | 动态物体不产生也不正确遮挡 GI | **硬前置 = 引擎动画系统**，最远 |

**分批口径**：A~E = 「demo 变可用」的关键五项，改动小收益大；F~G = 修正系统性近似；H~J = 结构性升级。各批独立立项、独立验收，不动 B8 的降噪链结构（H/I 在其上叠加）。具体批次号与施工拍板随各批设计档立。

## 3.15 B0~B9 首轮实机排障落码点（2026-11-28）

首轮实机（AMD 780M）暴露十一个真 bug，全部修复，逐条沉淀：

| # | 症状 | 根因 | 修法 |
|---|------|------|------|
| 1 | TLAS 实例恒 0、三 RT Pass 从不派发 | Mesh BLAS 三处判据读**上传后已释放的 CPU 副本**（`hasTriangles`/`blas` 守卫判 `indices`、`vertexCount` 读 `positions`） | 判据改 GPU 侧 `indexBuffer`；新增 `uploadedVertexCount` 上传期钉死（MeshUpload 与 bounds 同手法） |
| 2 | VVL VUID-03673 构建输入地址无效 | 顶点/索引缓冲缺 `ACCELERATION_STRUCTURE_BUILD_INPUT` usage | MeshUpload 两处补 usage 位 |
| 3 | 射线全落空、阴影全亮 | TLAS 每帧被多 RT Pass 各重建一次、共享 scratch，构建间屏障缺 BUILD→BUILD | `buildToReadBarrier` 补 `ACCELERATION_STRUCTURE_BUILD` 阶段 + WRITE access |
| 4 | 反射重影、GI 糊 | rtdenoise 半分辨率 `texelFetch` 采全分辨率 G-Buffer（只读左上 1/4） | G-Buffer 一律 `gUV(px)` 归一采样（LINEAR = 2×2 盒式降采样口径） |
| 5 | 封闭空间 GI 漏光（盒内阴影不对、邻壁亮斑） | 命中点太阳项只看 ndl 无可见性 | **REND-23-B 首步提前落（B 方案）**：仅朝阳命中补一条主光可见性射线，遮挡太阳项归零 |
| 6 | 盒内乱反射、物体色斑乱跳 | 屏幕反查只查屏界不查可见性——不可见命中拿到别处像素的材质 | `projectToScreen` 补命中点 vs 该像素 G-Buffer 世界位置比对（最近纹素、0.3 容差），不可见判灰色回退 |
| 7 | 降噪链从未运行（改着色器零变化、开关无差异） | ping-pong 单代静默退化：反查另一半只查不建恒 null + 读代写死 slot 0（**Pitfalls #46**） | 反查惰性补建 + 读代按帧奇偶交替 |
| 8 | Cleaner 日志每帧刷 DESCRIPTOR_SET | temporal 绑定历史逐帧交替身份 ×「身份变即重建」缓存（**Pitfalls #47**） | passKey+纹理身份变体缓存，每 passKey 留 2 变体 |
| 9 | 辅助网格图案烙进 GI、RT 阴影与 CSM 口径分叉 | TLAS 收集不过滤透明/castShadow=false；透明物不写 G-Buffer 反查必失配 | **实例掩码口径**：`MASK_LIGHT_OCCLUDER(0x01)`=不透明且 castShadow（阴影/GI 射线 cullMask）、`MASK_REFLECT_VISIBLE(0x02)`=透明与不投影物（反射/调试 0x03），四支 shader 同步 |
| 10 | RTGI 档环境项「阴影不对」——binary 太阳弹射斑、整体忽亮忽暗 | **GI 天光断供**：桥在 RTGI 档把常量环境项清零（`AmbientMode` 非 CONSTANT = 纯黑旧口径），而 RTGIPass/RTReflectPass 的天光输入恰读这份字段（`ambientRadiance`）——`ambientRad=0` 下命中点/通天射线只剩 `sunVisible` binary 太阳项（DDGI 期 BUG-14「ambient 不能设 0」同族） | **GI 天光独立来源（2026-11-28 拍板方案 B）**：`Environment.giSkySetting`（复用 AmbientSetting、默认中性灰 0.5×1.0、RTGI 档检查器内联展开）→ 桥推 `RenderingSystem.setGiSky` → `giSkyRadiance()`；`ambientRadiance()` 删除防误接回；常量项清零口径不动（消费端 rtgiParams.x=1 整项替换，不参与求和） |
| 11 | GI 太阳弹射与直接光 RT 阴影远处「打架」（遮挡判口径不一致） | `sunVisible` 求交 tMax 借用 `params.y`=GI 采样距离 20m，而 RT 阴影 150m——20m 外遮挡者不挡 GI 太阳项 | `lightDir.w` 预留位启用，Java 传 `RT_SHADOW_MAX_DISTANCE=150`（push 仍 128B，RenderDoc push 导出实测核对） |

**新增排障设施**：`--rtDenoiseBypass` 启动参数（降噪链整段旁路 raw 直出，逐项排查用）；RenderDoc 抓 Java 程序实操沉淀至 `Vulkan_Pitfalls.md` §六-9（jcmd 取命令行 + @argfile 正斜杠 + 重打包后再抓）。

**随验补一笔（掩码批次的次生 bug）**：rtgi.compute 的 `CULL_LIGHT_OCCLUDER` 曾误声明在 `main()` 内而 `sunVisible()` 先行引用 → glslc 编译失败 → 资产 invalid → `ensurePipeline` 每帧重试装载 = AssetLibManager「GUID 缓存命中」DEBUG 刷屏 + RTGI 不出图。修 = 常量提文件域；**纪律：改 shader 必先过 glslc 再交付**（本轮起执行，四支已验）。

**误判一笔**：盒子半埋地面时「影子留在原地不跟随」为坑壁受影的合法现象，非 bug——删物体影子随之消失即证。

**第二轮实机确认（2026-11-28，giSky 修复后）**：`--rtDenoiseBypass` raw 直出下逐项核对——push 常量全对（RenderDoc 导出：ambientRad=0.5×3 / lightDir.w=150 / sunRad=1），「整体很亮」随 giSky 0.3 回落——#10/#11 修复实锤生效。**剩余症状定性为设计缺陷而非 bug**：换视角 uGi 明暗整幅重排（「一会这暗一会那暗」）——屏幕反查（B7 权宜做法）命中点材质取自「本帧屏幕可见像素」，屏外/被挡/背面命中一律退 0.5 灰，**同一表面随相机朝向在真材质与灰兜底间翻转 = GI 视角相关**。降噪链关停期间时域平均缺席放大了观感（binary 太阳项 + 4 spp 斑驳无收敛；另注：`hammersley((i+shift)%spp)` 相位错开只是同组样本重排，跨帧并不换样，静止帧累积同一有偏估计——B8 修复时须一并改 per-frame R2 偏移）。**根治归 REND-23-D 材质代理表**（`customIndex` 反查 + storage buffer 材质表，`RayTracingScene.objectAt` 面已备）；时域平滑归 B8 降噪链修复（`historyValid` 现把整套重投影机制在相机移动帧整个关掉——shader 重投影 + 离散度判据本就为运动帧准备，Java 侧条件应放开）。两项排期为 RTGI「可验收」形态的前置。

---

## 4. 风险与后置

- **R1 780M 不支持**：若日志三项全 false，光追主线就地改为「先补 RHI-14 + L5 后处理」或换硬件，**不硬啃软件回退**（软件 RT 在这个引擎里没有意义）。
- **R2 扩展在列但位为 false**：`supportsRayQuery` 已双判，避免 B1 建设备时才炸。
- **R3 与既有 RHI 抽象的冲突**：AS 是 RHI 里全新的资源类型（既不是 Buffer 也不是 Texture），B1/B2 要新增 `AccelerationStructure` 抽象；抽象怎么切分（AS 是否独立 `GPUResource`、TLAS 更新是否走 `CommandBuffer`）留到 B1 拍板。
- **后置**：~~镜面反射消费（L7 之后）~~ **已于 B9 落码（2026-11-26）**；剩余后置项 = B8 时域降噪（GI 与粗糙面反射的斑点）、SSR 与 RT 的混合（屏幕内省射线、屏幕外走 RT，当前全走 RT）、IBL 环境镜面（REND-16，届时 RT 反射与 cubemap 按 roughness 混合）、光线追踪阴影的降噪（与 REND-21-C2 的抖动步进同源）。

## 5. 自验口径（未编译，交用户）

1. 启动日志出现 `光追能力探针: rayQuery=… accelerationStructure=… bufferDeviceAddress=…`。
2. 三项都是 `true`：B1 可开工；任一项 `false`：按 R1 处理，先把日志贴回来。
3. 现有渲染画面、`error.log` 与 B0 之前**无差异**（探测不改任何状态）。
4. B1 后：启动日志改四态 `光追能力: supported(rayQuery=…) enabled(rayQuery=…) accelExt=… bdaExt=…`；RTX 机器上 `enabled(rayQuery)=true`，780M 上允许 false 且引擎照常跑。
5. B2a 后：控制台出现 `BLAS 构建` 一行（网格、图元数、AS 字节数）；构建失败时抛 `IllegalStateException`/`VulkanException` 并带 Vulkan 结果码，不允许静默。
6. B3 后：按 F9 在「相机主射线 → 主光方向 → 关」三态循环；相机态轮廓与光栅画面贴合（无偏移/自交/漏检），主光态遮挡轮廓与现有 CSM 阴影轮廓重合；设备无 ray query（780M）时写「未就绪」且画面不变。
