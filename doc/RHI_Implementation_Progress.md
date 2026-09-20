# RHI 实现进度记录

> 版本：v0.2
> 日期：2026-09-20
> 对应实现计划：`doc/RHI_Implementation_Plan.md`

---

## 总览

| 阶段 | 状态 | 说明 |
|------|------|------|
| ① 地基（types/desc/异常） | ✅ 完成 | 纯 Java，无 GPU 依赖 |
| ② 入口与设备 | ✅ 完成 | Instance / PhysicalDevice / Device |
| ③ 资源与描述符 + 管线 | ✅ 骨架完成 | 核心类已创建，部分方法待 Stage 4/5 完善 |
| ④ RenderGraph 核心 | ✅ 完成 | 声明/编译/屏障计划/图缓存/执行器，编译链路可无 GPU 单测 |
| ⑤ 呈现与帧执行 | ⬜ 未开始 | |
| ⑥ 验证与引擎接入 | ⬜ 未开始 | |

---

## 阶段 ① 地基（完成）

### 创建文件

**异常（2 个）**
- `exception/RHIException.java` — 根异常
- `exception/VulkanException.java` — 带 VkResult 错误码

**types/ 枚举（27 个）**
- `Format.java` — 像素格式（含 BC 压缩）
- `TextureType.java` / `TextureUsage.java`
- `BufferUsage.java`
- `PrimitiveTopology.java` / `CullMode.java` / `FrontFace.java` / `PolygonMode.java`
- `CompareOp.java` / `StencilOp.java`
- `BlendFactor.java` / `BlendOp.java` / `ColorComponentFlags.java`
- `DescriptorType.java` / `ShaderStage.java` / `PipelineBindPoint.java`
- `DynamicState.java`
- `Filter.java` / `SamplerAddressMode.java` / `SamplerMipmapMode.java` / `SampleCount.java`
- `MemoryUsage.java`
- `AttachmentLoadOp.java` / `AttachmentStoreOp.java` / `TextureLayout.java`
- `IndexType.java` / `PipelineStage.java` / `AccessFlags.java` / `DependencyFlags.java`
- `QueryType.java` / `CommandPoolFlags.java`

**desc/ 描述类（11 个）**
- `BufferDesc.java` / `MemoryPreference.java`
- `TextureDesc.java` / `TextureViewDesc.java` / `SamplerDesc.java`
- `ShaderDesc.java`
- `PipelineLayoutDesc.java` / `DescriptorSetLayoutDesc.java`
- `GraphicsPipelineDesc.java` / `ComputePipelineDesc.java` / `SwapChainDesc.java`

**其他**
- `VertexLayout.java` — 顶点布局（缓存去重、不可变）

### 设计要点
- 所有 desc 为不可变 final 类（禁用 record，遵守 AGENTS.md 红线 8）
- 构造函数带参数校验
- 每个枚举注释中写明对应 Vulkan 常量
- `FrontFace` 注释中标注坐标约定（CW 补偿 Y 翻转）

---

## 阶段 ② 入口与设备（完成）

### 创建文件

**接口**
- `RHI.java` — 入口，含 `RHIInstanceConfig`
- `Instance.java` / `Device.java` / `Queue.java`
- `Surface.java` / `SwapChain.java` / `Semaphore.java` / `Fence.java`
- `GPUResource.java` — 资源基类
- `CommandBuffer.java` — RHICmd
- `SubmitInfo.java` / `FrameArena.java` / `UploadData.java`
- `Texture.java` / `Buffer.java` / `Sampler.java` / `TextureRegion.java`
- `GraphicsPipeline.java` / `ComputePipeline.java` / `PipelineLayout.java` / `PipelineCache.java`
- `ShaderModule.java` / `DescriptorSetLayout.java` / `DescriptorSet.java` / `DescriptorUpdate.java`
- `RHIDevicePrefs.java`

**实现**
- `VulkanInstance.java` — 创建 VkInstance + 校验层 + 扩展
- `VulkanPhysicalDevice.java` — 设备选择评分（独立显卡优先）
- `VulkanDevice.java` — 逻辑设备 + 队列获取
- `VulkanQueue.java` — 队列实现
- `VulkanSurface.java` — Win32 Surface

**render/ 包**
- `Platform.java` — 平台枚举
- `RenderSurface.java` — 呈现目标契约

**data/ 包**
- `RHIViewport.java` / `RHIScissor.java` / `RHILoadOp.java`
- `RHIClearValue.java` / `RHIClearColor.java` / `RHIClearDepthStencil.java`

### 关键设计
- `VulkanInstance`：Debug 模式启用 `VK_LAYER_KHRONOS_validation`，按平台加载 surface 扩展
- `VulkanPhysicalDevice.select()`：枚举设备 → 检查队列族（graphics/present）→ 评分选择最佳
- `VulkanDevice`：创建逻辑设备，获取 graphics/present 队列（compute/transfer 暂复用 graphics 族）

