# 编辑器调试线设计（Editor DebugLine Design）

> 版本：v0.1（2026-10-23 拍板 + 首版实现 + 用户实测验收通过）
> 定位：**编辑器专属**世界空间调试线（对齐 Unity Gizmos.DrawLine 语义）——选中物体 mesh bounds 线框、相机视锥线框等；运行时游戏若将来需要，整套下沉 core（PickPass 反向路径，本档 §6 后置）。
> 配套：`doc/Engine_Picking_Design.md`（覆盖制 Pass 先例）、`doc/Engine_DebugText_Design.md`（双帧槽 buffer 先例）、`doc/Engine_RenderPipeline_Design.md`（Pass 编排 6.2）。

---

## 1. 定位与拍板

| 决策点 | 拍板（2026-10-23） | 理由 |
|--------|------|------|
| 落点 | **javelin-editor 专属，core 零改动**（用户拍板：编辑器才有） | 调试线是编辑器观感需求，裸引擎不带；GizmoPass 同为编辑器侧自建 Pass 先例 |
| 渲染路线 | **覆盖制自建 PSO + 管线插 Pass**（PickPass 同款），不走 DebugText overlay | 线是世界空间的，必须拿逐视口 VP（UBO1 viewProj 动态偏移）；overlay 只有 backbuffer 无相机 |
| 挂点 | `addPass(pass, "Transparent")` 命名插入 | 插在 TransparentForwardPass 之后、GizmoPass 之前——Gizmo 轴置顶语义不受影响（轴压线） |
| 深度语义 | **场景深度咬合**：LEqual 测试 + ZWrite Off，线被不透明几何遮挡（Unity Gizmo 观感；bounds 被物体自身裁一半是正常观感） | 备选「恒置顶」被否——bounds/视锥的遮挡关系本身是调试信息 |
| 生命周期 | **持续到 `clear()`**（DebugText 同语义），消费方每帧 `clear()` + 重提交 | 「跟随物体 + 选中才显示」（用户拍板）＝每帧重画：无绑定/通知机制，物体动/取消选中下一帧线即更新 |
| 显隐门控 | **选中驱动**：仅选中节点（含其组件）出线；相机视锥同样是选中 Camera 节点才画（与 Unity「所有相机常显视锥」有意分歧，语义更收敛） | 用户拍板「一般也只有选中的物体才会显示」 |
| 消费扩展 | **组件绘制器策略层**（用户设计）：`ComponentDebugLine<T>` 接口 + 按组件类型注册，新增组件类型零改消费方 | Inspector 注册器同模式（按类型分派） |
| 线宽 | **恒 1px** | Vulkan `lineWidth > 1.0` 需 wideLines 特性（桌面普遍不支持）；粗线后置走屏幕空间三角形扩张 |
| mesh bounds 画法 | **世界 AABB**（`Aabb.transform` 重新轴对齐，随旋转呼吸） | Unity 口径；且与将来视锥剔除判定盒同形态，画的=用的，呼吸是真实信息。OBB 后置加 `obb()` 便捷方法 |

## 2. 架构

```
DebugDrawLine（静态门面，editor.debug——Input/DebugText 门面范本）
    │  line(from,to,color) / aabb(worldAabb,color) / clear()——世界空间端点，持续到 clear
    ▼
DebugLineBatch（数据面：预分配 ByteBuffer 直拼 pos vec3 + RGBA8 = 16B/顶点、每线 2 顶点；
               容量 8192 线，超限截断 + 一次性 warn）
    ▲ 持有（构造注入）
DebugLineAppState（editor.debug，AbsEditorAppState）
    │  onAttach：建 batch + Pass + addPass(pass,"Transparent") + bind 门面 + 注册 drawers
    │  update：clear() → 选中节点组件查表分派 drawer.drawDebugLine(component, node)
    │  onDetach：removePass + unbind
    ▼
DebugLinePass（extends AbstractForwardPass，覆盖制）
    addToGraph：视口过滤（targetView 名）→ 颜色 LOAD + 深度 LOAD 咬合（MSAA 分支见 §3）
    回调：slot.update(batch) → set0(UBO1 viewProj 动态偏移) → bindVertexBuffer → draw

DebugLineRegistry（Class<T> → ComponentDebugLine<T> 精确类型匹配）
    └─ drawer 包：MeshDebugLine<MeshRenderer> / CameraDebugLine<Camera> / BoxColliderDebugLine<BoxCollider>
```

