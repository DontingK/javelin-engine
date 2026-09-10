# Javelin RHI 设计文档

## 1. 概述

Javelin RHI（Rendering Hardware Interface）是 Javelin 引擎的图形硬件抽象层，基于 Vulkan 实现，提供跨平台、类型安全的 Java 图形 API。

### 1.1 设计目标

- **类型安全**：利用 Java 类型系统，编译期捕获错误
- **零开销抽象**：抽象类层仅承载类型与生命周期管理，热路径不引入额外开销
- **显式控制**：暴露 GPU 细节，开发者完全掌控渲染
- **现代 Vulkan**：支持 Vulkan 1.3、动态渲染、网格着色器
- **可扩展**：未来可接入 OpenGL、Metal、DirectX 12

### 1.2 非目标

- 不是场景管理器（GameScene/Node/Mesh 在 javelin-core）
- 不是材质系统（Material/Shader 资产在 javelin-core）
- 不是窗口系统（Window 在 javelin-window）

---

## 2. 架构

```
┌─────────────────────────────────────────┐
│           javelin-core                  │
│    GameScene / Camera / Material / Mesh     │
│    GlfwWindow（实现 RHIWindow）          │
├─────────────────────────────────────────┤
│           javelin-rhi                   │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐  │
│  │   API   │ │  Types  │ │ Vulkan   │  │
│  │(抽象类) │ │(枚举标志)│ │(后端实现) │  │
│  └─────────┘ └─────────┘ └──────────┘  │
├─────────────────────────────────────────┤
│      javelin-log / LWJGL / JOML         │
└─────────────────────────────────────────┘
```

### 2.1 依赖规则

```
javelin-rhi
  ├── 依赖 javelin-log（日志）
  ├── 依赖 LWJGL（Vulkan / GLFW / shaderc 绑定）与 JOML（数学）
  └── 不依赖 javelin-core
        （窗口抽象 RHIWindow 定义在 rhi 内，由 javelin-core 的 GlfwWindow 实现）
```

---

## 3. 核心概念

### 3.1 资源生命周期

所有 GPU 资源继承 `GPUResource` 抽象基类，GC 自动触发**延迟释放**（详见 `doc/GPU-RESOURCE-LIFECYCLE.md`）：

```java
public abstract class GPUResource {
    public abstract long handle();        // 原生 Vulkan handle
    protected abstract void doDispose();  // 子类实现释放逻辑

    public final void dispose();          // 请求释放：加入延迟队列，framesInFlight 帧后真正释放（帧安全，幂等）
    public boolean isDisposed();          // 状态由基类管理
}
```

- **自动释放**：基类内置 `Cleaner`，GC 回收时自动请求延迟释放，防止当前帧仍在使用
- **手动释放**：`dispose()` 可显式调用，与 GC 路径走同一个延迟队列
- **延迟释放**：`DelayedResourceDisposer` 按帧推进队列，到期后调用 `disposeNow()` 真正执行 `doDispose()`
- **拆卸**：`GPUResource.flushDisposals()` 强制销毁所有待释放资源（引擎关闭时调用，须先 `waitIdle`）

**禁止依赖 Finalizer**。延迟释放由 `java.lang.ref.Cleaner` 驱动。

### 3.2 对象句柄 vs 抽象类

| 层级 | 形式 | 说明 |
|------|------|------|
| 用户 API | `abstract class Buffer extends GPUResource` | 抽象类，承载生命周期管理 |
| 内部实现 | `class VulkanBuffer extends Buffer` | 封装 VkBuffer + VkDeviceMemory |
| Vulkan 层 | `long bufferHandle` | 原生 `long` 句柄 |

### 3.3 线程模型

| 操作 | 线程安全 | 说明 |
|------|---------|------|
| `Device.create*()` | 是 | 内部同步 |
| `CommandBuffer.record*` | 否 | 每线程独立 CommandBuffer |
| `Queue.submit()` | 是 | 内部锁保护 |
| `Buffer.uploadData()` | 否 | 需外部同步 |

**推荐模式**：每渲染线程一个 `CommandPool`，录制期间无锁。

---

## 4. 模块结构