### 已知问题
- `VulkanSurface.physicalDevice()` 返回 null，Stage 5 完善
- `VulkanDevice` 中 compute/transfer 队列暂复用 graphics 队列

---

## 阶段 ③ 资源与描述符 + 管线（骨架完成）

### 创建文件

**生命周期**
- `CleanerRegistry.java` — 延迟销毁队列（Cleaner + 两帧规则）

**内存**
- `VulkanMemory.java` — 内存分配工具（required/preferred 两级选择）

**资源实现**
- `VulkanBuffer.java` — Buffer 实现（创建 + 映射写入）
- `VulkanTexture.java` — Texture 实现（完整 Format 映射，含 BC 压缩）
- `VulkanSampler.java` — Sampler 实现

**管线实现**
- `VulkanShaderModule.java` — SPIR-V magic 校验
- `VulkanDescriptorSetLayout.java` — DescriptorSetLayout 实现
- `VulkanDescriptorSet.java` — DescriptorSet 实现（update 方法）
- `VulkanPipelineLayout.java` — PipelineLayout 实现
- `VulkanGraphicsPipeline.java` — 完整 PSO 创建流程
- `VulkanComputePipeline.java` — ComputePipeline 实现

### 关键设计
- **CleanerRegistry**：`DestroyTask` 只持有 device + handle，不引用 RHIResource 实例（避免自引用陷阱，设计文档 17.6-1）
- **VulkanMemory**：`findMemoryType` 按 required 过滤 + preferred 评分选择最优内存类型
- **VulkanBuffer**：创建 + 内存分配 + 绑定，支持 `update()` 映射写入
- **VulkanGraphicsPipeline**：完整 PSO 创建（着色器 → 顶点输入 → 光栅化 → 深度 → 混合 → 动态状态）

### 已知待完善

| 问题 | 说明 | 计划阶段 |
|------|------|---------|
| `VulkanGraphicsPipeline.createPipelineLayout()` | 简化实现，未使用 `desc.descriptorSetLayouts()` | Stage 4 |
| `VulkanTexture.update()` | 需要 staging buffer + 命令缓冲 | Stage 5 |
| `VulkanDevice.frameArena()` / `pipelineCache()` | 未实现 | Stage 3 后续 |
| `CleanerRegistry` 与帧循环集成 | 需要帧推进时调用 `onFrameEnd()` | Stage 5 |
| `VulkanPipelineLayout` 中 `DescriptorSetLayoutDesc` 转 handle | 需要实际的 DescriptorSetLayout 对象 | Stage 4 后续（描述符落地时） |

---

## 阶段 ④ RenderGraph 核心（完成，2026-09-20）

### 创建文件

**公开 API（对照 26.5 节）**
- `RenderGraphBuilder.java` — 声明 + 编译 + 执行接口（addGraphicsPass / addComputePass / structureHash / compile / execute）
- `PassDef.java` — Pass 声明（读写意图 + 回调；22.3 ResourceUsage 语义）
- `GraphicsPassBuilder.java` / `ComputePassBuilder.java` — Pass 图内句柄
- `CompiledGraph.java` — 编译产物：拓扑序 + 屏障计划 + 结构哈希 + exportDot()/exportJson()（13.3）

**实现**
- `RenderGraphImpl.java` — 声明收集 + 编译（拓扑 / 屏障计划 / structureHash）+ 执行器（24.3）；编译链路纯 Java 无 GPU 调用
- `LayoutTracker.java` — 布局跟踪表（帧内，屏障冗余消除依据）
- `RenderGraphCache.java` — 图缓存（18.2）：哈希命中复用计划 + 每帧 rebind 运行时数据（18.7-3）
- `VulkanBarrierGenerator.java` — RHI 布局/阶段/访问 → Vulkan 常量映射 + 同步参数推导表（录制侧 Stage 5 消费）

**接口扩展**
- `CommandBuffer` — 增加 `pipelineBarrier(texture, oldLayout, newLayout)`（执行器自动插入，上层禁用）与 `bindPipeline(ComputePipeline)`（26.4 遗漏，计算 Pass 必需）
- `RHI.beginRenderGraph()` — 图构建入口

### 关键设计

- **依赖推导**：RAW / WAW / WAR 均建立声明序边；Kahn 拓扑（声明序入队，稳定排序）；循环依赖抛 `RHIException`
- **屏障计划**：每 Pass 每纹理一条（同 Pass 合并、写优先于读）；目标布局语义化（COLOR_ATTACHMENT / DEPTH_STENCIL_ATTACHMENT / SHADER_READ_ONLY）
- **structureHash**：Pass 名 / 类型 / 管线 handle / 附件（纹理 handle + kind + loadOp）/ 读声明；不含清屏值 / 视口 / uniform / 回调（18.2）
- **执行器**：`cmd.begin → 逐 Pass 屏障插入（tracker 比较，冗余不插）→ 绑定管线 / 视口 → 回调录制 → cmd.end`；backbuffer 起始 PRESENT_SRC、图尾自动转回
- **缓存失效**：swapchain recreate 换纹理句柄 → 哈希天然变化 → 自动失效（18.5 / 23.6-3）；`invalidate()` 显式失效

