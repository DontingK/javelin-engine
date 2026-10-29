# RHI 设计・分卷一：核心概念与接口

> 总纲：
> `RHI_Design.md`
> （含 1 概述 / 2 设计原则 / 11 架构取舍与坑点 + 完整章节索引）
> 本卷包含：第 3 包结构 / 4 核心数据类型 / 5 相机与视口模型 / 6 清屏（LoadOp）机制 / 9 RenderView 上层封装 / 10 接口定义补充
> 章节编号全局唯一（1~21），跨卷引用按编号查找，完整索引见总纲。



***

## 3. 包结构

> v0.23：命名统一为 26 节 API 清单体系（入口 RHI/Instance/Device + 资源简洁名 + data/ + render/），废除 RHI 前缀冗余命名。

```
com.javelin.rhi               // API 层（26 节规格）

├─ RHI.java                   // 入口：createInstance（23.4a 阶段1）
├─ Instance.java              // createDevice / createSurface（23.4a 阶段2/4）
├─ PhysicalDevice.java        // 队列族查询（graphics / present）
├─ Device.java                // 资源创建 + 队列 + FrameArena + PipelineCache（26.1）
├─ Queue.java                 // submit / waitIdle（24.4）
├─ CommandBuffer.java         // RHICmd 录制接口（15 / 24.3）
├─ GPUResource.java           // 资源基类（17.1 Cleaner 所有权）
├─ Texture.java / Buffer.java / Sampler.java
├─ ShaderModule.java          // SPIR-V（16.2，javelin-tool shaderc）
├─ GraphicsPipeline.java / ComputePipeline.java
├─ PipelineLayout.java / PipelineCache.java
├─ DescriptorSetLayout.java / DescriptorSet.java（15.8）
├─ RenderGraphBuilder.java / CompiledGraph.java
├─ GraphicsPassBuilder.java / ComputePassBuilder.java / PassDef.java（22.3）
├─ Fence.java / Semaphore.java / FrameArena.java（24 / 17.3）
├─ UploadData.java / MemoryPreference.java（25）
├─ RHIException.java
├─ data/                      // 值类型（保留 RHI 前缀，表意清晰）
│  ├─ RHIViewport.java
│  ├─ RHIScissor.java
│  ├─ RHILoadOp.java
│  ├─ RHIClearValue.java
│  ├─ RHIClearColor.java
│  └─ RHIClearDepthStencil.java
└─ render/                    // 呈现模型（第 23 节）
   ├─ RenderSurface.java      // 呈现目标契约（上层实现：GLFW / Swing）
   ├─ Platform.java           // 平台枚举（决定 surface 扩展）
   ├─ Surface.java            // VkSurfaceKHR 封装（RHI 消费 RenderSurface）
   ├─ SwapChain.java          // acquire / present / recreate（23.3）
   ├─ SwapChainDesc.java      // PresentMode / ColorSpace
   ├─ GLFWRenderSurface.java  // 上层实现：GLFW 窗口
   └─ SwingRenderSurface.java // 上层实现：Swing Canvas

com.javelin.rhi.impl           // 内部实现，上层不可访问

├─ VulkanRHI.java
├─ VkInstance.java / VkDevice.java / VkQueue.java
├─ VkBuffer.java / VkTexture.java / VkSampler.java
├─ VkShaderModule.java / VkGraphicsPipeline.java / VkComputePipeline.java
├─ VkPipelineLayout.java / VkPipelineCache.java
├─ VkDescriptorSetLayout.java / VkDescriptorSet.java
├─ VkCommandBuffer.java / VkCommandPoolManager.java
├─ VkRenderGraphImpl.java / VkBarrierGenerator.java / VkQueueScheduler.java
├─ VkSurface.java / VkSwapChain.java
├─ VkFence.java / VkSemaphore.java / VkFrameArena.java
└─ CleanerRegistry.java       // 17 节延迟销毁队列
```




***

## 4. 核心数据类型



