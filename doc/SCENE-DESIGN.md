# GameScene 场景系统设计文档

> 本文档描述 `javelin-core` 中 `com.javelin.core.scene` 包的场景系统架构。
> 设计参考 Unity（Node + Component 组合模式），**不使用 ECS**。

## 设计目标

- Unity 风格的 Node / Component / Transform / GameScene 层次模型
- 明确的组件生命周期（onAttach / onEnable / onUpdate / onDisable / onDetach）
- 与现有 `Subsystem` 框架、`Renderable` 渲染收集机制、主循环时序无缝集成
- 销毁安全（允许在 onUpdate 中销毁任意节点）
- 为编辑器 Inspector 与后续场景序列化预留统一接口

## 类总览

```
SceneManager ──持有──▶ GameScene ──管理──▶ Node（根节点列表，可多根）
                          │
                          │  addRoot / update / collectRenderables / findNodesByName
                          ▼
Node ──恰好一个──▶ Transform（特殊 Component，承载层级关系）
  │
  │  addComponent / getComponent / setActive / destroy
  ▼
Component（抽象基类，每具体类型每 Node 最多一个实例）
  └── 子类：Transform、以及用户/引擎实现的组件（可实现 Renderable 参与渲染）
```

| 类 | 职责 |
|----|------|
| `Node` | 场景节点：名称、激活开关、组件列表、子级层级、销毁请求 |
| `Component` | 组件抽象基类：生命周期状态机与回调 |
| `Transform` | 特殊组件：局部 TRS、父子链接、世界矩阵脏标记缓存 |
| `GameScene` | 根节点管理、每帧组件更新、帧末销毁处理、渲染对象收集 |
| `SceneManager` | 活动场景管理（单活动场景），向引擎主循环提供统一入口 |

## Node

- 继承 `ObjectId`，全局唯一 int id，编辑器可经 `ObjectCache.get(id)` 反查
- 构造时自动创建并 attach 唯一一个 `Transform`
- **组件约束**：每个具体组件类型最多一个实例（Unity 语义），重复 `addComponent` 抛 `IllegalStateException`
- 层级：`addChild(Node)` / `removeChild(Node)` / `setParent`（经 Transform 委托）；reparent 时自动同步场景引用并刷新子树激活状态
- 查询：`getComponent(Class)`（支持父类/接口）/ `hasComponent` / `getComponents`（含 Transform）

## Component 生命周期

状态机由 Node/GameScene 驱动，子类只重写回调（全部空实现）：

```
addComponent        → onAttach → onEnable（节点处于激活层级时）
每帧 GameScene.update → onUpdate（处于激活执行状态时）
setEnabled(false)   → onDisable
setActive(false)    → onDisable（节点层级失活，递归整棵子树）
removeComponent     → onDisable（如已激活）→ onDetach
node.destroy()      → onDisable（如已激活）→ onDetach（帧末统一执行）
```

保证：

- `onAttach` 必先于首个 `onEnable`；`onDisable` 必先于 `onDetach`
- `onEnable` / `onDisable` 成对且不重复（内部 `activated` 状态迁移）
- 同一帧内新增的组件从下一帧开始 `onUpdate`
- **激活执行状态** = 已 attach && enabled && `node.activeInHierarchy()`，即 `isActivated()`

### Transform 是特殊 Component

与 Unity 同构：`node.getComponent(Transform.class)` 统一查询；由 Node 构造时自动创建，
**禁止手动 add / remove**（两处均有防护，抛 `IllegalStateException`）。

## Transform

- 局部 TRS：`localPosition` / `localRotation` / `localScale`（JOML 类型）
- 层级：一个 Node 恰好一个 Transform，父子双向链接由 Transform 维护；
  `setParent` 时 local TRS 保持不变（不做 keepWorld 换算），并拒绝成环
- **脏标记惰性更新**：
  - 任一 TRS setter → 立即重算 `localMatrix`，并向所有子孙递归传播 `worldDirty`
  - `worldMatrix()` 首次访问或脏时沿父链重算并缓存：`parent == null ? localMatrix : parent.worldMatrix() × localMatrix`
  - 通过 `localPosition()` 等返回的引用直接修改 JOML 对象后，必须调用 `markDirty()`
- 遍历便捷：`implements Iterable<Transform>`，直接子级只读迭代（`for (Transform child : transform)`）

## 激活状态

