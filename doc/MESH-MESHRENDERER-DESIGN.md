# Mesh & MeshRenderer 设计文档

> 版本：v1.0
> 日期：2026-09-13
> 模块：javelin-core

---

## 1. 设计目标

为 Javelin Engine 5 提供完整的网格渲染能力：
- `Mesh`：纯几何数据容器（顶点 + 索引）
- `MeshRenderer`：组件，实现 `Renderable` 接口，将 Mesh + Material 转换为 `RenderCommand`
- `MaterialInstance`：运行时材质实例，管理 GPU 资源（UBO、DescriptorSet）
- `DescriptorPoolManager`：全局描述符池管理

---

## 2. 现状分析

| 已有 | 缺失 |
|------|------|
| `Renderable` 接口（`collect(List<RenderCommand>)`） | `Mesh` 类 |
| `RenderCommand`（持有 GPU 资源引用） | `MeshRenderer` 类 |
| `Material` / `Shader` / `ShaderPass` 资产 | `MaterialInstance` 运行时实例 |
| `VertexLayout` / `Buffer` / `GraphicsPipeline` RHI 接口 | `DescriptorPoolManager` |
| `ForwardRenderPass`（遍历 RenderCommand 提交 GPU） | UBO 更新机制 |
| `Vertex` 顶点数据结构 | |
| `MeshBuilder` 流式构建器 | |

---

## 3. 核心设计

### 3.1 Mesh — 纯几何数据容器

```
┌─────────────────────────────────────┐
│               Mesh                  │
├─────────────────────────────────────┤
│  CPU 数据（加载时填充）              │
│    - float[] vertexData             │
│    - int[]   indexData              │
│    - VertexLayout vertexLayout      │
│    - int vertexCount / indexCount   │
│    - IndexType indexType            │
├─────────────────────────────────────┤
│  GPU 资源（运行时外部创建）            │
│    - Buffer vertexBuffer            │
│    - Buffer indexBuffer             │
└─────────────────────────────────────┘
```

#### 3.1.1 顶点属性支持

Mesh 通过 `VertexLayout` 定义顶点格式，支持以下属性（由 `Vertex` 和 `MeshBuilder` 封装）：

| 属性 | 格式 | 大小 | 说明 |
|------|------|------|------|
| Position | `R32G32B32_SFLOAT` | 12B | 位置 (xyz) |
| Normal | `R32G32B32_SFLOAT` | 12B | 法线 (xyz) |
| UV | `R32G32_SFLOAT` | 8B | 纹理坐标 (st) |
| Color | `R32G32B32A32_SFLOAT` | 16B | 顶点颜色 (rgba) |
| Tangent | `R32G32B32A32_SFLOAT` | 16B | 切线 (xyzw, w=手性) |
| UV2 | `R32G32_SFLOAT` | 8B | 第二套 UV |
| BoneIndices | `R32G32B32A32_SINT` | 16B | 骨骼索引 (4个) |
| BoneWeights | `R32G32B32A32_SFLOAT` | 16B | 骨骼权重 (4个) |

#### 3.1.2 构建方式

**方式一：MeshBuilder（推荐）**
```java
Mesh mesh = new MeshBuilder()
    .withPositions().withNormals().withUVs().withColors()
    .vertex(-1, -1, 0).normal(0, 0, 1).uv(0, 0).color(1, 0, 0, 1)
    .vertex( 1, -1, 0).normal(0, 0, 1).uv(1, 0).color(0, 1, 0, 1)
    .vertex( 0,  1, 0).normal(0, 0, 1).uv(0.5f, 1).color(0, 0, 1, 1)
    .index(0, 1, 2)
    .build();
```

**方式二：Vertex 数组**
```java
Vertex[] verts = {
    new Vertex(-1, -1, 0).normal(0, 0, 1).uv(0, 0),
    new Vertex( 1, -1, 0).normal(0, 0, 1).uv(1, 0),
    new Vertex( 0,  1, 0).normal(0, 0, 1).uv(0.5f, 1),
};
Mesh mesh = Mesh.fromVertices(verts, new int[]{0, 1, 2});
```

**方式三：原始 float[]（外部工具/导入器）**
```java
Mesh mesh = new Mesh();
mesh.setVertexData(new float[]{ /* interleaved data */ });
mesh.setVertexLayout(new VertexLayout(...));
```