```
// RHIViewport 视口

public record RHIViewport(float x, float y, float width, float height, float minDepth, float maxDepth) {}

// RHIScissor 裁剪矩形

public record RHIScissor(int x, int y, int width, int height) {}

// 附件加载操作枚举

public enum RHILoadOp {

    LOAD,        // 保留附件原有内容

    CLEAR,       // Pass启动自动清屏

    DONT_CARE    // 丢弃原有内容，驱动优化

}

// 清屏值密封接口（RHIClearUintColor = 整数附件清屏，拾取 2026-10-23——Vulkan 规范
// UINT 附件须用 uint32 分量，float 清是验证层 mismatch）

public sealed interface RHIClearValue permits RHIClearColor, RHIClearUintColor, RHIClearDepthStencil{}

public record RHIClearColor(float r, float g, float b, float a) implements RHIClearValue {}

public record RHIClearUintColor(int r, int g, int b, int a) implements RHIClearValue {}

public record RHIClearDepthStencil(float depth, int stencil) implements RHIClearValue {}
```



***

## 5. 相机与视口模型

> 相机不属于 RHI 资源，完全由上层引擎维护。
> 相机输出：view、proj、viewProj 矩阵、viewport、scissor。
> 每个 GraphicsPass 可以绑定独立
> `RHIViewport`
> 和
> `RHIScissor`
> 。
> RHI 内部录制 CommandBuffer 时自动调用
> `vkCmdSetViewport`
> /
> `vkCmdSetScissor`
> 。

### 注入方式（推荐）

在 Pass 构建阶段绑定视口、裁剪与 Uniform 矩阵：



```
pass.setViewport(viewport);

pass.setScissor(scissor);

pass.setUniform("Camera.viewProj", viewProjFloatArray);
```



* 同一相机的多个 Pass，共用同一套相机参数

* 多相机场景：每个 RenderView 的全部 Pass 使用各自独立 Viewport/Scissor/ 相机矩阵

> 不推荐：在 RHICmd 回调内动态切换视口，仅用于特殊硬件多视口 Forward 场景。

### 坐标约定（Vulkan NDC：Y 向下）

> 关键差异：Vulkan 的 NDC 与 OpenGL 不同——**Y 轴向下**、**深度范围 [0,1]**。引擎所有投影矩阵、顶点数据、UV 必须按此约定。

| 项 | Vulkan | OpenGL（对比） |
|---|---|---|
| NDC Y | **向下**（y=+1 顶部，y=-1 底部） | 向上（y=+1 顶部） |
| NDC Z（深度） | **[0, 1]** | [-1, 1] |
| 窗口 / 帧缓冲坐标 | 原点**左上**，y 向下 | 原点左下，y 向上 |
| 纹理 UV（采样） | 原点左上，v 向下 | 原点左下，v 向上 |

**对引擎的要求：**

1. **投影矩阵必须 y 翻转**：标准右手系投影矩阵（如 glm::perspective，按 OpenGL 约定）用于 Vulkan 时需翻转 NDC Y（投影矩阵第二行取负，或乘翻转矩阵），否则画面上下颠倒；正交投影同理；
2. **深度范围映射**：投影矩阵需把深度映射到 [0,1]（Vulkan）而非 [-1,1]；Reverse-Z（近处 1、远处 0，配合 [0,1]）可提高深度精度（P2 可选）；
3. **顶点 / 世界坐标**：引擎约定 **y-up 右手系**（建模工具 / 引擎世界一致）；NDC 翻转发生在投影矩阵，不在顶点数据；
4. **UV**：Vulkan 采样 v 向下（原点左上）；美术资产若按 OpenGL 惯例（原点左下）需在导入时翻转 v（或采样器配置）；
5. **viewport**：vkCmdSetViewport 的 y 由 Vulkan 约定（左上原点），RHI 直接透传即可，无需额外翻转。
6. **绕序（winding）**：y 翻转是镜像变换（行列式 < 0），会把所有三角形的**屏幕绕序 CCW ↔ CW 互换**——屏幕绕序只看投影后 2D (x,y) 的带符号面积（z 不参与、x 不变），因此 x/z 无额外影响。**适配方案（推荐 A）**：投影矩阵 y 翻转 + 管线 `frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE`，顶点资产 / 索引完全不动；备选 B：导入时反转三角形索引顺序（front face 保持 CW，跨 API 资产统一标准）。**front face 约定引擎全局一致**（不得 Pass 间混用）。
   - **澄清**：y 翻转只作用于 clip / NDC / 屏幕空间——顶点位置与法线在模型 / 世界 / 视图空间不变（M、V 不翻 y），光照与法线贴图不受影响；z 不参与绕序判定，深度 [0,1] 单独约定（见第 2 条）。
   - **实测修正（2026-09-23，TriangleTest）**：CCW 资产（Y 向上）不翻转时在 Vulkan framebuffer（y 向下）中即为 **CW** 绕序（frontFace=CW 可见、CCW 被剔）；y 翻转后变为 **CCW** 绕序，**须配 frontFace=CCW**。本节原配对（翻转 + CW）有误，已按实测修正。
   - **默认约定（记住这一句）**：资产 / 索引缓冲 = **逆时针（CCW，front）**——建模工具惯例，资产不动；管线 frontFace = **逆时针（CCW）**——y 翻转镜像后 CCW 资产在 framebuffer 恰为 CCW 绕序（实测），与 Vulkan 默认值一致，**仍显式设置**。二者成对出现，是 y 翻转方案的固定搭配。


