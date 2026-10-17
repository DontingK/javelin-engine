# 引擎场景设计（Engine Scene Design）

> 版本：v0.3（2026-10-01：§9 .scene 序列化落地）
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
3. **组件登记**：`MeshRenderer` / `Camera` / `Light` 自动进入 RenderingSystem 注册表；
4. **生命周期不在此触发**——由 `update` 帧驱动状态机统一驱动（下帧 awake/enable，
   §4，2026-10-02 拍板）。

### 3.3 scene.remove(root) 语义

1. 递归注销整棵子树；
2. 即时触发 `onDisable`（按通知态）→ `onDestroy`（Unity 卸载同步语义——帧驱动
   生命周期唯一即时触发点）；
3. 从 RenderingSystem 注册表移除（该组件不再被渲染收集）；
4. 重置组件生命周期状态（awaked/notifyEnabled/started）——裸态可重挂，整轮重跑。

## 4. 生命周期

```
scene.add(root)        → 归属 + 注册（零回调，挂入生效）
挂入后首帧 update      → onAwake → onEnable → onStart（三遍序，见下）
每帧 update           → 状态机对齐通知态 → onUpdate(dt)（通知态开着的）
setActive/setEnabled 翻转 → 下帧 update 联动 onEnable/onDisable
scene.remove / 摘出场景 → 即时 onDisable（按通知态）→ onDestroy + 状态重置
```

- **帧驱动状态机（2026-10-02 拍板）**：生命周期回调统一由 `update` 三遍序驱动——
  遍 1 awake（不判激活，Unity: inactive 对象也执行 Awake）；遍 2 通知态对齐
  （应激活 = enabled 且层级激活，翻转触发 onEnable/onDisable——setActive 级联
  diff 递归已删除，setEnabled 回调联动缺口同步修复）；遍 3 start + update。
  三遍序保持 Unity「Awake 全部先于 Enable 全先于 Start/Update」的阶段序
  （挂入即时触发语义随本拍板移除，偏离 Unity 一帧：挂入/翻转下帧生效）；
- 执行者：**逻辑线程主循环**统一驱动（遍历场景活动节点，对齐 27.1）；
- 组件间 Update 顺序：暂按添加顺序（P2 支持优先级 / 依赖排序，对齐 Unity Script Execution Order）；
- `setActive(false)` 级联子节点：子节点 `isActiveInHierarchy()` 为 false，但自身 active 状态保留（恢复 true 可重新激活）。

## 5. 内置渲染组件

### 5.1 MeshRenderer（合并设计，无 MeshFilter；字段已落地 2026-09-25）

```java
class MeshRenderer extends Component {
    Mesh mesh;                    // 共享资产引用（一个 mesh 多 renderer 复用）
    Material material;            // RHI 20 节材质实例（每 renderer 独立）
    boolean castShadow = true;
    boolean receiveShadow = true;
}
```

- **几何共享靠资产引用**（同一 Mesh 对象被多个 MeshRenderer 引用），**渲染差异靠材质实例**——不需要单独的 MeshFilter 组件；
- `mesh`/`material` 为 null 时不渲染（missing 语义）；节点层级不激活或组件
  enabled=false 同样不渲染（Unity 口径，2026-10-05 拍板：SceneRenderBridge 翻译时
  统一过滤——setActive/setEnabled 只影响生命周期状态机藏不住渲染，此处是唯一
  关卡；2026-10-17 扩为 `isActiveAndEnabled`——组件级禁用同口径，编辑器取消勾选
  MeshRenderer 头仍渲染实锤，Camera 同步补齐；激活/禁用翻转即时注销防旧
  RenderObject 残留）；SceneRenderBridge
  每帧 pull 翻译（getRenderables → RenderObject），mesh/material 身份变化 =
  注销重注（v0.1）；
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

- ~~`.scene` 序列化格式~~ **已落地（2026-10-01，见 §9）**；材质引用的完整闭环仍与 `.mat` 资产格式一起定（§9.4 已知限制）；
- **Prefab 资产**：节点树模板 → 实例化（走两步式 `scene.add`）；
- 自定义组件分派优化：按接口分派 / 回调缓存（组件数量上升后再做；运行时反射已禁用）；
- 遮挡剔除、GPU Instancing、LOD；
- `Environment`（雾 / 天空盒 / 环境光）。

## 9. .scene 序列化（✅ 已落地 2026-10-01）

### 9.1 总体方案：全对象图直写直读，read 全量恢复零收尾

Capsule 序列化协议本身支持对象图共享/循环引用（IdentityHashMap + id 引用 + 两遍
populate），场景树的对象图回链**直接双向落盘**：`Component.node`（组件→节点）、
`Transform.parent/children`（父子双向）、`Node.scene`（归属→**导出根的 id 引用**）
——read 后对象图即完整，**无任何 post-populate 收尾修复**（序列化保持纯值语义，
read 只回写）。

