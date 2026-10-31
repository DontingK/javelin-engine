# RHI 实现进度记录

> 版本：v0.6
> 日期：2026-09-23
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
| ⑥ 验证与引擎接入 | ✅ 完成 | 帧循环收口 → 清屏/三角形/纹理/UBO1+push 逐级验收 → **引擎接入 MultiCameraDemo 验收通过（2026-09-25），阶段⑥关账** |

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

~~GENERAL 目标的精确同步参数（syncFor 细化）与 computePass 目标阶段~~
✅ 已完成于阶段⑥第一步（pipelineBarrier 扩展 write / computePass 参数 + syncFor GENERAL 分支，见下文）。

---

## 阶段 ⑥ 第一步：帧循环收口 + 清屏（2026-09-21 完成，2026-09-22 端到端验证跑通）

### 用户决策（2026-09-21）
- 帧循环放 **core 侧**（javelin-core `cn.donting.javelin.core.render`），通过扩展 Device 公开接口驱动——不建 RHI 内 FrameScheduler
- swapchain recreate 触发在帧循环类**内部**（isResized 轮询 + acquire<0 双路径，自包含）
- 本次验证范围 = **清屏**，三角形留到下一步

### RHI 侧改动（javelin-rhi）
- **Device 接口扩展**：`createFence()` / `createFence(boolean signaled)` / `createSemaphore()` /
  `createSwapChain(SwapChainDesc)`。VkSurfaceKHR 改由 createDevice 创建并由设备持有
  （修正原先把 `RenderSurface.nativeHandle()`（HWND）当 VkSurfaceKHR 传给 present 族检查的潜在 bug）；
  上层不再经 Instance.createSurface 重复创建
- **CleanerRegistry 修复（阶段⑥激活暴露的潜在 bug，不修会导致帧推进后资源被误销毁）**：
  - 原 `cleaner.register(new Object(), ...)` 监护的是临时对象 → 任务要么立即触发要么永不触发；
    改为以资源实例自身为 referent
  - 原各资源 register 传 device 为 referent（全设备共享 referent + Cleaner 强持有 → 永不触发）
  - 显式 dispose 与 Cleaner 兜底会对同一句柄二次 vkDestroy → 新增 `markDisposed(handle)` 登记、
    DestroyTask 触发时跳过、executeDestroy 后清理集合
- **图缓存执行**：`RenderGraphBuilder.execute(cmd, backbuffer, CompiledGraph)` 三参重载
  （缓存命中路径跳过内部编译）；recreate → `RenderGraphCache.invalidate()` 咬合生效（23.6-3）
- **SwapChainTexture 附件 view bug**：beginRenderPass 的 fillAttachmentInfo 对交换链图像也走
  ensureView()（原 else 分支把 VkImage 当 VkImageView 传入 VkRenderingAttachmentInfo）
- **GENERAL 同步精化**：`CommandBuffer.pipelineBarrier` 增 5 参重载（write / computePass），
  执行器按 Barrier.isWrite() + Pass.compute() 传入；`syncFor` 增 GENERAL 目标分支（toGeneral），
  兑现 26.5 扩展的遗留承诺
- `CleanerRegistry` 类与 `onFrameEnd` 公开化（core 帧推进调用）

### core 侧新增（javelin-core）
- `render/FrameState.java` —— fence（signaled）+ imageAvailable + renderFinished + cmd + arena（设计 24.1）
- `render/FrameLoop.java` —— 帧迭代（24.1 顺序）+ recreate 双路径 + CleanerRegistry 帧推进 + 最小化跳过；
  单线程退化模式（27.1）
  - API：`renderFrame(Function<Texture, RenderGraphBuilder> graphFactory)`——acquire 先于图声明，
    backbuffer 以入参提供给声明回调（对计划 renderFrame(RenderGraphBuilder) 的必要修正）
- `test/java/com/javelin/core/ClearColorTest.java` —— 端到端清屏验证，
  运行：`gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.ClearColorTest`

### 已知问题（本步接受，记入待办）
| 问题 | 影响 | 方向 |
|------|------|------|
| backbuffer 句柄参与 structureHash | image(idx) 帧间交替 → 单槽图缓存每帧全量重编译（图小，功能正确） | 终点 backbuffer 改以"槽位身份"参与哈希 |
| FrameArena 1MB/块 | 设计默认 64MB；环形 = 每 FrameState 一块（等价 3 块轮换语义） | 容量配置化 |
| `VulkanQueue.submit` 等待阶段硬编码 COLOR_ATTACHMENT_OUTPUT | 纯图形首画面够用 | compute 提交时按用途分派等待阶段 |

