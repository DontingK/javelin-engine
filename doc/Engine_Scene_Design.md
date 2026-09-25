# 引擎场景设计（Engine Scene Design）

> 版本：v0.2（2026-09-25：2.4 节 Transform 拍板为 Component 子类）
> 说明：引擎侧场景设计文档（组件模型，对齐 Unity 心智），与 RHI 设计文档（`RHI_Design.md` 系列）配套。
> 职责边界：RHI 文档管"怎么渲染"（图、Pass、绑定、呈现、帧执行）；本文管"场景里有什么、每帧怎么变成渲染输入"。
> 关联章节：RHI 第 9 节 RenderView、第 19 节多相机、第 20 节 Material、第 27 节线程模型（帧描述）。

## 1. 设计目标与总则

- **组件模型（Unity 式）**：`Node` 是容器，`Component` 是行为/数据，`Scene` 是根。
- **渲染与逻辑分离**：Transform 树服务于逻辑（层级、动画、物理）；渲染不需要树——需要的是每帧排序好的扁平渲染列表。
- **线程边界（对齐 RHI 27 节）**：逻辑线程构建场景、更新组件、收集渲染列表；产出帧描述交给渲染线程。
- **命名**：不用 `GameObject`（与 Unity 心智解绑），统一为 **`Node`**——即场景图节点。

## 2. 核心类型

### 2.1 Scene（场景根）

```java
class Scene {
    String name;
    List<Node> rootNodes;        // Transform 树根（直接子节点）
    Environment environment;     // 环境光 / 雾 / 天空盒（P2）
    Light sun;                   // 主方向光（可空）
}
```

### 2.2 Node（容器，原名 GameObject）

```java
class Node {
    String name;
    boolean active = true;                    // activeSelf
    Transform transform;                      // 必有且唯一（构造时自动持有）
    List<Component> components;

    <T extends Component> T getComponent(Class<T> type);       // 线性扫描（组件少，够用）
    <T extends Component> List<T> getComponents(Class<T> type);
    Component addComponent(Class<? extends Component> type);   // 经 ReflectiveFactory 零反射创建（组件类须 @Reflective + 公开无参构造，AOT 安全）
    void setActive(boolean active);           // 级联子节点
    boolean isActiveInHierarchy();            // 自身 + 全部祖先 active
}
```

- `new Node()` 裸创建：**无场景归属**、不注册、不触发任何回调（纯数据构建）；
- 构造时自动持有 `Transform`（Unity 式，必有且唯一）。

### 2.3 Component（抽象基类）

```java
abstract class Component {
    Node node;                    // 所属节点
    boolean enabled = true;

    void onAwake() {}             // 首次激活（挂入场景时）
    void onEnable() {}
    void onStart() {}             // 首帧 onUpdate 前
    void onUpdate(float dt) {}
    void onDisable() {}
    void onDestroy() {}
}
```

- 用户自定义组件：继承 `Component` 覆写回调，**组件类须标 `@Reflective`（公开无参构造）**，由 `ReflectiveFactory` 零反射创建（GraalVM AOT，禁反射）；
- 内置组件：`MeshRenderer` / `Camera` / `Light`（见第 5 节）。

### 2.4 Transform（组件，每个 Node 必有且唯一）

> v0.1 原文为"独立类，不是组件"；**v0.2 拍板更正：Transform 就是 Component 子类**，
> 由 Node 构造时自动挂载（经 addComponent + ReflectiveFactory），
> 享受组件的生命周期与 getNode() 反引用，禁止重复添加。

```java
class Transform {
    Vector3 localPosition;
    Quaternion localRotation;
    Vector3 localScale;
    Transform parent;
    List<Transform> children;
    Matrix4 localToWorld;         // 缓存 + 脏标记
    // setParent / getChild / getParent / getWorldPosition ...
}
```

- **节点层级 = Transform 树**：`node.transform.children` 即子节点的 Transform；
- **脏标记**：仅当 local 变换变化时重算自身 world 矩阵并向下传播（子树级联更新，不每帧全量重算）。

## 3. 创建与挂入

### 3.1 两条路径

| 路径 | 用法 | 适用 |
|---|---|---|
| 一步式 | `scene.createNode("name")` | 单个简单物体 |
| 两步式 | `new Node()` 搭层级 → `scene.add(root)` | 层级结构 / 预制体实例化 |

- `scene.createNode(name)` = 创建 + 注册 + 触发挂入（一步完成）；
- 两步式：构建期纯数据搭好 Transform 树（不注册、无回调），提交期一次性挂入。

### 3.2 scene.add(root) 语义（两步式提交）

1. **递归**：`root` 及其 Transform 子树**整棵**加入场景（children 无需逐个 add）；
2. **归属检查**：节点已在其他场景 / 已在本场景 → 抛 `IllegalStateException`；
3. **生命周期**：先整棵树 `onAwake`（按层级序），再整棵树 `onEnable`（Unity 语义：Awake 全部先于 Enable）；
4. **组件登记**：`MeshRenderer` / `Camera` / `Light` 自动进入 RenderingSystem 注册表。