### 与计划文件清单的差异

| 计划 | 实际 | 原因 |
|------|------|------|
| `VulkanRenderGraphImpl` | `RenderGraphImpl` | 编译与执行只依赖 RHI 接口，后端无关，命名去 Vulkan 化 |
| `VulkanQueueScheduler` | 未创建 | 单命令缓冲顺序录制 + 单队列，无可调度内容；留 Stage 5（24.4 提交模型） |

### 已知待完善

| 问题 | 说明 | 计划阶段 |
|------|------|---------|
| `setUniform(String, float[])` | 执行器不解析 name→binding（需 shader 反射）；多实例 uniform 走回调内 bindDescriptorSet（19.4） | Stage 5/6 |
| `pipelineBarrier` 的 vkCmd 录制 | 需 VulkanCommandBuffer 实现消费 `VulkanBarrierGenerator` | Stage 5 |
| RT 内存别名（17.4） | 未实现，所有附件独立分配 | P1 |
| Debug 开关（单 Pass 执行 / 关别名，13.3） | 未实现 | Stage 6 |
| 计算 Pass 写声明 | PassDef 仅读声明（addTextureRead），storage 写未声明 | 待用户拍板是否扩展 26.5 |

---

## 编译状态

```
gradlew :javelin-rhi:compileJava  ✅ 通过（阶段③ + 阶段④均验证）
```

---

## 下一步（阶段 ⑤ 呈现与帧执行）

### 目标
帧循环收口，出第一个画面。

### 计划创建文件
```
VulkanCommandBuffer.java      // 消费 VulkanBarrierGenerator，落地 pipelineBarrier
VulkanSwapChain.java          // acquire / present / recreate（23.3）
FrameState.java               // fence / semaphore / cmd / arena 环形复用（24.1）
```

### 关键挑战
- FrameState 环形（maxFramesInFlight=2~3）+ fence 闸门
- swapchain recreate → 图缓存失效咬合（23.6-3）
- VulkanTexture.update() staging 路径（25 节）
- CleanerRegistry.onFrameEnd() 帧推进集成（17 节两帧规则）

---

## 附录：API 签名确认记录

实现过程中已确认的 LWJGL API 签名（来源：lwjgl-vulkan-3.4.2-sources.jar）：

| API | 签名 |
|-----|------|
| `vkCreateInstance` | `(VkInstanceCreateInfo, VkAllocationCallbacks, PointerBuffer) → int` |
| `vkCreateDevice` | `(VkPhysicalDevice, VkDeviceCreateInfo, VkAllocationCallbacks, PointerBuffer) → int` |
| `vkCreateGraphicsPipelines` | `(VkDevice, long, VkGraphicsPipelineCreateInfo.Buffer, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreateComputePipelines` | `(VkDevice, long, VkComputePipelineCreateInfo.Buffer, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreateBuffer` | `(VkDevice, VkBufferCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreateImage` | `(VkDevice, VkImageCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkAllocateMemory` | `(VkDevice, VkMemoryAllocateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkMapMemory` | `(VkDevice, long, long, long, int, PointerBuffer) → int` |
| `vkCreateShaderModule` | `(VkDevice, VkShaderModuleCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreateDescriptorSetLayout` | `(VkDevice, VkDescriptorSetLayoutCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreatePipelineLayout` | `(VkDevice, VkPipelineLayoutCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkCreateSampler` | `(VkDevice, VkSamplerCreateInfo, VkAllocationCallbacks, LongBuffer) → int` |
| `vkUpdateDescriptorSets` | `(VkDevice, VkWriteDescriptorSet.Buffer, VkCopyDescriptorSet.Buffer) → void` |
| `vkGetPhysicalDeviceSurfaceSupportKHR` | `(VkPhysicalDevice, int, long, IntBuffer) → int` |

### 常见陷阱记录

| 陷阱 | 说明 |
|------|------|
| `Fence.wait()` 覆盖 `Object.wait()` | 改名为 `await()` |
| `VK_API_VERSION_1_3` 在 `VK13` 类 | 需 `import static VK13.VK_API_VERSION_1_3` |
| `VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE` 在 `VK12` 类 | 需 `import static VK12.VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE` |
| `VkDeviceQueueCreateInfo.queueCount()` 是 getter | 由 `pQueuePriorities.remaining()` 自动设置 |
| `vkCreateGraphicsPipelines` 需要 `Buffer` 而非单个对象 | 用 `calloc(1, stack)` 创建单元素 Buffer |
