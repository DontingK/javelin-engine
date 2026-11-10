# 引擎资产热重载设计（Engine_AssetReload_Design）

> 版本：v0.6（2026-11-10 **材质 shader 换绑改落共享实例**（用户实测验收通过）——检查器 `onShaderCommit` 引擎帧首原位 `setShader`（不再靠 `.mat` 进 reload 链换指针），收窄口径与 targetClass 不变）
> 拍板（2026-10-24，用户）：**反射遍历流派（UE ReloadPackages 口径本地化）**——持有引用形态不动（Material.shader / MeshRenderer.material 继续死攥对象），重导后由重载协调器**反射遍历场景对象图**找到持有旧实例的 Asset 字段、换新实例指针。v0.1 令牌表方案（持有者钩子 + 依赖登记表）作废。
> 拍板（2026-10-29，用户）：**热重载范围收窄只 shader**——判据 = 文件是真值源 + 渲染侧按实例身份烤死。Shader（文件源 + set2 布局/UBO 尺寸/PSO 缓存键按身份烤死）必须 reload；.mat（编辑=内存直改即时生效——数值参数 UBO 每绑重打包/面型活读，文件是镜像）、.texture/.smesh/像素源（GPU 侧上传烤死须重上传，材质/网格实例换代后 reconcile 重建的基建已在）**摘出 reload 链，后面再加**。walker 基建全保留（shader 换代的 Material.shader 换指针 + 补默认值联动照旧）。
> 关联档：Engine_Material_Design.md §3/§4（材质桥与序列化）、Engine_Rendering_Design.md（RenderingSystem 职责）、Editor_Design.md §1（设计基准=完全参考 Unity）、Editor_Menu_Design.md（project.reimport）。
> 拍板（2026-10-30，用户）：**Walker 流派自「沿场景对象图递归遍历」改「编辑器对象注册表全量扫描」**——`ObjectId` 增构造回调 registrar（默认 null，构造路径零开销），`EditorObjectRegistry` 编辑器启动装上、以弱引用收拢全部存活 `ObjectId`（Node/Component/AbsAsset/材质条目）；Walker 只扫单对象字段、**不沿图递归**，可达性交给注册表，覆盖范围不再依赖 GameScene 可达性（Inspector 缓存实例、场景外持有者一并覆盖）。配套两处：**换指针优先走同类型 setter**（取代原「反射直写字段、绕过 setter」口径，§7 旁路警示作废）；**Material 补默认值后处理退回 `Material.setShader` 单点**（§4.3 改口）。同日：**导入完成回调总线化**——`AssetImportPipeline` 删除 `ImportListener` 内部接口，改经 `EditorEventBus` 发 `AssetImportEvent`（§5.2）。

---

## §1 目标与非目标

**目标**：编辑器内修改 `.shader` / `.mat` / 贴图源文件 → 触发重导 → 画面实时更新，且编辑器状态全程不丢（选中、Gizmo、编辑器相机位姿、进行中的未保存场景修改）。

**非目标**：
- 游戏运行时热更——重载协调器在编辑器模块，不进 AOT 镜像（反射无 GraalVM reflect-config 代价）；core 侧唯一新增（invalidate/reload）不在热路径；
- WatchService 后台监听（后置，Windows 上编辑器多段保存误触发坑，见 §5.1）；
- 跨机协作同步、资产删除联动（§7 留白）。

---

## §2 现状盘点

### 2.1 已就位的部分（本设计基本不动的）