```
javelin-rhi/src/main/java/com/javelin/rhi/
├── api/
│   ├── Device.java              # 逻辑设备
│   ├── Instance.java            # Vulkan 实例
│   ├── PhysicalDevice.java      # 物理设备
│   ├── Queue.java               # 提交队列
│   ├── CommandBuffer.java       # 命令缓冲区
│   ├── Buffer.java              # GPU 缓冲区
│   ├── Texture.java             # 纹理/图像
│   ├── TextureView.java         # 图像视图
│   ├── Sampler.java             # 采样器
│   ├── Shader.java              # 着色器模块
│   ├── GraphicsPipeline.java    # 图形管线
│   ├── ComputePipeline.java     # 计算管线
│   ├── PipelineLayout.java      # 管线布局
│   ├── DescriptorSet.java       # 描述符集
│   ├── DescriptorSetLayout.java # 描述符集布局
│   ├── DescriptorPool.java      # 描述符池
│   ├── RenderPass.java          # 渲染通道
│   ├── Framebuffer.java         # 帧缓冲
│   ├── SwapChain.java           # 交换链
│   ├── Fence.java               # 栅栏
│   ├── Semaphore.java           # 信号量
│   ├── QueryPool.java           # 查询池
│   ├── RHIWindow.java           # 窗口抽象（由 javelin-core 实现）
│   ├── GPUResource.java         # 资源抽象基类（Cleaner + 延迟释放）
│   └── DelayedResourceDisposer.java # GPU 资源延迟释放管理器
│
├── types/
│   ├── Format.java              # 像素格式
│   ├── ImageLayout.java         # 图像布局
│   ├── ImageUsage.java          # 图像用途标志
│   ├── BufferUsage.java         # 缓冲区用途标志
│   ├── MemoryUsage.java         # 内存分配策略
│   ├── ShaderStage.java         # 着色器阶段
│   ├── PipelineStage.java       # 管线阶段
│   ├── AccessFlags.java         # 访问标志
│   ├── BlendFactor.java         # 混合因子
│   ├── BlendOp.java             # 混合操作
│   ├── CompareOp.java           # 比较操作
│   ├── CullMode.java            # 剔除模式
│   ├── FrontFace.java           # 正面朝向
│   ├── PolygonMode.java         # 多边形模式
│   ├── PrimitiveTopology.java   # 图元拓扑
│   ├── VertexFormat.java        # 顶点属性格式
│   ├── IndexType.java           # 索引类型
│   ├── Filter.java              # 纹理过滤
│   ├── SamplerMipmapMode.java   # Mipmap 模式
│   ├── SamplerAddressMode.java  # 寻址模式
│   ├── AttachmentLoadOp.java    # 附件加载操作
│   ├── AttachmentStoreOp.java   # 附件存储操作
│   ├── PresentMode.java         # 呈现模式
│   ├── ColorSpace.java          # 颜色空间
│   └── ...                      # 其他 Vulkan 枚举映射
│
├── desc/                        # 创建描述符（POJO）
│   ├── BufferDesc.java
│   ├── TextureDesc.java
│   ├── TextureViewDesc.java
│   ├── SamplerDesc.java
│   ├── ShaderDesc.java
│   ├── GraphicsPipelineDesc.java
│   ├── ComputePipelineDesc.java
│   ├── PipelineLayoutDesc.java
│   ├── DescriptorSetLayoutDesc.java
│   ├── RenderPassDesc.java
│   ├── FramebufferDesc.java
│   ├── SwapChainDesc.java
│   └── VertexLayout.java        # 顶点输入布局
│
├── vulkan/                      # Vulkan 后端实现
│   ├── VulkanInstance.java
│   ├── VulkanPhysicalDevice.java
│   ├── VulkanDevice.java
│   ├── VulkanQueue.java
│   ├── VulkanCommandBuffer.java
│   ├── VulkanCommandPool.java
│   ├── VulkanBuffer.java
│   ├── VulkanTexture.java
│   ├── VulkanTextureView.java
│   ├── VulkanSampler.java
│   ├── VulkanShader.java
│   ├── VulkanGraphicsPipeline.java
│   ├── VulkanPipelineLayout.java
│   ├── VulkanDescriptorSet.java
│   ├── VulkanDescriptorSetLayout.java
│   ├── VulkanDescriptorPool.java
│   ├── VulkanSwapChain.java
│   ├── VulkanFence.java
│   └── VulkanSemaphore.java
│
├── RHIFactory.java            # 简化初始化工厂（RHIConfig / RHIContext）
├── Renderer.java              # 每帧渲染管理（帧同步 + 延迟释放驱动）
├── RHIException.java          # 异常基类
└── VulkanException.java       # Vulkan 专用异常
```

