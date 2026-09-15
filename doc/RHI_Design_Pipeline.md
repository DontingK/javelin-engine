# RHI 设计 · 分卷二：渲染管线与 RenderGraph

> 总纲：`RHI_Design.md`（含 1 概述 / 2 设计原则 / 11 架构取舍与坑点 + 完整章节索引）
> 本卷包含：第 7 单相机多 Pass / 8 多相机分屏多 Pass / 12 引擎侧渲染管线与 RHI 管线区分 / 13 架构调试性评估 / 14 可编排 Pass 体系与默认混合渲染管线 / 18 RenderGraph 图缓存复用 / 19 多相机多角度渲染实现
> 章节编号全局唯一（1~21），跨卷引用按编号查找，完整索引见总纲。

---


## 7. 单相机多 Pass（Deferred 管线）

> 一个相机对应多组 Pass：GBuffer 几何 Pass → 光照 Pass → 透明物体 Pass。
> 同一个 Pass 回调内循环绘制全部物体；**禁止一个物体创建一个 Pass**。

```java
Camera mainCam = new Camera();
mainCam.recalculateMatrices();
Matrix4f viewProj = mainCam.getViewProjMatrix();

RHIRenderGraph graph = rhi.beginRenderGraph();

// GBuffer几何Pass
graph.addGraphicsPass("GBufferPass", pass -> {
    pass.setPipeline(gbufferPipeline);
    pass.addColorOutput(albedoTex, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(normalTex, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(worldPosTex, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addDepthOutput(depthTex, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f,0));

    pass.setViewport(new RHIViewport(0,0,1920,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,1920,1080));
    pass.setUniform("Camera.viewProj", viewProj.toArray());

    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getOpaqueObjects()){
            cmd.bindVertexBuffer(obj.vertexBuffer,0);
            cmd.bindUniformBuffer(1, obj.matrixBuffer);
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

// 光照Pass，读取GBuffer输出最终颜色
graph.addGraphicsPass("LightingPass", pass -> {
    pass.setPipeline(lightPipeline);
    pass.addColorOutput(finalColorTex, RHILoadOp.CLEAR, new RHIClearColor(0.05f,0.05f,0.05f,1));
    pass.addTextureRead(albedoTex);
    pass.addTextureRead(normalTex);
    pass.addTextureRead(worldPosTex);
    pass.addTextureRead(depthTex);

    pass.setViewport(new RHIViewport(0,0,1920,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,1920,1080));
    pass.setUniform("Camera.viewProj", viewProj.toArray());

    pass.setRenderCallback(cmd -> cmd.draw(3,1));
});

// 透明物体Pass
graph.addGraphicsPass("TransparentPass", pass -> {
    pass.setPipeline(transparentPipeline);
    pass.addColorOutput(finalColorTex, RHILoadOp.LOAD);
    pass.addDepthOutput(depthTex, RHILoadOp.LOAD);
    pass.addTextureRead(depthTex);

    pass.setViewport(new RHIViewport(0,0,1920,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,1920,1080));
    pass.setUniform("Camera.viewProj", viewProj.toArray());

    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getTransparentObjects()){
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

graph.submit();
```


---

## 8. 多相机分屏多 Pass

> 场景：编辑器多视口 / 双人游戏分屏。
> 方案选型：**独立 Pass 方案（推荐）**。
> 规则：每个相机拥有一套完整 Deferred 管线（GBuffer + Lighting + Transparent）；
> 场景资源（顶点缓冲、材质）全局共享；每个相机拥有独立 GBuffer 纹理与深度纹理。
> 如果多个相机渲染到同一张 Swapchain 纹理：后渲染的相机使用`RHILoadOp.LOAD`，叠加绘制。

### 上层示例代码

```java
Camera camA = new Camera(); // 左半屏主相机
Camera camB = new Camera(); // 右下角预览相机
camA.recalculateMatrices();
camB.recalculateMatrices();

RHIRenderGraph graph = rhi.beginRenderGraph();

// ========== 相机A整套Deferred ==========
graph.addGraphicsPass("CamA_GBuffer", pass -> {
    pass.setPipeline(gbufferPipeline);
    pass.addColorOutput(albedoTexA, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(normalTexA, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(worldPosTexA, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addDepthOutput(depthTexA, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f,0));

    pass.setViewport(new RHIViewport(0,0,960,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,960,1080));
    bindCamera(pass, camA);

    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getOpaqueObjects()){
            cmd.bindVertexBuffer(obj.vertexBuffer,0);
            cmd.bindUniformBuffer(1, obj.matrixBuffer);
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

graph.addGraphicsPass("CamA_Lighting", pass -> {
    pass.setPipeline(lightPipeline);
    pass.addColorOutput(swapchainColorTex, RHILoadOp.CLEAR, new RHIClearColor(0.05f,0.05f,0.05f,1));
    pass.addTextureRead(albedoTexA);
    pass.addTextureRead(normalTexA);
    pass.addTextureRead(worldPosTexA);
    pass.addTextureRead(depthTexA);

    pass.setViewport(new RHIViewport(0,0,960,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,960,1080));
    bindCamera(pass, camA);
    pass.setRenderCallback(cmd -> cmd.draw(3,1));
});

graph.addGraphicsPass("CamA_Transparent", pass -> {
    pass.setPipeline(transparentPipeline);
    pass.addColorOutput(swapchainColorTex, RHILoadOp.LOAD);
    pass.addDepthOutput(depthTexA, RHILoadOp.LOAD);
    pass.addTextureRead(depthTexA);
    pass.setViewport(new RHIViewport(0,0,960,1080,0,1));
    pass.setScissor(new RHIScissor(0,0,960,1080));
    bindCamera(pass, camA);
    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getTransparentObjects()){
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

// ========== 相机B整套Deferred ==========
graph.addGraphicsPass("CamB_GBuffer", pass -> {
    pass.setPipeline(gbufferPipeline);
    pass.addColorOutput(albedoTexB, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(normalTexB, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addColorOutput(worldPosTexB, RHILoadOp.CLEAR, new RHIClearColor(0,0,0,1));
    pass.addDepthOutput(depthTexB, RHILoadOp.CLEAR, new RHIClearDepthStencil(1.0f,0));

    pass.setViewport(new RHIViewport(1400,720,400,360,0,1));
    pass.setScissor(new RHIScissor(1400,720,400,360));
    bindCamera(pass, camB);

    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getOpaqueObjects()){
            cmd.bindVertexBuffer(obj.vertexBuffer,0);
            cmd.bindUniformBuffer(1, obj.matrixBuffer);
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

graph.addGraphicsPass("CamB_Lighting", pass -> {
    pass.setPipeline(lightPipeline);
    pass.addColorOutput(swapchainColorTex, RHILoadOp.LOAD);
    pass.addTextureRead(albedoTexB);
    pass.addTextureRead(normalTexB);
    pass.addTextureRead(worldPosTexB);
    pass.addTextureRead(depthTexB);

    pass.setViewport(new RHIViewport(1400,720,400,360,0,1));
    pass.setScissor(new RHIScissor(1400,720,400,360));
    bindCamera(pass, camB);
    pass.setRenderCallback(cmd -> cmd.draw(3,1));
});

graph.addGraphicsPass("CamB_Transparent", pass -> {
    pass.setPipeline(transparentPipeline);
    pass.addColorOutput(swapchainColorTex, RHILoadOp.LOAD);
    pass.addDepthOutput(depthTexB, RHILoadOp.LOAD);
    pass.addTextureRead(depthTexB);
    pass.setViewport(new RHIViewport(1400,720,400,360,0,1));
    pass.setScissor(new RHIScissor(1400,720,400,360));
    bindCamera(pass, camB);
    pass.setRenderCallback(cmd -> {
        for(RenderObject obj : scene.getTransparentObjects()){
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});

graph.submit();
```

