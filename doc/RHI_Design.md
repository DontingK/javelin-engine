# RHI设计文档（Java + LWJGL Vulkan，声明式渲染图）

> 版本：v0.22
> 目标：上层引擎完全屏蔽 Vulkan 底层细节，上层只描述资源读写与 Pass 声明，RHI 内部自动处理屏障、队列、命令录制、同步。
> 模型：被动式渲染图（RenderGraph），适配单相机 / 多相机分屏场景

> 本文档为合并整理版，由以下内容合并而成：
> - 主文档：第 1~11 节（概述、设计原则、包结构、核心数据类型、相机与视口、清屏机制、单/多相机 Pass 示例、RenderView、接口定义、架构取舍）
> - 追加文档一：第 13 节《引擎侧渲染管线 与 RHI 管线的区分》
> - 追加文档二：第 14 节《架构调试性评估》
> - 追加文档三：第 16 节《可编排 Pass 体系与默认混合渲染管线设计》
>
> v0.2（P0 补齐）：新增第 15 节《资源描述符与绑定模型》；统一第 10 节 RHICmd 接口（补 bindPipeline / bindDescriptorSet / pushConstants）；明确 Vulkan 1.3 运行基线。
> v0.3（P1 补齐）：新增第 16 节《Shader SPIR-V 加载与 PipelineCache》、第 17 节《资源生命周期与内存管理》、第 18 节《RenderGraph 图缓存复用》。
> v0.4（生命周期模型修订，用户决策）：第 17 节取消引用计数，改为「Java 对象持有资源句柄 + 对象不可达时由 Cleaner 触发入延迟销毁队列」；Cleaner 职责范围限定为**上层引擎需要的资源**（纹理 / 着色器 / 缓冲等），RHI 内部资源不走 Cleaner。
> v0.5（新增实现示例）：新增第 19 节《多相机多角度渲染实现》——综合第 8/9/15/16/18 节，给出多相机多角度渲染的完整落地代码与 UBO 绑定模型澄清。
> v0.6（概念对齐 Unity，用户决策）：新增第 20 节《Shader 多 Pass 与 Material 实例模型》——Shader 对应多个 Pass（GBuffer/ShadowCaster/Transparent/DepthOnly），Material 是 Shader 的实例（参数 + 贴图），材质参数布局为 Shader 级、跨 Pass 共用。
> v0.7（用户决策：暂不采用变体系统）：变体相关内容降级为「可选扩展」——第 12.7 节标注暂不采用；第 16.3 节改为「0 变体 + uniform 分支」策略；第 20.5 节改为「变体：暂不采用」；ShaderPass 去掉变体维度字段。
> v0.8（用户决策：着色器文件语法）：新增第 21 节《着色器文件语法（.shader）》——Unity ShaderLab 简化版：保留 Properties / 状态命令 / FallBack 手感，去掉 SubShader 层，`Pass GBuffer {}` 块名即渲染阶段名，[Toggle] 走 uniform 分支。
> v0.9（语法修订，用户决策）：GLSL 代码改为 `vs { }` / `fs { }` 分离块（入口统一 main()，无需 #pragma），外链用 `vs = "file"` / `fs = "file"`。
> v0.10（语法修订，用户决策）：**GLSL 全部内嵌，不使用 `#include`**——构建工具将引擎内置块（camera / obj）与 Properties 生成的材质声明注入到 vs/fs 内的 `//@auto` 标记处；移除外链写法。
> v0.11（语法瘦身，用户决策）：**去掉 `FallBack`**（加载失败由引擎报错并替换内置默认）、**去掉 `Tags.Queue`**（排序完全归引擎）、**去掉 `[Toggle]` 语法**（开关属性用普通 Float + uniform 分支）。
> v0.12（记录存档，用户决策）：21.1 补充「为什么不需要 Tags.Queue」设计决策存档；新增 21.7 完整参考示例（PBR_Standard.shader）。
> 说明：第 12 节原文档暂缺，章节编号保留原文档编号。
> 拆分（v0.13）：本文档降级为**总纲**，正文仅保留第 1 / 2 / 11 节；其余章节按领域拆入 4 个分卷（见下方索引）。章节编号全局唯一，跨卷引用保持「第 X 节」写法即可。
> v0.14（设计决策存档）：新增第 22 节《RenderGraph 职责分层》——图核心（依赖推导 / 屏障生成 / 调度）属于 RHI，引擎只提供声明；ResourceUsage 语义契约；迁移纪律（业务代码不碰 CommandBuffer）。
> v0.15（新增章节）：新增第 23 节《渲染表面与呈现模型》——RenderSurface 是呈现目标（不一定是窗口：GLFW / Swing Canvas），由上层实现、RHI 消费；Surface / SwapChain 归 RHI；backbuffer 作为图终点。
> v0.16（补充）：第 23.4a 节新增初始化流程详解（阶段 0~5：呈现目标 / Instance / 物理设备选择（present 队列族检查）/ 逻辑设备 / Surface / SwapChain），含 resize 与 device lost 恢复路径。
> v0.17（新增章节）：新增第 24 节《帧执行与同步模型》——多帧在飞（maxFramesInFlight=2~3）；RenderGraph 执行器（LayoutTracker + 屏障插入算法 + 命令缓冲分配 + Pass 回调时机）；同步原语归属；提交模型（单命令缓冲 / 双队列 / 异步计算留 P1）；帧时序。
> v0.18（新增章节）：新增第 25 节《数据上传路径》——三类数据入口（一次性加载 / 每帧动态 / 运行期小更新）；内存类型两级选择（required/preferred）；Staging 池策略；上传 API；上传不走 RenderGraph 的衔接规则。
> v0.19（补充）：第 15.8 节新增《描述符池管理》——池按 set 布局类型 × 帧数预算环形复用；更新时序固定三步（构建期声明 / 帧首写入 / 录制期只绑定）；set1 动态 UBO 动态 offset 绑定；Bindless 预留。
> v0.20（新增章节）：新增第 26 节《API 全貌清单》——收拢 RHI 全部公开接口签名（入口 / 资源 / 描述符管线 / 队列命令 / RenderGraph / 呈现同步），作为实现规格书，每条注明出处。
> v0.21（修正）：第 3 节包结构补 render/ 子树（RenderSurface / Platform / Surface / SwapChain / SwapChainDesc / GLFWRenderSurface / SwingRenderSurface），对齐第 23 节呈现模型；修复编辑器转义乱码（`&#x20;`、`\_`）。
> v0.22（新增章节）：新增第 27 节《线程模型》——双线程起步（逻辑线程构建图声明 / 渲染线程 compile+execute+提交）；帧描述双缓冲交接；资源线程安全规则（销毁只走 Cleaner）；输入延迟预算；并行录制与异步加载留 P2。

