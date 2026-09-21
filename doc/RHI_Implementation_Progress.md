# RHI 实现进度记录

> 版本：v0.4
> 日期：2026-09-21
> 对应实现计划：`doc/RHI_Implementation_Plan.md`

---

## 总览

| 阶段 | 状态 | 说明 |
|------|------|------|
| ① 地基（types/desc/异常） | ✅ 完成 | 纯 Java，无 GPU 依赖 |
| ② 入口与设备 | ✅ 完成 | Instance / PhysicalDevice / Device |
| ③ 资源与描述符 + 管线 | ✅ 骨架完成 | 核心类已创建，部分方法待 Stage 4/5 完善 |
| ④ RenderGraph 核心 | ✅ 完成 | 声明/编译/屏障计划/图缓存/执行器，编译链路可无 GPU 单测 |
| ⑤ 呈现与帧执行 | ✅ 骨架完成 | VulkanCommandBuffer/SwapChain/Fence/Semaphore/FrameArena 已创建，帧循环待 Stage 6 收口 |
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
| ~~计算 Pass 写声明~~ | ✅ 已解决：26.5 扩展新增 addTextureStorageRead / addTextureStorageWrite（见下文「26.5 扩展」） | — |

---

## 编译状态

```
gradlew :javelin-rhi:compileJava  ✅ 通过（阶段③ + 阶段④均验证）
```

---

## 阶段 ⑤ 呈现与帧执行（骨架完成，2026-09-20）

### 创建文件

**同步原语**
- `VulkanFence.java` — Fence 实现（await/reset，可创建为已信号态）
- `VulkanSemaphore.java` — Semaphore 实现

**命令缓冲**
- `VulkanCommandBuffer.java` — 完整实现（begin/end、dynamic rendering beginRenderPass/endRenderPass、bindPipeline、pipelineBarrier、bindDescriptorSet、draw/drawIndexed/dispatch、setViewport/setScissor、copyBuffer/copyBufferToTexture）

**呈现**
- `VulkanSwapChain.java` — SwapChain 实现（acquireNextImage/present/recreate，内部类 SwapChainTexture 包装 backbuffer）
- `VulkanSurface.java` — 修复 capabilities()（原 physicalDevice() 返回 null），增加 createSwapChain(device, desc) 包私有方法

**帧内存**
- `VulkanFrameArena.java` — FrameArena 实现（单帧 HOST_VISIBLE 缓冲，allocate/writeAligned/reset）

### 修改文件

| 文件 | 修改内容 |
|------|---------|
| `CommandBuffer.java` | 增加 beginRenderPass/endRenderPass（dynamic rendering）、copyBuffer、copyBufferToTexture |
| `VulkanDevice.java` | 构造函数 surface 感知（传 surfaceHandle）、创建命令池、实现 allocateCommandBuffer/frameArena/pipelineCache、增加 createFence/createSemaphore 工厂、uploadTexture staging 路径 |
| `VulkanQueue.java` | 实现 submit（VkSubmitInfo 组装 + vkQueueSubmit） |
| `VulkanGraphicsPipeline.java` | 保存 pipelineLayoutHandle，暴露 layoutHandle() |
| `VulkanComputePipeline.java` | 同上 |
| `VulkanTexture.java` | 增加 ensureView()（创建默认 VkImageView）、aspectMaskOf() |
| `CleanerRegistry.java` | ResourceType 增加 SEMAPHORE/FENCE/COMMAND_POOL |
| `RenderGraphImpl.java` | 执行器包裹 renderPass 生命周期（图形 Pass 自动 beginRenderPass/endRenderPass） |

### 关键设计

- **dynamic rendering**：Vulkan 1.3 核心，无需 VkRenderPass/VkFramebuffer；附件信息从 CompiledGraph.Attachment 直接转 VkRenderingAttachmentInfo
- **renderPass 包裹**：执行器在图形 Pass 前根据附件声明自动 beginRenderPass（含 loadOp/clearValue），回调后 endRenderPass；计算 Pass 不包裹
- **staging 上传**：createTexture(data) → 创建 staging buffer → 录制转换 UNDEFINED→TRANSFER_DST + copyBufferToTexture → fence 等待
- **管线布局跟踪**：bindPipeline 时缓存 layoutHandle，bindDescriptorSet 消费（解决描述符绑定需要 layout 的问题）

### 已知待完善