三段时序（与 DebugText「写网格→appendOverlay→回调」同构，引擎线程串行）：

1. **AppState.update**（renderFrame 前）：`clear()` + drawer 提交——batch 填好本帧线；
2. **addToGraph**（buildFrameGraph 内）：目标视口匹配则进图，选 PSO（按 MSAA 档）、`slot.update(0, batch)` 写入本帧双帧槽；
3. **回调**（图执行）：`bindDescriptorSet(0, frameSet, 1, {uboOffset}) + bindVertexBuffer + draw`——PSO 经声明期 `setPipeline` 已绑（RenderGraphImpl 346 自动 bindPipeline，DebugText 先例）。

双帧槽防的是 `Buffer.update` 无 fence 防护的已知短板（UBO1/DebugText 同款取舍：frameIndex % 2 轮转）。

## 3. 渲染设计

### 3.1 视口过滤与图结构

- Pass 持目标视口名（构造注入，编辑器 = `"Editor Camera Node"`，RenderView name = 相机节点名）；`addToGraph` 对非匹配视口直接 return——**GAME 视口（用户相机 view）天然无线**（Unity 口径：Scene gizmo 不进 Game view）；
- 匹配视口恒进图（空 batch 回调零 draw，DebugText 口径）——线出现/消失不扰动图缓存；视口集合变化（SCENE/GAME 切换）= 正常结构失效；
- GAME 模式下编辑器相机 view 不在图，Pass 无处进图，全场景零线。

### 3.2 深度咬合（两分支）

**非 MSAA**：`addColorOutput(ctx.colorTarget(), LOAD) + addDepthOutput(ctx.depth(), LOAD)`——1x 深度即场景深度（Opaque 写入、透明 ZWrite Off 不写），LEqual 咬合。

**MSAA**：场景深度在 `ctx.msaaDepth()`（多采样，不做 resolve；1x `ctx.depth()` 无场景内容，LOAD 是未定义值——**不能用在飞深度**）。附件采样数必须一致（验证层抓）：
`addColorOutput(ctx.msaaColor(), LOAD, null, ctx.colorTarget())`（resolve 终点= backbuffer，AVERAGE 全量 resolve 幂等正确，Engine_RenderPipeline_Design §5.3 同论证）+ `addDepthOutput(ctx.msaaDepth(), LOAD)`。PSO `sampleCount` = MSAA 档。

判定与 AbstractForwardPass.isMsaaPath 同口径（继承复用）。透明物 ZWrite Off → 线被透明物遮挡部分照常可见（不透明深度遗留），与主通道深度复用语义一致。

### 3.3 PSO（覆盖制，≤2 枚）

`GraphicsPipelineDesc`：LINE_LIST、CullMode NONE、Blend **Off**（v0.1 线不透明，alpha 渐隐后置）、depthTest On / depthWrite **Off** / LEqual、颜色格式 = backbuffer 格式、深度格式 D32_SFLOAT、set 布局仅 set0（`RenderingSystem.frameSetLayoutDesc()`，无材质 set2）、**pushConstantSize 0**、dynamic viewport/scissor、sampleCount 按视口档（缓存键 = SampleCount，惰性建）。顶点色走顶点属性（loc1）不走 push。

### 3.4 顶点与 buffer

- `loc0 pos vec3 (R32G32B32_SFLOAT) + loc1 color (R8G8B8A8_UNORM)` = **16B/顶点，每线 2 顶点**，LINE_LIST；
- 双帧槽 hostVisible VERTEX buffer（8192 线 = 256KB × 2 槽）一次分配，CPU 侧 batch 内预分配 ByteBuffer 复用；
- 容量超限截断 + 一次性 warn（运行期扩容后置）。

### 3.5 shader（资产导入链，2026-11-07 改向）