1. **wire 层 guid 令牌早已完成**：资产间引用序列化即 guid——`Material.write` 的 `shader` 字段、`MeshRenderer.write` 的 `mesh/material` 字段走框架 guid 分流；`read` 经 `BinaryImporter.loadAsset(guid, type)`（`BinaryImporter.java:134`，二进制路径）与 `TextInputCapsule`（文本路径，loadScene 主路径）解回实例。**存储格式与运行时持有形态均零改动**。
2. **渲染链对「新实例」天然自适应**：`SceneRenderBridge.sync` 对 `MeshRenderer` 的 mesh/material **身份变化 = 注销重注**（`SceneRenderBridge.java:161`）——组件材质指针一换，下帧自动 `removeRenderer` + `addRenderer` 重建材质资源；`pipelineFor` 的 key 含 `identityHashCode(shader)`，新 Shader 实例 = 新 key = 新建 PSO。渲染侧重接线**不需要新协议**，需要的只是旧资源回收（§2.2-3）。
3. **贴图/Mesh 的 GPU 孤儿已有出口**：GPU 句柄回填资产本体（`TextureUpload.upload` 幂等、`texture2D.getGpu()`），实例被换后旧实例 GC 不可达 → RHI Cleaner 兜底销毁（Vulkan_Pitfalls #24 修后可靠）。**此路不泄**。
4. **导入链已有**：mtime 比对增量（`needImport`）、异步导入管线（`AssetImportPipeline`）+ `ImportListener`、手动强制重导（`project.reimport`，绕过 mtime 比对覆盖写产物）。
5. **材质数值即时生效**：`materialSet()` 每次调用重打包 MaterialBlock 写 UBO——Inspector 材质编辑通道（遗留项）接入后数值实时是白送的。

### 2.2 卡点（本设计要解决的）

1. **弱缓存被强引用顶住**：`AssetLibManager` 的 guid 缓存是 WeakReference，但场景对象图强持旧实例——外部重导覆盖 Library 产物后，`loadGuid` 永远命中内存里的旧活实例，盘上新产物读不到。且无 `invalidate` 口。**（与持有形态无关，反射流派同样必须解）**
2. **引用死攥且无失效感知**：持有者不知道资产换了，需要有人替它换指针——反射 Walker 即此角色。
3. **RenderingSystem 缓存只进不出**：`materialResources` / `materialLayouts` / `materialLayoutDescs` / `pipelines` 仅 `close()` 清理——换实例后旧条目全部变孤儿，每次热刷泄一套 GPU 资源（PSO/描述符集/UBO）。
4. **触发缺位**：无焦点自动扫描（`tools.refresh` 已随去 Web 化退役），无 watcher。

---

## §3 总体架构

反射遍历流派三层分工：

```
┌─ core 机制层（仅一件）── AssetLibManager：invalidate(guid) / reload(guid)
│                          （丢弱缓存条目 → 重读盘上产物，返回 old/new 实例）
├─ 编辑器重载层 ─────────── AssetReloadService：订阅导入事件（总线）→ reload 得新实例 →
│                          Walker 扫对象注册表快照换 Asset 字段指针（setter 优先）→
│                          renderingSystem.requestReconcile()
└─ 触发层 ──────────────── 焦点扫描（Unity Auto Refresh 口径）/ 手动 project.reimport
```

分工原则：core 只提供「拿到新实例」的机制（丢缓存+重读），**不做任何持有者感知**（无依赖登记表、无钩子接口——v0.1 的两样全废）；换指针的知识（哪些对象、哪些字段）由编辑器侧反射 Walker 在重导时现场获得——**v0.5：可达性由编辑器对象注册表承担（全量快照，不依赖场景图可达），Walker 只负责单对象的字段替换**（UE 口径的「现场判别」保留，遍历范围改由注册表给出）。

---

## §4 契约

### 4.1 AssetLibManager / AssetManager 扩展

```java
// AssetManager 接口新增
void invalidate(String guid);   // 丢弱缓存条目（cache/guidAssetRefMap/trackedRefs 同步清），
                                // 下次 loadGuid 走盘读新产物

// AssetLibManager 具体类新增（编辑器侧已有具体类引用先例：ProjectAssetLibManager）
ReloadResult reload(String guid);   // invalidate + loadGuidInstance 重读，返回 {old, new}
```

**此层与持有形态完全解耦**：无论谁持有、持多久，reload 只负责「下次能拿到新实例」。

### 4.2 反射 Walker（编辑器侧，cn.donting.javelin.editor.asset.reload）

