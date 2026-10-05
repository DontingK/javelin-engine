# 引擎碰撞设计（Engine Collision Design）

> 版本：v0.1（2026-10-05，**草案——§1 决策表待用户拍板后定稿**）
> 范围：二期②「碰撞检查」的**纯数学层（L0）**——Ray、碰撞形状、相交例程、包围盒。游戏逻辑查询、编辑器拾取（二期③ Ray）与后期物理系统共用的几何地基。
> 定位：本文设计 L0 并**预留**上层依赖形状；场景级 raycast（L1）设计归 Ray 期、物理系统（L2）选型后立项，各归其档，本文不展开其内部。
> 配套：`doc/Engine_Roadmap.md` §2.2/§2.3；物理步进挂点见 `Engine_Design.md` §2.2/§3。
> 参考骨架：`javelin-core` 的 `physics` 包空壳（2026-10-05，用户明示"只是参考"）——包结构与 `collideWithRay` 签名经评估**保留**，其余按 §1 重构。

## 1. 待拍板决策表

| # | 决策点 | 备选 | 推荐 | 理由 |
|---|--------|------|------|------|
| D1 | 非均匀缩放处理 | A 形状参数预缩放（参考骨架 `applyScale`，jME 同款）；B **射线逆变换吸收缩放，形状参数永不动** | B | A 原地改参破坏形状共享（物理 body 共享 shape 是刚需）、非幂等（重复施加双重缩放）、把"世界缩放"泄漏进纯数学层；B 靠 §4.2 不变量零成本直得世界距离，接口还能整个删掉 applyScale |
| D2 | Ray 方向归一化 | 强制归一化；**不强制**（t 以 direction 实长为单位，§4.1） | 不强制 | 射线经节点逆矩阵变换进本地空间后方向天然非单位；"t 按实长计"恰使此时 t=世界距离（§4.2），跨候选直接比 t 排序、免变换落选者的命中点。JOML 自家的线段包装（`intersectLineSegmentAab`/`testLineSegmentTriangle`）就是同一契约 |
| D3 | `CollisionShape` 继承 `Serializable` | 继承（参考骨架）；**不继承** | 不继承 | 纯数学层不背序列化契约；落盘归未来 Collider 组件数据面（Unity 口径：`BoxCollider.size`/`SphereCollider.radius` 直接是组件字段，形状类只是运行时几何）。届时组件 write 遵红线 9/10 |
| D4 | v0.1 形状集 | Sphere+Box+Aabb；+Cylinder；+三角网格 | Sphere+Box+Aabb | 编辑器拾取的最小充分集；圆柱 JOML 无现成实现需自研且非拾取刚需；三角网格拾取随 L1 实测再定 |
| D5 | 参考骨架 `Collidable`/`CollisionResults` | 保留；**v0.1 移除**（L1 设计时重议） | 移除 | 两者是场景层概念（结果须携带 Node/Component 引用），与"现在只要纯数学"冲突；引擎既有先例是 pull 翻译（SceneRenderBridge），L1 大概率不需要 jME 式 Collidable 接口 |
| D6 | 命中数据形态 | out 参 + 返回 t；命中对象分配 | out 参 | 对齐 JOML 风格与 `Transform.getWorldPosition(out)` 先例；未命中约定 out 参不变，调用方复用缓冲零分配 |
| D7 | Collider 组件的形状持有形态 | A 组件持形状对象字段（`Collider { CollisionShape shape }`）；B **组件字段即形状参数**（`BoxCollider { center/size }`、`MeshCollider` 引用 Mesh 资产） | B | A 使形状被迫实现 Serializable（与 D3 冲突）且接口类型字段 read 需 instanceof 分流；B 序列化=纯基元/资产 guid 引用，Unity/PhysX 同构——组件=authoring 面（落盘），形状类=引擎内部运行时几何（查询/物理层构建缓存） |
| D8 | CollisionShape 是否继承 Component | A 形状即组件（`SphereCollider extends Component implements CollisionShape`，类合并）；B **不继承——挂节点的是 Collider 组件，形状=引擎内部运行时几何** | B | 挂节点、参与生命周期/序列化的应是 Collider 组件（「碰撞体=组件负责形状」拍板的应有之义）；A 则 L2 物理 body 将持场景对象引用（违 §6「body=自持变换+形状」）、形状失去多体共享、纯数学阶段无类可建；分工先例=MeshRenderer↔Mesh（组件持有/构建引擎内部对象） |
| D9 | 按 Mesh 自动拟合形状参数的方法归属 | A `CollisionShape` 接口方法（fitFromMesh）；B **`Collider` 组件层方法 `fitFromMesh(Mesh)`** | B | A 把资产类 Mesh 倒灌进纯数学层（违 §2 分层）；fit 是 authoring 一次性操作非查询数学；拟合产物 = center 偏移 + 形状参数两件，center 归组件字段（D7-B）——形状类查询时才构建，没有可拟合的落点 |