### 验证排障记录（2026-09-22，清屏首跑三连障）
1. **VkResult=-6（VK_ERROR_LAYER_NOT_PRESENT）**：debug 模式请求 `VK_LAYER_KHRONOS_validation`，
   本机未装 Vulkan SDK。修复：`VulkanInstance` 创建前先 `vkEnumerateInstanceLayerProperties` 检查，
   校验层缺失时 warn 降级继续（不再硬失败）；装了 SDK 的机器自动启用。
2. **NPE @ VkWin32SurfaceCreateInfoKHR.validate**：LWJGL 3.4 生成代码对 `hinstance`/`hwnd` 均做
   非空检查（Vulkan 规范里 hinstance 本可选）。修复：补
   `hinstance(WinBase.GetModuleHandle(null, (CharSequence) null))`（lwjgl core WinBase 绑定）。
3. **VkResult=-1000000000（VK_ERROR_SURFACE_LOST_KHR）@ surface capabilities**：
   `glfwCreateWindow` 返回的是 GLFW 内部结构体指针而非 HWND；阶段⑥改 RHI 手写
   `vkCreateWin32SurfaceKHR` 后 `RenderSurface.nativeHandle()` 语义错位（原设计写"GLFW window 指针"）。
   该驱动对野指针 HWND 的创建与 formats/present 查询不校验（全"成功"），仅 capabilities
   真查窗口状态时拒绝。修复：`GLFWRenderSurface.nativeHandle()` 在 WINDOWS 下经
   `GLFWNativeWin32.glfwGetWin32Window` 翻译为真 HWND（core 侧翻译，RHI 不依赖 GLFW）；
   `RenderSurface.nativeHandle()` 注释改为"平台原生窗口句柄，勿直接传 GLFW 句柄"。

### 阶段⑥ 第二步：三角形（2026-09-23 验收通过）

新增 `javelin-core/src/test/java/com/javelin/core/TriangleTest.java`：

- **着色器**：GLSL 源码内嵌测试类，运行时经 javelin-tool `ShaderCompiler`（shaderc，此前无人调用）
  编译为 SPIR-V `byte[]` → `ShaderDesc` → `Device.createGraphicsPipeline`。用户拍板：不走预编译 .spv 资产。
- **PSO**：`VertexLayout` = pos(R32G32B32_SFLOAT@0) + color(R32G32B32_SFLOAT@12)，stride 24；
  `colorAttachmentFormats` 取 `swapChain().image(0).desc().format()`；`dynamicStates = {VIEWPORT, SCISSOR}`
  （仅这两项，`toVkDynamicState` 不支持 1.3 新增项）。
- **顶点缓冲**：`BufferUsage.VERTEX` + `MemoryPreference.hostVisibleCoherent()` + `UploadData` 一次填充
  （Buffer 无 staging 路径，hostVisible 是唯一可行路线；staging 留待后续）。
- **Y 翻转**：用户拍板最终由**投影矩阵**翻转（接入相机时落地）；测试暂用负高度视口 `setViewport(0, h, w, -h, 0, 1)`，
  二者对绕序影响等价。

**绕序实测结论（2026-09-23，判决性实验）**：CCW 资产（Y 向上）**不翻转**时在 Vulkan framebuffer（y 向下）
即为 CW 绕序（frontFace=CW 可见）；**翻转后变为 CCW 绕序，须配 frontFace=CCW**。
设计文档 166-168 行原配对"翻转 + frontFace=CW"**有误**，已修正（RHI_Design_Core.md 推荐 A 改为 CCW，
RHI_Design.md 130 行"视口翻转由 RHI 封装"改为"投影矩阵翻转、视口透传"）。

**排障记录（首跑不可见，隔离实验四步定位）**：
1. `cull=none` 仍不可见 → 排除剔除；
2. `vb=shader`（顶点写死 VS）+ 正高度视口可见 → 锁定顶点缓冲路径；
3. 负高度视口 + `vb=shader` 可见 → 排除视口翻转；
4. 回读显存（临时 dump）：float 全为非规格化乱值，字节整体错位 → **根因：`ByteBuffer.allocateDirect()`
   默认大端，GPU 期望小端**。修复：`.order(ByteOrder.LITTLE_ENDIAN)`。
   附带修复：`VulkanGraphicsPipeline` 顶点输入结构体漏 `sType$Default()`（规范补全，保留）。

