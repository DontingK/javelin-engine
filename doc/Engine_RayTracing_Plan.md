# 光追首范围（P4-06）推进计划

> 版本：v0.3（2026-11-25，B2a 已落码：AS 设备地址分配 + BLAS 构建 + Mesh 缓存；B2b = TLAS 待做）
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
| 计算 Pass 基建 | 已有（DDGI 三支 compute），B3 可直接照搬 |

本机日常开发机显卡 = **AMD Radeon 780M（RDNA3 iGPU）**，驱动 32.0.11039.6002；但**实际光追验收显卡是 RTX**（2026-11-25 用户明确），780M 不作为光追判据——B0 探针仍保留，用于在任意机器上先看到「这台能不能开工」，而不是等 B2 建 AS 时才炸。

## 2. 批次划分

| 批 | 内容 | 产出 | 状态 |
|----|------|------|------|
| **B0** | 能力探针：`VulkanPhysicalDevice` 枚举设备扩展（懒算一次）、`Device.hasDeviceExtension(String)` / `Device.supportsRayQuery()`（扩展位 + feature 位双判）、启动期一行日志 | 能否开工的判据 | ✅ 2026-11-25 落码（未编译） |
| **B1** | 能力启用：`VkDeviceCreateInfo` 按「驱动真的上报」拼装扩展列表（`VK_KHR_deferred_host_operations` → `VK_KHR_acceleration_structure` → `VK_KHR_ray_query` / `VK_KHR_buffer_device_address`）+ 对应特性链；`BufferUsage` 加 `SHADER_DEVICE_ADDRESS` / AS 存储 / AS 构建输入三档；**能力可选不阻断启动**，但 `Device.rayQueryEnabled()` 报「这一台设备真的能用」，B2 建 AS 前查它、false 就显性抛错 | 设备能创建 AS | ✅ 2026-11-25 落码（未编译） |
| **B2** | AS 构建：每 `Mesh` 一条 BLAS（静态、`MeshUpload` 后一次性建）、每帧一条 TLAS（instances = `RenderObject` 世界矩阵 + 实例自定义索引）；AS 缓冲走 device-local，构建用一次性 command buffer + fence | `rayQuery` 可求交 | ✅ B2a 2026-11-25 落码（未编译）；B2b（TLAS）待做 |
| **B3** | 最小消费：半分辨率 compute Pass 里对每个像素投一条主光方向射线，输出 `命中距离 / miss` 可视化（先验证数值与 shadow map 一致），再决定是「替换 CSM」还是「与 CSM 混合」 | 可验收的求交画面 | 待拍板 |

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

**B2a 未做**：TLAS（instances 缓冲 + 每帧重建/更新 + 自定义索引语义）、BLAS 构建后的 GPU 侧屏障（`BUILD_WRITE → READ`，当前只靠 fence 主机等待，B3 若同队列后续读需补 `vkCmdPipelineBarrier`）、RHI 外零接线（`RenderingSystem` 尚未挂 BLAS/TLAS）。

---

## 4. 风险与后置

- **R1 780M 不支持**：若日志三项全 false，光追主线就地改为「先补 RHI-14 + L5 后处理」或换硬件，**不硬啃软件回退**（软件 RT 在这个引擎里没有意义）。
- **R2 扩展在列但位为 false**：`supportsRayQuery` 已双判，避免 B1 建设备时才炸。
- **R3 与既有 RHI 抽象的冲突**：AS 是 RHI 里全新的资源类型（既不是 Buffer 也不是 Texture），B1/B2 要新增 `AccelerationStructure` 抽象；抽象怎么切分（AS 是否独立 `GPUResource`、TLAS 更新是否走 `CommandBuffer`）留到 B1 拍板。
- **后置**：镜面反射消费（L7 之后）、光线追踪阴影的降噪（ray query 每像素 1 采样会有噪点，需要 TAA/时空复用，那时与 REND-21-C2 的抖动步进同源）。

## 5. 自验口径（未编译，交用户）

1. 启动日志出现 `光追能力探针: rayQuery=… accelerationStructure=… bufferDeviceAddress=…`。
2. 三项都是 `true`：B1 可开工；任一项 `false`：按 R1 处理，先把日志贴回来。
3. 现有渲染画面、`error.log` 与 B0 之前**无差异**（探测不改任何状态）。
4. B1 后：启动日志改四态 `光追能力: supported(rayQuery=…) enabled(rayQuery=…) accelExt=… bdaExt=…`；RTX 机器上 `enabled(rayQuery)=true`，780M 上允许 false 且引擎照常跑。
5. B2a 后：控制台出现 `BLAS 构建` 一行（网格、图元数、AS 字节数）；构建失败时抛 `IllegalStateException`/`VulkanException` 并带 Vulkan 结果码，不允许静默。