> **已拍板（2026-10-05 用户）**：**碰撞体=组件、负责形状；刚体=独立组件（后置）**——Unity 式 Collider/Rigidbody 分体架构，已并入 §2/§6；上表 D1~D7 为其余待拍板项（D7 为本条引出的形状持有形态子决策）。

骨架处置小结：包结构（Ray 居 `physics` 根、形状居 `collision.shape`）与 `collideWithRay` 签名保留；删 `applyScale`、去 `Serializable`、`CylinderShape` 后置重做、`Collidable`/`CollisionResults` 移除（D5）。

## 2. 三层定位（现在做 L0，为 L1/L2 立规矩）

```
L2 物理系统（后期，选型后立项：自研/物理库）
│  PhysicsSubsystem：earlyUpdate 步进 → 碰撞事件 → lateUpdate 回写 Transform（Engine_Design §2.2）
│  刚体 world：body = L0 形状（多体共享）+ 自持变换 + 动力学量
▲ pull Collider/RigidBody 组件注册表构 body
L1 场景查询（二期③ Ray + 编辑器拾取，设计归 Ray 期）
│  Collider 组件族（已拍板：碰撞体=组件、负责形状）+ 屏幕点→世界射线（逆 viewProj）+ 场景 raycast → RaycastHit
▲ 全部几何计算委托
L0 纯数学（本档，现在做）：com.javelin.core.physics(.collision)
   Ray / CollisionShape 族 / Aabb / 相交例程——零 ECS、零场景、零序列化、零帧语义
```

「考虑物理系统」落在三条规矩上：

- **形状可共享**：形状类只有几何参数、无运行态 ⇒ 物理 body 可多体共享同一实例（D1-B 保证缩放永不污染形状参数）；
- **上层持变换、L0 只见本地射线**：L1 用 `Transform.getLocalToWorld()`、L2 用 body 自持变换，各自把射线变换到形状本地空间后再调 L0——形状代码里不出现"世界"；
- **物理包围盒不经形状接口**：broadphase 的世界 Aabb 由上层用「本地 Aabb + body 变换」膨胀（§5 同一例程），L0 不为物理特设 API。

## 3. L0 类清单