| 问题 | 说明 | 计划阶段 |
|------|------|---------|
| FrameState 环形复用 | 当前每帧新建 fence/semaphore/cmd，未实现 2~3 帧环形 + fence 闸门 | Stage 6 |
| CleanerRegistry.onFrameEnd() 集成 | 帧循环未接入，延迟销毁队列未推进 | Stage 6 |
| VulkanSurface.createSwapChain() 公开方法 | 抛异常，需通过 Device 内部调用；上层 API 待 Stage 6 定夺 | Stage 6 |
| VulkanFrameArena 多帧环形 | 当前单帧缓冲，未实现 3 帧版本化 | Stage 6 |
| VulkanTexture.update() 运行期更新 | 仍抛异常（staging 池未实现） | Stage 6/P1 |
| VulkanBuffer.update() DEVICE_LOCAL | 直接 map 会失败，需 staging 路径 | Stage 6 |
| pipelineBarrier computePass 参数 | CommandBuffer 签名无此参数，syncFor 只能传 false | Stage 6 |
| GENERAL 同步参数精化 | Storage 读写的屏障目标布局为 GENERAL，syncFor 现走 coarse() 粗粒度兜底（正确性优先）；精确参数（SHADER_READ/SHADER_WRITE + 目标阶段）依赖 CommandBuffer.pipelineBarrier 签名扩展（write / computePass 标志），与上行一并留 Stage 6 | Stage 6 |
| RenderGraph 执行器提交/present | 当前只录制，提交和 present 由上层帧循环负责 | Stage 6 |

---

## 26.5 扩展（2026-09-21）

背景：阶段④遗留问题「PassDef 仅读声明，storage 写未声明」。用户拍板扩展 26.5 节 API，
形态为**显式 Storage 命名**（而非 ReadUsage 参数化）。

### API 变化（设计文档 26.5 节同步更新）

`PassDef` 新增两个声明方法：

| 方法 | 语义 | 屏障目标布局 |
|------|------|-------------|
| `addTextureStorageRead(Texture)` | 读：Storage（storage image 读） | GENERAL |
| `addTextureStorageWrite(Texture)` | 写：Storage（storage image 写） | GENERAL |

`addTextureRead` 语义收窄为采样读（Sampled → SHADER_READ_ONLY），与 22.3 节的
语义化用途（Attachment.Color / Depth / Sampled / Storage）一一对应。

### 编译链路扩展（RenderGraphImpl）

- **依赖推导**：storageWrite 按写参与 RAW / WAW / WAR 边推导；storageRead 按读参与（与 sampled 读同级）
- **屏障计划**：storageWrite → GENERAL 写屏障；storageRead → GENERAL 读屏障；
  合并规则统一为 `mergeBarrier`：写优先于读；同为写保持先声明者（附件 > Storage 写）；
  同为读时 GENERAL 优先于 SHADER_READ_ONLY（GENERAL 可同时满足两种着色器读）
- **structureHash**：storageRead / storageWrite 各自带独立标记混入哈希（0x5352 / 0x5357），
  与 sampled 读标记（0x52AD）区分——声明集合变化必然导致图缓存失效
- **CompiledGraph.Pass**：新增 `storageReads()` / `storageWrites()` 结构列表（rebind 按结构字段从缓存取），
  JSON 导出同步增加两个数组

### 留待阶段⑥

GENERAL 目标的精确同步参数（syncFor 细化）与 computePass 目标阶段，均依赖
`CommandBuffer.pipelineBarrier` 签名扩展，已记录于阶段⑤已知待完善表。

---

## 下一步（阶段 ⑥ 验证与引擎接入）

### 目标
帧循环收口，出第一个画面。

### 关键挑战
- FrameState 环形（maxFramesInFlight=2~3）+ fence 闸门
- swapchain recreate → 图缓存失效咬合（23.6-3）
- CleanerRegistry.onFrameEnd() 帧推进集成（17 节两帧规则）
- 首次引擎端对端验证（clear color 三角形）

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
| `vkBeginCommandBuffer` / `vkEndCommandBuffer` / 所有 `vkCmd*` | 第一个参数是 `VkCommandBuffer` 对象，不是 `long`；通过 `new VkCommandBuffer(address, device)` 构造 |
| `VkSubmitInfo.pCommandBuffers` | 参数是 `PointerBuffer` 不是 `LongBuffer`；用 `stack.mallocPointer(1)` + `.put(0, address)` |
| `VkSubmitInfo.signalSemaphoreCount(int)` | setter 不存在，由 `pSignalSemaphores.remaining()` 自动推断 |
| `VkPresentInfoKHR.waitSemaphoreCount(int)` | setter 不存在，由 `pWaitSemaphores.remaining()` 自动推断 |
| `VkRenderingInfo.colorAttachmentCount(int)` | setter 不存在，由 `pColorAttachments.remaining()` 自动推断 |
| `VK_INDEX_TYPE_UINT8_EXT` | 在 `VK14` 类（`VK14.VK_INDEX_TYPE_UINT8`），不在 `VK10` |
| `vkEnumeratePhysicalDevices` | 三参数：`(VkInstance, IntBuffer, PointerBuffer)`，无单参数版本 |
| `vkCmdSetViewport` / `vkCmdSetScissor` | 需要 `VkViewport.Buffer` / `VkRect2D.Buffer`；单元素用 `nvkCmdSetViewport(cmd, 0, 1, vp.address())` |