---

## 5. 核心 API 详解

> 以下各抽象类均继承 `GPUResource`，所列方法均为 `public abstract`。

### 5.1 Device

```java
public abstract class Device extends GPUResource {
    
    // 查询
    PhysicalDevice physicalDevice();
    Instance instance();
    
    // 队列
    Queue graphicsQueue();
    Queue computeQueue();
    Queue transferQueue();
    
    // 内存资源
    Buffer createBuffer(BufferDesc desc);
    Texture createTexture(TextureDesc desc);
    
    // 管线对象
    Shader createShader(ShaderDesc desc);
    GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc);
    ComputePipeline createComputePipeline(ComputePipelineDesc desc);
    PipelineLayout createPipelineLayout(PipelineLayoutDesc desc);
    
    // 描述符
    DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc);
    DescriptorPool createDescriptorPool(DescriptorPoolDesc desc);
    
    // 同步
    Fence createFence(boolean signaled);
    Semaphore createSemaphore();
    
    // 命令
    CommandPool createCommandPool(Queue queue, CommandPoolFlags flags);
    CommandBuffer createCommandBuffer(CommandPool pool, boolean primary);
    
    // 交换链
    SwapChain createSwapChain(RHIWindow window, SwapChainDesc desc);
    
    // 等待
    void waitIdle();
    void waitForFences(Fence[] fences, boolean waitAll, long timeoutNanos);
    void resetFences(Fence[] fences);
}
```

### 5.2 CommandBuffer

```java
public abstract class CommandBuffer extends GPUResource {
    
    // 录制控制
    void begin();
    void beginSecondary(RenderPass renderPass, int subpass, Framebuffer framebuffer);
    void end();
    void reset();
    
    // 动态渲染（Vulkan 1.3）
    void beginRendering(RenderingInfo info);
    void endRendering();
    
    // 传统渲染通道
    void beginRenderPass(RenderPassBeginInfo info);
    void nextSubpass();
    void endRenderPass();
    
    // 管线绑定
    void bindPipeline(GraphicsPipeline pipeline);
    void bindPipeline(ComputePipeline pipeline);
    
    // 描述符
    void bindDescriptorSets(PipelineBindPoint bindPoint, PipelineLayout layout,
                           int firstSet, DescriptorSet[] sets, int[] dynamicOffsets);
    
    // 顶点/索引
    void bindVertexBuffer(int binding, Buffer buffer, long offset);
    void bindIndexBuffer(Buffer buffer, long offset, IndexType indexType);
    
    // 动态状态
    void setViewport(int x, int y, int width, int height, float minDepth, float maxDepth);
    void setScissor(int x, int y, int width, int height);
    void setLineWidth(float width);
    void setDepthBias(float constant, float clamp, float slope);
    void setBlendConstants(float r, float g, float b, float a);
    void setDepthBounds(float min, float max);
    void setStencilCompareMask(StencilFace face, int mask);
    void setStencilWriteMask(StencilFace face, int mask);
    void setStencilReference(StencilFace face, int reference);
    
    // 绘制
    void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance);
    void drawIndexed(int indexCount, int instanceCount, int firstIndex,
                     int vertexOffset, int firstInstance);
    void drawIndirect(Buffer buffer, long offset, int drawCount, int stride);
    void drawIndexedIndirect(Buffer buffer, long offset, int drawCount, int stride);
    
    // 计算
    void dispatch(int groupX, int groupY, int groupZ);
    void dispatchIndirect(Buffer buffer, long offset);
    
    // 复制
    void copyBuffer(Buffer src, Buffer dst, BufferCopy[] regions);
    void copyBufferToTexture(Buffer src, Texture dst, TextureLayout dstLayout,
                             BufferTextureCopy[] regions);
    void copyTextureToBuffer(Texture src, TextureLayout srcLayout, Buffer dst,
                             BufferTextureCopy[] regions);
    void copyTexture(Texture src, TextureLayout srcLayout,
                     Texture dst, TextureLayout dstLayout, TextureCopy[] regions);
    void blitTexture(Texture src, TextureLayout srcLayout,
                     Texture dst, TextureLayout dstLayout, TextureBlit[] regions,
                     Filter filter);
    
    // 屏障
    void pipelineBarrier(PipelineStage srcStage, PipelineStage dstStage,
                         DependencyFlags dependencyFlags,
                         MemoryBarrier[] memoryBarriers,
                         BufferMemoryBarrier[] bufferBarriers,
                         TextureMemoryBarrier[] textureBarriers);
    
    // 推送常量
    void pushConstants(PipelineLayout layout, ShaderStage stages,
                       int offset, byte[] data);
    
    // 查询
    void resetQueryPool(QueryPool pool, int first, int count);
    void beginQuery(QueryPool pool, int query);
    void endQuery(QueryPool pool, int query);
    void writeTimestamp(PipelineStage stage, QueryPool pool, int query);
    
    // 执行二级命令缓冲区
    void executeCommands(CommandBuffer[] secondary);
}
```