**入口**：`EditorObjectRegistry.snapshot()`——编辑器对象注册表产出的全部存活对象快照。注册表经 `ObjectId.setRegistrar` 装入构造回调（`ObjectId` 构造尾部同步通知；未装回调时构造零额外开销，引擎运行时恒不装），以**弱引用**收拢每个 `ObjectId` 实例，不阻 GC；死条目在 snapshot 入口清理（清理节奏自持，不依赖外部 update 驱动）。

**不变量「Asset 引用持有者 ⊆ ObjectId」**：凡持有 Asset 引用字段的类必须继承 `ObjectId`，否则注册表扫不到——这是注册表流派对新增持有者的唯一申报义务（`MaterialTextureEntry`/`MaterialValueEntry` 已补继承 `ObjectId`；Node/Component/AbsAsset 原本即是）。

**扫描规则**（单对象字段扫描，**不沿对象图递归**——可达性由注册表承担）：

1. 逐字段（含父类链，`ClassUtil.getAllFields`）：`static` / `transient` 跳过；
2. 字段值是 `List` → 遍历元素，命中 guid 的 Asset 元素换指针；
3. 字段值是（非基本类型）数组 → 元素同上（基本类型数组元素装箱后不匹配 Asset，自然跳过）；
4. 字段值是 `Asset` 子类 → guid 命中即换指针（§4.3 setter 优先）；
5. 其余一律不递归——Asset 实例与其内部条目本身也是注册表中的独立条目，各自被扫描。

**为什么不再递归进 Asset 内部**：旧流派需「命中 Asset 字段后进实例一跳」才能到 `Material.shader`（组件→Material→Shader 两跳），`IdentityHashMap` 防环也因此必需。注册表流派下 Material 本体即快照成员，其 `shader` 字段被独立扫描覆盖——递归与防环一并免去。

### 4.3 换指针（setter 优先）与 Material 保参

一次重导事件只处理一个 guid，shader 与材质各自成事件、各自扫一遍注册表快照。

**换指针口径**：命中 guid 的 Asset 字段，优先调用**公开单参 setter**（名 `set + 字段名`、参数类型等于字段类型，如 `setShader` / `setMesh` / `setImage`）——保留 setter 的语义与副作用；无匹配 setter 才反射直写字段。setter 查找结果缓存（含「无 setter」缺席结果），批量多 guid reload 时不重复扫方法表。**setter 抛异常不回退反射直写**（守卫语义不该被绕过）。`List`/数组元素仍走 `set`（无 setter 概念）。

**Material 保参回到 `setShader` 单点**：v0.5 前由 Walker 反射直写 `Material.shader` 后另做补默认值后处理（绕过 setter 的代价）；现 Walker 命中 `Material.shader` 即走 setter → 直调 `Material.setShader`，而 `setShader` 已改 **Unity 口径**（材质档 §3.2）：换 shader 时**同名同类型保留原值、新属性取新默认值、旧有而新无（含同名但类型变）丢弃**——同 guid 换代（热重载）与换绑一视同仁，首次绑定列表本就为空、等于全填默认。补默认值的硬约束（`MaterialBlock.pack` 缺失即 NPE）由 `setShader` 自身保证，Walker 侧后处理实现随之删除。

**语义**：换 shader = 同名参数保留用户值、新参数取新默认值、已删参数丢弃（不炸），与 Unity 材质保参行为对齐（原 §4.3 语义不变，实现位置从 Walker 后处理挪到 `setShader`）。

### 4.4 RenderingSystem 孤儿回收（对账）

新增 `requestReconcile()`：置脏标记，**下一帧 collectFrame 首执行 mark-and-sweep**（单线程退化下帧首即安全点；重载是事件不是常态，帧路径零常驻成本）。语义：