### 3.3 scene.remove(root) 语义

1. 递归注销整棵子树；
2. 触发 `onDisable` → `onDestroy`（按层级序）；
3. 从 RenderingSystem 注册表移除（该组件不再被渲染收集）。

## 4. 生命周期

```
scene.add(root)        → onAwake（整树，层级序）→ onEnable（整树）
首帧 onUpdate 前       → onStart
每帧                  → onUpdate(dt)（active 且 enabled 的组件）
scene.remove / setActive(false) → onDisable
销毁                  → onDestroy
```

- 执行者：**逻辑线程主循环**统一驱动（遍历场景活动节点，对齐 27.1）；
- 组件间 Update 顺序：暂按添加顺序（P2 支持优先级 / 依赖排序，对齐 Unity Script Execution Order）；
- `setActive(false)` 级联子节点：子节点 `isActiveInHierarchy()` 为 false，但自身 active 状态保留（恢复 true 可重新激活）。

## 5. 内置渲染组件

### 5.1 MeshRenderer（合并设计，无 MeshFilter）

```java
class MeshRenderer extends Component {
    Mesh mesh;                    // 共享资产引用（一个 mesh 多 renderer 复用）
    Material material;            // RHI 20 节材质实例（每 renderer 独立）
    boolean castShadow = true;
    boolean receiveShadow = true;
}
```

- **几何共享靠资产引用**（同一 Mesh 对象被多个 MeshRenderer 引用），**渲染差异靠材质实例**——不需要单独的 MeshFilter 组件；
- 未来 `SkinnedMeshRenderer` 对称扩展（自带 `SkinnedMesh` 字段），不依赖本设计。

### 5.2 Camera（对应 RHI 9 节 RenderView）

```java
class Camera extends Component {
    float fov, near, far;
    boolean isMain;
    // RenderView 由引擎内部生成并关联（视图矩阵 / 投影矩阵 / 视口）
    // 投影矩阵按 Vulkan NDC 约定生成（Y 向下、深度 [0,1]）——见 RHI 文档第 5 节坐标约定
}
```

### 5.3 Light

```java
class Light extends Component {
    enum Type { DIRECTIONAL, POINT, SPOT }
    Type type;
    Vector3 color;
    float intensity;
    boolean castShadow;
}
```

- 主方向光：`Scene.sun` 引用（或 `type == DIRECTIONAL` 的首个标记）。

## 6. RenderingSystem（引擎内部，非公开）

### 6.1 注册表

- `scene.add` / `addComponent` 时按类型登记：`renderers` / `cameras` / `lights`；
- `scene.remove` 时注销（增删 O(1)，不每帧遍历全场景）。

### 6.2 每帧收集（逻辑线程）

```
updateTransforms(scene)                  // 脏标记 → 重算 localToWorld（2.4）
for (Camera cam : cameras)
    for (MeshRenderer r : renderers)
        if (r.isActiveAndEnabled && frustumCull(cam, r))
            cam.view.renderList(opaque/transparent).add(toRenderObject(r))
```

- `RenderObject`：轻量引用（mesh + material + worldTransform 引用，不复制数据）；
- **剔除**：视锥剔除（CPU，每相机每帧）；遮挡剔除 P2；
- RenderList 按相机生成（多相机天然支持，对齐 RHI 19 节）。

### 6.3 每帧产物（帧描述，对齐 RHI 27.2）

- **RenderList**（每相机一份）：
  - `opaque`：按材质排序（减少状态切换）；
  - `transparent`：由远及近；
  - `shadowCasters`：主光源视角（阴影 Pass 用）。
- 帧描述 = 图声明 + 场景快照 + 各相机 RenderList → 渲染线程 compile + execute + 提交。

## 7. 与 RHI 文档的衔接

| 本文概念 | RHI 文档 | 关系 |
|---|---|---|
| Camera 组件 | 9 节 RenderView | 引擎将 Camera 绑定为 RenderView（视图/投影/视口） |
| 多相机 RenderList | 19 节多相机综合示例 | 每相机独立渲染列表 → 每 view 的 Pass 链 |
| MeshRenderer.material | 20 节 Material 实例 | 材质实例直接挂在渲染组件上 |
| 帧描述（6.3） | 27.2 帧交接 | 逻辑线程产出，渲染线程消费（领先 1 帧） |
| RenderingSystem | 24 节帧执行 | 收集产物是 Pass 回调的输入（view.renderList） |

## 8. 待定（P2）

- `.scene` 序列化格式：Node 树落盘（名字 / 组件 / Transform 值 / 材质引用），与 `.mat` 资产格式一起定；
- **Prefab 资产**：节点树模板 → 实例化（走两步式 `scene.add`）；
- 自定义组件分派优化：按接口分派 / 回调缓存（组件数量上升后再做；运行时反射已禁用）；
- 遮挡剔除、GPU Instancing、LOD；
- `Environment`（雾 / 天空盒 / 环境光）。

---

> （注：部分内容可能由 AI 生成）