**根引用走 id 不走 guid（框架层语义，2026-10-02 用户拍板）**：Capsule 的 Asset
分流（外部资产按 GUID 引用）对「引用到本导出根的 Asset 实例」改走对象 id——
`TextExporter/BinaryExporter` 记录根实例，Capsule 分流前判 `isRoot`（**按实例
同一性**，不用 guid 比较——规避 null guid 撞车与同 guid 异实例歧义）；读侧按
落盘标记分流，零改动。图内 Asset 引用的唯一实例即 Node.scene→GameScene；
MeshRenderer 的 mesh/material 是外部资产，guid 语义不变。

**注册表**：components 主表直传落盘（与 Node.components 引用同一批组件实例，
identity 去重共享保持；update 顺序=序列化时点）；cameras/renderables 为
instanceof 分流投影，read 时从 components 重建（renderables 元素接口 Renderable
未继承 Serializable 走不了列表泛型通道，分流表本身也是派生数据）。

**生命周期（拍板 2026-10-02，两步演进）**：生命周期回调统一由 update 帧驱动
状态机驱动（§4）——反序列化 read 零回调、生命周期状态（awaked/notifyEnabled/
started）不落盘，**update 首帧自然驱动 awake/enable/start**，与 read 全量恢复
方案无缝衔接（无需任何特例）；attachSubtree 幂等判据（node.scene 非空跳过）与
「已挂入」真态恰好自洽。编辑器场景（gameScene 不被 update 驱动）永不触发——
预期行为；游戏模式加载链路（SceneImporter）跑 update 即自然全链路。

（方案演进存档：ResolveAware 框架回调 → 显式 resolve 过渡 → 2026-10-02 拍板
read 全量恢复，前两者移除——注册表 read 重建后幂等判据自洽是收尾命题消失的关键。）

### 9.2 各类序列化数据面

| 类 | 落盘字段 | 不落盘（运行态） |
|---|---|---|
| Component 基类 | enabled + node（id 双向引用） | awaked/notifyEnabled/started（生命周期状态） |
| Transform | parent + children + local TRS | localMatrix/localToWorld/dirty（read 后 dirty=true 首帧重算） |
| Node | name/active/components（含 Transform）/transform + scene（根 id 引用）+ layer/tags 投影 | ObjectId.id |
| Camera | 全部用户字段（枚举走 name()；Color/Rect 直传） | — |
| MeshRenderer | mesh/material（资产引用）+ castShadow/receiveShadow | — |
| GameScene | super（guid/lastModified 内嵌）+ name + rootNodes + components 主表 | cameras/renderables 分流投影（read 重建） |

read 侧要点：Transform 直接回写 parent/children **不走 setParent**（不触发
onParentChanged 场景联动）；read 全量恢复后**零收尾**（无 resolve 类钩子）。

### 9.3 拍板记录（2026-10-01）

- **guid 内嵌**：GameScene.write 调 super.write 内嵌 guid/lastModified（对齐 Shader
  先例）；**真值源仍是伴生 .meta**——编辑器加载侧（JavelinEditor.loadScene）同步
  meta.guid 进 gameScene，保存时内嵌自述、双源一致（Mesh 的"不内嵌"口径不采）。
- **Tag/layer 投影存储**：tags 存名字符串列表（read 时 `Tag.of` 驻留恢复）、layer
  存 int 索引（read 时 `Layer.of` 查表 + 越界兜底 DEFAULT）。**红线 10 辨析**：
  Tag 是私有构造驻留值对象、Layer 是启动时从设置构建的静态注册表，都不是对象图
  节点——投影不涉及对象图引用同一性；read 侧静态查表不依赖任何嵌套对象 populate，
  时序永远安全。不违反红线 10 本意（红线针对对象图共享字段被包装切断）。
- ~~**生命周期**：resolve 走 attachSubtree 完整链路 fire onAwake/onEnable（Unity
  LoadScene 语义）~~（已被下条 2026-10-02 拍板覆盖）；started 不落盘由 update
  首帧补发 onStart（既有机制）。
- **read 全量恢复（2026-10-02 用户拍板，覆盖上条生命周期口径）**：scene 归属
  落盘为根 id 引用（框架 Asset 分流对根实例走 id）+ 注册表 components 直传/
  分流重建——read 纯回写零收尾，resolve/ResolveAware 移除。红线 10 辨析：
  cameras/renderables 的 instanceof 分流重建——元素实例在
  导入器列表填充阶段已存在，不解引用元素字段、不依赖嵌套 populate，时序安全
  （同 Tag/Layer 投影口径：红线本意防 populate 时序坑与对象图包装断裂，两者皆不涉）。