### 5.3 Buffer

```java
public abstract class Buffer extends GPUResource {
    long size();
    BufferUsage usage();
    MemoryUsage memoryUsage();
    
    // 映射（仅 CPU_VISIBLE 内存）
    ByteBuffer map();
    ByteBuffer map(long offset, long size);
    void unmap();
    boolean isMapped();
    
    // 便捷上传
    void upload(byte[] data);
    void upload(ByteBuffer data);
    void upload(long offset, ByteBuffer data);
}
```

### 5.4 Texture

```java
public abstract class Texture extends GPUResource {
    int width();
    int height();
    int depth();
    int mipLevels();
    int arrayLayers();
    Format format();
    TextureUsage usage();
    TextureType type();         // TEXTURE_2D, TEXTURE_3D, TEXTURE_CUBE, etc.
    SampleCount samples();
    
    // 创建视图
    TextureView createView(TextureViewDesc desc);
    
    // 布局转换（内部状态追踪）
    TextureLayout currentLayout();
}
```

### 5.5 SwapChain

```java
public abstract class SwapChain extends GPUResource {
    
    Format imageFormat();
    ColorSpace colorSpace();
    int width();
    int height();
    int imageCount();
    PresentMode presentMode();
    TextureUsage imageUsage();
    
    // 获取当前帧图像
    int acquireNextImage(long timeoutNanos, Semaphore signalSemaphore, Fence signalFence);
    Texture getImage(int index);
    
    // 重新创建（窗口大小变化）
    void recreate(int width, int height);
    void recreate(SwapChainDesc desc);
    
    // 呈现
    void present(Queue queue, Semaphore[] waitSemaphores);
}
```

---

## 6. 描述符设计

### 6.1 顶点布局

```java
public record VertexLayout(VertexAttribute[] attributes) {
    
    public int stride() {
        return Arrays.stream(attributes)
            .mapToInt(VertexAttribute::size)
            .sum();
    }
    
    public record VertexAttribute(
        int location,           // shader location
        VertexFormat format,    // R32G32B32_SFLOAT, etc.
        int offset,             // byte offset in vertex
        boolean normalized
    ) {
        public int size() { return format.size(); }
    }
}
```

### 6.2 图形管线描述符

```java
public record GraphicsPipelineDesc(
    Shader vertexShader,
    Shader fragmentShader,
    Shader geometryShader,          // nullable
    Shader tessellationControl,     // nullable
    Shader tessellationEvaluation,  // nullable
    
    VertexLayout vertexLayout,
    PrimitiveTopology topology,
    
    // 光栅化
    PolygonMode polygonMode,
    CullMode cullMode,
    FrontFace frontFace,
    float lineWidth,
    boolean depthClampEnable,
    boolean rasterizerDiscardEnable,
    
    // 深度/模板
    DepthStencilState depthStencil,
    
    // 混合
    BlendState[] colorBlendAttachments,  // per-attachment
    float[] blendConstants,
    
    // 多重采样
    MultisampleState multisample,
    
    // 动态状态
    DynamicState[] dynamicStates,
    
    // 布局
    PipelineLayout layout,
    
    // 渲染通道（传统）
    RenderPass renderPass,
    int subpass
) {}

public record DepthStencilState(
    boolean depthTestEnable,
    boolean depthWriteEnable,
    CompareOp depthCompareOp,
    boolean depthBoundsTestEnable,
    float minDepthBounds,
    float maxDepthBounds,
    boolean stencilTestEnable,
    StencilOpState front,
    StencilOpState back
) {}

public record BlendState(
    boolean blendEnable,
    BlendFactor srcColorBlendFactor,
    BlendFactor dstColorBlendFactor,
    BlendOp colorBlendOp,
    BlendFactor srcAlphaBlendFactor,
    BlendFactor dstAlphaBlendFactor,
    BlendOp alphaBlendOp,
    ColorComponentFlags colorWriteMask
) {
    public static BlendState opaque() { ... }
    public static BlendState alphaBlend() { ... }
    public static BlendState additive() { ... }
}
```