**遗留（待办）**：PSO 创建缺 `VkPipelineRenderingCreateInfo`（pNext 未挂 colorAttachmentFormats，
当前靠驱动宽容工作）——规范违反，待修。

### 阶段⑥ 第三步：纹理采样 + 描述符落地（2026-09-24 验收通过）

新增 `javelin-core/src/test/java/com/javelin/core/TextureTest.java`：`resources/texture.jpeg`
经 ImageIO 解码（JDK 内置，无新依赖）→ 紧密打包 RGBA → `R8G8B8A8_UNORM` 纹理（staging 上传）
→ set0/binding0 组合采样器描述符 → 中央四边形（CCW，pos+uv，stride 16）采样显示。
图片路径：`-Dtexture.path` 优先，否则从工作目录逐级向上找 `resources/texture.jpeg`。

RHI 侧改动（描述符链路此前是空壳：管线布局恒为空、无描述符池、update 视图错误）：

| 文件 | 改动 |
|------|------|
| `Device.java` | 新增 `createDescriptorSet(DescriptorSetLayout)`（15.5 示例签名落地，26.1 已同步） |
| `VulkanDevice.java` | 设备级单描述符池（2048 sets、全类型预算、FREE 标志，随设备销毁）；实现 `createDescriptorSet` |
| `VulkanGraphicsPipeline.java` | `createPipelineLayout` 按 `desc.descriptorSetLayouts()` 真实创建 VkDescriptorSetLayout 并挂入（管线持有、随管线销毁）；`pushConstantSize > 0` 时挂 ALL_GRAPHICS 范围；dispose 补毁 VkPipelineLayout（原泄漏）与 set 布局 |
| `VulkanDescriptorSet.java` | update 的 SAMPLED/STORAGE_TEXTURE 改用 `VulkanTexture.ensureView()`（原误把 VkImage 句柄当视图）；STORAGE_TEXTURE 布局改 GENERAL；**补 `descriptorCount(1)`**（原 calloc 零初始化=0，vkUpdateDescriptorSets 按 0 条写入=静默不写，2026-09-24 黑屏根因） |
| `CleanerRegistry.java` | ResourceType 增加 DESCRIPTOR_POOL |

兼容性说明：管线内部 set 布局与上层 `createDescriptorSetLayout` 创建的布局来自相同
`DescriptorSetLayoutDesc`，「定义一致」（identically defined），可安全绑定（15.7-2）。

已知限制（本步接受）：
- 描述符池为设备级单池，未做 15.8 的按「布局 × 帧数」环形复用（留待材质/多 Pass 阶段）
- `VkPipelineRenderingCreateInfo` 仍未挂（沿用三角形遗留）
- 上传后纹理实际停在 TRANSFER_DST，而 LayoutTracker 每帧以 UNDEFINED 为初值——屏障
  oldLayout=UNDEFINED 规范允许（内容不保留语义），验收实测本机驱动正常显示，内容未丢

### 阶段⑥ 第四步：push constants + UBO1 帧环形（2026-09-24 验收通过）

用户拍板 uniform 通道划分（设计文档 15.3 已同步）：**push** = 每物件/每帧高频小量
（model/tpf）；**set0 UBO1** = 全帧共享、所有材质通用的引擎内置属性（相机/时间/灯光）；
**set2 UBO2** = 各材质自有参数。动态 UBO 偏移降级为备选（骨骼矩阵等大数据用）。

RHI 侧改动：
- `CommandBuffer` 新增 `pushConstants(int offset, ByteBuffer data)`（26.4 已同步）：
  `vkCmdPushConstants`，stageFlags=ALL_GRAPHICS（与管线布局声明一致），
  管线布局句柄复用 bindPipeline 缓存；pushConstantSize 的管线布局接线是第三步顺带完成的

新增 `PushUniformTest`（`gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.PushUniformTest`）：