### 依赖自动推导规则

1. 单个相机内部：GBuffer → Lighting → Transparent，串行执行
2. CamA_Lighting 写入 swapchainColorTex，CamB_Load 读取旧内容继续写入；RHI 自动识别写依赖，保证 CamA 全部 Pass 执行完成后再执行 CamB
3. 若两个相机渲染到完全独立的纹理，无资源读写依赖，RHI 拓扑排序后可**GPU 并行执行**

> 备选方案：单 Pass 硬件多视口（仅 Forward，不支持独立 GBuffer，作为高级扩展）


---

## 12. 引擎侧渲染管线 与 RHI 管线的区分

> 核心一句话：
> **RHI 管线 = GPU 硬件管线的抽象（PSO，固定状态组合，底层资源）；引擎侧渲染管线 = 上层业务编排（RenderGraph / RenderView，场景、相机、光照、Pass 组织）。**
> 很多人混淆：RHI 是**硬件抽象层**；引擎渲染管线是**渲染工作流编排层**，建立在 RHI 之上。

### 12.1 概念区分

#### RHI 管线（`RHIPipeline`）

对应 Vulkan `VkPipeline` / DX12 `ID3D12PipelineState`，也就是**PSO（Pipeline State Object）**

- 属于**RHI 层**，硬件级状态集合，一次性创建，不可修改
- 包含：顶点着色器、片元着色器（计算管线则是计算着色器）、输入布局、光栅化状态、混合状态、深度模板状态、顶点拓扑、渲染附件格式
- 职责：告诉 GPU「这一组绘制用什么着色器、怎么光栅化、怎么混合、怎么测试深度」
- 粒度：单个 DrawCall 使用的**硬件状态包**
- 生命周期：**创建后长期驻留**，提前预热 / 离线编译，运行时只绑定，不修改内部参数
- 分为两类：
  1. GraphicsPipeline：图形管线（顶点→光栅→片元）
  2. ComputePipeline：计算管线（Compute Shader）

> 限制：PSO 创建开销巨大，**不能每帧新建**，RHI 层做 PSO 缓存池，避免重复创建。

#### 引擎侧渲染管线（Render Pipeline / RenderGraph）

属于**引擎上层（RHI 之上）**，和 GPU 硬件无关，是**一帧内渲染任务的编排逻辑**

- 由多个 Pass 组成（GBufferPass / LightingPass / TransparentPass / BloomPass / TonemapPass）
- 描述：**资源输入输出、执行顺序、相机 / 视口、场景物体提交策略、剔除、排序**
- 粒度：**一整套帧渲染流程**（Forward / Deferred / Forward+）
- 不是 GPU 对象！只是 CPU 侧的任务描述，每帧构建或者复用
- 职责：
  1. 场景剔除、物体分类（不透明 / 透明 / 静态实例）
  2. 为每个 Pass 声明读写资源（纹理、缓冲）
  3. 交给 RHI 的 RenderGraph 做依赖分析、拓扑排序、自动屏障
  4. 在 Pass 回调中，绑定 RHIPipeline，发起 DrawCall

### 12.2 层级关系

```Plain Text
【引擎层】引擎渲染管线（Deferred管线，RenderView + RenderGraph）
        ↓（生成多个Graphics/Compute Pass）
【RHI层】RHIRenderGraph、RHIGraphicsPass
        ↓（Pass回调内绑定RHIPipeline，录制RHICmd命令）
【RHI底层实现】Vulkan（VkPipeline、CommandBuffer、Barrier）
        ↓
GPU硬件执行管线
```

> 重点：**引擎管线 ≠ RHI 管线**
> 一套引擎 Deferred 管线，会**复用多个 RHIPipeline**：GBuffer PSO、光照 PSO、透明物体 PSO、后处理 PSO。
> 同一个 RHIPipeline，可以被多个 Pass、多个相机复用。

### 12.3 对比表格

|项目|RHI 管线 RHIPipeline|引擎侧渲染管线（Deferred/Forward）|
|---|---|---|
|所属层级|RHI 底层抽象层|引擎上层（RHI 之上）|
|对应硬件|GPU PSO（VkPipeline）|无硬件对象，纯 CPU 任务编排|
|修改性|创建后不可修改，一次性构建|每帧动态构建 / 复用，Pass 可以动态增删|
|粒度|单次 DrawCall 硬件状态|完整一帧渲染流程，由多个 Pass 组成|
|包含内容|Shader、输入布局、光栅 / 混合 / 深度状态|Pass 列表、资源依赖、相机、物体剔除、排序策略|
|生命周期|长生命周期，缓存复用|帧级，每帧构建 / 销毁|
|作用|GPU 绘制状态|组织渲染任务顺序、资源读写关系|