源码 `builtin-asset-editor/debugline/debugline.vert/.frag`（+ 伴生 .meta）经编辑器 `SpvShaderImporter` 编译 SPIR-V 落 Library，`DebugLinePass` 经 `AssetManager.loadAsset("editor/debugline/debugline.vert|.frag")` 装载（缺失/坏 stub 显性报错，文案 `debugLine.assetMissing`/`assetInvalid`）。vs：`set0 binding0 CameraBlock{mat4 viewProj}`（实例名 cam 与引擎注入模板一致）+ `loc0 vec3 aPos / loc1 vec4 aColor` → `gl_Position = viewProj * vec4(aPos,1.0)`；fs：直写顶点色。

> **已迁移（2026-11-07）**：~~javelin-tool `debugline/DebugLineAssets` CLI（GLSL → shaderc → `.spv`），
> 产物提交 `javelin-editor/src/main/resources/debugline/`，`DebugLinePass` 读 classpath `.spv`~~
> ——该「tool 内嵌 spv」路线整体清退（与 pick/DebugText 同批）：`DebugLineAssets`/
> `resources/debugline/`/`generateDebugLineSpv` 任务与 editor 侧 `.spv` 全部删除。
> 至此**内置资源迁移步骤二收官**（Skybox 档 §8）。

## 4. 绘制器策略层

```java
public interface ComponentDebugLine<T extends Component> {
    /** 世界端点在此算好提交门面；node 提供世界矩阵（显式传参免反向导航） */
    void drawDebugLine(T component, Node node);
}
```

- **DebugLineRegistry**：`Map<Class<?>, ComponentDebugLine<?>>` 精确类型匹配（`get(component.getClass())`，**不做继承链爬升**——复用走实现类内共享方法，查表隐式回退会让「哪个类画了这条线」不可见，Inspector 注册器同口径）；重复注册覆盖 + warn。泛型配对注册，unchecked cast 收口在查找方法一处。
- **一个节点多组件 → 全画**：逐组件查表分派，MeshRenderer + BoxCollider 各画各的。
- **drawer 无编辑器状态依赖**（CameraDebugLine 的 aspect 例外——见下），排除编辑器相机的门控不需要：编辑器相机节点在编辑子场景、不可被选中，选中门控天然排除。

首批三个（editor.debug.drawer）：

| 绘制器 | 画法 | 颜色 |
|--------|------|------|
| `MeshDebugLine`（MeshRenderer） | `mesh.getBounds()` 本地 Aabb → `Aabb.transform(世界矩阵)` 世界 AABB 12 边 | 绿（Unity collider/bounds 观感） |
| `CameraDebugLine`（Camera） | fov/near/far（或 orthoSize）× aspect 算 8 本地角点（相机 forward=+Z）→ 逐角点 `transformPosition(世界矩阵)` → 12 边（**OBB 式**——角点直乘矩阵，从任何角度看视锥形状都对） | 黄（Unity 相机 gizmo） |
| `BoxColliderDebugLine`（BoxCollider） | center±size/2 本地 8 角点 → 直乘世界矩阵 → 12 边（OBB 贴合，Unity collider gizmo 口径） | 绿 |

`CameraDebugLine` 的 aspect：构造注入 `JavelinEditor`（drawer 由 AppState 注册时传入）——`viewportRect` 比例 × 场景视口宽高比（`viewportWidth()/viewportHeight()`，与 screenToRay 的 targetWidth 同源；全屏 rect 时即视口宽高比）。这是唯一带编辑器依赖的 drawer（相机视锥的 aspect 语义上就是「渲染到哪个面」）。

**变换语义（用户问点存档）**：端点永远世界空间提交——旋转/缩放/平移被消费方「本地几何 × 节点世界矩阵」一次性吸收，门面/Pass 完全不感知变换；每帧重提交＝「跟随物体」无任何绑定机制（Gizmo 轴同原理）。

## 5. 生命周期与 GPU 资源销毁

