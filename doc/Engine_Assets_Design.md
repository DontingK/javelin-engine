# 引擎资产设计（Engine Assets Design）

> 版本：v0.3
> 说明：引擎侧资产格式与类设计（Mesh / 纹理 / .mat 材质），与 RHI 文档（`RHI_Design.md` 系列）和引擎场景设计（`Engine_Scene_Design.md`）配套。
> 关联：RHI 第 20/21 节（Material / .shader 语法）、第 25 节（数据上传）、第 26 节（Buffer）；`Engine_Scene_Design.md` 5.1（MeshRenderer）。
> v0.2：第 2 节 Mesh 修订——per-attribute 数据（Unity 式）+ 数据驱动属性推断 + VertexLayout 规范化排序与缓存 + 首次上传冻结。
> v0.3（2026-10-29）：§2.9 Mesh 子网格——单索引数组 + SubMesh 区间列表（Unity subMeshCount/SetTriangles 语义）+ .smesh [SUBMESH_N] 段；消费侧多材质见 Engine_Scene_Design §5.1。**2026-10-30 验收销账（MeshSerializationTest + 多材质实机）。**

## 1. 总则

- **资产 = 共享、不可变、GPU 只读**：Mesh / 纹理 / .mat 被多个渲染对象引用（资产引用，不复制）；
- **生命周期**：资产对象 GC 不可达 → Cleaner（RHI 17 节）延迟销毁 GPU 资源——资产层不手动管理 GPU 生命周期；
- **上传**：走 RHI 25 节数据上传路径（一次性 staging → device local；动态数据走 HOST_VISIBLE）；
- **跨线程**：不可变资产可被逻辑线程 / 渲染线程安全共享（RHI 27.3）。

## 2. Mesh 类设计

### 2.1 定位

- **不可变**：构建期填数据，**首次上传后冻结**（再改抛异常）——GPU 数据只读，多线程共享安全；
- **可共享**：多个 MeshRenderer 引用同一 Mesh（`Engine_Scene_Design.md` 5.1）；
- **双层**：CPU 几何数据（资产层）+ GPU 缓冲（RHI 层）；
- **子网格**（2026-10-29 落地，§2.9）：单索引数组 + 区间列表，MeshRenderer 多材质按子网格对应（`Engine_Scene_Design.md` 5.1）。

### 2.2 类定义（per-attribute，Unity 式）

```java
class Mesh {
    // —— CPU 数据（构建期，per-attribute）——
    private float[] positions;    // 3n（必选）
    private float[] normals;      // 3n（可选）
    private float[] tangents;     // 4n（可选，w = 副切线符号）
    private float[] uv0;          // 2n（可选）
    private float[] colors;       // 4n（可选）
    private int[] indices;        // 3m（三角形索引）
    private AABB bounds;          // recalculateBounds() 计算（视锥剔除用）

    // —— 属性与布局（数据驱动，自动推断）——
    private EnumSet<VertexAttribute> attributes;   // 由已填数组生成
    private VertexLayout layout;                    // 规范化自动排布（2.4）

    // —— GPU（lazy 上传后冻结）——
    private Buffer vertexBuffer;   // VERTEX 用途（RHI 26.2）
    private Buffer indexBuffer;    // INDEX 用途
    private boolean uploaded;

    // —— API（Unity 式）——
    void setPositions(float[] p);                    // 必选
    void setNormals(float[] n);
    void setTangents(float[] t);
    void setUVs(float[] uv);
    void setColors(float[] c);
    void setIndices(int[] idx);                      // 必选
    void setAttribute(VertexAttribute attr, float[] data);   // 自定义属性通用入口
    void recalculateBounds();                        // AABB 重算
    void recalculateNormals();                       // 缺失时生成（角度阈值平滑）

    void upload(RHI rhi, UploadData uploader);       // 首次上传后冻结（再 set 抛异常）
    Buffer vertexBuffer();                        // 未上传时触发 lazy 上传
    Buffer indexBuffer();
}
```

> **实现存档（2026-09-26，用户拍板）**：Mesh 属性的内部存储即序列化形态——
> `ArrayList<MeshAttributeData>`（name/components/format/data 自描述节点，name 唯一）
> + `int[] indices`，Capsule write/read 字段直通零转换（红线 10 的根治式满足：
> 不搞 Map→列表转换、不搞懒装配）。序列化只落 CPU 数据；uploaded/layout/GPU 句柄
> 运行态不落盘，.smesh 导入产物 = 未上传的纯 CPU 网格。

> **属性不是封闭枚举**——是描述对象，内置属性为预定义常量，用户可 new 自定义属性，机制完全一致。