### 12.4 和 bgfx 的对照（承接上一节）

- bgfx 里的 `Program` ≈ RHIPipeline（shader 组合），但 bgfx**没有独立 PSO 对象**，混合 / 深度状态是 bitflag 动态拼接，内部隐式缓存 PSO。
- bgfx 的整套帧渲染流程（多个 View）≈ 引擎侧渲染管线。

> bgfx 把「引擎管线编排」和 RHI 层揉在一起，所以上层不需要单独写 RenderGraph。

### 12.5 常见坑点（重点，写进文档）

1. ❌ 不要把引擎渲染 Pass 和 RHIPipeline 绑定死
   同一个 Pass（例如 GBufferPass）可以切换不同 RHIPipeline（不同材质），一个 Pass 内部可以绑定多个 PSO，循环 DrawCall。
2. ❌ 不要每帧新建 RHIPipeline
   PSO 创建是重量级操作，RHI 必须维护`PipelineCache`，支持查找、预编译、异步加载。
3. ❌ 混淆 "管线状态" 和 "渲染任务顺序"
   RHIPipeline 只定义**怎么画**；引擎渲染管线定义**画哪些、先后顺序、读写哪些 RT**。
4. 多相机场景：**同一套 RHIPipeline 可以被多个 RenderView/Pass 共享**，不需要每个相机单独创建 PSO。

### 12.6 代码示例（Java，直观区分）

#### RHIPipeline（RHI 层，一次性创建，全局缓存）

```java
// 启动/加载阶段创建，只创建一次
RHIPipelineDesc gbufferPipelineDesc = new RHIPipelineDesc();
gbufferPipelineDesc.vertexShader = loadSPIRV("gbuffer.vert.spv");
gbufferPipelineDesc.fragmentShader = loadSPIRV("gbuffer.frag.spv");
gbufferPipelineDesc.inputLayout = meshInputLayout;
gbufferPipelineDesc.depthStencilState = DepthState.DEPTH_TEST_WRITE;
gbufferPipelineDesc.blendState = BlendState.NO_BLEND;
gbufferPipelineDesc.renderTargetFormats = List.of(RGBA8, RGBA16F, RGBA16F, D32_SFLOAT);

RHIPipeline gbufferPipeline = rhi.createGraphicsPipeline(gbufferPipelineDesc);
// 存入全局PipelineCache，后续复用
pipelineCache.put("gbuffer_opaque", gbufferPipeline);
```

#### 引擎渲染管线（引擎层，每帧构建 RenderGraph）

```java
// 每帧执行：引擎Deferred管线
public void renderFrame(Scene scene, List<RenderView> views) {
    RHIRenderGraph graph = rhi.beginRenderGraph();
    // 引擎管线：遍历所有相机视口，生成整套Pass链
    for(RenderView view : views) {
        view.buildRenderGraph(graph, scene);
    }
    graph.submit();
}

// RenderView内部，GBuffer Pass（引擎层Pass）
graph.addGraphicsPass("GBufferPass", pass -> {
    pass.setPipeline(pipelineCache.get("gbuffer_opaque")); // 复用RHI管线
    pass.addColorOutput(albedoTex, RHILoadOp.CLEAR, clearColor);
    pass.addDepthOutput(depthTex, RHILoadOp.CLEAR, depthClear);
    pass.setRenderCallback(cmd -> {
        // 在回调内，多次绑定不同RHIPipeline，绘制物体
        for(RenderObject obj : scene.opaqueObjects) {
            cmd.bindPipeline(obj.material.pipeline); // 切换RHI管线
            cmd.bindVertexBuffer(obj.vb,0);
            cmd.drawIndexed(obj.indexCount,1);
        }
    });
});
```

### 12.7 扩展设计：管线变体（可选，暂不采用）

> 决策（v0.7）：**本项目暂不采用变体系统**（复杂度高、收益小）。贴图差异用占位贴图 + uniform 分支解决（见第 16.3 节）。以下仅作为未来扩展思路记录。

引擎层若未来需要同一个 shader 的多种变体（带法线贴图 / 不带、开启阴影 / 关闭）：

- **变体属于引擎侧管理**，引擎维护变体列表；
- 最终每个变体，对应一条独立`RHIPipeline`，交给 RHI 缓存。

---


---

## 13. 架构调试性评估

> 一句话结论：**只要在设计时预埋调试接口，这套架构调试上限极高；但如果裸写、不加调试支撑，调试难度会远高于 bgfx 风格。**
> bgfx 优势：API 扁平，直接抓帧、简单渲染错误一眼定位；
> RenderGraph 方案：分层多、多了一层资源依赖图，**调试需要配套工具链，工具到位就很强，没有工具就很折磨**。

### 13.1 天然自带的调试优势（本架构的强项）

#### 1. RenderGraph 本身就是调试载体

RenderGraph 在 CPU 侧保存了**每帧完整资源读写关系、Pass 清单、资源别名信息**，可以直接导出：

- DOT 图 / Graphviz 渲染依赖图，可视化 Pass 先后、纹理读写、内存别名；
- 导出 JSON：每 Pass 名称、输入资源、输出资源、LoadOp/StoreOp、资源格式、屏障信息；
- 可以单步执行 Pass：开启调试模式，**一帧只跑一个 Pass**，看每个 Pass 输出 RT，快速定位是 GBuffer 错，还是光照 Pass 出错。

> bgfx 没有全局资源依赖记录，很难自动导出完整管线依赖图，只能靠抓帧工具看 CommandBuffer。

#### 2. 资源生命周期可追踪

RHI 层所有资源（RHITexture/RHIBuffer/RHIPipeline）都用句柄 + 内部资源表，可埋调试信息：

- 资源创建堆栈（Java 调用栈，记录在哪一行代码创建这个纹理）；
- 资源名称标记（`setDebugName("GBuffer_Albedo")`，对接 Vulkan Debug Utils）；
- 资源泄漏检测：帧结束统计未销毁资源，打印堆栈。