- **生命周期帧驱动（2026-10-02 用户拍板，同日接续上条）**：onAwake/onEnable/
  onDisable/onStart/onUpdate 统一由 update 三遍序状态机驱动（§4）——挂入/
  setActive/setEnabled 翻转**下帧生效**（偏离 Unity 即时语义一帧），onDestroy
  保持摘出即时（Unity 卸载同步语义，唯一即时触发点）；attachSubtree/
  onComponentAttached 瘦身为纯注册，fireActiveChanged 级联 diff 递归删除，
  setEnabled 不联动回调的原缺口同步修复；摘出重置生命周期状态（重挂整轮重跑）。
  与 read 全量恢复衔接：生命周期状态不落盘，恢复场景 update 首帧自然全链路。
- **文本通道**：编辑器 .scene 源文件走 TextExporter（文本协议，Editor_Design §7
  既有拍板）；文本/二进制的资产引用语义差异——文本侧 guid 为 null 时 @ref 内嵌
  完整对象，二进制侧 Asset 恒走 TYPE_ASSET（null guid 写 null 引用丢失）。
  **guid 引用读侧修正（2026-10-04）**：文本 guid 引用不带类名，字段声明类型是
  唯一类型来源——TextInputCapsule 曾硬编码 Asset.class 接口回解（ReflectiveFactory
  实例化接口即崩），场景首条文本 guid 引用实测暴露，改传调用方 type。
- **Mesh 产物自描述 guid（用户拍板 2026-10-04 终定：Mesh 序列化调 super）**：
  `Mesh.write/read` 调 `AbsAsset` super（guid/lastModified 内嵌产物）+
  `SimpleMeshImporter` 导出前 `mesh.setGuid(guid)`（取源文件头，与
  ShaderImporter 同款）——加载实例自带 guid，字段引用（MeshRenderer.mesh）
  走对象 guid 分流落 @guid，空壳坑（对象无 guid → 内嵌上传后 CPU 副本已
  释放的空 Mesh，Ctrl+S 两次实证）闭环。前史：09-26 曾拍板「write/read 不调
  super、产物不内嵌 guid」，当日 javadoc 误注「与 Shader 同款」（Shader 实
  调 super）——两格式分叉即空壳根因；10-04 加载侧盖章（回填对象）两度尝试
  被否、写侧反查方案实现后撤回，终定产物侧对齐。**旧格式产物无 guid 键**：
  super.read 优雅回 null（等价旧行为），删 Library 产物强制重导即迁移
  （plane 产物已删）。

### 9.4 已知限制与开放项

- **Material 内嵌数据面已通（2026-10-04 同日两步）、剩 .mat 资产格式**：上午
  NPE 暴露旧缺口——Material 无 write 时期重载读回 shader-null 残实例（原表述
  「读回 null 不渲染」与框架行为不符）流入 RenderObject.forwardPass 即崩；
  RenderObject 构造器显式判空（**用户拍板：残材质 = 文件损坏口径，显式报错
  不静默跳过——曾补的 SceneRenderBridge `getShader()==null` missing 判定按同
  拍板撤除，missing 语义仅覆盖 mesh/material 为 null**）；随即 **Material
  write/read 落地**根治——shader 走框架 guid 分流（guid 非空经 AssetManager
  解回）、values/textures 重构为条目列表直通（Capsule 无 Map 通道，
  MaterialValueEntry/MaterialTextureEntry，同 Mesh 拍板存储形态 = 序列化形态）
  ——内嵌材质保存重载完整渲染（plane 闭环，用户驱动：「保存时是怎么样，读取
  出来应该都是一样的」）。剩 **.mat 资产格式**：材质 guid 化共享/实例化、
  Inspector 编辑载体；
- **子树剪贴板（copy/paste）**：序列化数据面已通（Node 图 round-trip 成立），
  剪贴板机制随命令落地——导出前须置根 transform.parent=null（避免父链整棵拖进
  图）并清子树 node.scene=null（旧场景非导出根走 GUID 查不到恢复 null，粘贴
  setParent 挂接时经 attachSubtree 全新挂入—— GUID 为 null 的旧场景在文本通道
  会 @ref 内联，导出侧清引用一行避免）；
- **引擎 Game 模式 guid 链路**：`JavelinEngine.loadScene`（GameSettings.defaultSceneGuid
  → Library 产物）仍断——.scene 无 SceneImporter（不产 Library 产物），随
  「编辑器跑 Game」任务接线（引入 SceneImporter 或改直接读源文件，届时拍板）。

### 9.5 验收

**状态：✅ 2026-10-04 用户实测验收通过**（单测 + 编辑器「建树 → 保存 → 重启还原」）。

- 单测 `SceneSerializationTest`（javelin-core，二进制通道 round-trip）：树结构与
  双向回链 / guid 内嵌 / 投影恢复 / 归属 read 直恢复+注册表 read 重建+生命周期
  不 fire / Camera 全字段 / MeshRenderer 资产引用共享（stub AssetManager 按
  guid 解回同一实例）；
- 编辑器实测（文本通道）：建空物体树 → Ctrl+S 保存 → 重启编辑器 → 树完整还原
  + 无 dirty 星号。

---

> （注：部分内容可能由 AI 生成）