| 类/接口 | 包 | 职责 |
|---|---|---|
| `Ray` | `physics` | `origin`/`direction`（Vector3f，可变数据类，单线程使用）；§4 契约；半直线（t≥0 才算命中） |
| `CollisionShape` | `collision.shape` | 接口，唯一方法 `float collideWithRay(Ray ray, Vector3f hitPoint, Vector3f hitNormal)`：返回命中 t、**负值=未命中**（对齐 JOML `intersectRayTriangle` 惯例），未命中时 out 参不变；契约=射线已在形状本地空间、direction 可不归一化。**无 applyScale、不继承 Serializable**（D1/D3） |
| `SphereShape` | 同上 | `radius`、中心=本地原点；例程**自写判别式二次方程**（§4.4——不能包 JOML） |
| `BoxShape` | 同上 | `halfExtents`（Vector3f）、中心=本地原点、边沿本地轴；例程**自写 slab**（片①实现期改定：JOML 版不回传命中面轴、需 Vector2f 结果缓冲——自写零分配且法线轴精确；t 契约与 JOML 相同按 direction 实长计） |
| `CylinderShape` | 同上 | `radius`/`height`、轴向本地 Y；**已实现**（2026-10-05 用户提前派发，原片④后置）——无限圆柱二次方程根按 y∈[−h/2,h/2] 裁剪 + 上下端盖平面根按盘内判定，取 t≥0 最小边界穿越点（凸体统一入口/出射语义），自研（JOML 无此例程，§4.4） |
| `Aabb` | `collision` | `min`/`max`；`setFromPoints(float[] xyz)`（Mesh.bounds 数据源）、`transform(Matrix4fc, Aabb out)`（**行和公式实现**——§5 勘误）、`rayIntersect(Ray, Vector2f nearFar)` 包 JOML（返回语义已核源码）。**不实现 CollisionShape**——语义=保守粗筛包围，非精确碰撞几何 |

L1 契约：**Collider 组件族查询管线已实现**（片② 2026-10-05，`scene.component` 包：`Collider` 基类收 `center`（全碰撞体共有）+ `collideShape` 统一管线——逆矩阵变换射线（origin 扣 center、direction 不归一化 §4.2）→ 形状例程 → 命中点加 center 经 M 回换、法线经法线矩阵变换后归一化；`BoxCollider`/`SphereCollider`/`CylinderCollider`（2026-10-05 编辑器移动轴拾取需要）每查把组件参数同步进缓存形状实例（D7-B：组件字段即参数，免脏标记）后委托管线，write/read 数据面齐（center 基类 + 参数子类））；**Rigidbody 组件**（拍板：独立组件、后置）、`RaycastHit`（point/normal/distance + Node/Component 引用）、场景级 raycast 查询面——归 Ray 期与编辑器拾取一起定。

## 4. 射线契约与缩放数学（本档地基）

### 4.1 t 的单位

约定：**命中点 = origin + t·direction，t 以 direction 的实长为单位**。direction 归一化时 t 即距离；L1 变换后的非单位方向下 t=世界距离（§4.2）。解出的 t<0 视为未命中（目标在身后）。

### 4.2 缩放吸收不变量（D1-B 的核心）

世界射线（单位方向 d̂）对节点世界矩阵 M（TRS，可含非均匀缩放）：

```
o_l = M⁻¹ · o_w     （点变换）
d_l = M⁻¹ · d̂_w     （向量变换，不归一化）── Ray 进形状
```

形状解得 p_l = o_l + t·d_l，两侧左乘 M：

```
M·p_l = M·(M⁻¹·o_w) + t·(M·(M⁻¹·d̂_w)) = o_w + t·d̂_w
       ⟹   t 即世界空间距离（与 M 藏了什么旋转/非均匀缩放无关）
```

因此 L1 拾取循环：每候选变换射线 → 拿 t → **跨候选直接比 t 排序**，只有赢家才回换命中量：

- 命中点：p_w = M·p_l（`transformPosition`）；
- 法线：n_w ∝ (M⁻¹)ᵀ·n_l——非均匀缩放下直接用 M 旋转法线是错的，须走法线矩阵（JOML `Matrix4f.normal(dest)`，已核签名）后归一化；负缩放（镜像）自动正确。

### 4.3 例程约束

所有相交例程**不得假设 |direction|=1**（slab/二次方程/Möller–Trumbore 的 t 本就按实长计）。自研例程遵守同一契约；单测必含非归一化方向用例（§7）。

### 4.4 JOML 例程适配性（已核 1.10.8 源码，勿凭印象改判）