**归属**：本约定是 RHI 全局基线，引擎侧（Camera 投影矩阵生成、资产导入）必须遵守——见 `Engine_Scene_Design.md` 5.2。





***

## 6. 清屏（LoadOp）机制

清屏**不是 RHICmd 手动调用指令**，而是附件属性声明，在 Pass 添加颜色 / 深度输出时指定。

底层依赖 Vulkan Dynamic Rendering，`vkCmdBeginRendering`携带 ClearValue。

重载接口：



```
void addColorOutput(Texture tex);

void addColorOutput(Texture tex, RHILoadOp loadOp, RHIClearValue clearValue);

void addDepthOutput(Texture tex);

void addDepthOutput(Texture tex, RHILoadOp loadOp, RHIClearValue clearValue);
```



* `RHILoadOp.CLEAR`：Pass 启动自动清空附件

* `RHILoadOp.LOAD`：保留上一次写入内容，用于叠加渲染（分屏多相机场景）

### 区域清屏（阶段⑥新增）

Vulkan 的 loadOp 清屏**无视 scissor，始终清整个附件**（细节见 Vulkan_Pitfalls 第 16 条）。
分区域清屏只能由执行器在渲染通道开始后以 `vkCmdClearAttachments` 实现（dynamic rendering
开启的同样是渲染通道实例，该命令合法；rect 为 framebuffer 坐标、须在 renderArea 内）。

对应两个入口，按场景二选一：

* **声明式（推荐）**：附件声明带区域，执行器自动处理——

```
void addColorOutput(Texture tex, RHILoadOp loadOp, RHIClearColor clearValue, RHIScissor clearRegion);
```

  语义仍是 `CLEAR + clearValue`，`clearRegion` 非空时执行器把实际 loadOp 降为 LOAD
  （保住矩形外内容），矩形内批量发 `vkCmdClearAttachments`。多相机各自清自己的视口矩形
  （第 8 节后续相机）走这个；
* **命令式**：帧中局部清屏（portal/编辑器工具）在渲染回调内调用——

```
void clearColor(Texture tex, RHIClearColor color, RHIScissor rect);   // CommandBuffer
```

  约束：texture 必须是本 Pass 已声明的颜色附件（按索引定位 attachment）。

> 出处：javelin-engine4 `VkRenderBackend.clearViewAttachments` 同款做法（生产验证）；
> Unity 现代后端（D3D12 rects / Vulkan clearAttachments）同源；UE 用清屏 quad 另辟蹊径。
> 整附件清屏仍优先 CLEAR loadOp（零额外命令），区域清屏只用于"矩形外内容须保留"的场景。



***

## 9. RenderView 上层封装（推荐）

> 目的：消除重复手写大量 Pass 代码，一个 RenderView 代表一个视口 / 相机。



