# 引擎渲染设计（Engine Rendering Design）

> 版本：v0.2
> 日期：2026-09-24
> 说明：引擎侧渲染交接专档——**场景怎么变成 RHI 的输入**：RenderingSystem 注册表与每帧收集、RenderView/RenderList/RenderObject/FrameDescription 字段级定义（逻辑线程与渲染线程的交接契约，RHI 27.2）、与 RHI FrameLoop 的调用关系、Y 翻转落点（已拍板：投影矩阵）。
> v0.2 变更（2026-09-25）：**场景渲染接入落地**——SceneRenderBridge pull 翻译层
> （Camera→RenderCamera、MeshRenderer→RenderObject，阶段②遗留接线关闭）；
> RenderObject 持 Material（4.2 内置无光照取舍关闭，材质桥 = Engine_Material_Design §4）。
> 职责边界：本文管**「收集与交接」**；Pass 怎么组织归 `Engine_RenderPipeline_Design.md`（消费本文产物）；场景组件模型归 `Engine_Scene_Design.md`（本文消费其 MeshRenderer/Camera/Light）；RHI 管**「图怎么执行」**（22 节「声明在引擎，推导执行在 RHI」）。

---

## 1. 设计目标与总则

1. **渲染与逻辑分离**（Scene 1 节）：Transform 树服务逻辑，渲染消费**每帧排序好的扁平渲染列表**——渲染层不遍历场景树。
2. **帧描述是数据不是执行**（RHI 27.2）：addPass 声明阶段不碰 GPU，帧描述可安全跨线程传递；v0.1 走**单线程退化模式**（27.1），但结构按双线程交接契约设计——未来启用渲染线程不改结构。
3. **引擎零 Vulkan**：所有 GPU 资源经 RHI 接口持有；销毁只走 Cleaner（RHI 17.1）。
4. 不改动 RHI 已发布 API 语义；缺口回 RHI 文档增订（Engine_Design 5 节）。

## 2. RenderingSystem（引擎内部，非公开）

### 2.1 职责

- **注册表**：`renderers` / `cameras` 两个表（lights 未实现）；v0.1 直注册模式由上层直接 add/remove，场景模式由 **SceneRenderBridge 每帧 pull**（getCameras/getRenderables → 翻译 → 同一套注册 API，阶段②拍板 2026-09-25，RenderingSystem 不感知 scene 包）；
- **每帧收集**（逻辑线程，mainLoop 渲染阶段前）：产出一份**帧描述**（FrameDescription）；
- **持有渲染侧常驻资源**：每视口 depth RT、UBO1 环形缓冲（随 FrameState 槽位）、内置着色程序（v0.1）。

### 2.2 每帧收集流程

```
collectFrame(frameIndex, backbuffer):
  0. SceneRenderBridge.sync(scene, rs, backbuffer)  // pull 翻译：注册/注销/同步相机与物体（collectFrame 之前）
  1. updateTransforms = Transform 脏标记惰性重算    // getLocalToWorld() 时触发，无独立遍历 pass
  2. for cam in cameras (isActiveAndEnabled):
       view = cam.renderView                        // 引擎 RenderView（第 3 节）
       view.viewport/scissor ← SceneRenderBridge 布局（多相机横向均分 backbuffer 宽高）
       frustumCull(cam, renderers) → view.list
       sort: opaque 按材质 hash（减少 PSO 切换）
             transparent 相机空间由远及近
             shadowCasters ← castShadow && 主光存在
  3. UBO1 写入本帧数据（相机/时间/灯光，第 6 节）
  4. 组装 FrameDescription（第 5 节）
```

- **剔除**：视锥剔除已拍板后置（2026-09-25，Aabb/FrustumIntersection 待优化阶段回加）；遮挡剔除 P2（Scene 6.2）；
- **排序**：opaque 排序键 =（材质 identity, layout hash，RenderObject.bucketKey）；透明按视图空间 Z 升序（由远及近）。

## 3. RenderView（引擎侧，对应 RHI 9 节）

> RHI 9 节的 RenderView 是「视图/投影/视口」的消费契约；引擎侧 RenderView 是**每帧为每个相机生成**的视图描述，字段级定义如下（RHI 文档只有概念，本文定引擎侧实体）。

```java
class EngineRenderView {              // 命名 RenderView，包 com.javelin.core.render
    Camera camera;                    // 来源组件（Scene 5.2）
    Matrix4 view;                     // transform.worldToLocal（世界 → 视图）
    Matrix4 proj;                     // Vulkan NDC 约定 + Y 翻转（第 7 节）
    RHIViewport viewport;             // 目标区域内视口（分屏 < 全屏）
    RHIScissor scissor;
    Texture targetOverride;           // 非空 = 渲染到纹理（编辑器），空 = backbuffer
    RenderList list;                  // 本视口渲染列表（第 4 节）
    // Deferred 阶段扩展：gbuffer/lightingRT 句柄（RenderPipeline 5 节资源规划）
}
```