- **活集** = 当前 `renderers` 的 Material 实例 + 其 `getShader()`；
- `materialResources`：key 不在活集 → 退役；在活集但 `builtFromShader != material.getShader()`（shader 被换、UBO 尺寸/贴图槽数已变）→ 退役——**MaterialResources 增记 builtFromShader 字段**，下次 `materialSet` 懒重建；**纯贴图热重载同此通道（Material 身份与 shader 均未变）**：MaterialResources 须同时增记**绑定贴图实例表**（SlotBinding：槽名 → 首绑 Texture2D 实例），对账判据扩为 builtFromShader 变 **或任一贴图槽实例变** → 退役重建——否则活描述符集强持旧贴图（`set.update` 回填过 gpu 句柄），**旧 GPU 贴图既不上屏也永不释放**（Cleaner 因强引用存在永不触发，2026-10-24 补录的实锤缺口）；
- `materialLayouts` / `materialLayoutDescs`：key（Shader）不在活集 → 退役；
- `pipelines`：key 是 identityHashCode 拼接串，**需增旁注 `Map<GraphicsPipeline, Shader>`**（最小侵入，不改 key 结构）——旁注 Shader 不在活集 → 退役；
- **退役 = 两帧模式**（照 `retiredTextures` 先例：stamp + 2 帧后 dispose，GPU in-flight 安全；maxFramesInFlight=2，帧 N 的栅栏在帧 N+2 首被 await）。

> **v0.5 判据升级**：`materialSet` 除 reconcile 的活集比对之外，**每帧**另比 `material.getTextureRevision()`（建造时记入 MaterialResources）与每个 `SlotBinding` 记的 `Texture2D.getRevision()`——不一致即自行两帧退役重建（贴图档 §8 / 材质档 §3.3）。运行期换图/改采样由此**无需调用方触发 reconcile 即生效**；reconcile 仍负责 shader 换代与孤儿回收。

**GPU 数据释放责任边界**（两条通道，2026-10-24 拍板视角澄清）：
1. PSO/布局/UBO/描述符集——由 reconcile **确定性释放**（反射 Walker 只换 Java 指针不碰 GPU，释放责任全在对账，这是 reconcile 不可省的根本原因）；
2. RHI Texture/Mesh 缓冲（句柄挂资产本体）——旧实例被 Walker 摘引用后，**闸门在其宿主描述符集先释放**（set 强持 gpu 句柄），随后 GC 不可达 → RHI Cleaner 兜底销毁（#24 修后 image+free 成对）。「手动 dispose 优先、Cleaner 仅兜底」口径不变——reconcile 释放 set 就是那个手动 dispose。

### 4.5 Scene 根豁免

打开场景自身 guid 变更：**豁免字段级重映射**——不脏（scene.changed / SceneWatchAppState）→ `loadScene` 整场景重载（编辑器相机/Gizmo 在编辑器侧不受影响，选中经 SceneSelectEvent 恢复）；脏 → 跳过 + 警示日志（Unity 脏场景不静默重载口径，弹窗形式后置 §10）。

---

## §5 编辑器侧（策略层）

### 5.1 触发（Unity Auto Refresh 口径）

- **回焦点扫描**（批次②落地，2026-10-27）：主窗口 focusGained → `EditorFocusGainedEvent`（总线解耦窗口与扫描）→ `AssetReloadService` 后台线程跑 `AssetImportPipeline.refresh()`（现成扫描逻辑：walkAssetDir → needImport/未导入 → submitImport，纯 IO 不占 EDT）→ 有待导入弹 **ImportProgressDialog 模态小框**（Swing Timer 轮询 `getImportingCount()` 归零自动关；不可手动关——关了焦点回落重扫描重弹循环）。导入中不重扫（自身写盘 file.save 内源重导 mtime 幂等，重复提交无益）。
- **手动口保留**：`project.reimport`（强制重导，现行命令不动）。
- **坑点存档（2026-10-29 实测死循环，根治=guid meta 单源拍板）**：无 guid 头的用户
  .shader（create.shader 建的）撞管线 `libraryFileFor`「按文件头解析产物 guid」——
  头取 null → `isImportedInLibrary` 恒 false，refresh 每轮重提交；叠加模态进度框
  （弹出夺主窗焦、关框还焦）成自持循环：扫描→提交导入→弹框失焦→导完关框→
  重获焦点→再扫描，永不停（用户改 .mat 颜色只是引信，循环由无头 .shader 自己撑）。
  根治（Editor_Design §6.2）：guid 头只剩 src 下 .java 专属，`libraryFileFor` 非脚本
  一律按伴生 meta 解 guid。教训：**焦点扫描的提交条件必须是「导入能使其变假」的
  条件**——恒真条件 + 焦点反馈环 = 死循环。