- **UBO1 帧环形**：单块 hostVisible 缓冲 2 槽 × 256B（= maxFramesInFlight），槽号 =
  `frameCounter % 2`（与 FrameState 环形同奇偶，槽位复用前有 fence 闸门），set0 声明
  DYNAMIC_UNIFORM_BUFFER、绑定用动态偏移切槽——2~3 帧 in-flight 下不撕裂。
  **动态 UBO 偏移路径首个消费者，未实测**
- **push**：mat4 model + float tpf（std140 打包，声明范围 80B），每物件一条命令
- **首次引入投影矩阵**：Y 翻转入投影（perspective m[5] 取负）、视口恢复正高度
  （兑现"翻转放投影矩阵"拍板），frontFace=CCW 结论不变；两个绕 Y 轴旋转的贴图四边形
- set 编号：Vulkan 管线布局不允许断档，push 替代 set1 后材质贴图挂 **set1**，
  15.3 的 set2=材质是否重排待材质阶段拍板

已知限制：UBO1 环形为测试内手写（正式版应进 FrameLoop/FrameState，17.3 待做）。

### 阶段⑥ 遗留修复：PSO 挂 VkPipelineRenderingCreateInfo（2026-09-24 修复，2026-09-25 验收确认）

三角形阶段记录的规范违反修复：`renderPass(VK_NULL_HANDLE)` 走 dynamic rendering 时，
pNext 必须挂 `VkPipelineRenderingCreateInfo`。修复从 `GraphicsPipelineDesc` 的
`colorAttachmentFormats` / `depthAttachmentFormat` 构建该结构体（此前这两个字段被完全忽略）：

- `pColorAttachmentFormats` 经 `VulkanTexture.toVkFormat` 转换填入
- 深度模板混合格式（D24S8 / D32S8 / S8）把同一格式同时填进 `stencilAttachmentFormat`，
  纯深度格式则 UNDEFINED
- `pipelineInfo.pNext(renderingInfo)` 用 LWJGL 类型化重载（保留既有 pNext 链）；
  签名经 lwjgl-vulkan 3.4.2 源码核实（pNext 重载 + sType$Default 均存在）

影响面：所有 PSO 创建（TextureTest/PushUniformTest 路径都会走到），预期行为不变
（此前靠驱动宽容），校验层环境下不再报 VUID。

### 待办（阶段⑥后续）
- ~~JavelinEngine.mainLoop 接线 FrameLoop、RenderingSystem 接入~~ ✅ **验收通过（2026-09-25），RHI 阶段⑥正式关账**：
  `Engine_Rendering_Design.md`/`Engine_RenderPipeline_Design.md` v0.1 先行，RenderingSystem
  （UBO1 帧环形收编 + RenderList 排序，视锥剔除/AABB 属优化点后置）+ v0.1 Forward 管线（双 Pass）+
  MultiCameraDemo（左右分屏双相机 + 3 旋转四边形含 1 透明）用户运行验收无误；
  同轮顺带复跑确认了 PSO 挂 VkPipelineRenderingCreateInfo 修复（87080e9）。
  RHI 遗留未清项转入后续计划：Buffer staging 上传、描述符池环形复用（15.8）、
  backbuffer 槽位身份参与 structureHash、UBO1 环形正式化（已随引擎侧收编落地）等，见引擎实现计划与 RHI 文档待办
- RHI 内部封装视口 Y 翻转（吸收测试里的负高度手法后，上层不再处理）
- Buffer staging 上传（deviceLocal 顶点/索引缓冲的前置）
- 描述符池按「布局 × 帧数」环形复用（15.8 正式方案，替代设备级单池）

### 阶段⑥ 前置（已完成，2026-09-21）

core 侧窗口接口：`cn.donting.javelin.core.window` 包——`Window` / `GLFWWindow` /
`GLFWRenderSurface`（适配器，包私有）。Window 为 `JavelinEngine` 构造注入属性
（用户拍板：不是子系统），Window 与 RenderSurface 分离设计，
详见 `doc/Engine_Window_Design.md`（用户已拍板：分离 + 最小呈现集）。

---

## 三期批次①：SSBO + compute 全链（2026-10-31 落地，待实机验收）

**背景**：Engine_Lighting_Design §9 批次①（RHI 底座前置）。实勘校正设计稿认知：SSBO 描述符链
（DescriptorType.STORAGE_BUFFER / DescriptorUpdate.storageBuffer / VulkanDescriptorSet 布局映射）、
`dispatch` / 双 bindPoint `bindPipeline`、compute Pass 进图/拓扑/执行骨架、RenderStats 计数——
RHI 实装期已有，**非本批工作**。真缺口五项 + 资产面/通道面：