```
public class RenderView {

    public Camera camera;

    public RHIViewport viewport;

    public RHIScissor scissor;

    // 每个视口独立GBuffer资源

    public Texture gbufferAlbedo;

    public Texture gbufferNormal;

    public Texture gbufferWorldPos;

    public Texture depthTex;

    public void buildRenderGraph(RenderGraphBuilder graph, Scene scene){

        // 内部自动添加GBufferPass / LightingPass / TransparentPass

        // 自动绑定viewport、scissor、相机uniform矩阵

    }

}
```

使用示例：



```
RenderView viewA = new RenderView(camA, new RHIViewport(0,0,960,1080,0,1));

RenderView viewB = new RenderView(camB, new RHIViewport(1400,720,400,360,0,1));

RenderGraphBuilder graph = rhi.beginRenderGraph();

viewA.buildRenderGraph(graph, scene);

viewB.buildRenderGraph(graph, scene);

graph.submit();
```



***

## 10. 接口定义补充

> **命名说明（v0.24）**：本节早期使用 `RHI` 前缀命名（`RHIGraphicsPass` / `RHICmd` / `RHITexture` 等），**已全部废弃**。统一以第 26 节 API 清单为准：接口名无 `RHI` 前缀（`GraphicsPassBuilder` / `CommandBuffer` / `Texture`），值类型保留 `RHI` 前缀（`RHIViewport` / `RHIScissor` / `RHILoadOp` / `RHIClearValue` 等，位于 `data/` 包）。下文代码块已按新命名修订。

```
public interface GraphicsPassBuilder {

    void setPipeline(GraphicsPipeline pipeline);

    void addColorOutput(Texture tex);

    void addColorOutput(Texture tex, RHILoadOp loadOp, RHIClearValue clear);

    void addDepthOutput(Texture tex);

    void addDepthOutput(Texture tex, RHILoadOp loadOp, RHIClearValue clear);

    void addTextureRead(Texture tex);

    void addBufferRead(Buffer buf);

    void setViewport(RHIViewport viewport);

    void setScissor(RHIScissor scissor);

    void setUniform(String name, float[] data);

    void setRenderCallback(Consumer<CommandBuffer> callback);

}

public interface CommandBuffer {   // 即 RHICmd，26.4 节正式名称

    void bindPipeline(GraphicsPipeline pipeline);   // 显式绑定PSO，一个Pass内可切换多次（与12.6节示例对齐）

    void bindVertexBuffer(Buffer buffer, long offset);

    void bindIndexBuffer(Buffer buffer, IndexType type, long offset);

    // 描述符集绑定；dynamicOffsets 用于动态UBO（每物体矩阵切换，见第15节）

    void bindDescriptorSet(int set, DescriptorSet descriptorSet,
                           int dynamicOffsetCount, int[] dynamicOffsets);

    // 便捷绑定：set+binding 指定单个资源（内部走描述符集或bindless索引）

    void bindUniformBuffer(int set, int binding, Buffer buffer, long offset, long range);

    void bindTexture(int set, int binding, Texture tex, Sampler sampler);

    void pushConstants(int stageFlags, int offset, int size, byte[] data);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void dispatch(int groupX, int groupY, int groupZ);

}
```

> 接口修正说明（v0.2，P0）：
> `CommandBuffer`（原 `RHICmd`）
> 补充
> `bindPipeline`
> ，消除与第 12.6 节示例
> `cmd.bindPipeline(obj.material.pipeline)`
> 的不一致；
> `setUniform(String, float[])`
> 仅允许在 Pass
> **构建期**
> 声明，RHI 编译为帧级 UBO 写入（见第 15.4 节）；回调内禁止字符串查找；
> `DescriptorSet`
> 、
> `GraphicsPipelineDesc.descriptorLayouts`
> 在第 15 节定义。



***



***

## 26. API 全貌清单（v0.20）

> **用途**
> ：实现规格书 —— 把散落在 10 / 15 / 17 / 18 / 23 / 24 / 25 节的公开接口收拢为完整签名清单，实现时对照逐项落地。所有资源接口继承
> `GPUResource`
> （17.1 Cleaner 所有权）；
> `handle()`
> 返回 Vulkan 句柄（RHI 内部使用）；record / 枚举出处已注明。