- WatchService 后置：Windows 上编辑器保存是多次写入（事件风暴+中间态误触发），焦点扫描已覆盖主用例。
- **批次②剩余**：Scene 根豁免（.scene 重导整场景重载/脏警示）——外部改 .scene 经扫描重导 Library 但场景对象图 warn 跳过，画面不刷新。

### 5.2 AssetReloadService（新增，编辑器）

订阅 `AssetImportEvent`（导入开始/完成/失败，`AssetImportPipeline` 直接发到 `EditorEventBus`）——
START 无消费，完成/失败转既有重映射路径，经引擎线程帧首执行（**范围收窄拍板 2026-10-29：
targetClass 仅 `.shader` 命中**——.mat/.texture/.smesh/像素源经「无热重载目标类型，
跳过」日志路径流出，不 reload 不换指针）：

1. `manager.reload(guid)` → old/new 实例；
2. `new == null`（产物损坏/导入失败）→ **保持旧引用 + warn**（残档口径：不炸主循环）；
3. guid 是打开场景 → §4.5 豁免路径；
4. Walker 扫 `EditorObjectRegistry.snapshot()`（§4.2/4.3，setter 优先——shader 换代的
   `Material.shader` 经 `setShader` 换指针 + 保参联动不受收窄影响）；
5. `renderingSystem.requestReconcile()`；
6. 发编辑器事件（消费方：Inspector 材质面板刷新、Project 面板等）。

**总线化（2026-10-30）**：`AssetImportPipeline` 原 `ImportListener` 内部接口 + `addListener/notifyListeners`
已删除，导入开始/完成/失败改为发布 `AssetImportEvent`（载荷 = `Phase` + 源文件 + guid 快照，
不携带可变 `ImportTask`——总线不可变契约）。发布方在导入 worker 线程、消费方在总线泵线程串行回调；
解耦导入管线与消费方（热重载、进度框、Project 面板统一订总线）。

### 5.3 写盘竞态

导入异步线程写产物 vs 引擎线程 `loadGuid` 读盘：产物写侧改**临时文件 + 原子改名**（单点改动，导入器产物落盘处），读者永远读完整文件；`loadGuidInstance` 的 `synchronized(this)` 串行同 guid 并发读。

---

## §6 失败安全

- **Shader 语法错**：导入器落 **stub 产物**（空字段 Shader + guid，任务 SUCCESS 带 errorMessage——拍板 2026-10-27，原「导入拒绝不覆盖产物保持旧版」口径作废）→ 热重载 reload → 场景换到 stub → 渲染侧 `Shader.isValid()` 判坏回退默认 shader（紫色，DefaultMaterial 门面，2026-10-29 由 DefaultShader 改名）；**修复后 reimport 真产物 → walker stub→real 换代 + Material 补默认值恢复**（stub 带 guid 可匹配，无「需重载场景」边界）。验收必测。
- **换 shader 后 Pass 被删**：严格制下 `pipelineFor` 抛 IllegalStateException（「未声明 Pass」）——**第一期维持现状**（渲染侧显式报错口径一致）；Pass 兼容预校验置灰后置（§10）。
- **产物损坏 / guid 解析失败**：reload 返回 null → 跳过重映射 + warn（§5.2-2）。
- **脏持有者**：第一期只有 Scene 脏判据生效（§4.5）；Material 内存脏待 Inspector 材质编辑通道落地后接入（跳过 + warn，Unity 口径）。

---

## §7 边界与已知留白