## 章节索引（拆分后）

|章节|标题|所在文档|
|---|---|---|
|1|概述|`RHI_Design.md`（总纲）|
|2|设计原则|`RHI_Design.md`（总纲）|
|3|包结构|`RHI_Design_Core.md`|
|4|核心数据类型|`RHI_Design_Core.md`|
|5|相机与视口模型|`RHI_Design_Core.md`|
|6|清屏（LoadOp）机制|`RHI_Design_Core.md`|
|7|单相机多 Pass（Deferred 示例）|`RHI_Design_Pipeline.md`|
|8|多相机分屏多 Pass|`RHI_Design_Pipeline.md`|
|9|RenderView 上层封装（推荐）|`RHI_Design_Core.md`|
|10|接口定义补充|`RHI_Design_Core.md`|
|11|架构取舍与坑点|`RHI_Design.md`（总纲）|
|12|引擎侧渲染管线 与 RHI 管线的区分|`RHI_Design_Pipeline.md`|
|13|架构调试性评估|`RHI_Design_Pipeline.md`|
|14|可编排 Pass 体系与默认混合渲染管线设计|`RHI_Design_Pipeline.md`|
|15|资源描述符与绑定模型（P0 补齐）|`RHI_Design_Resources.md`|
|16|Shader SPIR-V 加载与 PipelineCache（P1 补齐）|`RHI_Design_Resources.md`|
|17|资源生命周期与内存管理（P1 补齐）|`RHI_Design_Resources.md`|
|18|RenderGraph 图缓存复用（P1 补齐）|`RHI_Design_Pipeline.md`|
|19|多相机多角度渲染实现（综合示例，v0.5）|`RHI_Design_Pipeline.md`|
|20|Shader 多 Pass 与 Material 实例模型（对齐 Unity，v0.6）|`RHI_Design_Shader.md`|
|21|着色器文件语法（.shader，v0.8）|`RHI_Design_Shader.md`|
|22|RenderGraph 职责分层（设计决策存档，v0.14）|`RHI_Design_Pipeline.md`|
|23|渲染表面与呈现模型（v0.15）|`RHI_Design_Pipeline.md`|
|24|帧执行与同步模型（v0.17）|`RHI_Design_Pipeline.md`|
|25|数据上传路径（v0.18）|`RHI_Design_Pipeline.md`|
|26|API 全貌清单（v0.20）|`RHI_Design_Core.md`|
|27|线程模型（逻辑线程 + 渲染线程，v0.22）|`RHI_Design_Pipeline.md`|