```java
class VertexAttribute {
    final String name;        // "POSITION" / "BONE_WEIGHTS"（任意）
    final int components;     // 2 / 3 / 4
    final Format format;      // FLOAT（起步）；P2：U8 / U16 / HALF
    final int location;       // 由 layout 按规范序分配（见 2.4，与填写顺序无关）
}

// 内置常量（预定义实例）
VertexAttribute POSITION = new VertexAttribute("POSITION", 3, FLOAT);  // location 0
VertexAttribute NORMAL   = new VertexAttribute("NORMAL",   3, FLOAT);  // location 1
VertexAttribute TANGENT  = new VertexAttribute("TANGENT",  4, FLOAT);  // location 2
VertexAttribute UV0      = new VertexAttribute("UV0",      2, FLOAT);  // location 3
VertexAttribute COLOR    = new VertexAttribute("COLOR",    4, FLOAT);  // location 4

// 用户自定义示例：骨骼权重（location 5+，按 name 字典序）
VertexAttribute BONE_WEIGHTS = new VertexAttribute("BONE_WEIGHTS", 4, FLOAT);
```

### 2.4 VertexLayout（规范化排序 + 缓存 + 自动排布）

> **只声明"有哪些属性"，引擎规范化排序 + 自动排布。规范化保证：相同属性集合 → 唯一 layout → 管线复用**（RHI 16 节管线缓存以 layout 为键）。

- **规范化排序（固定序）**：内置 `POSITION < NORMAL < TANGENT < UV0 < COLOR`（POSITION 恒在偏移 0），自定义属性排其后按 **name 字典序**；
- **location = 规范序索引**（0,1,2...），**与 API 填写顺序无关**——`setUVs` 先填还是 `setNormals` 先填，layout 结果相同；
- **缓存去重**：`VertexLayout.of(属性集合)` → 规范化 → 查缓存，命中返回同一不可变实例（线程安全共享）；
- **排布**：按规范序紧密排列（interleaved，单绑定点 0）；起步全 `FLOAT`（2/3/4 分量）4 字节对齐天然成立 → **stride = 各属性字节和**；
- **管线复用**：相同顶点结构的 Mesh 共享同一管线，draw call 之间只切换资源绑定。

| 示例属性集合 | 规范序排布 | stride |
|---|---|---|
| POSITION | 3f | 12B |
| POSITION + NORMAL + UV0 | 3f + 3f + 2f | 32B |
| POSITION + NORMAL + TANGENT + UV0 + COLOR | 3f + 3f + 4f + 2f + 4f | 64B |

- P2：`Format.U8`（COLOR 压缩省带宽）、`U16`（骨骼索引）、`HALF`（精度/带宽权衡）——按格式对齐规则排布；
- P2：多绑定点（分离布局）、显式指定 location。

### 2.5 与 Shader 顶点输入的匹配（RHI 16 节管线创建）

- **顶点输入 = Mesh.layout 与 shader 声明的交集**；
- Mesh 缺某属性（如无 NORMAL）→ shader 对应 location **读到默认值**（Vulkan 规范：缺失属性取零值/默认）——不崩，效果退化（如无光照）；
- shader 未读的属性（如 COLOR）→ 忽略，无影响；
- 因此：**简化 Mesh（如阴影 Pass 只绑位置）可复用同一 shader**；
- **location 约定（固定表）**：.shader 顶点输入 `layout(location = N)` 的 **N = 规范序索引**：

| location | 属性 |
|---|---|
| 0 | POSITION |
| 1 | NORMAL |
| 2 | TANGENT |
| 3 | UV0 |
| 4 | COLOR |
| 5+ | 自定义属性（按 name 字典序） |

写 .shader 时按此表声明，避免错位。

### 2.6 构建与数据流（Unity 式）

```java
Mesh mesh = new Mesh();
mesh.setPositions(pos);     // 必选
mesh.setNormals(normals);   // 可选
mesh.setUVs(uv);            // 可选
mesh.setIndices(indices);   // 必选
mesh.recalculateBounds();   // AABB（视锥剔除必需）
// layout 由已填属性自动推断 + 规范化（2.4）
```

- **数据驱动**：给了哪些数组 → 就有哪些属性（无需显式声明属性集合）；
- **自定义属性**：`setAttribute(BONE_WEIGHTS, data)` 与内置 `setXxx` 完全等价（统一 VertexAttribute + float[]）；
- **防御性拷贝**：set 时拷贝数组（调用方后续修改不影响 Mesh）；
- **bounds**：`recalculateBounds()` 显式调用（或上传前自动），供 `Engine_Scene_Design.md` 6.2 视锥剔除；
- **indexSize**：上传时按顶点数自动选 uint16（≤65535）/ uint32。