- **setter 旁路（v0.5 作废）**：旧流派 Walker 反射直写字段、绕过 `MeshRenderer.setMaterial` 等 setter（代价 = setter 一旦有副作用需同步登记后处理）。v0.5 改 **setter 优先**：命中字段名对应的公开同类型单参 setter 即调用，无 setter 才反射直写——旁路代价消除，Material 保参也回退 `setShader` 单点（§4.3）。Inspector 的「读 getter 写 setter」铁律本就不受影响（那是检查器编辑通道的规矩，本通道是运行时重载）。
- **新增持有自动覆盖**：反射流派的收益——将来新增任何 Asset 引用字段（Node→脚本资产、组件→音频等）零申报自动被 Walker 覆盖；代价是上面 setter 旁路一条。
- 删除资产源文件：Library 产物残留不失效（Unity 同口径），联动清理后置；
- GAME 视口与 SCENE 视口共用场景 → 热重载对两视口同时生效（Unity 同口径）；
- Mesh 热重载：协议同构（mesh 指针换 + sync 注销重注 + MeshUpload 幂等 + Cleaner 兜底），但 `.smesh` 外部编辑罕见，批次后置；
- 反射安全（SecurityManager/setAccessible 限制）：编辑器桌面 JVM 无此约束。

---

## §8 批次划分（按用户派发的增量步执行，只做派发那一片）

> **收窄修订（拍板 2026-10-29）**：批次①~②已落地；**批次③贴图/Mesh 热重载与 .mat/
> .smesh 文件级 reload 摘除——「后面再加」挂账**（基建不动：walker/reload/reconcile
> 全保留，targetClass 加回分支即恢复）；材质的实时生效 = 检查器面板内存操作（数值
> UBO 每绑重打包/面型活读/贴图槽实例换代），外部手改 .mat 文件不自动生效。

| 批次 | 内容 | 触发口 |
|---|---|---|
| ① core + 最小闭环 | invalidate/reload + 反射 Walker（含 Material 后处理）+ `requestReconcile` 孤儿回收 | 手动 `project.reimport` 链跑通 |
| ② | 焦点扫描触发 + Scene 根豁免（脏判据/整重载/警示）——焦点扫描✅（2026-10-27），Scene 根豁免仍后置 | focusGained |
| ~~③~~ 贴图热重载 + Mesh 热重载 | **摘除挂账（2026-10-29 拍板「后面再加」）**——恢复时 targetClass 加回 .texture/.smesh/.mat 分支 | 同上 |
| ④ | Inspector 材质编辑通道接入 + 内容 hash 替代 mtime（防「时间戳变内容没变」误重导，UE DDC 启示）+ Pass 兼容预校验 | — |

> **基建修订（v0.5，2026-10-30）**：Walker 换注册表流派（§4.2）+ 导入事件总线化（§5.2）——
> **不改批次范围与收窄口径**（targetClass 仍仅 `.shader`），只换「怎么找到持有者」与「怎么收到导入通知」；
> 批次①~②成果（invalidate/reload、reconcile 孤儿回收、焦点扫描）全部沿用。

---

## §9 验收清单

1. 改 `.shader` 颜色常量 → 手动 reimport → 画面当帧变；选中/Gizmo/编辑器相机不丢；
2. 改 `.shader` 属性默认值 → 材质面板同名参数用户值保留、新增属性取新默认值、删属性不炸（NPE 路径专项）；
3. ~~改 `.mat` → 场景画面变~~ **摘除（2026-10-29 拍板收窄）**——材质编辑走检查器内存操作即时生效；外部手改 .mat 不自动生效（恢复时 targetClass 加回分支）；
4. ~~改贴图源文件 → 画面变~~ **摘除挂账「后面再加」**（批次③ 原范围，恢复时加回）；
5. shader 语法错 → 画面回退默认 shader（紫）+ stub 产物标记 + 创建命令错误弹框（stub 拍板 2026-10-27）；修复后 reimport 画面恢复；
6. **F3 RenderStats 存活计数不随热刷次数增长**（孤儿回收验证——11 类存活计数是现成验证工具）；
7. 连续热刷 ×N 次无显存增长（resize-oom 教训的姊妹验证口径）；
8. 焦点扫描（批次②）：外部改 **.shader** → 切回编辑器 → 自动生效；编辑器自身保存不误触发。
9. **v0.5 注册表覆盖**：场景外持有者（Inspector 缓存实例等）改 .shader 也被换指针；运行期对场景 Texture2D 实例换图/改采样 → 画面即时更新、F3 存活计数不涨（§4.4 v0.5 判据 + 贴图档 §8）。