| 例程 | t 契约 | 结论 |
|---|---|---|
| `intersectRayAab`（slab） | 实长单位——JOML 自家 `intersectLineSegmentAab` 以未归一化 `p1−p0` 调它并按 t∈[0,1] 判定 | 可直接包——BoxShape 实现期改自写（需要命中面法线轴+零分配，片①）；`Aabb.rayIntersect`（片③）仍包它（只要 near/far，缓冲由调用方传参） |
| `intersectRayTriangle`（MT） | 同上（`testLineSegmentTriangle` 同款佐证） | 直接包 |
| `intersectRaySphere` | **tca/thc 变体只对归一化方向成立**——thc 是弦半长不随 \|d\| 缩放，非归一化方向 t 直接算错（javadoc 明标 normalized） | **不可包**——SphereShape 自写二次方程（约 10 行：a=d·d、b=2·L·d、c=L·L−r²，t=(−b−√Δ)/2a，a 随 \|d\| 平方缩放故 t 天然实长单位） |
| `RayAabIntersection` / `FrustumIntersection` | 区间算术快速多盒拾取 / 视锥剔除结构 | 后置（§5/§6） |
| （无） | 射线-圆柱、射线-OBB | JOML 无，需要时自研 |

## 5. Aabb 与 Mesh.bounds 回归（Roadmap §2.2 前置项闭环——片③已实现 2026-10-05）

- **Mesh.bounds 三条计算路径**（read 不可派生——红线 10：嵌套属性此时可未 populate）：① `setPositions`/`setAttribute(POSITION)` **set 即失效清除+有效数据即算**（空/短数据 → null，不残留旧盒——setFromPoints 对短数据不改变当前盒，不清除会残留旧盒，用户指正 2026-10-05）；② 导入产物首次 `getBounds()` 惰性兜底（CPU 副本尚在）；③ MeshUpload 释放 CPU 副本前调用 `getBounds()` 钉死（此后无数据可算）。无 POSITION 且 CPU 副本已释放 → null（fitFromMesh 消费侧显式报错）；
- **世界 Aabb**：`transform` = 中心经 M 变换 + 半尺寸按 |M₃ₓ₃| 行绝对值加权和展开。**实现期勘误**：对盒子此式与 8 角点极值**数学等价**（盒=区间积，各输出轴极值恒为 中心′ᵢ ± Σ|M_ij|·he_j）——原「精确/保守两档」设计不成立，行和即精确值且省 7 次矩阵-向量积；8 角点仅作单测对照基准；
- `RayAabIntersection` 后置：v0.1 拾取线性遍历物体量级下够用。

## 6. 物理系统预留（L2 契约面，不实现）

- **步进时序**（Engine_Design §2.2 已有挂点，重申）：`earlyUpdate` 物理步进 → `scene.update`（脚本读碰撞事件/查询结果）→ `lateUpdate` 回写 Transform；
- **body 结构**：共享形状（L0 实例）+ 自持变换副本（物理世界内部值，不引用场景 Transform——Unity/PhysX 同构）+ 动力学量；同步=组件 ↔ world 双向拷贝；
- **Collider 组件（已拍板 2026-10-05：碰撞体=组件、负责形状）**：L1 面；形状持有形态见 D7（推荐 B——组件字段即形状参数 `center`/`size`/`radius`…，offset 归组件不归形状：形状中心恒=本地原点）；物理系统 pull 组件注册表构 body；序列化走 Scene 档组件通道（红线 9/10 生效）；
  **CollisionShape 不继承 Component（D8）**——形状不是场景对象（无生命周期/不落盘/不挂节点），节点上的 Transform 才是形状的世界定位来源：查询时上层把世界射线经节点逆矩阵变到形状本地空间（§4.2）；分工同 MeshRenderer↔Mesh；
  **片②已实现（2026-10-05）**：`Collider` 基类（center + `collideShape` 统一变换管线，内部缓冲每查复用零分配）+ `BoxCollider`/`SphereCollider`（缓存形状实例每查同步参数委托；`@Reflective` 编译期自动注册，序列化经 GameScene 组件主表直传零额外接线）；
  **fitFromMesh(Mesh)（D9，authoring 便利）**：按 mesh 本地包围数据拟合 center/参数（mesh 本地=节点本地，MeshRenderer 无额外网格变换）；Box 精确贴合包围盒、Sphere 保守包围（中心=盒心、半径=半对角线，最小包围球后置）、Cylinder 取 max(半宽X,半宽Z) 为半径/全高为高（轴向 Y——**编辑器移动轴拾取即此形态**，MoveTool 三轴已挂 2026-10-05）；数据源=POSITION 属性或 Mesh.bounds 缓存（**片③ 前置**——Mesh 上传后默认释放 CPU 副本，bounds 缓存不丢）；编辑器「加 Collider 自动贴合渲染网格」走此方法；
