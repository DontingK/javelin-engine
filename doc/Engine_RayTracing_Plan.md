# 光追首范围（P4-06）推进计划

> 版本：v0.1（2026-11-25，B0 已落码；B1 起待拍板）
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

本机显卡 = **AMD Radeon 780M（RDNA3 iGPU）**，驱动 32.0.11039.6002。RDNA2 起硬件有 RT 加速器，理论上 ray query 可用，但**Windows 驱动 + iGPU 组合必须实测**——不实测就写 AS 构建，等于赌。

## 2. 批次划分

| 批 | 内容 | 产出 | 状态 |
|----|------|------|------|
| **B0** | 能力探针：`VulkanPhysicalDevice` 枚举设备扩展（懒算一次）、`Device.hasDeviceExtension(String)` / `Device.supportsRayQuery()`（扩展位 + feature 位双判）、启动期一行日志 | 能否开工的判据 | ✅ 2026-11-25 落码（未编译） |
| **B1** | 能力启用：`VkDeviceCreateInfo` 加三扩展 + `VkPhysicalDeviceRayQueryFeaturesKHR` / `VkPhysicalDeviceAccelerationStructureFeaturesKHR` / `VkPhysicalDeviceBufferDeviceAddressFeatures` 链；`BufferUsage` 加 `SHADER_DEVICE_ADDRESS` 与 AS 存储位；缺能力时**构造期显性抛错**（不静默降级） | 设备能创建 AS | 待拍板 |
| **B2** | AS 构建：每 `Mesh` 一条 BLAS（静态、`MeshUpload` 后一次性建）、每帧一条 TLAS（instances = `RenderObject` 世界矩阵 + 实例自定义索引）；AS 缓冲走 device-local，构建用一次性 command buffer + fence | `rayQuery` 可求交 | 待拍板 |
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

## 4. 风险与后置

- **R1 780M 不支持**：若日志三项全 false，光追主线就地改为「先补 RHI-14 + L5 后处理」或换硬件，**不硬啃软件回退**（软件 RT 在这个引擎里没有意义）。
- **R2 扩展在列但位为 false**：`supportsRayQuery` 已双判，避免 B1 建设备时才炸。
- **R3 与既有 RHI 抽象的冲突**：AS 是 RHI 里全新的资源类型（既不是 Buffer 也不是 Texture），B1/B2 要新增 `AccelerationStructure` 抽象；抽象怎么切分（AS 是否独立 `GPUResource`、TLAS 更新是否走 `CommandBuffer`）留到 B1 拍板。
- **后置**：镜面反射消费（L7 之后）、光线追踪阴影的降噪（ray query 每像素 1 采样会有噪点，需要 TAA/时空复用，那时与 REND-21-C2 的抖动步进同源）。

## 5. 自验口径（未编译，交用户）

1. 启动日志出现 `光追能力探针: rayQuery=… accelerationStructure=… bufferDeviceAddress=…`。
2. 三项都是 `true`：B1 可开工；任一项 `false`：按 R1 处理，先把日志贴回来。
3. 现有渲染画面、`error.log` 与 B0 之前**无差异**（探测不改任何状态）。