Vulkan 原生`VK_EXT_debug_utils`可以和 RHI 调试名打通，RenderDoc 抓帧时直接看到你命名的纹理、Pipeline，而不是一堆无名字的`VkImage`。

#### 3. 分层隔离，问题定位可以逐层缩小范围

故障分层排查路径：

1. 引擎层：RenderGraph 导出 JSON，检查 Pass 顺序、资源读写声明是否写错（**纯 CPU 逻辑，不用抓 GPU 帧**）
2. RHI 层：检查 RHIPipeline 创建是否成功、描述符 / 附件格式是否匹配
3. 底层 Vulkan：RenderDoc 捕获，看 CommandBuffer、图像屏障、Layout 转换

> 典型例子：画面黑屏
> - bgfx：直接去 RenderDoc 看 View 的命令，很难区分是**提交顺序错误**还是**资源依赖问题**；
> - RenderGraph 架构：先导出渲染图，一眼看到：LightingPass 在 GBufferPass 前面执行 → 直接定位上层依赖声明写错，不用打开 RenderDoc。

#### 4. 多视口 / 多相机场景调试友好

编辑器多视口，RenderGraph 里每个 Pass 都绑定归属 RenderView，导出信息自带视口标记。
可以单独关闭某一个 RenderView 渲染，单独渲染单个预览窗口，隔离多相机之间互相干扰的 bug。

### 13.2 这套架构带来的调试痛点（重点工程坑）

1. **两层命令模型：RenderGraph 构建阶段 + Pass 回调录制 Cmd，容易混淆故障层级**
   - 构建阶段：CPU 声明资源依赖；
   - Submit 阶段：RHI 内部拓扑排序、生成屏障、录制底层 CommandBuffer。
   > 坑：你写的 Pass 资源声明，和最终底层生成的 VkBarrier 不是同一个代码位置。**屏障自动生成，你看不到原始屏障代码，屏障 bug 会很难排查**。
   > bgfx 屏障是内部隐式生成，但没有 RenderGraph 拓扑层，少一层间接。

2. **RT 内存别名（内存复用）增加调试复杂度**
   RenderGraph 自动复用临时 RT 显存，多个逻辑纹理映射同一块 VkImage。
   在 RenderDoc 里面，会看到多个资源共用同一块内存，抓帧时容易混淆：
   > RenderDoc 显示同一块内存被多个逻辑 RT 覆盖，不看 RHI 导出的渲染图，很难区分逻辑资源。
   > 👉 解决方案：调试模式**关闭内存别名**，所有 RT 独立分配显存，Debug 配置和 Release 配置分开。

3. **CPU 构建图的开销，以及构建阶段的 bug，GPU 捕获工具看不到**
   RenderGraph 拓扑排序、依赖解析是纯 CPU 逻辑，RenderDoc 这类 GPU 抓帧工具看不到这一段。
   如果依赖图拓扑排序出错（Pass 执行顺序错误），RenderDoc 只能看到最终 GPU 命令，看不到**为什么这个顺序生成**。
   > bgfx 没有这一层 CPU 图构建，所有提交顺序直接编码在业务代码，RenderDoc 看到的顺序就是代码写的顺序。

4. 回调式录制命令，堆栈信息容易丢失
   DrawCall 录制在 Pass 回调闭包内，默认情况下 RenderDoc 捕获的 DrawCall，很难直接映射回上层引擎的 Pass 名称。
   需要手动埋入`DebugMarker`（Vulkan DebugUtils 的 Begin/End label），在 Pass 回调自动插入调试标记。

### 13.3 必须预埋的调试接口（RHI 设计时就要写进去，推荐直接进 RHI 规范）

> 建议写进 RHI 接口定义，Debug/Release 两套分支

1. 资源命名接口

```java
void setDebugName(RHIResource res, String name);
```

对接 Vulkan DebugUtils，所有 RHITexture/RHIBuffer/RHIPipeline 都支持命名。

2. RenderGraph 调试导出接口

```java
// 导出DOT图，Graphviz可视化
String renderGraph.exportDot();
// 导出JSON完整帧信息
String renderGraph.exportJson();
// 调试开关：禁用RT内存别名
renderGraph.setDebugDisableAliasing(true);
// 单Pass执行调试模式
renderGraph.setDebugSinglePass(String passName);
```

3. 自动插入 DebugMarker
   RHI 在 RHIGraphicsPass 开始 / 结束时，自动注入`vkCmdBeginDebugUtilsLabelEXT`，RenderDoc 捕获时，CommandBuffer 里直接按 Pass 分组。

4. Pipeline 校验调试
   RHIPipeline 创建时 Debug 模式开启严格校验：附件格式、输入布局、shader 接口匹配校验，提前抛出异常，而不是等到 DrawCall 才黑屏。

5. 帧捕获钩子
   RHI 层预留回调，一键触发 RenderDoc 捕获当前帧，不用切外部快捷键。

### 13.4 对比 bgfx 调试体验小结

|维度|RenderGraph+RHI（当前方案）|bgfx View+Encoder|
|---|---|---|
|上层管线逻辑调试|强，导出依赖图，CPU 侧就能排查 Pass 顺序|弱，依赖 GPU 抓帧|
|GPU 底层命令调试|需要埋 DebugMarker，否则间接性强|简单，命令直接提交，View 天然分组|
|多视口 / 复杂 Deferred 调试|优秀，可隔离单个 RenderView/Pass|一般，多 View 同步 bug 难定位|
|RT 别名内存调试|Release 强，Debug 模式要关闭别名才好排查|无内存别名，显存占用高，但简单|
|入门调试上手难度|高（需要理解两层抽象）|低，扁平 API|
|工具依赖|**强依赖自定义导出工具**|仅依赖 RenderDoc 即可|

### 13.5 工程建议

1. 编译配置区分：
   - Debug：**关闭 RT 内存别名、开启所有 DebugUtils、开启资源堆栈追踪、开启 RenderGraph 导出**；
   - Release：开启内存别名，关闭调试标记，降低 CPU 开销。