- `onAttach`：场景未就绪 warn 跳过（EditGridAppState 同口径）；否则建 batch/Pass、`addPass(pass, "Transparent")`、bind 门面、注册 drawers；
- `update`：`clear()` → `EditorSelection.getSelectedNode()` 每帧直读（静态单选，不走事件订阅）→ 有效目标判定（仍属用户场景，TransformGizmo 同兜底）→ 逐组件查表分派；
- `onDetach`：`removePass` + 门面 unbind（发生在 shutdownAll，早于 waitIdle——**GPU 资源不在此 dispose**）；
- **GPU 资源（PSO/双帧槽 buffer）不显式 dispose**：编辑器 AppState 无 waitIdle 后钩子（PickSystem.close 是引擎侧硬编码位，编辑器 Pass 无对应挂点）；buffer 断引用交 Cleaner 两帧规则（DebugText resize 先例），PSO 断引用、进程退出由 device 销毁兜底——编辑器进程级生命周期、无中途卸载场景，无泄漏实害。将来运行时下沉 core 时改走 `Subsystem.closeGpu` 钩子。

## 6. 边界与后置

| 项 | 现状 | 后置方向 |
|----|------|---------|
| 落点 | editor 专属 | 运行时游戏需要时整套下沉 core（门面/batch/Pass 平移 + closeGpu 钩子，drawer 按消费方分家） |
| 粗线 | 1px 恒定 | 屏幕空间三角形扩张（两顶点法线挤出） |
| alpha 渐隐线 | Blend Off | 开标准 alpha blend（PSO 一处改动） |
| 线容量 | 8192 截断 warn | 运行期扩容（buffer 断引用重建，DebugText resize 同法） |
| OBB 便捷方法 | 无（bounds 画世界 AABB） | `obb(localCorners, matrix)` / `frustum()` 变体 |
| EDT 入队桥接 | 仅引擎线程（编辑器 AppState 全在引擎线程，无需求） | DebugText 同后置 |
| duration/depthTest 逐线重载 | 无 | Unity `Debug.DrawLine(...,duration)` 语义后置 |
| Ray 命中线/其他 gizmo | 无 | 消费方加 drawer 或直接调门面 |

## 7. 验收清单（用户实测）

1. 选中带 MeshRenderer 的物体出绿色 bounds 线框（12 边）；移动物体线跟随（每帧重画）、取消选中线消失。
2. 旋转物体：bounds 盒随旋转「呼吸」（世界 AABB 口径），非 OBB 贴合。
3. 选中带 Camera 的节点出黄色视锥线框（近/远矩形 + 4 侧边），任意角度观察形状正确；编辑器相机自身永不画锥。
4. 选中带 BoxCollider 的节点出贴合盒（随物体旋转，OBB）。
5. 遮挡：线被前方不透明物体遮挡（LEqual 咬合）、被物体自身裁一半；Gizmo 轴恒压线。
6. MSAA_8 开启：线照常显示且遮挡正确（msaaColor/msaaDepth 分支）；无验证层报错。
7. GAME 视图：不画任何调试线。
8. 运行日志：DebugLineAppState 初始化 + 首帧回调执行 + 截断 warn（超限测试）正常。

## 8. 文件清单

| 层 | 文件 |
|----|------|
| editor | `debug/DebugDrawLine.java`（新，门面）/ `debug/DebugLineBatch.java`（新）/ `debug/DebugLinePass.java`（新）/ `debug/DebugLineAppState.java`（新）/ `debug/DebugLineRegistry.java`（新）/ `debug/ComponentDebugLine.java`（新）/ `debug/drawer/MeshDebugLine.java`（新）/ `debug/drawer/CameraDebugLine.java`（新）/ `debug/drawer/BoxColliderDebugLine.java`（新）/ `JavelinEditor.java`（挂载一行） |
| tool | `debugline/DebugLineAssets.java`（新）+ `resources/debugline/debugline.vert|frag`（新）+ `build.gradle` generateDebugLineSpv 任务 |
| 产物 | `javelin-editor/src/main/resources/debugline/debugline.vert|frag.spv`（提交仓库） |

## 9. 落地顺序

1. ✅ 设计档——本文档（v0.1，2026-10-23）；
2. tool：GLSL 源 + DebugLineAssets + generateDebugLineSpv（编译产物提交 editor resources）；
3. editor：门面 → batch → Pass → Registry/接口 → 三 drawer → AppState → JavelinEditor 接线；
4. 验收样例：编辑器手工验收（§7 清单，验收状态归 Engine_Features.md 挂账）。

---

> （注：部分内容可能由 AI 生成）