**RHI 改动**：
- `VulkanComputePipeline.createPipelineLayout` 原空壳（只 sType 即创建，无 pSetLayouts/pPushConstantRanges
  ——compute 管线绑不了任何描述符集也用不了 push constant）→ 照 VulkanGraphicsPipeline:246-281 补齐
  （ownedSetLayouts 持有 + dispose 成对），stageFlags=COMPUTE；
- `VulkanCommandBuffer.pushConstants` stageFlags 原写死 ALL_GRAPHICS（compute 管线布局声明 COMPUTE 位，
  不匹配即 validation 报错/静默失效）→ 按当前 bindPoint 分流；
- **Buffer 进图同步**（「能跑」与「正确」的分界线）：`PassDef.addBufferRead/addBufferWrite` 声明面 +
  `RenderGraphImpl` 依赖推导纳入 buffer（与纹理共用 writers/readers 表，**句柄取反作命名空间**——
  VkImage/VkBuffer 句柄同为 long，规范不保证跨类型不撞键）+ structureHash（0x4252/0x4257 标记）+
  `CompiledGraph.BufferBarrier` 屏障计划（编译期按拓扑序跟踪最近使用，仅非首用出条目——首帧首用无
  帧内先行者，帧外同步归上传路径）+ 执行器 `bufferLastUse` 跟踪 + `CommandBuffer.bufferBarrier`
  （VkBufferMemoryBarrier，VK_WHOLE_SIZE，源/目标域按两端 Pass 类型现推：compute=COMPUTE_SHADER、
  图形=FRAGMENT_SHADER，访问位 SHADER_READ/WRITE）；
- `DescriptorUpdate.storageBuffer` 原 range=0 直传 VkDescriptorBufferInfo（VUID 要求 >0 或 VK_WHOLE_SIZE，
  0 非法）→ 整段绑定显式 VK_WHOLE_SIZE + `storageBuffer(binding, buffer, offset, range)` 重载；
- 删死代码：`VulkanPipelineLayout` + `PipelineLayout` 接口 + `desc/PipelineLayoutDesc` +
  `Device.createPipelineLayout`（setLayouts 循环空转零消费方；管线布局统一由两 Pipeline 类内建装配）。

**资产面**（`.compute` 独立资产类型，2026-10-31 用户拍板——Unity 口径扩展名区分；内嵌 spv 路线判废）：
core `ComputeShader` 资产类（name + spirv，isValid stub 判据对齐 Shader；红线 9 非 final）；
editor `ComputeShaderImporter`（shaderc COMPUTE 编译，**无注入**——compute 不声明 cam/pc/MaterialBlock，
手写全部 layout）+ `ComputeShaderImportSettings` + `.compute` FileType/图标/compute.svg +
MetaManager/AssetImportPipeline `detectAssetType` 补 .compute→ComputeShader + 双语言包键
`import.compute.importFailed`。一文件一 kernel（GLSL 入口恒 main；Unity 式 #pragma kernel 多入口需
HLSL 源码改写机制，拍板不值；批次③ ClusterBuild 三步=三文件）。

**set1 通道**：`RenderingSystem` 帧级 storage 注册表（`registerFrameStorage(binding 1~2, buffer)` /
`unregister` / `frameStorageSet()` 懒建+脏重写）+ set1 布局翻牌（原 emptySetLayout 空 占位 → binding1/2
SSBO，**对齐灯光档 §3.4 终态槽位**——批次②③扩全套时验收 shader 不用重写）+ Opaque/Transparent 两 Pass
补绑（有注册才绑；图声明 addBufferRead 进依赖推导）+ 覆盖制 pass（PickPass/DebugLinePass）只带 set0 不受影响。

**验收件**（**2026-10-31 终拍板：纯测试形态，编辑器不掺和**——"编辑器就是编辑器，不应该去演示这些
东西；test 类自己通过 tool 编译就好了"，推翻同日早先的"编辑器实机验收"路线；builtin 三资产与
ComputeTestPass/AppState 已撤）：测试源码资产进 **core test resources**（首跑踩坑修正：.compute 原误放
editor test resources，core 测试类加载器不可见直接崩——test 源集/资源不跨模块，资源须跟测试同模块）——
`src/test/resources/compute/` 两份 .compute + `shader/ssbo-read.vert/.frag`（全屏三角形 fs 读 SSBO）；
- **editor `ComputeShaderImporterTest`**：内联 .compute 源码 → ComputeShaderImporter → 真实编译 + 坏源
  ShaderCompileException 显性抛（导入器装配面单测；源码内联免两模块 duplicate .compute 漂移——
  PushUniformTest 内联先例）；