2. 开发流程：优先在 CPU 侧导出渲染图排查管线逻辑，**不要一上来就开 RenderDoc**；RenderDoc 只用来查底层 PSO、shader、屏障、资源布局 bug。
3. 最小化测试：写单元测试，单独跑单个 Pass，验证 GBuffer、后处理 Pass 正确性。

---


---

## 14. 可编排 Pass 体系与默认混合渲染管线设计

> 核心问题：**RenderGraph 可编排 Pass 体系，是否要内置一套默认混合渲染管线（Forward+ / Deferred 混合）**
> 一句话结论：
> ✅ RenderGraph 本身只做**通用 Pass 编排框架（不绑定任何渲染算法）**；
> ✅ 在引擎层，基于这套 RenderGraph，**提供一套开箱即用的默认混合管线（Forward+ + Deferred 混合，也就是常说的混合渲染管线）**；
> ✅ 用户可以完全替换这套默认管线，自己重新编排 Pass，实现纯 Forward、纯 Deferred、路径追踪等管线。

> 术语澄清：混合渲染管线 = 不透明物体走 Deferred，透明物体、半透明、粒子、UI 走 Forward 前向渲染；很多引擎叫**Deferred+Forward 混合管线**，UE、Unity HDRP 都是这套。

### 14.1 两层职责拆分（非常关键，避免耦合）

1. **RHIRenderGraph（RHI 层）：只做通用 Pass 编排框架，不内置任何渲染管线逻辑**
   RenderGraph 只提供能力：
   - 新增图形 Pass / 计算 Pass；
   - 声明资源读写、LoadOp/StoreOp；
   - 自动依赖拓扑、屏障、RT 内存别名；
   - Pass 回调录制 RHI 命令。
   **RHI 层不内置 GBuffer、光照、透明 Pass 这些业务逻辑。**
   RenderGraph 是通用编排器，它不知道什么是 Deferred、什么是 Forward，它只理解资源和 Pass。
   > 类比 UE：FRDG 只是编排框架，GBuffer / 光照 Pass 写在引擎渲染模块，不在 RHI 里。

2. **引擎渲染层（RenderPipeline 模块）：基于 RenderGraph 实现【默认混合渲染管线】**
   引擎内置一套完整、可插拔、可修改的混合管线：
   - 不透明物体：Deferred（GBuffer → 光照计算）
   - 半透明物体、粒子、植被、UI：Forward 前向渲染
   - 后处理链：Bloom、TAA、Tonemap、色差等
   这套管线由一堆预定义好的 Pass 组成：
   `GBufferPass` → `LightingPass` → `DepthCopyPass` → `TransparentForwardPass` → `PostProcessChain`

   > 引擎层对外暴露两种使用模式：
   > 模式 A：直接使用引擎默认混合管线，只改材质、光照参数，不用手动编排 Pass（适合普通场景）
   > 模式 B：完全接管 RenderGraph，删掉默认管线，自己从零增加 Pass，自定义整套渲染流程（适合定制渲染、管线实验）

### 14.2 默认混合管线的 Pass 编排结构（引擎内置）

```Plain Text
// 引擎默认混合管线，每帧构建RenderGraph
graph.addGraphicsPass("GBufferPass")
    .write(gbufferAlbedo, gbufferNormal, gbufferMaterial, depthTex)
    .read(sceneMesh, material)
    .callback(...) // 绘制不透明物体输出GBuffer

graph.addGraphicsPass("LightingPass")
    .read(gbufferAlbedo, gbufferNormal, gbufferMaterial, depthTex)
    .write(lightingRT)
    .callback(...) // 延迟光照计算，支持光源剔除

graph.addCopyPass("CopyDepth")
    .read(depthTex)
    .write(forwardDepthTex) // 拷贝深度，供给透明物体使用

graph.addGraphicsPass("TransparentForwardPass")
    .read(forwardDepthTex)
    .write(lightingRT, Blend=ON)
    .callback(...) // 半透明物体前向渲染，混合到光照RT

graph.addGraphicsPass("PostProcessPass")
    .read(lightingRT)
    .write(swapchainColor)
    .callback(...) // 后处理，输出到屏幕
```

编排特点：

1. 不透明走 Deferred，减少重叠片元开销；
2. 透明物体不能进 Deferred（多图层混合困难），切 Forward；
3. 深度缓冲拷贝：Deferred 的深度，提供给 Forward 透明物体做深度测试；
4. 后处理在最后统一执行。

### 14.3 可编排 Pass 的两种扩展方式

#### 方式 1：在默认管线基础上插入 / 替换 Pass（推荐，低成本扩展）

不改动原有整套管线，在指定位置插入自定义 Pass。
示例：在 LightingPass 之后、透明物体之前插入 SSR 反射 Pass

```Plain Text
默认顺序：GBuffer → Lighting →【插入SSR Pass】→ CopyDepth → Transparent → PostProcess
```

引擎预留钩子点：

- PreGBuffer
- PostGBuffer
- PostLighting
- PreTransparent
- PostTransparent
- PrePostProcess

用户可以在这些钩子追加自定义 Pass，不用重写整套管线。

#### 方式 2：完全丢弃默认管线，从零编排所有 Pass（完全自定义）

直接清空 Pass 列表，自己 addGraphicsPass，自由实现：

- 纯 Forward 管线
- 纯 Deferred
- Forward+（Tiled Lighting）
- 光线追踪管线
- 2D 管线

> 这就是 RenderGraph「可编排」的核心价值：**同一套 RHI+RenderGraph 底层，支持多种渲染管线切换**。

### 14.4 与 UE / Unity 对比

1. UE5
   - RDG 是通用 Pass 编排框架；
   - 引擎内置一套默认 Deferred 混合管线；
   - 可以用 RenderGraph 钩子插入自定义 Pass；也可以完全重写一整套管线。
2. Unity HDRP
   - SRP RenderGraph 做编排；
   - HDRP 内置混合管线（Deferred 不透明 + Forward 透明）；
   - 用户可注入自定义 Pass；也可以写全新 SRP 管线。

> 共同点：**编排框架和默认管线解耦，框架独立，管线是上层业务**。

### 14.5 工程约束 & 坑点