**职责边界**：
- Mesh 是**共享资产**，可被多个 MeshRenderer 引用
- Mesh 只存储几何数据，不创建/销毁 GPU Buffer
- Buffer 生命周期由外部（如导入管线或 AssetManager）管理
- 多个 MeshRenderer 共享同一个 Mesh 时，共用同一套 vertexBuffer/indexBuffer

---

### 3.2 MaterialInstance — 运行时材质实例

```
┌─────────────────────────────────────┐
│         MaterialInstance              │
├─────────────────────────────────────┤
│  源材质模板                          │
│    - Material material                │
│    - Map<String, Object> overrides  │
├─────────────────────────────────────┤
│  GPU 资源                            │
│    - Buffer uboBuffer               │
│    - DescriptorSet descriptorSet    │
│    - DescriptorSetLayout layout     │
├─────────────────────────────────────┤
│  方法                                │
│    - build(Device, pool, layout)    │
│    - uploadUboData()  ← 每帧调用     │
│    - setFloat/setVector4/setTexture   │
└─────────────────────────────────────┘
```

**设计要点**：
- `Material` 是**共享资产**，可被多个 MeshRenderer 引用
- 每个 `MeshRenderer` 持有独立的 `MaterialInstance`（运行时参数独立）
- `MaterialInstance` 引用共享的 `Material` 作为模板，通过 `overrides` 覆盖参数
- UBO 数据脏标记（`uboDirty`），变化时重新上传
- DescriptorSet 按 Pass 缓存（简化版先单 Pass）

---

### 3.3 MeshRenderer — 渲染组件

```
┌─────────────────────────────────────┐
│         MeshRenderer                │
│         (Component + Renderable)    │
├─────────────────────────────────────┤
│  引用                                │
│    - Mesh mesh                      │
│    - MaterialInstance materialInstance│
├─────────────────────────────────────┤
│  运行时缓存                          │
│    - GraphicsPipeline pipeline      │
│    - PipelineLayout pipelineLayout  │
│    - boolean pipelineDirty          │
├─────────────────────────────────────┤
│  方法                                │
│    - collect() → 生成 RenderCommand │
│    - buildPipeline() → 创建 Pipeline │
└─────────────────────────────────────┘
```

**渲染流程**：
```
MeshRenderer.collect()
  │
  ├─► 获取 Node.worldMatrix()
  ├─► 获取 Mesh.vertexBuffer / indexBuffer
  ├─► 获取 MaterialInstance.descriptorSet
  ├─► 确定 RenderLayer（OPAQUE / TRANSPARENT）
  └─► 构建 RenderCommand 加入列表

RenderCommand 被 ForwardRenderPass 执行：
  bindPipeline → bindVertexBuffer → bindIndexBuffer
  → bindDescriptorSets → drawIndexed
```

---

### 3.4 DescriptorPoolManager — 全局描述符池

```
┌─────────────────────────────────────┐
│      DescriptorPoolManager          │
├─────────────────────────────────────┤
│  配置                                │
│    - MAX_SETS = 1024                │
│    - MAX_UNIFORM_BUFFERS = 1024     │
│    - MAX_COMBINED_IMAGE_SAMPLERS = 512│
├─────────────────────────────────────┤
│  方法                                │
│    - allocateSet(layout)            │
│    - reset()  ← 每帧调用             │
│    - dispose()                       │
└─────────────────────────────────────┘
```

---

## 4. 类图

```
                    ┌─────────────────────────────┐
                    │         Asset（共享）          │
                    │  ┌─────────┐  ┌──────────┐   │
                    │  │  Mesh   │  │ Material │   │
                    │  └────┬────┘  └────┬─────┘   │
                    └───────┼────────────┼─────────
                            │            │
                            │ 引用       │ 引用（模板）
                            ▼            ▼
                    ┌─────────────────────────────┐
                    │        MeshRenderer           │
                    │  ┌────────────┐  ┌──────────┐ │
                    │  │    Mesh    │  │MaterialInstance│
                    │  │  (共享引用) │  │ (独立实例)   │
                    │  └────────────┘  └────┬─────┘ │
                    └─────────────────────────┼───────
                                              │
                                              ▼
                    ┌─────────────────────────────┐
                    │         GPU 资源             │
                    │  ┌─────────  ┌──────────  │
                    │  │ Pipeline│  │  Buffer  │  │
                    │  │(MeshRenderer│ (UBO/VB/IB)│
                    │  │  独立缓存) │            │  │
                    │  └─────────┘  └──────────┘  │
                    └─────────────────────────────┘
```

---

## 5. 关键设计决策