---

## §10 待拍板项

| 项 | 现状口径 | 备选 |
|---|---|---|
| Scene 脏时警示形式 | 日志 warn（第一期） | 顶部弹条/对话框（Unity 式），批次②实测定 |
| 换 shader 删 Pass 的帧内报错 | 维持渲染侧显式报错（严格制） | 重载前 Pass 兼容预校验、不兼容材质置灰，批次④ |
| Walker 递归深度 | **v0.5 起不再递归**（§4.2——注册表内 Asset 与条目各自独立被扫描，Material.shader 自然可达） | 若将来出现「仅经 Asset 内部字段可达的非注册表持有者」再评估 |

---

## 版本沿革

- v0.1（2026-10-24）：初稿，令牌表流派（持有者钩子 `AssetReloadAware` + 依赖登记表 + 按实例自描述 guid 匹配）。**同日被用户推翻**——持有引用形态不动，改反射遍历。
- v0.2（2026-10-24）：反射遍历流派（UE ReloadPackages 口径本地化）：core 缩到 invalidate/reload 一件；持有者钩子/依赖登记全废，换指针知识由反射 Walker 现场遍历获得；Material 补默认值后处理（pack 缺 entry NPE 实锤新增）；其余（SceneRenderBridge 身份重注、孤儿两帧退役、Scene 根豁免、失败安全、批次划分）继承 v0.1 核实成果。**同日补录**：§4.4 增 GPU 释放责任边界 + 纯贴图热重载缺口（MaterialResources 须记贴图槽实例表，否则活 set 强持旧贴图永不释放、画面不更新——对账判据扩为 shader 变或贴图槽实例变）。
- v0.3（2026-10-27）：**批次①落地（2026-10-30 用户实测验收通过——批次①热重载链、批次②焦点扫描/进度模态框、坏 shader stub 回退链一并验收，§9 清单 #1/#2/#5/#6/#7/#8 全过）**——
  - **reload 归属拍板（2026-10-27，用户）**：core `AssetLibManager` 的 `cache/guidAssetRefMap/trackedRefs/refQueue` 本就 protected，唯一 core 资产侧改动 = `loadCache` 放 protected；**invalidate/reload 实现落编辑器 `ProjectAssetLibManager`**（自持弱缓存结构的子类即编辑器重实现的管理器），core 接口不加 API。
  - **Walker 落 `editor/asset/reload`**：遍历规则照 §4.2/§4.3；判据实现细节：`MaterialResources.SlotBinding` 记首绑 assigned 实例 + usedPlaceholder 状态——**纹理壳后补图（setImage）是「实例同、image 状态变」，占位状态比对覆盖之**；占位贴图（WHITE/BLACK/NORMAL）是静态单例，实例身份比对可靠；贴图槽壳/占位不区分统一走 stale 判据。
  - **reconcile 活集口径核实**：`materialSet/pipelineFor` 消费方只有 AbstractForwardPass（均出自 renderers），PickPass/DebugLinePass 自建 PSO 不经缓存——活集 = renderers 的 Material+Shader 安全，无误杀路径。
  - **材质面板最小钩子（批次④提前项）**：`MaterialInspectorContent` 订 `AssetReloadedEvent`（onAttach 订/onDetach 退，契约随 InspectorContent 生命周期）→ 匹配本面板文件即 flushPending + 重拉编辑载体——**防 v0.11 即时联动退化**：reload 把场景换到盘上新实例后，不重拉则面板编辑旧实例，同实例联动成防抖反馈；批次④全量接入（内容 hash、Pass 预校验）仍后置。
  - **批次边界**：.png/.jpg/.jpeg 像素源（Image 产物换装要连 Texture2D/GPU 重传）跳过归批次③；.scene 重导 warn 跳过（豁免路径批次②）；Mesh 热重载走 Walker 协议同构（批次③验收）。触发口 = 手动 `project.reimport`（ImportListener → submitTask 引擎帧首），焦点扫描批次②。
  - **同日增补**：坏 shader **stub 产物 + 渲染回退默认 shader**（Engine_Material_Design v0.3）——`Shader.isValid()` 判据 + `DefaultShader` 门面（JavelinEngine.initialize 注入 AssetManager）；ShaderImporter 编译失败落 stub（任务 SUCCESS 带 errorMessage）走完成链 → 热重载 reload → walker 换到 stub → 渲染判坏回退默认紫色；§6 失败安全 / §9 #5 拍板同步更新（原「保持旧版本」口径作废）；reconcile 活集显式加默认材质单例防误退役。
  - **同日增补二**：**焦点扫描落地（批次②）+ 导入进度模态框**——MainFrame windowGainedFocus → EditorFocusGainedEvent → AssetReloadService 后台 refresh() 复用（pipeline 加 getImportingCount 在途 getter）→ ImportProgressDialog 模态小框（Timer 轮询自动关，不可手动关防焦点循环）；批次②剩余 Scene 根豁免仍后置。