- `activeSelf()`：节点自身开关
- `activeInHierarchy()`：自身与所有祖先 active 取与
- `setActive(boolean)`：变化时刷新自身及**所有子孙**的组件 onEnable / onDisable
- GameScene.update 跳过整棵失活子树；collectRenderables / collectCameras 复用激活快照（失活组件已在快照重建时被过滤）

## 销毁安全

`node.destroy()` 不立即拆除：

- 已加入场景的节点 → 进入 GameScene 的 `pendingDestroy` 队列
- `GameScene.update()` 帧末统一处理：递归销毁子树，组件按 onDisable → onDetach 摘除，从父级和根列表移除
- 未加入场景的游离节点 → 立即拆除

允许在任意组件的 `onUpdate` 中销毁任意节点（包括自身），遍历使用快照（根节点 / 组件 / 子节点各一层拷贝），不会触发并发修改异常。

## 渲染集成

GameScene 不直接持有 `RenderList`，职责边界与 `Renderable` javadoc 一致。

**增量缓存 + 脏标记快照**（参照 javelin-engine4 Scene 设计）：相机（`Camera`）与可渲染对象
（`Renderable`）列表为增量维护缓存 —— Node 在组件增删（addComponent / removeComponent /
destroyNow）与场景挂接变化（`syncScene` 级联整棵子树，含跨场景 reparent）时同步向场景
注册/注销；其上再维护一份激活组件快照（activeCameras / activeRenderables）：

```
GameScene.collectRenderables(List<Renderable> out)
    → ensureCacheFresh()：cacheDirty 时从 allRenderables 过滤 isActivated() 重建快照
    → out.addAll(activeRenderables)
GameScene.collectCameras(List<Camera> out)   （同上，等价 engine4 filterActive 过滤语义）
RenderList.collectFrom(List<Renderable>)   （已有机制消费）
```

打脏时机：注册/注销成功置 `cacheDirty = true`；激活状态翻转统一汇聚于
`Component.updateActivation()`（setActive / setEnabled / 层级变化均经此路径），
翻转时调用 `GameScene.markCacheDirty()`。因此收集接口为 collect 风格复用输出列表，
无变化帧收集为 O(1) 且零分配，不做整树遍历，失活节点/组件自然从快照中消失。

组件实现 `Renderable` 即自动参与渲染收集；视锥剔除、Layer 剔除、排序仍由 `RenderList` 统一负责。

## 引擎主循环时序

`JavelinEngine.mainLoop()` 每帧执行顺序（与 `Subsystem` 文档约定一致）：

```
window.pollEvent
Time.update(deltaTime)
flushExecuteQueue                        ← 多线程任务汇入主线程
subsystemManager.earlyUpdate(dt)         ← 物理步进、输入预处理
sceneManager.update(dt)                  ← 组件 onUpdate + 帧末销毁
update()                                 ← 子类游戏逻辑
subsystemManager.update(dt)              ← AI、游戏逻辑
subsystemManager.lateUpdate(dt)          ← 物理同步、相机跟随
renderer.beginFrame()                    ← 失败（交换链重建）则跳过本帧
subsystemManager.preRender()
render()                                 ← 引擎渲染
subsystemManager.render()
subsystemManager.postRender()
renderer.endFrame()
```

关闭顺序（`shutdown()`）：场景与子系统在 **GPU 资源销毁之前** 关闭：

```
flushExecuteQueue
sceneManager.shutdown()                  ← 场景 dispose
subsystemManager.shutdownAll()
renderer.dispose() → swapChain.dispose() → rhiContext.dispose()
GPUResource.flushDisposals()
```

## 线程模型

GameScene / Node / Component / Transform **仅主线程操作**，不做内部加锁。
其他线程通过 `JavelinEngine.submitToExecuteQueue()` 将任务汇入主线程后再触碰场景。

## 扩展点

- **自定义组件**：继承 `Component`，重写需要的生命周期回调；需要渲染则同时实现 `Renderable`
- **序列化钩子**：组件可实现现有 `Serializable` 接口 + `@Reflective` 注册，场景级序列化器（.jscene）为后续迭代

## 后续规划（范围外）

- 内置组件：Camera、MeshRenderer、Light 等
- 场景序列化（.jscene）与 `ReflectiveFactory` 反序列化加载
- 多场景 additive 加载（SceneManager 已预留接口形状）
- 视锥剔除 / Layer 掩码剔除（RenderList 层）