### 26.1 入口（RHI / Instance / Device）



```
interface RHI {

    Instance createInstance(RHIInstanceConfig config, RenderSurface.Platform platform);

    // platform 决定 surface 扩展（23.4a 阶段1）

}

interface Instance extends GPUResource {

    Device createDevice(RHIDevicePrefs prefs, RenderSurface surface);

    // 选物理设备：graphics 族 + present 族检查（23.4a 阶段2）

    Surface createSurface(RenderSurface renderSurface);   // vkCreateXXXSurfaceKHR（23.3）

    long handle();   // VkInstance

}

interface Device extends GPUResource {

    Texture createTexture(TextureDesc desc, UploadData data);        // 25.4

    Buffer  createBuffer(BufferDesc desc, UploadData data);

    GraphicsPipeline createGraphicsPipeline(GraphicsPipelineDesc desc);

    ComputePipeline  createComputePipeline(ComputePipelineDesc desc);

    PipelineLayout   createPipelineLayout(PipelineLayoutDesc desc);

    DescriptorSetLayout createDescriptorSetLayout(DescriptorSetLayoutDesc desc);

    DescriptorSet createDescriptorSet(DescriptorSetLayout layout);   // 描述符池分配（15.8 落地简化：设备级单池）

    ShaderModule     createShaderModule(byte[] spirv);               // 16.2

    Queue graphicsQueue(); Queue computeQueue(); Queue transferQueue(); // 24.4

    CommandBuffer allocateCommandBuffer(boolean secondary);          // 24.3

    FrameArena frameArena();                                         // 17.3

    PipelineCache pipelineCache();                                   // 16.4

    // --- 帧同步原语（FrameState 装配，24.2） ---
    Fence createFence();                                             // 未置位
    Fence createFence(boolean signaled);                             // signaled=true：帧环形首帧闸门直接通过
    Semaphore createSemaphore();                                     // acquire / renderFinished 衔接

    // --- 交换链（VkSurfaceKHR 由 createDevice 创建并由设备持有，23.4a 阶段 4） ---
    SwapChain createSwapChain(SwapChainDesc desc);   // ⚠ 不持有 Device 上下文，落地为抛异常；
                                                     // 实际入口为 Device.createSwapChain（阶段⑥决策，23.4a 阶段 4）

    void submit(SubmitInfo info);                                    // 24.4 统一入口

    void waitIdle();

    long handle();   // VkDevice

    // --- GPU 信息与渲染统计（Engine_RenderStats_Design.md v0.1） ---
    GPUInfo gpuInfo();         // 设备/驱动静态信息（设备构造时提取，只读）
    RenderStats renderStats(); // 11 类存活资源计数 + 上一帧 draw 统计快照（drawCalls/triangles/instances/dispatches）
    void resetFrameStats();    // 引擎每帧录制收尾后显式调用：runtime 快照进 lastFrame 并清零（跳帧重复快照，显示值稳定）

    // --- MSAA 能力（2026-10-06 抗锯齿链路；limits 位掩码解码，COUNT_1 规范恒支持） ---
    Set<SampleCount> supportedColorSampleCounts();   // 颜色附件支持档位（引擎钳制用）
    Set<SampleCount> supportedDepthSampleCounts();   // 深度附件支持档位（与色彩档共同约束）

}
```

### 26.2 资源（GPUResource / Texture / Buffer）