- v0.4（2026-10-29）：**热重载范围收窄只 shader**（拍板判据 = 文件真值源 + 渲染侧按实例身份烤死）——.mat/.texture/.smesh/像素源摘出 reload 链「后面再加」，基建全保留（targetClass 加回分支即恢复）；沿革细节见 §8 收窄修订。
- v0.5（2026-10-30）：**Walker 改对象注册表全量扫描 + 导入事件总线化**（用户拍板，§4.2/§4.3/§5.2 重写）——
  - `ObjectId` 增构造回调 `setRegistrar`（默认 null，引擎运行时零开销）；新增 `EditorObjectRegistry`（弱引用收拢存活 `ObjectId`，snapshot 入口清理死条目，JVM 关闭钩子卸下），`JavelinEditor` 构造尽早 install；`MaterialTextureEntry`/`MaterialValueEntry` 补继承 `ObjectId`（不变量「Asset 引用持有者 ⊆ ObjectId」）；
  - Walker 由「沿 GameScene 递归遍历（IdentityHashMap 防环）」改「扫快照单对象字段，不递归」——覆盖范围不再依赖场景图可达（Inspector 缓存实例、场景外持有者一并覆盖），`Material.shader` 因 Material 本体在快照中自然可达（不再需进 Asset 内部一跳）；
  - 换指针改 **setter 优先**（`set + 字段名`、同类型单参；含缺席缓存；setter 异常不回退反射直写），§7 setter 旁路警示作废；Material 补默认值后处理删退，保参归 `Material.setShader` 单点（材质档 v0.5）；
  - **导入回调总线化**：`AssetImportPipeline` 删 `ImportListener` + `addListener/notifyListeners`，改发 `AssetImportEvent`（`Phase` + 源文件 + guid 快照）到 `EditorEventBus`；`AssetReloadService` 订阅总线消费（START 忽略，完成/失败转重映射）。
  - **动机**：注册表会话原型（旧 core `ObjectCache` 因 `update()` 无调用点而退役）的教训在案——本批把清理挂在 snapshot 入口，避免「依赖外部帧驱动」重蹈覆辙。
- v0.6（2026-11-10）：**材质 shader 换绑改落共享实例（收窄口径不变；用户实测验收通过）**——用户报「材质检查器改 shader 后使用该材质的物体不变」：`onShaderCommit` 走盘面重解析私有实例、`.mat` 又被收窄挡在 reload 链外，场景持有的 Material 实例 shader 指针无人换（收窄时的「材质编辑=内存直改即时生效」假设只覆盖数值/面型/贴图槽）。修法=换绑落 `loadGuid` 缓存共享实例、**引擎帧首** `setShader` + `requestReconcile`（`MaterialResources.stale()` 的 builtFromShader 判据 + `pipelineFor` 的 shader 身份键已支持原位换代，批次①之前「按首绑烤死」的判据不再成立）；面板载体与共享实例分歧时按盘面值补齐再收回共享实例。**targetClass 仍仅 `.shader`**——外部手改 `.mat` 自动生效仍归批次③挂账（恢复时加回分支，另需自写导入抑制防面板逐次重建）。