---

## 7. 动态渲染（Vulkan 1.3）

优先使用 `vkCmdBeginRendering` 替代传统 RenderPass/Framebuffer：

```java
public record RenderingInfo(
    Rect2D renderArea,
    int layerCount,
    RenderingAttachmentInfo[] colorAttachments,
    RenderingAttachmentInfo depthAttachment,
    RenderingAttachmentInfo stencilAttachment
) {}

public record RenderingAttachmentInfo(
    TextureView view,
    TextureLayout imageLayout,
    AttachmentLoadOp loadOp,
    AttachmentStoreOp storeOp,
    ClearValue clearValue,          // used if loadOp == CLEAR
    TextureView resolveView,        // nullable
    TextureLayout resolveImageLayout
) {}
```

使用示例：

```java
// 动态渲染 - 无需 RenderPass/Framebuffer 对象
cmd.beginRendering(new RenderingInfo(
    new Rect2D(0, 0, width, height),
    1,  // layerCount
    new RenderingAttachmentInfo[] {
        new RenderingAttachmentInfo(
            swapChainImageView,
            TextureLayout.COLOR_ATTACHMENT_OPTIMAL,
            AttachmentLoadOp.CLEAR,
            AttachmentStoreOp.STORE,
            new ClearValue(0.1f, 0.1f, 0.15f, 1.0f),
            null, null
        )
    },
    new RenderingAttachmentInfo(
        depthImageView,
        TextureLayout.DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
        AttachmentLoadOp.CLEAR,
        AttachmentStoreOp.DONT_CARE,
        new ClearValue(1.0f, 0),
        null, null
    ),
    null
));

// 绑定管线、描述符、绘制...
cmd.bindPipeline(pipeline);
cmd.draw(3, 1, 0, 0);

cmd.endRendering();
```

---

## 8. 内存分配

### 8.1 策略

| MemoryUsage | Vulkan 内存类型 | 用途 |
|-------------|----------------|------|
| `GPU_ONLY` | DEVICE_LOCAL | 顶点/索引缓冲、纹理、GPU 只读数据 |
| `CPU_TO_GPU` | HOST_VISIBLE + DEVICE_LOCAL | 每帧更新的 UBO、动态顶点数据 |
| `GPU_TO_CPU` | HOST_VISIBLE | 回读缓冲区、查询结果 |
| `CPU_ONLY` | HOST_VISIBLE + HOST_COHERENT | 暂存缓冲区（staging） |

### 8.2 分配器接口

> 规划中，当前实现直接在 `VulkanBuffer` / `VulkanTexture` 内分配内存，尚未抽出独立分配器。

```java
public interface MemoryAllocator {
    
    Allocation allocateBuffer(long size, BufferUsage usage, MemoryUsage memoryUsage);
    Allocation allocateTexture(TextureDesc desc, MemoryUsage memoryUsage);
    
    void free(Allocation allocation);
    
    ByteBuffer map(Allocation allocation);
    void unmap(Allocation allocation);
    void flush(Allocation allocation, long offset, long size);
    void invalidate(Allocation allocation, long offset, long size);
    
    // 统计
    Stats stats();
    
    void dispose();
}

public interface Allocation {
    long deviceMemory();    // VkDeviceMemory
    long offset();
    long size();
}
```

---

## 9. 帧资源管理

### 9.1 Renderer（每帧同步）

`Renderer` 管理每帧的同步对象与呈现流程：

```java
public class Renderer {

    public Renderer(Device device, SwapChain swapChain, int maxFramesInFlight);

    public boolean beginFrame();   // 等待 fence → acquire 图像 → 推进延迟释放队列 → 开始录制
    public void endFrame();        // 结束录制 → submit → present
    public CommandBuffer commandBuffer();
    public Texture currentImage();
    public void waitIdle();
    public void dispose();         // waitIdle → 释放帧资源 → flush 延迟队列
}
```