- **core `ComputeSsboTest`**（RhiTestBase 家族，**批次①验收口**）：双段 compute 链（A 写 bufferA →
  B 读 A 写 bufferB，compute→compute RAW 同步）→ 图形 Pass 全屏三角形 fs 读 bufferB（compute→图形
  跨 Pass 同步）→ 帧 60 `Buffer.read` 读回 65536 元素与 CPU 期望值逐元素比对（确定性图案，数据错即
  屏障/链路断——错误显性化）+ 肉眼灰度渐变条带画面。运行：
  `gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.ComputeSsboTest`。

**验收口径**：✅ **已验收关账（2026-10-31 当日用户实测）**——ComputeSsboTest 全环跑通（读回 65536
元素逐比对通过 + 画面正确 + ESC 退出不崩）+ ComputeShaderImporterTest 绿。F3 存活计数不涨
（编辑器日常使用中 compute 相关常驻资源为零——set1 通道未注册时零开销）。

**排障实录（2026-10-31 首跑/二跑）**：① 首跑 EXCEPTION_ACCESS_VIOLATION = 自建 PSO 动态状态
空集 + 执行器恒调 setViewport（非动态下调用=VUID 违规→驱动 UB，Pitfalls #27，修=补
VIEWPORT/SCISSOR 动态声明）；② 二跑读回比对 4931/65536 超容差 = **sin 大自变量区间归约
GPU/CPU 实现差**（非链路/屏障问题——零散分布是精度指纹；换纯整数位运算图案，Pitfalls #28）。
诊断手段：onInit 逐步日志 bisect + VulkanComputePipeline 布局/管线两步日志（该路径历史零调用需
子步可见）。

**涉及文件**：RHI——VulkanComputePipeline/VulkanCommandBuffer/PassDef/CommandBuffer/CompiledGraph/
RenderGraphImpl/DescriptorUpdate/Device/VulkanDevice（删三文件）；core——ComputeShader（新）/
RenderingSystem/OpaqueForwardPass/TransparentForwardPass/test ComputeSsboTest（新）+ test resources
三源码；editor——ComputeShaderImporter（新）/ComputeShaderImportSettings（新）/FileTypeSetup/
MetaManager/AssetImportPipeline/test ComputeShaderImporterTest（新）/icon/compute.svg/lang 双包。

---

## Cleaner 延迟销毁重构（2026-10-30，无实踩，防御性根治）

**背景**：阶段⑥引入的 `markDisposed(handle)` + `DISPOSED_HANDLES` 全局集合（见上「显式 dispose 与
Cleaner 兜底会对同一句柄二次 vkDestroy」）有两条次生隐患（详见 `Vulkan_Pitfalls.md` #26）：
① 句柄被驱动复用后撞上陈旧登记项，抑制新资源的 Cleaner 兜底（泄漏，集合只增不减）；
② 手动 dispose 走立即 `vkDestroy*`、绕过两帧延迟队列（在飞资源可能被提前销毁）。

**改动**：
- `CleanerRegistry.Registration` 合一——`register` 返回 `Registration`（持 `Cleanable` + `DestroyTask`），
  资源实例持有它；`dispose()` = 置 `task.manual` + `cleanable.clean()`（入队销毁动作 + 注销 Cleaner
  一次完成，同一句柄只可能销毁一次）；删除 `DISPOSED_HANDLES` / `markDisposed` / `markDisposed` 登记逻辑；
- **手动 dispose 也入两帧延迟队列**（不再立即销毁）；新增 `flush()`，`VulkanDevice.dispose` 在
  `vkDestroyDevice` 前调用冲刷剩余条目（原排队条目随设备销毁被隐式回收、显式销毁落空）；
- 全部资源类改持 `Registration`、`dispose()` 调 `registration.dispose()`；`VulkanComputePipeline`
  补内部 layout 注册（此前未注册、只能显式销毁）；
- 释放日志化（info：来源 手动/Cleaner + type/handle/memory/frame；跳过释放降 debug）。