- **生命周期**：帧级。每帧由收集流程重建（矩阵/视口/列表都变）；相机组件持有 stable 身份（name），跨帧资源（depth RT）按相机名索引复用；
- **多相机**：每个 active 相机一个 RenderView（RHI 19.1），各视口独立 Pass 链，共享 backbuffer 时 LOAD 叠加（RenderPipeline 4.7）。

## 4. RenderObject 与 RenderList

### 4.1 RenderObject（轻量引用，不复制数据）

```java
class RenderObject {
    Mesh mesh;                        // 资产引用（Engine_Assets_Design，多 renderer 共享）
    Material material;                // 材质实例（set 2 描述符集 / PSO 由渲染侧懒创建）
    Matrix4 world;                    // transform.localToWorld 引用（帧描述存活期内场景不修改）
    boolean transparent;              // 由材质前向 Pass 的 Blend 态得出（RHI 21.1 引擎决定队列）
    boolean castShadow;
}
```

### 4.2 v0.1 着色模型取舍（已关闭，2026-09-25）

- ~~材质系统缺位，直挂贴图内置无光照采样~~——**材质桥已落地**（Engine_Material_Design §4 全项实现）：RenderObject 持 Material；通道 = set0 UBO1（camera/time）+ push（model/tpf）+ set2 材质（MaterialBlock UBO + 贴图槽）；set1 为 ObjectBlock 空占位（per-object 走 push constants，RHI 20.4 编号）；
- 决策存档：原"内置无光照着色程序 + 单贴图 set1"临时形态已删除（`installBuiltinShaders`/`textureSet(Texture)` 移除，MultiCameraDemo 迁移材质路径）。

### 4.3 RenderList（每视口一份）

```java
class RenderList {
    List<RenderObject> opaque;          // 按 shaderKey/材质排序
    List<RenderObject> transparent;     // 由远及近
    List<RenderObject> shadowCasters;   // castShadow 子集（ShadowPass 用，实现后置）
}
```

## 5. FrameDescription（帧描述，RHI 27.2 交接契约）

```java
class FrameDescription {
    long frameIndex;                    // 单调帧号（CleanerRegistry 推进、UBO1 槽位同奇偶）
    Texture backbuffer;                 // 本帧终点（FrameLoop acquire 后注入）
    List<EngineRenderView> views;       // 全部活动相机视图（≥1）
    List<LightSnapshot> lights;         // 灯光快照（值拷贝，帧内稳定）
    Ubo1FrameSlot ubo1;                 // UBO1 本帧槽：buffer + 槽内偏移（第 6 节）
    float timeSec, tpf;                 // Time 快照（UBO1 内容来源）
    // 场景快照语义：RenderObject.world 引用 Transform 缓存矩阵——
    // 帧描述存活期内逻辑侧不得修改对应 Transform（双线程时由双缓冲保证，见第 8 节）
}
```

- **结构字段 vs 运行数据**：相机集合/灯光集合变化属结构变化（下游图缓存 structureHash 失效，RHI 18.5）；矩阵/颜色/时间只改数据；
- **灯光快照**：`LightSnapshot{type, color, intensity, direction/position(world), castShadow}`——值拷贝防帧内被逻辑侧改。

## 6. UBO1 收编（RHI 17.3 / 进度文档待办落地）

v0.1 把 PushUniformTest 里**测试内手写**的 UBO1 环形收编为引擎正式资源：