使用模式：

```java
renderer.beginFrame();
// 录制命令到 renderer.commandBuffer()
renderer.endFrame();
```

### 9.2 延迟释放（DelayedResourceDisposer）

`Renderer` 构造时创建 `DelayedResourceDisposer`（延迟帧数 = `maxFramesInFlight`）并注册为全局释放器（`GPUResource.setDisposer()`）：

- 每帧在 `beginFrame()` 内、fence 等待完成后推进队列，到期资源调用 `disposeNow()` 真正释放
- `dispose()` 时 `flush()` 强制释放所有待释放资源
- 引擎关闭时按入队顺序（帧资源 → 交换链 → 设备 → 实例）统一 `GPUResource.flushDisposals()`

详细设计见 `doc/GPU-RESOURCE-LIFECYCLE.md`。

---

## 10. 错误处理

### 10.1 异常体系

```java
public class RHIException extends RuntimeException {
    public RHIException(String message) { super(message); }
    public RHIException(String message, Throwable cause) { super(message, cause); }
}

public class VulkanException extends RHIException {
    private final int vkResult;
    public VulkanException(int vkResult, String message) {
        super(message + " (vkResult: " + vkResult + ")");
        this.vkResult = vkResult;
    }
}

public class ValidationException extends RHIException {
    public ValidationException(String message) { super("VALIDATION: " + message); }
}
```

### 10.2 调试回调

```java
public interface DebugMessenger {
    void message(DebugSeverity severity, DebugType type, String message);
}

public enum DebugSeverity {
    VERBOSE, INFO, WARNING, ERROR
}

public enum DebugType {
    GENERAL, VALIDATION, PERFORMANCE, OTHER
}
```

---

## 11. 扩展接口

> 规划中，当前仅有 Vulkan 后端，通过 `RHIFactory` 直接初始化。

### 11.1 后端工厂

```java
public interface RHIBackend {
    String name();
    boolean isSupported();
    Instance createInstance(InstanceDesc desc);
}

// SPI 注册
public interface RHIBackendProvider {
    RHIBackend create();
}
```

### 11.2 特性查询

```java
public enum Feature {
    DYNAMIC_RENDERING,          // Vulkan 1.3
    SYNCHRONIZATION_2,          // VK_KHR_synchronization2
    BUFFER_DEVICE_ADDRESS,      // Vulkan 1.2
    DESCRIPTOR_INDEXING,        // Vulkan 1.2
    TIMELINE_SEMAPHORE,         // Vulkan 1.2
    RAY_TRACING,                // VK_KHR_ray_tracing_pipeline
    MESH_SHADER,                // VK_EXT_mesh_shader
    VARIABLE_RATE_SHADING,      // VK_KHR_fragment_shading_rate
    // ...
}

public interface PhysicalDevice {
    boolean supports(Feature feature);
    boolean supportsExtension(String extension);
    DeviceLimits limits();
}
```

---

## 12. 版本规划

| 阶段 | 目标 | 特性 |
|------|------|------|
| **MVP** | 三角形 | Instance, Device, SwapChain, GraphicsPipeline, CommandBuffer, Buffer, 动态渲染 |
| **v0.2** | 纹理 | Texture, Sampler, 描述符集, 推送常量 |
| **v0.3** | 深度/MSAA | 深度缓冲, 多重采样, 渲染到纹理 |
| **v0.4** | 计算 | ComputePipeline, SSBO, 计算着色器 |
| **v0.5** | 高级特性 | 时间轴信号量, 缓冲区设备地址, 描述符索引 |
| **v1.0** | 稳定 | 光追, 网格着色器, 完整验证 |

---

## 13. 命名规范

| 类型 | 前缀/后缀 | 示例 |
|------|----------|------|
| 抽象类（API 层） | 无 | `Device`, `Buffer`, `Texture` |
| Vulkan 实现 | `Vulkan` | `VulkanDevice`, `VulkanBuffer` |
| 描述符 | `Desc` | `BufferDesc`, `TextureDesc` |
| 枚举 | 无 | `Format`, `ImageLayout` |
| 标志位 | `Flags` | `BufferUsage`, `PipelineStage` |
| 句柄 | `Handle` | `TextureHandle`（用户层） |

---

*文档版本: 1.1*
*最后更新: 2026-09（适配抽象类 API 层 + Cleaner 延迟释放设计）*