- **Rigidbody 组件（已拍板：独立组件、实现后置）**：负责动力学量（mass/velocity/重力响应…）与积分；**Collider × Rigidbody = 物理 body**；无 Rigidbody 的 Collider = 静态碰撞体/纯查询体（拾取与 trigger 可用，不进积分）；Rigidbody 产生接触需自身或子节点上至少一个 Collider（Unity 同构）；
- **v0.1 明确不做**：broadphase 加速结构、接触流形/求解器、CCD、触发器事件、物理材质——L2 立项时各归其档。

## 7. 验收

- **单测**（javelin-core，解析解对照，用户运行）：每形状 命中/未命中/切线掠过/负 t 身后/origin 在形状内部/**非归一化方向 t=世界距离**（直证 §4.2）/非均匀缩放往返（本地命中回世界 vs 世界空间直算一致）；
  **✅ 已落地全绿（2026-10-05 用户运行验收）**：SphereShapeTest/BoxShapeTest/CylinderShapeTest = 8+8+13 用例；
- **Demo**：L1 落地时按 Roadmap §2.3 点击拾取验收（本档无 Demo）。

## 8. 实现分片建议（拍板后按片派发，每片验收=单测绿）

| 片 | 内容 |
|----|------|
| ① | Ray + CollisionShape + SphereShape + BoxShape + 单测（**✅ 已验收 2026-10-05**） |
| ② | Collider 组件 `collideWithRay` 实现（世界→本地变换+center 偏移+形状委托+世界回换）+ 单测（含非均匀缩放往返）（**✅ 已验收 2026-10-05**——ColliderTest 13 用例：恒等/平移/旋转/缩放往返/全不变量/法线矩阵 invert().transpose() 交叉验证/射线不被改写/裸组件守卫/参数即时生效） |
| ③ | Aabb + Mesh.bounds 回归 + 世界 Aabb + Collider.fitFromMesh 实现（Box 精确/Sphere 保守，数据源走 bounds）+ 单测（**已实现 2026-10-05，待单测验收**——AabbTest 9 用例含 8 角点对照基准与 JOML 语义、MeshBoundsTest 3 用例含 roundtrip 惰性、ColliderTest 增 3 用例含拟合→查询闭环与显式报错） |
| ④ | CylinderShape 自研数学 + 单测（**✅ 已验收 2026-10-05**，用户提前派发实现） |

三角网格拾取不设片——随 L1 编辑器拾取实测再定（D4）。

## 9. 边界与已知取舍

- L0 零 JOML 之外依赖、查询路径零分配（out 参风格贯穿）；
- 形状中心恒=本地原点（`center` 参数归 Collider 组件，Unity 口径）；
- Ray/Aabb/形状为可变数据类，单线程使用（对齐引擎主循环线程模型），不保证并发安全；
- 纯数学层无日志——排障手段=单测解析对照（红线 6 精神：可运行侧才谈加日志）。

---

> （注：部分内容可能由 AI 生成）