- **归属**：RenderingSystem 持有；按 FrameState 槽位（maxFramesInFlight=2）做 **2 槽 × 槽容量**的 hostVisible 缓冲，槽号 = `frameIndex % 2`，槽位复用前经 FrameState fence 闸门（RHI 24.5 帧版本化）；
- **槽容量**：单槽 = `对齐(相机块 + 灯光块 + 时间块, 256B)`；**槽内布局为引擎私有约定**（v0.1 字段顺序：camera viewProj → time → lights 数组头），着色器侧 `CameraBlock` 等布局随内置 shader 同改——属引擎/材质边界，材质专档落地时若重排需同步内置 shader；
- **写入时机**：collectFrame 第 3 步（逻辑侧帧首写，RHI 27.3-4：渲染侧只读绑定）；
- **动态偏移**：Pass 绑 set0 时传本槽偏移（RHI 19.4-1 机制，PushUniformTest 已验证）；
- 每相机矩阵不占独立段——相机矩阵**统一进 UBO1**（拍板：set0 UBO1 = 全帧共享；多相机时 viewProj 按相机序排在槽内，Pass 绑定时取对应偏移，等价 19 节 camUniformOffset 语义但并入引擎槽布局）。
- **push constants 形态拍板（2026-09-26 存档）：`model` + `tpf`，弃用早期原型的 `mvp` 形态**。理由：① 多相机下 mvp 需每物件×每相机在 CPU 重算重推，model 则每物件推一次、相机切换走 UBO1 动态偏移零额外推送；② mvp 形态着色器拿不到 model，法线变换/世界坐标/光照（三期 GBuffer 硬需求）不可行；③ 顶点侧多一次 mat4 乘法代价可忽略。早期骨架的 mvp push 见 git 历史 `ead65ab`。

## 7. Y 翻转落点（已拍板，2026-09-24）

- **投影矩阵翻转**：`proj.m[5] *= -1`（perspective 生成时 Y 项取负），视口**透传正高度**（RHI_Design 130 行已修正、PushUniformTest 已验证）；
- 生成点：相机组件 `recalculateMatrices()`（投影 + 视图），引擎 RenderView 构造时引用结果；
- 绕序配套：CCW 资产 + frontFace=CCW（三角形绕序实验结论，2026-09-23）；
- RHI 内部封装视口 Y 翻转（进度文档待办）**不改变本落点**——那是给裸用 RHI 的上层提供的便利，引擎侧始终走投影翻转。

## 8. 与 RHI FrameLoop 的调用关系

### 8.1 v0.1 单线程退化（27.1）

```
mainLoop 每帧（Engine_Design 2.1）：
  ... 逻辑更新（子系统）...
  scene.update(tpf)                 // 场景组件生命周期（JavelinEngine.setScene 设置后）
  FrameDescription desc = renderingSystem.collectFrame(frameIndex, backbuffer提供者)
  frameLoop.renderFrame(backbuffer -> renderPipeline.buildGraph(graph, PipelineContext.of(desc, backbuffer)))
  frameIndex++
```

- `backbuffer` 在 acquire 之后可得——FrameLoop.renderFrame 的 `Function<Texture, RenderGraphBuilder>` 签名天然支持：图声明回调内先做 SceneRenderBridge.sync（需 backbuffer 尺寸做视口布局）再 collectFrame 组装 PipelineContext，**acquire 先于图声明**（阶段⑥落地修正，RHI 24.1）；
- renderPipeline 来源：`RenderingSystem.renderPipeline`（默认 `DefaultHybridRenderPipeline`，模式 B 可换，RenderPipeline 6.1）。

### 8.2 未来双线程（P1，27.2/27.3）

- 帧描述双缓冲：逻辑帧 N 产出的 desc 入队，渲染线程消费 N-1；
- FrameDescription 引用 Transform 缓存矩阵（第 5 节注释）——双缓冲保证渲染侧读取期间逻辑侧写的是**下一轮**的 Transform；启用前需把 Transform 脏更新纳入快照语义（P1 时设计）；
- 单线程路径代码不改动，仅把「渲染帧」调用挪到渲染线程。

## 9. 与 RHI / 其他文档的衔接

| 本文概念 | 出处 | 关系 |
|---|---|---|
| 帧描述双缓冲 / 线程规则 | RHI 27 节 | 本文 FrameDescription 是 27.2 的字段级落地 |
| FrameLoop / FrameState / fence 闸门 | RHI 24 节 + core 实现 | UBO1 槽位复用与 backbuffer 时序的上游 |
| RenderView 消费契约 | RHI 9 节 | 本文引擎侧实体的对应物 |
| Pass 组织 / PipelineContext | `Engine_RenderPipeline_Design.md` | 本文 FrameDescription → 其 PipelineContext |
| MeshRenderer/Camera/Light 组件 | `Engine_Scene_Design.md` 5 节 | 注册表的数据源 |
| Mesh / VertexLayout | `Engine_Assets_Design.md` | RenderObject.mesh 的资产定义 |

## 10. 待定（P2）

- Transform 快照语义（双线程前置，第 8.2）；
- 遮挡剔除、GPU Instancing、LOD（Scene 8）；
- 材质系统接入后 RenderObject 字段替换（4.2）与 UBO1 槽内布局重排（第 6 节）；
- 相机后处理配置（每视口 PostProcessChain 差异）、环境/天空盒快照。

---

> （注：部分内容可能由 AI 生成）