```
interface GPUResource {          // 所有资源基类（17.1）

    void dispose();              // 唯一手动销毁入口；不可达时 Cleaner 兜底

}

interface Texture extends GPUResource {

    TextureDesc desc();

    void update(TextureRegion region, ByteBuffer data);   // 25.4（staging 池）

    ByteBuffer readback();                                // P2（HOST_CACHED）

    long handle();   // VkImage

}

interface Buffer extends GPUResource {

    BufferDesc desc();

    int writeAligned(int offset, FloatBuffer data);       // 返回实际偏移，256B 对齐（25.4 / 15.7-1）

    void update(int offset, ByteBuffer data);             // 运行期更新（staging 池）

    void read(int offset, int length, ByteBuffer out);    // 读回（GPU 完成后调；拾取 2026-10-23，
                                                          // 可见性由调用方 fence 保证）

    long handle();   // VkBuffer

}

// 拾取读回环（2026-10-23，Engine_Picking_Design.md）：多槽（cmd+fence+staging 池化）
class TextureReadbackRing implements AutoCloseable {

    boolean submit(Texture t, TextureLayout oldLayout, int x, int y, int w, int h, long tag);
                       // 非阻塞投递：屏障 oldLayout→TRANSFER_SRC + copyImageToBuffer + fence 提交；
                       // 槽在飞行（GPU 落后）返回 false 跳帧
    boolean poll();     // 非阻塞 FIFO 收割最旧完成槽（完成后 resultTag()/readResult(out) 可读）

}

// Fence 增补：boolean isSignaled();（vkGetFenceStatus 非阻塞，读回环跳帧判定）
// CommandBuffer 增补：void copyImageToBuffer(Texture src, Buffer dst, TextureRegion region);
//   （RHI 内部读回路径，TRANSFER_SRC 布局调用方预转——copyBufferToTexture 的镜像）
// Format 增补：int bytesPerPixel();（非压缩格式，读回容量计算）
```

### 26.3 描述符与管线



```
interface DescriptorSetLayout extends GPUResource { long handle(); }  // VkDescriptorSetLayout

interface DescriptorSet extends GPUResource {

    void update(DescriptorUpdate... updates);   // 材质创建/修改时，多帧复用（15.8-4）

    long handle();   // VkDescriptorSet

}

interface GraphicsPipeline extends GPUResource { long handle(); }     // VkPipeline

interface ComputePipeline  extends GPUResource { long handle(); }

interface PipelineLayout   extends GPUResource { long handle(); }

interface ShaderModule     extends GPUResource { long handle(); }     // VkShaderModule

interface PipelineCache    extends GPUResource {                      // 16.4

    void saveToFile(Path path);

    void loadFromFile(Path path);

    long handle();   // VkPipelineCache

}
```

### 26.4 队列与命令缓冲



```
interface Queue {

    void submit(CommandBuffer cmd, SubmitInfo info);  // wait/signal semaphore + fence（24.1）

    void waitIdle();

}

interface CommandBuffer {   // RHICmd（15 节 / 24.3）

    void begin(); void end();

    void bindPipeline(GraphicsPipeline p);

    void bindDescriptorSet(int setIndex, DescriptorSet set, int dynamicOffsetCount, int[] dynamicOffsets);

    void pushConstants(int offset, ByteBuffer data);   // model/tpf 等高频小量（布局按 pushConstantSize 声明）

    void bindVertexBuffer(Buffer vb, int binding);

    void bindIndexBuffer(Buffer ib, IndexType type, int offset);

    void draw(int vertexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount);

    void drawIndexed(int indexCount, int instanceCount, int firstIndex);  // 三参重载（参数序对齐 vkCmdDrawIndexed）：firstIndex 指向已绑定索引缓冲起始索引，子网格范围绘制（MeshDraw 消费，2026-10-29）

    void dispatch(int x, int y, int z);

    void setViewport(RHIViewport vp);

    void setScissor(RHIScissor sc);

    void clearColor(Texture t, RHIClearColor c, RHIScissor rect);  // 渲染通道内区域清屏（须为本 Pass 颜色附件）

    void pipelineBarrier(Texture t, TextureLayout oldLayout, TextureLayout newLayout);  // 3 参便捷式

    void pipelineBarrier(Texture t, TextureLayout oldLayout, TextureLayout newLayout,
                         boolean write, boolean computePass);        // 执行器专用：精确同步参数（24.3）

}
```

### 26.5 RenderGraph（声明 + 执行）