**涉及文件**：`CleanerRegistry` / `VulkanBuffer` / `VulkanTexture` / `VulkanSwapChain`（SwapChainTexture）/
`VulkanFence` / `VulkanSemaphore` / `VulkanSampler` / `VulkanShaderModule` / `VulkanDescriptorSetLayout` /
`VulkanPipelineLayout` / `VulkanGraphicsPipeline` / `VulkanComputePipeline` / `VulkanDevice`。

---

## FrameArena 现状存档（2026-10-23 调查，无代码变更）

> 背景：帧对象池评估时顺带查清 FrameArena 真实状态。结论先行：**FrameArena 自
> Stage 5 落地后一直空转**——FrameLoop 每帧 fence 闸门后 reset（`FrameLoop.java:105`），
> 但全库无任何调用方往 arena 写数据；每帧并行槽各占 1MB HOST_VISIBLE 显存，分配后
> 永不使用（RenderStats 的 buffer 存活计数含它，数字略有水分）。

- **设计意图**（`RHI_Design_Resources.md` §17.3）：每帧瞬态数据（动态 UBO / 每帧
  uniform / 瞬时缓冲）走 3 帧环形 bump arena——免逐对象分配、不经 Cleaner；设计
  默认 64MB×3。
- **实现**（Stage 5）：`VulkanFrameArena` 单块 1MB HOST_VISIBLE+COHERENT，256B 对齐
  bump 分配，reset 只归零 offset。
- **澄清：类注释过时**——"当前为单帧版本"不成立：实际每 FrameState 槽构造期各持
  一块（`FrameState.java:33`），FrameLoop 帧首 fence await 之后才 reset 本槽，多帧
  轮换复用语义已成立（26.5 节已知问题表同结论）；阶段⑤待办表"VulkanFrameArena
  多帧环形 | Stage 6"一行随 FrameState 环形落地自然消解。
- **为何无人用**：设计要喂的消费方后来全被引擎层专项方案吃掉——每帧 UBO 走
  RenderingSystem UBO1 持久缓冲（`frameIndex % N` 槽原地写）；Pass push 数据走
  AbstractForwardPass/PickPass 持久 direct ByteBuffer；DebugText/DebugLine 顶点走
  双缓冲槽。各消费方"持久缓冲 + 槽位轮换"比通用 arena 更贴合自身需求，通用池遂
  无接入方。
- **隐患两处**（已记 `Vulkan_Pitfalls.md` #25，待做小收口）：`VulkanDevice.frameArena()`
  per-call new（帧内误调即每帧 1MB 泄漏）；`VulkanBuffer.writeAligned` 的"实际应该用
  FrameArena"过时注释。
- **归宿**：Buffer staging（deviceLocal 上传，RHI 遗留项）是其赎回路径——
  宿主→device-local 传输的 host 侧暂存天然适配"写入 → copy → 下帧 reset 复用"。
  在此之前维持现状（无害但无用的占位）。

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

> 陷阱已集中迁移至 **`Vulkan_Pitfalls.md`**（含症状→根因→修法→出处与排障方法论），此处不再重复维护。
| `VkPresentInfoKHR.waitSemaphoreCount(int)` | setter 不存在，由 `pWaitSemaphores.remaining()` 自动推断 |
| `VkRenderingInfo.colorAttachmentCount(int)` | setter 不存在，由 `pColorAttachments.remaining()` 自动推断 |
| `VK_INDEX_TYPE_UINT8_EXT` | 在 `VK14` 类（`VK14.VK_INDEX_TYPE_UINT8`），不在 `VK10` |
| `vkEnumeratePhysicalDevices` | 三参数：`(VkInstance, IntBuffer, PointerBuffer)`，无单参数版本 |
| `vkCmdSetViewport` / `vkCmdSetScissor` | 需要 `VkViewport.Buffer` / `VkRect2D.Buffer`；单元素用 `nvkCmdSetViewport(cmd, 0, 1, vp.address())` |
| `vkCmdClearAttachments` | `(VkCommandBuffer, VkClearAttachment.Buffer, VkClearRect.Buffer) → void`；dynamic rendering 通道内合法；`VkClearAttachment.aspectMask/colorAttachment/clearValue`，`VkClearRect.rect/baseArrayLayer/layerCount`（rect 为 framebuffer 坐标且在 renderArea 内） |
