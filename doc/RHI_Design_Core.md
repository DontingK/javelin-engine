# RHI 设计 · 分卷一：核心概念与接口

> 总纲：`RHI_Design.md`（含 1 概述 / 2 设计原则 / 11 架构取舍与坑点 + 完整章节索引）
> 本卷包含：第 3 包结构 / 4 核心数据类型 / 5 相机与视口模型 / 6 清屏（LoadOp）机制 / 9 RenderView 上层封装 / 10 接口定义补充
> 章节编号全局唯一（1~21），跨卷引用按编号查找，完整索引见总纲。

---


## 3. 包结构

```Plain Text
com.javelin.rhi                // 上层可访问API
├─ RHI.java
├─ RHIResource.java
├─ RHIBuffer.java
├─ RHITexture.java
├─ RHISampler.java
├─ RHIPipeline.java
├─ RHIRenderGraph.java
├─ RHIGraphicsPass.java
├─ RHIComputePass.java
├─ RHICmd.java
├─ RHIConstants.java
├─ RHIException.java
└─ data/
   ├─ RHIViewport.java
   ├─ RHIScissor.java
   ├─ RHILoadOp.java
   ├─ RHIClearValue.java
   ├─ RHIClearColor.java
   └─ RHIClearDepthStencil.java

com.javelin.rhi.impl           // 内部实现，上层不可访问
├─ VulkanRHI.java
├─ VkResourceImpl.java
├─ VkRenderGraphImpl.java
├─ VkCommandPoolManager.java
├─ VkBarrierGenerator.java
└─ VkQueueScheduler.java
```


---

## 4. 核心数据类型

```java
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

// 清屏值密封接口
public sealed interface RHIClearValue permits RHIClearColor, RHIClearDepthStencil{}
public record RHIClearColor(float r, float g, float b, float a) implements RHIClearValue {}
public record RHIClearDepthStencil(float depth, int stencil) implements RHIClearValue {}
```


---

## 5. 相机与视口模型

> 相机不属于 RHI 资源，完全由上层引擎维护。
> 相机输出：view、proj、viewProj 矩阵、viewport、scissor。
> 每个 GraphicsPass 可以绑定独立`RHIViewport`和`RHIScissor`。
> RHI 内部录制 CommandBuffer 时自动调用`vkCmdSetViewport`/`vkCmdSetScissor`。

### 注入方式（推荐）

在 Pass 构建阶段绑定视口、裁剪与 Uniform 矩阵：

```java
pass.setViewport(viewport);
pass.setScissor(scissor);
pass.setUniform("Camera.viewProj", viewProjFloatArray);
```

- 同一相机的多个 Pass，共用同一套相机参数
- 多相机场景：每个 RenderView 的全部 Pass 使用各自独立 Viewport/Scissor/ 相机矩阵

> 不推荐：在 RHICmd 回调内动态切换视口，仅用于特殊硬件多视口 Forward 场景。


---

## 6. 清屏（LoadOp）机制

清屏**不是 RHICmd 手动调用指令**，而是附件属性声明，在 Pass 添加颜色 / 深度输出时指定。
底层依赖 Vulkan Dynamic Rendering，`vkCmdBeginRendering`携带 ClearValue。

重载接口：

```java
void addColorOutput(RHITexture tex);
void addColorOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clearValue);

void addDepthOutput(RHITexture tex);
void addDepthOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clearValue);
```

- `RHILoadOp.CLEAR`：Pass 启动自动清空附件
- `RHILoadOp.LOAD`：保留上一次写入内容，用于叠加渲染（分屏多相机场景）


---

## 9. RenderView 上层封装（推荐）

> 目的：消除重复手写大量 Pass 代码，一个 RenderView 代表一个视口 / 相机。

```java
public class RenderView {
    public Camera camera;
    public RHIViewport viewport;
    public RHIScissor scissor;

    // 每个视口独立GBuffer资源
    public RHITexture gbufferAlbedo;
    public RHITexture gbufferNormal;
    public RHITexture gbufferWorldPos;
    public RHITexture depthTex;

    public void buildRenderGraph(RHIRenderGraph graph, Scene scene){
        // 内部自动添加GBufferPass / LightingPass / TransparentPass
        // 自动绑定viewport、scissor、相机uniform矩阵
    }
}
```

使用示例：

```java
RenderView viewA = new RenderView(camA, new RHIViewport(0,0,960,1080,0,1));
RenderView viewB = new RenderView(camB, new RHIViewport(1400,720,400,360,0,1));

RHIRenderGraph graph = rhi.beginRenderGraph();
viewA.buildRenderGraph(graph, scene);
viewB.buildRenderGraph(graph, scene);
graph.submit();
```


---

## 10. 接口定义补充

```java
public interface RHIGraphicsPass {
    void setPipeline(RHIPipeline pipeline);

    void addColorOutput(RHITexture tex);
    void addColorOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear);
    void addDepthOutput(RHITexture tex);
    void addDepthOutput(RHITexture tex, RHILoadOp loadOp, RHIClearValue clear);

    void addTextureRead(RHITexture tex);
    void addBufferRead(RHIBuffer buf);

    void setViewport(RHIViewport viewport);
    void setScissor(RHIScissor scissor);
    void setUniform(String name, float[] data);

    void setRenderCallback(Consumer<RHICmd> callback);
}

public interface RHICmd {
    void bindPipeline(RHIPipeline pipeline);   // 显式绑定PSO，一个Pass内可切换多次（与12.6节示例对齐）

    void bindVertexBuffer(RHIBuffer buffer, long offset);
    void bindIndexBuffer(RHIBuffer buffer, RHIIndexType type, long offset);

    // 描述符集绑定；dynamicOffsets 用于动态UBO（每物体矩阵切换，见第15节）
    void bindDescriptorSet(int set, RHIDescriptorSet descriptorSet,
                           int dynamicOffsetCount, int[] dynamicOffsets);
    // 便捷绑定：set+binding 指定单个资源（内部走描述符集或bindless索引）
    void bindUniformBuffer(int set, int binding, RHIBuffer buffer, long offset, long range);
    void bindTexture(int set, int binding, RHITexture tex, RHISampler sampler);
    void pushConstants(int stageFlags, int offset, int size, byte[] data);

    void draw(int vertexCount, int instanceCount);
    void drawIndexed(int indexCount, int instanceCount);
    void dispatch(int groupX, int groupY, int groupZ);
}
```

> 接口修正说明（v0.2，P0）：
> - `RHICmd` 补充 `bindPipeline`，消除与第 12.6 节示例 `cmd.bindPipeline(obj.material.pipeline)` 的不一致；
> - `setUniform(String, float[])` 仅允许在 Pass **构建期**声明，RHI 编译为帧级 UBO 写入（见第 15.4 节）；回调内禁止字符串查找；
> - `RHIDescriptorSet`、`RHIPipelineDesc.descriptorLayouts` 在第 15 节定义。


---

---

> （注：部分内容可能由 AI 生成）