1. ❌ 禁止把 GBuffer、光照逻辑下沉到 RHI/RenderGraph 内部。RenderGraph 只负责资源依赖，不理解 PBR、光源、GBuffer。
2. 混合管线里**深度拷贝是典型开销点**：
   - 方案：用 RenderGraph 资源导入 + 子资源视图，尽量避免真实拷贝；
   - 或者利用 Vulkan 的图像布局转换，直接复用深度纹理做透明物体深度测试（需要注意读写冲突）。
3. 透明物体排序：属于引擎上层逻辑，**不属于 RenderGraph**。RenderGraph 只负责执行 Pass，物体排序、提交由引擎 RenderView 处理。
4. 管线切换：可以做配置开关，一键切换「默认混合管线 / 纯 Forward 管线」，两套管线复用同一套 RHI 和 RenderGraph。
5. 多视口：每个 RenderView 可以独立选择管线。比如主视口使用混合管线，材质预览窗口使用简化 Forward 管线。

### 14.6 API 设计示例（Java）

```java
// 方式A：直接使用引擎内置默认混合管线
RenderPipeline pipeline = new DefaultHybridRenderPipeline(scene, renderView);
pipeline.buildGraph(graph);

// 方式A扩展：在默认管线钩子插入自定义Pass
pipeline.addPassHook(PipelineHook.POST_LIGHTING, builder -> {
    builder.addGraphicsPass("SSRPass")
        .read(lightingRT, depthTex, gbufferNormal)
        .write(ssrRT);
});

// 方式B：完全自定义，抛弃默认管线
graph.reset();
graph.addGraphicsPass("CustomForwardPass", pass -> {
    // 自定义前向渲染逻辑
});
```

---


---

## 18. RenderGraph 图缓存复用（P1 补齐）

> 目标：解决「每帧构建渲染图 + 拓扑排序 + 字符串比较」的 CPU / GC 开销（第 11 节缺点 1 的正面对策）。
> 思路：**图结构稳定时，复用上一次的拓扑排序与屏障计划，只做轻量校验**。

### 18.1 问题分解

每帧固定开销来自：

1. Pass 对象创建（若每帧 new）→ GC 压力；
2. 资源依赖边构建 + 拓扑排序 → CPU 时间；
3. 屏障计划推导（每个资源每次状态转换）→ CPU 时间。

观察：**绝大多数帧的图结构不变**（相同 Pass 集、相同资源声明），只有数据（相机矩阵、物体列表）在变。

### 18.2 图缓存设计

```java
public class RenderGraphCache {
    private long lastStructureHash = -1;
    private CompiledGraph compiled;   // 复用对象：拓扑序 + 屏障计划 + Pass 执行列表

    public CompiledGraph compile(RHIRenderGraph graph) {
        long hash = graph.structureHash();   // Pass 类型 / 资源声明 / 依赖 的哈希
        if (hash == lastStructureHash && compiled != null) {
            return compiled;                 // 结构未变：直接复用（含屏障计划）
        }
        compiled = graph.compile();          // 首次 / 结构变化：全量编译
        lastStructureHash = hash;
        return compiled;
    }
}
```

- `structureHash()`：对每个 Pass 的（类型、资源读写集合、LoadOp/StoreOp、管线绑定）做确定性哈希，**不含运行时数据**（相机矩阵、物体列表不参与）；
- `CompiledGraph` 缓存：拓扑序列表、每资源屏障序列、RT 别名分配方案；
- 每帧仍执行一次轻量遍历（比对 Pass 清单哈希），O(Pass 数) 而非 O(依赖边²)。

### 18.3 屏障计划复用

- 屏障序列是图结构的纯函数：同一结构 → 同一组「资源 X 在 Pass A→B 之间从 layout1→layout2」；
- 复用后，帧内只做「写入时间戳 / 记录实际绑定」，不重新推导；
- 只有结构变化（新增 Pass、改 LoadOp、换资源格式）才重新推导，代价可接受（偶发帧内几 ms）。

### 18.4 Java 层对象池化

- Pass 构建对象池：`RHIGraphicsPass` 构建器对象循环复用（ArrayDeque 池），避免每帧 new；
- 边 / 依赖记录用 `int[]` 数组而非 `List<Edge>`，减少对象头开销；
- 拓扑排序用栈上 int 数组实现（非递归），避免栈溢出 + 对象分配；
- 帧级 `ByteBuffer` 从 FrameArena（17.3）分配，不触发 GC。

### 18.5 缓存失效时机（必须显式处理）

|变化|失效动作|
|---|---|
|Pass 集合 / 顺序变化|全量重编译|
|资源声明变化（读写 / 格式 / LoadOp）|全量重编译|
|管线切换（同一 Pass 换 PSO）|仅更新 Pass 的管线绑定，不重排拓扑|
|视口 / 相机参数变化|不影响结构，无需失效|
|Debug 单 Pass 调试（第 13 节）|强制重编译一次|

### 18.6 与 Debug 的交互

- Debug 构建**默认关闭图缓存**（每帧全量编译，便于单 Pass 调试与导出 DOT/JSON 对比）；
- Release 构建默认开启；提供 `rhi.setGraphCacheEnabled(boolean)` 开关做性能对照测试；
- 单元测试：同一结构编译两次，断言输出计划完全一致（幂等性）。

### 18.7 工程坑点

1. 哈希必须稳定且覆盖全部结构字段，**漏掉任一字段会导致屏障错误**（Debug 模式可加「结果校验」：与全量编译结果比对）；
2. 缓存命中时不重新生成 DOT/JSON（Debug 需要时强制刷新）；
3. 不要缓存跨帧的 Pass 回调闭包——回调内捕获的物体列表每帧变化，只缓存「执行顺序与屏障」；
4. 多相机动态增删 RenderView 会改变结构哈希，属正常失效，不做特殊优化；
5. 量化收益：结构稳定帧，CPU 侧构建开销应降到「哈希遍历 + 命令录制」量级；上线前用 JFR/JMH 测每帧耗时。

---


---

## 19. 多相机多角度渲染实现（综合示例，v0.5）