---

## 1. 概述

本 RHI 基于 LWJGL Vulkan 实现，采用**声明式渲染图架构**。
上层引擎不直接操作任何`Vk*`原生句柄，不需要手动管理：

- CommandBuffer、命令池
- Image/Buffer 内存、内存屏障、图像布局转换
- Semaphore / Fence、队列提交、跨队列同步

上层职责：

1. 创建资源（纹理、缓冲、管线）
2. 构建渲染图：添加 Pass，**声明资源读写依赖**
3. 在 Pass 回调中编写绘制指令（RHICmd 高层接口）

RHI 内部职责：

1. 收集所有 Pass 资源访问关系，构建依赖有向图并拓扑排序
2. 自动推导并插入管线 / 内存屏障、图像布局转换
3. 分配 CommandBuffer，在合适时机执行上层回调录制绘制命令
4. 自动分发任务到图形 / 计算 / 传输队列，自动处理跨队列同步
5. 资源生命周期托管（Java 对象持有句柄，Cleaner 兜底销毁 + 帧延迟销毁，见第 17 节）

> 术语区分
>
> - Pass：渲染图最小依赖单元，一个 Pass 内部可包含多条 DrawCall
> - DrawCall：`draw` / `drawIndexed`，绘制单个物体 / 实例，写在 Pass 回调内部
> - Camera：上层引擎对象，仅输出矩阵、视口信息，不属于 RHI 资源
> - RenderView：上层封装类，一个 RenderView 对应一个相机视口，自动生成整套渲染 Pass

## 2. 设计原则

1. **零 Vulkan 句柄泄露到上层**：上层禁止 import LWJGL Vulkan 原生包
2. **声明优先**：资源读写、清屏操作、视口参数在 Pass 构建阶段声明；不推荐在 RHICmd 回调内修改状态
3. **自动同步**：RHI 根据资源读写依赖自动生成屏障、布局转换
4. **资源复用**：渲染图支持临时 RT 内存别名，减少显存占用
5. **多线程友好**：渲染图构建阶段支持多线程 AddPass；提交阶段内部串行拓扑排序
6. **可扩展**：支持 Forward / Deferred，支持多相机、编辑器多视口、分屏渲染


---

## 11. 架构取舍与坑点

### 优点

1. 上层引擎完全隔离 Vulkan 底层，专注场景、相机、物体逻辑
2. 多相机编辑器视口代码复用性强，新增视口只需要新增 RenderView
3. RHI 自动屏障、自动队列调度，大幅减少同步 bug
4. 渲染图支持临时 RT 内存别名，降低显存开销

### 缺点

1. 渲染图依赖拓扑存在 CPU 开销，Pass 数量极多时性能下降
2. 多相机分屏独立 Deferred：物体剔除、DrawCall 会重复执行 N 次（N = 相机数量）

### 坑点清单

1. 多相机 Deferred 渲染，**每个相机必须拥有独立 Depth 纹理**，不能共用 DepthBuffer，会出现深度冲突
2. 同一张纹理被多个 Pass 写入，RHI 自动插入屏障强制串行执行
3. Vulkan NDC Y 轴向下，画面上下翻转由**投影矩阵**承担（见 RHI_Design_Core.md 161-168 节），RHI 视口直接透传，上层不需要处理视口 Y 翻转
4. RHICmd 对象不能被上层缓存，仅在回调执行期间有效
5. RHI 资源不能只靠 Java GC 释放 GPU 资源：上层丢弃引用后由 Cleaner 触发入延迟销毁队列，防止 GPU 资源泄漏（见第 17 节）

---

> （注：部分内容可能由 AI 生成）