### 2.7 上传与生命周期（lazy + 冻结）

- **lazy 上传**：`vertexBuffer()` / `indexBuffer()` 首次访问触发——场景加载只建资产不上传，渲染到才传；
- 上传路径：RHI 25 节（一次性 staging buffer → device local，单命令缓冲 + fence）；
- **冻结**：首次上传后不可变（再 set 抛 `IllegalStateException`）——不可变承诺（2.1）落地；
- **CPU 数据处置**：上传后**默认释放 CPU 副本**（`keepCpuCopy=false`，省内存）；需要 CPU 数据（物理 / 编辑器）时显式保留；
- **销毁**：Mesh 对象 GC 不可达 → Cleaner（RHI 17 节）延迟销毁 vertexBuffer / indexBuffer。

### 2.9 Mesh 子网格（2026-10-29 落地）

> **拍板**（用户 2026-10-29）：子网格 = 单索引数组 + 区间列表（不搞每子网格独立索引缓冲——
> 单索引缓冲不变，Vulkan `vkCmdDrawIndexed` firstIndex 范围绘制）；运行时 API 参考 Unity
> （`subMeshCount` / `SetTriangles` 语义）。消费侧：MeshRenderer 多材质列表 i 位对应子网格 i
> （`Engine_Scene_Design.md` 5.1）。

**数据面**：

```java
// Mesh 增量字段（序列化直通，红线 10）
ArrayList<SubMesh> submeshes;   // 区间列表；空 = 单一隐式全量子网格

// SubMesh 节点（照 MeshAttributeData 模式：@Reflective + Serializable，字段非 final）
class SubMesh {
    int firstIndex;   // 区间起点（指向 Mesh.indices）
    int indexCount;   // 区间索引数
}
```

**API（Unity 语义映射）**：

| API | Unity 对应 | 说明 |
|---|---|---|
| `setSubmeshes(List<int[]>)` | `SetTriangles` 批量形式 | 每个 int[] 一个子网格，内部拼接为 indices + 记录区间 |
| `getSubmeshCount()` | `subMeshCount` | 无显式子网格 = 1 隐式全量 |
| `getSubmeshFirstIndex(i)` / `getSubmeshIndexCount(i)` | — | 区间访问器（上传前后均可用；越界抛 IllegalArgumentException） |

**序列化**：`submeshes` 直通进 Mesh 产物（write 直传 / read 只回写引用）；旧产物无该键 =
单一隐式全量子网格（向后兼容，加载不破）。区间是运行数据非顶点数据——上传清理 CPU 副本时保留。

**上传校验**（MeshUpload，显性化红线 15）：区间须在索引数组范围内、indexCount > 0。

**.smesh 格式扩展**：`[SUBMESH0]`/`[SUBMESH1]`… 段（每行逗号分隔索引，同 [INDEX] 风格），
序号从 0 连续递增；`[INDEX]` 与 `[SUBMESH_N]` 互斥（同现=错误显性化）；无子网格段 = 单一
隐式全量（内置 .smesh 不动，向后兼容）。

**绘制链**：RHI `drawIndexed(indexCount, instanceCount, firstIndex)` 三参扩展（参数序对齐
vkCmdDrawIndexed）；MeshDraw `drawIndexed(cmd, mesh, submesh)` 解析区间；RenderObject 增
`submesh` 序号（每 (renderer, 子网格) 一实例，`Engine_Scene_Design.md` 5.1）。

### 2.10 待定（P2）

- **.mesh 二进制格式**（快加载）：紧凑头 + 顶点/索引块 + 包围盒；.obj 仅作导入源；
- 多绑定点（分离布局）、显式 location、U8/U16/HALF 格式；
- 骨骼网格（SkinnedMesh）对称设计（自带 SkinnedMesh 字段，`Engine_Scene_Design.md` 5.1）；
- **动态 Mesh**（骨骼蒙皮 / 粒子 / 物理形变 / 水面，每帧变化）：v0.1 已定**冻结语义**（上传即冻结，要改新建，见 `Engine_Material_Design.md` §5），destroy-and-recreate 扛不住每帧频率——后续专项支持，走独立 DynamicMesh 路径（hostVisible 常驻缓冲 + 每帧映射写入，逻辑/渲染线程分工对齐 RHI 27 节）；需求与设计要点记录于 `Engine_Material_Design.md` §6 首条；

---

> （注：部分内容可能由 AI 生成）