> 目标：演示如何用本设计实现「多个相机从不同角度渲染多个物体」。
> 核心一句话：**一个相机 = 一个 RenderView**（独立视口 + 独立 GBuffer/Depth）；所有相机共享场景资源；相机 / 物体矩阵写入同一帧级 UBO，用 dynamic offset 切换视角与物体。
> 综合引用：第 8 节（多相机分屏）、第 9 节（RenderView）、第 15 节（绑定模型）、第 16 节（PipelineCache）、第 18 节（图缓存）。

### 19.1 实现思路

1. **场景资源全局共享**：顶点 / 索引缓冲、材质管线（PipelineCache 全局持有）、物体 Descriptor Set（set 1，跨帧缓存）——所有相机复用同一批；
2. **每相机独立**：viewport / scissor、GBuffer×3 + Depth、相机矩阵在帧级 UBO 中独占一段偏移；
3. **视角切换 = dynamic offset**：Pass 绑定时传本相机偏移，同一个描述符集覆盖所有相机；
4. **并行 / 串行由写依赖决定**：独立输出 RT → GPU 并行；共享 swapchain 分屏 → 后渲染相机 LOAD 叠加、自动串行。

### 19.2 UBO 绑定模型澄清（先解决一个常见疑问）

> 疑问："UBO 不是一个着色器程序一个吗？"
> 澄清：**UBO 不属于着色器。着色器声明的是绑定接口（binding 插座），运行时把实际 buffer（数据）插上去；布局一致即可共享。**

```glsl
// shader 侧：声明"我在 set 0 binding 0 需要一块布局如下的 UBO"
layout(set = 0, binding = 0) uniform CameraBlock {
    mat4 viewProj;      // 所有需要相机矩阵的 shader 声明同一个块
} camera;
```

- 绑定的是「一段内存 + 布局」，不是「某个着色器的私有对象」；
- 只要 shader 声明的块布局一致，GBuffer / Lighting / Transparent 三套管线可读**同一块** buffer，只是从不同偏移读；
- 布局不一致的数据（材质参数 vs 相机矩阵）才需要各自的 buffer + 描述符集（set 2）；
- "一个程序一个 uniform"是 OpenGL 旧式 program 状态模型的直觉，Vulkan / DX12 是资源与着色器分离模型。

### 19.3 完整实现代码

```java
// ===== 每帧主流程 =====
public void renderFrame(Scene scene, List<RenderView> views) {
    // 1. 帧级 UBO（FrameArena 分配，见第 17.3 节）：每个相机独占一段，写入各自 viewProj
    RHIBuffer frameUbo = rhi.allocateFrameUbo(views.size() * CAM_UNIFORM_STRIDE);
    int offset = 0;
    for (RenderView view : views) {
        view.camUniformOffset = frameUbo.writeAligned(offset, view.camera.getViewProjMatrix());
        offset = view.camUniformOffset + CAM_UNIFORM_STRIDE;
    }

    // 2. 构建渲染图：每个 RenderView 生成整套 Pass 链
    RHIRenderGraph graph = rhi.beginRenderGraph();
    for (RenderView view : views) {
        buildViewPasses(graph, view, scene, frameUbo, frameSet);
    }
    graph.submit();   // 写依赖分析：独立输出并行，共享 swapchain 串行叠加
}

// ===== 单个相机视角：完整 Deferred Pass 链 =====
void buildViewPasses(RHIRenderGraph graph, RenderView view, Scene scene,
                     RHIBuffer frameUbo, RHIDescriptorSet frameSet) {
    final int camOffset = view.camUniformOffset;

    // —— GBuffer：从本相机视角绘制全部不透明物体 ——
    graph.addGraphicsPass(view.name + "_GBuffer", pass -> {
        pass.setPipeline(pipelineCache.get("gbuffer_opaque"));
        pass.addColorOutput(view.gbufferAlbedo,   RHILoadOp.CLEAR, CLEAR_BLACK);
        pass.addColorOutput(view.gbufferNormal,   RHILoadOp.CLEAR, CLEAR_BLACK);
        pass.addColorOutput(view.gbufferWorldPos, RHILoadOp.CLEAR, CLEAR_BLACK);
        pass.addDepthOutput(view.depthTex,        RHILoadOp.CLEAR, CLEAR_DEPTH);
        pass.setViewport(view.viewport);
        pass.setScissor(view.scissor);

        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{camOffset});  // 本相机视角
            for (RenderObject obj : scene.getOpaqueObjects()) {
                int objOffset = frameUbo.writeAligned(obj.modelMatrix);   // 物体级偏移
                cmd.bindDescriptorSet(1, objSet, 1, new int[]{objOffset});
                cmd.bindPipeline(obj.material.pipeline);                  // 材质管线全局共享
                cmd.bindVertexBuffer(obj.vertexBuffer, 0);
                cmd.bindIndexBuffer(obj.indexBuffer, RHIIndexType.UINT32, 0);
                cmd.drawIndexed(obj.indexCount, 1);
            }
        });
    });

    // —— Lighting：读本相机 GBuffer，输出本相机光照结果 ——
    graph.addGraphicsPass(view.name + "_Lighting", pass -> {
        pass.setPipeline(pipelineCache.get("lighting"));
        pass.addColorOutput(view.lightingRT, RHILoadOp.CLEAR, CLEAR_GRAY);
        pass.addTextureRead(view.gbufferAlbedo);
        pass.addTextureRead(view.gbufferNormal);
        pass.addTextureRead(view.gbufferWorldPos);
        pass.addTextureRead(view.depthTex);
        pass.setViewport(view.viewport);
        pass.setScissor(view.scissor);
        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{camOffset});
            cmd.draw(3, 1);   // 全屏三角形
        });
    });

    // —— Transparent：半透明物体混合叠加 ——
    graph.addGraphicsPass(view.name + "_Transparent", pass -> {
        pass.setPipeline(pipelineCache.get("transparent"));
        pass.addColorOutput(view.lightingRT, RHILoadOp.LOAD);   // 叠加，不清屏
        pass.addDepthOutput(view.depthTex, RHILoadOp.LOAD);
        pass.addTextureRead(view.depthTex);
        pass.setViewport(view.viewport);
        pass.setScissor(view.scissor);
        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{camOffset});
            for (RenderObject obj : scene.getTransparentObjects()) {
                int objOffset = frameUbo.writeAligned(obj.modelMatrix);
                cmd.bindDescriptorSet(1, objSet, 1, new int[]{objOffset});
                cmd.bindPipeline(obj.material.pipeline);
                cmd.bindVertexBuffer(obj.vertexBuffer, 0);
                cmd.drawIndexed(obj.indexCount, 1);
            }
        });
    });

    // —— Composite：本相机画面合成到目标（独立 RT / 分屏 swapchain 区域）——
    graph.addGraphicsPass(view.name + "_Composite", pass -> {
        pass.setPipeline(pipelineCache.get("composite"));
        pass.addColorOutput(view.targetTex, RHILoadOp.LOAD);   // 分屏用 LOAD 叠加
        pass.addTextureRead(view.lightingRT);
        pass.setViewport(view.viewport);
        pass.setScissor(view.scissor);
        pass.setRenderCallback(cmd -> {
            cmd.bindDescriptorSet(0, frameSet, 1, new int[]{camOffset});
            cmd.draw(3, 1);
        });
    });
}
```

