# RHI 实现计划（RHI Implementation Plan）

> 版本：v0.1
> 目标：按设计文档**从零重建** `javelin-rhi`（声明式 RenderGraph RHI），最终驱动引擎渲染 Demo。
> 规格来源：`doc/` 下 `RHI_Design.md` 系列（总纲 + 4 分卷，26 节 API 清单 + 27 线程模型 + 坐标约定）、`Engine_Scene_Design.md`、`Engine_Assets_Design.md`。
> 现状：`javelin-rhi` 源码已清空（build/ 残留旧命令式 RHI 编译产物，仅作 API 形态参考），Gradle 依赖已配好（lwjgl-vulkan）。

## 1. 约束（遵守 AGENTS.md 红线）

1. **不编译验证**：编译 / 运行交给用户执行，我提供运行命令；
2. **不写不确定代码**：不确定的设计先问用户；
3. **LWJGL / Vulkan API 签名不确定时，向用户要明确签名**（红线 4）——不瞎猜；
4. **问题定位**：第一优先级加日志，用户运行后看日志；
5. **Edit 唯一性**：每次修改后验证，禁止连续 Edit 不验证；
6. **AOT / 禁反射**：本项目基于 GraalVM 25（native image）——**禁止运行时反射**；对象创建 / 组件实例化一律走 `@Reflective` 注解 + `ReflectiveFactory`（零反射，编译期注册）；资产序列化走 `Serializable` + `InputCapsule/OutputCapsule` 手写协议。

## 2. 总路线（六阶段，依赖顺序）

```
① 地基（types/desc/异常，纯 Java 无 GPU）
   ↓
② 入口与设备（Instance / PhysicalDevice / Device）
   ↓
③ 资源与描述符（Buffer/Texture/Sampler + Cleaner + 描述符 + Shader/Pipeline）
   ↓
④ RenderGraph 核心（声明 / compile / 屏障 / 缓存 / 执行器）  ← 本设计灵魂
   ↓
⑤ 呈现与帧执行（RenderSurface / SwapChain / FrameState / 上传 / present）
   ↓
⑥ 验证与引擎接入（单测金字塔 / Demo 演进 / RenderingSystem 接入）
```

每阶段独立可验证（① 纯单测；② 打印设备信息；③ 建资源不崩；④ 无 GPU 图编译单测；⑤ 出画面；⑥ Demo 演进）。

## 3. 阶段明细

### 阶段 ① 地基：types + desc + 异常（无 GPU 依赖）

- **目标**：26 节 API 清单的类型系统落成纯 Java 代码，可单测；
- **产出**：
  - 异常体系：`RHIException`（根）、`VulkanException`（vk 调用失败包装，带错误码）；
  - `types/` 枚举（对照 26 节与旧 build 产物裁剪）：Format、TextureType、TextureUsage、BufferUsage、PrimitiveTopology、CullMode、FrontFace、PolygonMode、CompareOp、StencilOp/Face、BlendFactor/BlendOp、ColorComponentFlags、DescriptorType、ShaderStage、PipelineBindPoint、DynamicState、Filter、SamplerAddressMode、SamplerMipmapMode、SampleCount、MemoryUsage、AttachmentLoadOp/StoreOp、TextureLayout、IndexType、PipelineStage、AccessFlags、DependencyFlags、QueryType、CommandPoolFlags；
  - `desc/` 描述类（不可变数据类）：BufferDesc、TextureDesc、TextureViewDesc、SamplerDesc、ShaderDesc、PipelineLayoutDesc、DescriptorSetLayoutDesc、GraphicsPipelineDesc、ComputePipelineDesc、SwapChainDesc；
  - `VertexLayout`（RHI 侧顶点输入描述；引擎侧 `VertexAttribute` 自动排布后转换为本类）；
- **验收**：类型齐全对照 26 节；枚举值与 Vulkan 常量对应表写入文档注释；纯 Java 单测（值域/转换）。
- **对应文档**：26 节全部 desc/types 引用、`Engine_Assets_Design.md` 2.4/2.5。

### 阶段 ② 入口与设备

- **目标**：23.4a 初始化流程落地（阶段 0~3：呈现目标 → Instance → 物理设备 → 逻辑设备）；
- **产出**：
  - `Instance`：校验层（仅 DEBUG 构建）、surface 扩展（按 Platform）；
  - `PhysicalDevice`：枚举、队列族查询（graphics / present 可不同族）、特性查询；
  - `Device`：逻辑设备（双队列）、`Queue` 获取；
  - `Platform`（枚举，23 节：WINDOWS / MACOS / LINUX / ...）；
- **验收**：Demo 打印设备名 / 队列族 / 特性（运行由用户）；
- **注意**：LWJGL 的 vkCreateInstance / vkEnumeratePhysicalDevices 等签名实现时向用户确认（红线 4）。
- **对应文档**：23.4a、26.1。

### 阶段 ③ 资源与描述符 + 管线

- **目标**：GPU 资源体系 + 描述符管线 + Shader/Pipeline；
- **产出**：
  - `GPUResource` 基类 + **Cleaner 生命周期**（17 节：Java 对象不可达 → Cleaner → 延迟销毁队列，两帧规则；只管理上层资源——纹理 / 着色器 / buffer / 管线；内部对象确定性管理）；
  - `Buffer` / `Texture` / `TextureView` / `Sampler`：创建 + 上传（25 节三类入口：staging→device local / HOST_VISIBLE 环形 / 小更新池）+ 内存分配（MemoryUsage required/preferred）；
  - `Shader`：SPIR-V 加载（GLSL 编译走 `javelin-tool` ShaderCompiler）；
  - `PipelineLayout` / `DescriptorSetLayout` / `DescriptorPool` / `DescriptorSet`：15.8 描述符池（按 set 布局类型 × 帧数预算环形复用；set1 动态 UBO 动态 offset 256B 对齐）；
  - `GraphicsPipeline` / `ComputePipeline`：16 节（顶点输入 = VertexLayout、混合、光栅化、深度模板；管线缓存 key 含 structureHash）；