```
interface RenderGraphBuilder {

    GraphicsPassBuilder addGraphicsPass(String name, Consumer<PassDef> def);

    ComputePassBuilder  addComputePass(String name, Consumer<PassDef> def);

    CompiledGraph compile();                          // 18 节：拓扑 + 屏障计划 + structureHash

    void execute(CommandBuffer cmd, Texture backbuffer);  // 24.3 执行器

    void execute(CommandBuffer cmd, Texture backbuffer, CompiledGraph compiled);  // 图缓存产物执行（18 节命中路径）

}

interface PassDef {   // 声明式：读写意图 + 回调（22.3 ResourceUsage）

    void setPipeline(GraphicsPipeline p);

    void addColorOutput(Texture t, RHILoadOp op, RHIClearColor c);  // 写：Attachment.Color

    void addColorOutput(Texture t, RHILoadOp op, RHIClearColor c, RHIScissor clearRegion);  // 区域清屏（多相机视口，第 6 节）

    void addColorOutput(Texture t, RHILoadOp op, RHIClearColor c, Texture resolveTarget);  // MSAA resolve（2026-10-06：t 必须多采样、resolveTarget 必须 COUNT_1 同尺寸，通道结束硬件 AVERAGE 降采样；resolveTarget 按本 Pass 颜色附件写参与依赖/屏障/structureHash）

    void addDepthOutput(Texture t, RHILoadOp op, RHIClearDepthStencil c);

    void addTextureRead(Texture t);                                  // 读：Sampled → SHADER_READ_ONLY

    void addTextureStorageRead(Texture t);                           // 读：Storage → GENERAL

    void addTextureStorageWrite(Texture t);                          // 写：Storage → GENERAL

    void setViewport(RHIViewport v);

    void setScissor(RHIScissor s);

    void setUniform(String name, float[] data);       // 仅构建期（15.4）

    void setRenderCallback(Consumer<CommandBuffer> cb);  // 回调只做绘制（24.3）

}
```

### 26.6 呈现与帧同步（23 / 24）



```
interface Surface extends GPUResource {

    long handle();                       // VkSurfaceKHR

    RenderSurface renderSurface();       // 来源（23.2）

    Capabilities capabilities();

    SwapChain createSwapChain(SwapChainDesc desc);

}

interface SwapChain extends GPUResource {

    int acquireNextImage(long timeoutNanos, Semaphore signal);  // <0 = 需重建

    Texture image(int index);            // backbuffer，图终点

    int imageCount(); int width(); int height();

    SwapChainDesc.PresentMode presentMode();

    void present(int imageIndex, Semaphore wait);

    void recreate(SwapChainDesc desc);

}

interface Fence { void wait(); void reset(); }       // 24.1

interface Semaphore { }                              // 24.2 归属表

interface FrameArena {                               // 17.3 / 24.5

    int allocate(int size);

    int writeAligned(int offset, FloatBuffer data);

    void reset();                                    // 帧末，fence 闸门后

}

record UploadData(ByteBuffer data, int mipLevels) { static UploadData of(ByteBuffer, int); }  // 25.4

record MemoryPreference(Set<MemoryUsage> required, Set<MemoryUsage> preferred) {}             // 25.2
```

### 26.7 覆盖清单与出处



| 接口                                  | 出处               | 备注           |
| ----------------------------------- | ---------------- | ------------ |
| RenderSurface / Platform            | 23.2             | 上层实现         |
| Instance / Device / Queue           | 23.4a / 24.4     | 初始化 + 提交     |
| Texture / Buffer / GPUResource      | 17.1 / 25.4      | Cleaner + 上传 |
| DescriptorSet(Layout) / Pipeline(×) | 15 / 16          | 绑定 + 管线      |
| CommandBuffer（RHICmd）               | 15.2             | 绘制命令集        |
| RenderGraphBuilder / PassDef        | 18 / 22.3 / 24.3 | 声明 + 执行      |
| Surface / SwapChain                 | 23.3             | 呈现           |
| Fence / Semaphore / FrameArena      | 24.1 / 17.3      | 帧同步          |
| UploadData / MemoryPreference       | 25               | 上传 + 内存      |
| GPUInfo / RenderStats               | Engine_RenderStats_Design.md v0.1 | 统计（构造器/dispose 打点、快照双缓冲） |



***

> （注：部分内容可能由 AI 生成）