### 19.4 关键设计点

1. **视角切换靠 dynamic offset，不用 setUniform 字符串**：多相机数据必须走 set 0 动态偏移（第 15.3 节）；`setUniform(String, float[])` 只留给单实例 uniform（时间、光源数）。原因：同一管线布局下 name→offset 必须一致，字符串机制无法表达"每相机不同实例"。
2. **每相机独立 Depth**（第 11 节坑点 1）：不能共用 DepthBuffer，否则相机 B 的遮挡会被相机 A 的深度污染。
3. **并行 / 串行自动决定**（第 8 节）：独立 RT → 无写依赖 → GPU 并行；共享 swapchain → 后渲染相机 LOAD → 自动插屏障串行。
4. **DrawCall 重复 N 次**（第 11 节缺点 2）：每个相机遍历一遍场景，先做视锥剔除（场景层），后续可扩展 single-pass multi-view。
5. **图缓存收益**（第 18 节）：相机集合与 Pass 结构稳定时 structureHash 不变，整套多相机 Pass 链 + 屏障计划跨帧复用；增删相机才失效。

### 19.5 工程坑点

1. `CAM_UNIFORM_STRIDE` 必须满足 `minUniformBufferOffsetAlignment`（常见 256B），用 `writeAligned` 统一对齐（第 15.7 节坑点 1）；
2. 多相机共享同一管线布局：所有 RenderView 的 Pass 使用同一组描述符布局（同一 PSO），否则描述符布局冲突（第 15.7 节坑点 2）；
3. 分屏视口重叠时顺序敏感：后渲染相机覆盖先渲染相机，按业务需求排序（如预览窗画在顶层）；
4. 相机增删改变结构哈希 → 图缓存失效属正常（第 18.5 节）；
5. 相机矩阵每帧写入帧级 UBO（FrameArena），**不经过 Cleaner**——它属于 RHI 内部帧调度资源（第 17.1 节设计边界）。

---


---

---

## 22. RenderGraph 职责分层（设计决策存档，v0.14）

> **决策**：图核心（依赖推导、屏障生成、拓扑排序、命令录制调度）**必须属于 RHI**；引擎层只提供图的**内容声明**（哪些 Pass、读谁写谁、顺序意图）。即 **「声明在引擎，推导执行在 RHI」**。

### 22.1 为什么图核心必须在 RHI（论据存档）

1. **后端特定知识**：屏障、布局转换（COLOR_ATTACHMENT → SHADER_READ_ONLY）、同步域全部是后端对象（VkImageLayout / VkPipelineStageFlags）。图核心若放引擎层，引擎必然接触 Vk*，直接违反第 2 节设计原则 1「上层零 Vulkan 句柄泄露」；
2. **多后端**：同步推导若在引擎层，换后端（Metal / DX12）时推导逻辑要重写 N 遍；在 RHI 则每后端各实现一份推导，引擎声明零改动；
3. **图缓存**：structureHash 缓存的是 RHI 的中间结果（拓扑 + 屏障计划），天然属于 RHI（第 18 节）；
4. **可验证性**：同步推导集中在 RHI 一处，才能统一断言、调试、缓存；散在引擎层无法集中验证。

### 22.2 职责分层（注意项）

|层|管什么|不管什么|
|---|---|---|
|引擎层|相机 / 剔除 / 渲染列表、Pass 意图（addPass 声明读谁写谁）、Pass 回调录制绘制、默认管线编排|屏障、布局转换、录制顺序、队列调度|
|RHI 层|图构建（依赖 / 拓扑 / 屏障 / 录制 / 调度）、图缓存、资源生命周期|相机逻辑、剔除、材质业务|

### 22.3 ResourceUsage 语义契约（注意项）

- reads / writes 声明必须用**语义化资源用途**（Attachment.Color / Sampled / Storage 等），**不得暴露 VkImageLayout**；
- 引擎永远不知道 Vulkan 的存在，RHI 负责把语义翻译成布局 + 屏障；
- 现有 desc.TextureUsage 是雏形，扩展为完整的 ResourceUsage 集合即可。

### 22.4 迁移纪律（注意项）

- 业务代码（相机、材质、场景）**禁止直接依赖 Renderer.commandBuffer()**，只准通过渲染管线 / 图 API 录制；
- 已有 Pass 若直接操作 CommandBuffer 写死了屏障 + 绘制，迁移时改为「声明读哪些纹理 + 回调里只录绘制」；
- 在迁移完成前，新代码不得进一步加深对 CommandBuffer 的直接依赖（防止重构成本累积）。

### 22.5 两套设计对照（简记）

- **文档设计（声明式 RenderGraph）**：复杂度收进 RHI，同步可推导、可验证、可缓存；适合多相机 / 编辑器 / 后期链；
- **命令式 RHI（如 javelin-rhi 工程）**：复杂度留给业务，透明可控、起步快；作为底层执行后端保留；
- **二选一结论**：以文档设计为引擎主架构；工程作为 RHI 底座（api / desc / types / vulkan 保留），重构集中在编排层（Renderer → 图提交、RenderingPipeline → 图构建器）。

---

> （注：部分内容可能由 AI 生成）