- **验收**：创建 texture + buffer + pipeline 不崩（运行由用户）；生命周期单测（Cleaner 触发 / 延迟销毁时序）。
- **对应文档**：15 / 15.8 / 16 / 17 / 25 / 26.2 / 26.3。

### 阶段 ④ RenderGraph 核心（本设计灵魂）

- **目标**：声明式图从声明到执行的完整链路；
- **产出**：
  - `RenderGraphBuilder` / `PassDef` / `PassBuilder`：addPass（回调式声明）、资源读写声明（22.3 ResourceUsage 语义：READ / WRITE / RENDER_ATTACHMENT / DEPTH / TRANSFER 等）；
  - 图资源：纹理 / 缓冲的声明与实例化（loadOp / storeOp / clearValue / 尺寸）；
  - `compile`：拓扑排序、依赖推导、**屏障生成**（24.2 布局跟踪：目标布局推导、同 Pass 合并、冗余消除）、**图缓存**（18 节 structureHash：声明结构变化才失效；swapchain recreate 触发失效 18.5/23.6）；
  - **执行器**（24.3）：LayoutTracker + 屏障插入 + 单命令缓冲顺序录制；Pass 回调只做绘制不碰屏障（22.4 迁移纪律）；
  - `RHICmd` 录制接口（26.4）：绑定 / 绘制 / 资源屏障显式口（若图内无法表达时）;
- **验收**：**无 GPU 图编译单测**——依赖推导正确（先后顺序）、屏障生成正确（布局转换/合并/冗余消除）、缓存命中与失效（结构不变命中、声明变化失效）；mock RHICmd 记录调用序列验证执行顺序。
- **对应文档**：2 / 7 / 8 / 12 / 13 / 14 / 18 / 22 / 24 / 26.5。

### 阶段 ⑤ 呈现与帧执行

- **目标**：帧循环收口，出第一个画面；
- **产出**：
  - `RenderSurface` 接口（23 节：nativeHandle / platform / width / height / isResized / acknowledgeResize / isVisible）+ 上层实现（GLFW 在 core 或 demo 层；RHI 只消费接口）；
  - `Surface` / `SwapChain`（23.2/23.3：acquire / present / recreate；格式与呈现模式降级 FIFO；最小化尺寸兜底）；
  - `FrameState`（24.3）：fence / imageAvailable / renderFinished / cmd / arena **环形复用**（maxFramesInFlight=2~3；fence.wait 在 acquire 之前）；
  - 帧循环（24.4）：acquire → compile(缓存) → execute(录制+屏障) → submit → present；帧索引即版本号，fence 为临时资源回收闸门；
  - 数据上传（25 节）三类入口落地；
- **验收**：Demo 窗口 + 清屏（第一个可跑画面）；随后三角形（阶段 ⑥ 演进）。
- **对应文档**：23 / 23.4a / 24 / 25 / 26.6 / 27.2（帧描述交接接口预留）。

### 阶段 ⑥ 验证与引擎接入

- **目标**：测试金字塔落地 + 引擎场景接入；
- **产出**：
  - 测试金字塔：图逻辑纯单测（无 GPU）→ mock RHICmd 组件测试 → 真 GPU 黄金图集成（最少）；
  - Demo 演进：清屏 → 三角形（含坐标约定：投影 y 翻转 + frontFace=CW）→ 立方体 + 纹理 → 多相机（19 节）；
  - 引擎接入：`RenderingSystem`（`Engine_Scene_Design.md` 6 节）→ FrameDescription → 图声明（27.2 交接）；
- **验收**：多相机多物体 Demo（19 节综合示例）；坐标约定验证（画面不颠倒、剔除正确）。
- **对应文档**：19 / 27 / `Engine_Scene_Design.md` 6-7 / 坐标约定（Core 第 5 节）。

## 4. 不做（P2，实现后置）

- 多线程并行录制 / 异步加载线程（27.5）；
- QueryPool 性能回读、多队列 compute、DebugUtils 对象命名 / 帧 region（可后补）；
- 采样器缓存去重、Bindless。

## 5. 需要用户拍板的开放项

| 项 | 现状 | 建议 |
|---|---|---|
| Cleaner 延迟销毁的"两帧"实现 | 17 节已定语义 | 实现时按帧版本号 + fence 闸门落地 |
| RenderSurface 的 GLFW 实现归属 | 23 节：上层实现 | 放 demo / core（core 已依赖 glfw） |
| 描述符池预算（环形 × 帧数） | 15.8 已定 | 默认 2~3 帧预算，可配 |
| 旧实现参考 | build/ 产物 | 反编译参考 API 形态，不沿用旧签名 |
| 首个 Demo 载体 | 未定 | 建议 javelin-core 内 test 或独立 demo 模块 |

## 6. 文档对照索引

| 阶段 | 文档章节 |
|---|---|
| ① 地基 | 26（全）、Assets 2.4/2.5 |
| ② 入口 | 23.4a、26.1 |
| ③ 资源 | 15、15.8、16、17、25、26.2、26.3 |
| ④ RenderGraph | 2、7、8、12、13、14、18、22、24、26.5 |
| ⑤ 呈现帧 | 23、23.4a、24、25、26.6、27.2 |
| ⑥ 验证接入 | 19、27、Scene 6-7、坐标约定 |

---

> （注：部分内容可能由 AI 生成）