| 决策点 | 方案 | 理由 |
|--------|------|------|
| Mesh 是否持有 GPU Buffer | 持有引用，生命周期外部管理 | Mesh 是资产，可被多个 MeshRenderer 共享 |
| Pipeline 缓存位置 | MeshRenderer 内部 | 同一 Mesh + ShaderPass 的 Pipeline 可复用 |
| DescriptorSet 管理 | MaterialInstance 持有，DescriptorPoolManager 全局分配 | 每个实例独立参数，全局池避免碎片化 |
| UBO 更新 | MaterialInstance.uploadUboData() 每帧调用 | 材质参数可能每帧变化 |
| 模型矩阵传递 | PushConstant | 64-128 bytes 足够，无需 UBO |
| 材质参数传递 | UBO（Dynamic） | 参数数量多，UBO 更合适 |
| 多 Pass 支持 | 预留，先单 Pass | Shader 已有 Pass 列表，后续扩展 |

---

## 6. 待实现清单

### 6.1 核心类
- [ ] `com.javelin.core.render.mesh.Mesh`
- [ ] `com.javelin.core.render.material.MaterialInstance`
- [ ] `com.javelin.core.scene.component.MeshRenderer`
- [ ] `com.javelin.core.render.DescriptorPoolManager`

### 6.2 引擎集成
- [ ] `JavelinEngine` 中创建 `DescriptorPoolManager`
- [ ] `JavelinEngine.render()` 中调用 `MeshRenderer.buildPipeline()`
- [ ] `JavelinEngine.shutdown()` 中销毁 `DescriptorPoolManager`

### 6.3 UBO 布局
- [ ] 按 Shader Properties 自动生成 UBO 结构
- [ ] 支持 FLOAT、INT、BOOL、VECTOR2/3/4、COLOR、TEXTURE 类型

### 6.4 纹理绑定
- [ ] MaterialInstance 支持 `setTexture()`
- [ ] DescriptorSet 绑定 TextureView + Sampler

---

## 7. 使用示例

```java
// 创建 Mesh
Mesh mesh = new Mesh();
mesh.setVertexData(new float[]{...});
mesh.setIndexData(new int[]{...});
mesh.setVertexLayout(new VertexLayout(...));
mesh.setVertexCount(36);
mesh.setIndexCount(36);

// 创建 Material（从资产加载）
Material material = assetManager.loadAsset("materials/default.mat", Material.class);

// 创建 MeshRenderer
MeshRenderer renderer = new MeshRenderer();
renderer.setMesh(mesh);
renderer.setMaterial(material);

// 附加到节点
Node node = new Node("Cube");
node.addComponent(renderer);
scene.addRoot(node);

// 引擎自动收集 Renderable 并渲染
```

---

## 8. 附录

### 8.1 RenderCommand 字段映射

| RenderCommand 字段 | MeshRenderer 来源 |
|-------------------|-------------------|
| `pipeline` | `buildPipeline()` 创建 |
| `vertexBuffer` | `mesh.getVertexBuffer()` |
| `indexBuffer` | `mesh.getIndexBuffer()` |
| `vertexCount` / `indexCount` | `mesh.getVertexCount()` / `getIndexCount()` |
| `modelMatrix` | `node.transform().worldMatrix()` |
| `descriptorSets` | `materialInstance.getDescriptorSet()` |
| `renderLayer` | `determineRenderLayer()` |

### 8.2 枚举映射表

| 引擎层 (javelin-core) | RHI 层 (javelin-rhi) |
|----------------------|---------------------|
| `CullMode.Back` | `com.javelin.rhi.types.CullMode.BACK` |
| `CullMode.Front` | `com.javelin.rhi.types.CullMode.FRONT` |
| `CullMode.None` | `com.javelin.rhi.types.CullMode.NONE` |
| `CompareFunction.Off` | `CompareOp.ALWAYS` |
| `CompareFunction.Less` | `CompareOp.LESS` |
| `CompareFunction.LessEqual` | `CompareOp.LESS_OR_EQUAL` |
| `BlendFactor.One` | `BlendFactor.ONE` |
| `BlendFactor.SrcAlpha` | `BlendFactor.SRC_ALPHA` |
| `BlendFactor.OneMinusSrcAlpha` | `BlendFactor.ONE_MINUS_SRC_ALPHA` |
| `RenderLayer.Opaque` | `RenderLayer.OPAQUE` |
| `RenderLayer.Transparent` | `RenderLayer.TRANSPARENT` |
