# 体素化 AO 设计（Engine_VoxelAO_Design）

> **⛔ 本档已归档（2026-11-20）：P4-17 整体退役。**
> 退役拍板 = 用户「删了吧，有 SSAO 目前够了」——推翻同日 v0.9 的「角色降级、不随 DDGI 撤」定位。理由：DDGI 档本就不乘 AO（自身口径），AO 只剩「无 GI/低端档/网格外」三种边缘情况；而它带来 BUG-13（动态物体不刷新 + 同数量替换幽灵暗角）、每轮光照改动都要过的互斥判断、调试件去留、「整体均匀变暗」断案等持续成本；再叠加 `enabled` 缺省 `false`（现网画面从未有它的贡献），删除对观感零影响。
> **同日整链删除**：`voxelAO.compute` / `voxelAOBlur.compute` / `voxelize.compute`、`VoxelizationPass` / `VoxelGrid` / `VoxelAoPass` / `VoxelAoDebugReadback` / `MeshVoxelGeometry`、`VoxelAoSettings`、`VoxelAoRebuildMenuCommand`、set1 binding8/9（`sampler3D voxelAO` + `VoxelParamsBlock`）、`javelinVoxelAO` 环境项乘子、`tools.voxelAoRebuild` 命令、项目设置组与 i18n。批 E 五项与「整体均匀变暗」断案一并作废。
> **保留价值**：本档 §9 的排障实录（坑 #42/#43/#44 与采样偏移口径）与 §11 的三条分析，是 P4-05 首版投射线若走体素 DDA 时的直接输入；代码可从 `dc1e9e0` 取回（`git show dc1e9e0:<path>`）。
> **✅ 2026-11-21 关账**：用户编译 + 实机确认「没问题了」——删除后无残留引用，环境项回到 `ambient.rgb * ambient.a`，旧工程设置档里的 `voxelAoSettings` 段被默默忽略（`readSerializable` 无键即保默认，多余键不报错）。
>
> 以下为历史内容（v0.9 及以前），不再更新。

> v0.9（2026-11-20）｜工程台账 P4-17｜**定位扩展**：DDGI（P4-05）的前置底座 + **无 GI / 低端档兜底**（原「前置轻量档」口径见 §11）
> v0.8（2026-11-19）｜工程台账 P4-17｜四期施工序 ⑥（DDGI，P4-05）的前置轻量档
> 状态：批 A~D 全链落码 + **同日五轮实机排障**（见下）；**「整体均匀变暗」口径问题未收口**（表面定向读数诊断件已落码待跑，见 §9 末尾）；编译/运行归用户（红线 1）；**`.shader` 与内置 `lighting.frag` 产物必须重导**（注入器改动强制项）
>
> **2026-11-19 排障轮实装记录（五轮，坑 #42/#43/#44）**：
> ①「完全无效果」= 编辑器网格辅助线（GRID_SIZE 8000）混入体素化收集、包围盒撑到 ±4226 → 体素 66 米/格、射线一步跳空——修 = 收集按 `castShadow=false` 过滤（坑 #42）；
> ② AO 求值 kernel 两轮数学修正：while 跳出段吞掉贴邻几何（改为「自身壳厚度」门控跳越）+ 2× 假基线归一化（改为单表面解析基线 `vis / [0.5·(1+r(1-ln r))]`，r=偏移/maxDist）——修后读回实证 AO 图健康（均值 0.89、开阔 66%≥240、贴地/穿物体梯度正确）；
> ③「均匀变暗」一轮 = 新建 set1 只写 binding0~7、空白 binding8/9 参与绘制（08114 UB）——修 = 建立时全 binding 写占位初值（坑 #43）；
> ④ 03047 三轮不治（隔帧→隔两帧仍撞）= 共享 set 原地 update 无安全时机（pending = 已提交未完成，fence await 只护本槽）——终案 = **换整套 set1 不原地 update**，旧 set retire 退役（坑 #44，实测全清）；
> ⑤ 采样偏移 1→2 AO 体素（1 体素 0.086 < 膨胀壳厚 0.15，采样点留在壳内采到实体体素中间值）——**视觉仍未恢复**，待「表面定向读数」（占用图空邻居外推处的实际 AO 值分布，`VoxelAoDebugReadback.readSurfaceSamples` 已落码）断案；参考对照件 = Babylon IBLShadows（体素遮挡单件，`GI_RT_OpenSource_Reference.md` §4：表面点求值 + 二值命中 + 双边模糊，与我们的体素中心距离加权口径有结构性差异）。

## 1. 定位

当前环境光是一条常量项（`LightingParamsBlock.ambient`，着色端 `lighting.ambient.rgb * lighting.ambient.a`，见 `ShaderInjector` 注入段 L188）。它没有遮蔽概念：缝隙、桌底、墙角与开阔地面一样亮，是四期 ①~⑤ 之后最显眼的"光照不真实"来源。

体素化 AO 的做法：把场景栅格化成 3D 占用图，逐体素打半球射线统计"看得见天的比例"，运行期按世界位置三线性采样，把结果乘到环境项上。

与 SSAO 的分工（`Engine_SSAO_Design.md`）：

| 维度 | SSAO（已有，order=10） | 体素 AO（本档） |
|------|------------------------|-----------------|
| 信息来源 | 屏幕空间 G-Buffer 世界位置 + 法线 | 完整场景占用（含离屏遮挡） |
| 遮挡厚度 | 无厚度概念，靠半径/偏置调 | 有（射线碰到体素即截止） |
| 覆盖范围 | 仅屏内可见表面 | 全场景，屏幕外物体也参与遮挡 |
| 运行频率 | 每帧 | 一次性（场景静态，重建才跑） |
| 落点 | 后处理链 | 环境项（光照阶段） |

它不是 SSAO 的替代品：屏幕空间负责近处接触阴影的锐度，体素 AO 负责大尺度环境遮蔽（墙角发暗、室内整体压暗）。首版两者共存、可选叠加。

**同时它是 DDGI 的前置**：DDGI 探针更新需要一份"可以打射线的场景表示"，体素占用网格（后续加 albedo）就是那份表示。P4-17 先把体素化、DDA 射线步进、3D 纹理采样这三套基建建好，P4-05 再往上加探针网格 / 辐照度 atlas / 矩插值。

## 2. 首版范围

**做**：

- 单张 128³ 占用网格（R32_UINT，`imageAtomicOr` 位标记），覆盖场景包围盒（AABB 外扩一格）
- 静态体素化：场景加载后 / 编辑器手动重建时一次性跑
- AO 网格 64³（R8_UNORM），逐体素半球 16 条射线、3D DDA 步进，输出标量可见度
- 运行期三线性采样，只乘环境项（`ambient.rgb * ambient.a * ao`）
- 开关 + 强度 + 分辨率等参数落设置窗「图形」页新组，随现有恢复默认按钮

**不做（后置）**：

- 多级 clipmap（相机移动跟随）：首版单张覆盖整个场景，精度不足由 128³ 分辨率硬扛
- 方向性 AO（分半球/分轴向遮蔽）：DDGI 阶段一起做
- albedo 反弹光：归 DDGI
- 动态物体重体素化：首版只在显式重建时刷新（动态物体走动会导致 AO 停留在旧位置，文档标注）
- 与 SSAO 的智能混合（取 min / 相乘）：首版各自独立叠加

[[P1]]（**2026-11-19 用户拍板**）体素化路径 = **GPU compute 读 mesh 顶点/索引缓冲**：`MeshUpload` 建 buffer 时补 `BufferUsage.STORAGE`（纯代码侧改动，`.smesh` 产物不含 usage、无需重导）。CPU 路径作废（需 `keepCpuCopy=true` 且产物为序列化字段，要全量重导并常驻顶点内存）。

[[P2]]（**2026-11-19 用户拍板**）分辨率档位 = **占用 128³ + AO 64³**（内存 ~8 MB + 0.25 MB）；设置里保留 64/128/256 与 32/64/128 下拉，默认取 128/64。双 128³ 作为可选档（接触阴影更锐、AO 计算量 ×8）。

## 3. 数据面

### 3.1 资源

| 资源 | 类型 | 格式 / 用途 | 尺寸 |
|------|------|-------------|------|
| `voxelOccupancy` | `TextureType.TYPE_3D` + `TextureUsage.STORAGE`（可选 SAMPLED 供调试可视化） | `R32_UINT`，逐体素位标记（1 = 被几何覆盖）；创建时全零上传（`TRANSFER_DST`），避免布局转换丢弃后读到残渣 | N³（缺省 128） |
| `voxelAO` | `TYPE_3D` + `STORAGE` + `SAMPLED` | `R8_UNORM`，逐体素可见度 [0,1]（1 = 开阔）；创建时全零上传（`TRANSFER_DST`） | M³（缺省 64） |
| `voxelAOBlur` | `TYPE_3D` + `STORAGE` + `SAMPLED` | `R8_UNORM`，模糊产物 = 渲染侧最终采样面（光照侧读这张，不读原图） | M³（缺省 64） |
| `voxelSamplerNearest` / `voxelSamplerLinear` | `Sampler` | 占用图是 `r32ui`，整数格式采样必须 NEAREST；线性那只服务运行期 AO 三线性 | — |
| `voxelParams` | UBO（hostVisibleCoherent） | 见 3.2 | 64B |

3D 纹理底座已验收（`doc/RHI_Texture_Types_Design.md`，RHI-13 跨帧布局记忆），`TextureDesc` 带 `depth` 字段与 `validateShape`，`DescriptorUpdate.storageTexture(...)` / `sampledTexture(binding, texture, sampler)` 可直接用。

占用网格用 `R32_UINT` 而非 R8：体素化是多三角形写入同一体素，必须原子写；GLSL `imageAtomicOr` 对 `r32ui` 是保证支持的路径。AO 网格是"一 voxel 一线程、一写者"，无竞争，可用 R8。

### 3.2 参数

`VoxelParams`（UBO，字段顺序与着色端 `layout(std140) uniform` 对齐）：

| 偏移 | 字段 | 语义 |
|------|------|------|
| 0 | `vec4 originResolution` | xyz = 网格原点（世界坐标），w = 占用分辨率 N |
| 16 | `vec4 voxelSize` | xyz = 单体素边长，w = AO 分辨率 M |
| 32 | `vec4 aoParams` | x = 射线数，y = 最大射线距离（世界单位），z = 强度，w = 启用位 |
| 48 | `vec4 gridBounds` | xyz = 网格上界（origin + N × voxelSize），w = 保留 |

### 3.3 几何来源

GPU 体素化需要逐三角形遍历，数据来自 `Mesh` 的 GPU 缓冲：

- 顶点缓冲：绑定为 `STORAGE_BUFFER`（`DescriptorUpdate.storageBuffer(binding, buffer, 0, size)`），着色端按 `VertexLayout.Attribute` 的 `location` / `offset` / `stride` 取 POSITION 三分量
- 索引缓冲：`IndexType.UINT16` / `UINT32` 两分支（按 `mesh.indexType`）
- 每条 mesh 一次 dispatch：`dispatch(ceil(triangleCount / 64))`，world 矩阵与三角形区间走 push constant（`ComputePipelineDesc` 的 `pushConstantSize`）
- 子网格不切分：按 `indices` 全量体素化（区间重叠无副作用，原子写幂等）

前置改动：`MeshUpload` 建 `vertexBuffer` / `indexBuffer` 时补 `BufferUsage.STORAGE`（`BufferUsage` 是位组合，VERTEX|INDEX|STORAGE 合法）。这是运行时 flag，`.smesh` 产物不含 usage，**不需要重导**。**（2026-11-19 已落码，`MeshUpload` 两处 `BufferDesc`）**

几何读取面：`Mesh` 的 `vertexBuffer` / `indexBuffer` / `layout` / `indexType` / `indexCount` 是 package-private 运行态字段，体素侧经同包桥接类（`render.mesh` 包内的 `MeshVoxelGeometry`）读取，不把 GPU 句柄暴露到用户 API（批 B 落地）。

替代路径（已作废，见 §2 [[P1]]）：CPU 读 `Mesh.attributes` 需 `keepCpuCopy`，而它是**序列化字段**，现有产物为 false——要全量重导 `.smesh` 并常驻顶点内存。

## 4. 计算链

三个 compute kernel（各一个 `.compute` 资产，`ComputeShaderImporter` 走 shaderc COMPUTE、入口恒 main、不注入引擎声明，源码手写全部 `layout`）：

1. **`voxelize.compute`** — 逐三角形三轴投影，保守光栅：取三角形世界 AABB，沿主轴（法线绝对值最大分量）投影到 2D，在 2D 包围矩形内逐像素做重心坐标内外测试，命中的 (u, v) 按另一轴深度 DDA 填充 thickness（最小 1 体素），写 `imageAtomicOr(voxelOccupancy, ivec3, 1)`。经典 binvox 三轴投影的简化版：单 dispatch 内直接三维遍历 AABB 也可行，按三角形体积决定策略。
2. **`voxelAO.compute`** — 逐 AO 体素（`local_size 4×4×4`，`dispatch(M/4, M/4, M/4)`）：以 AO 体素中心为原点，按「z 均匀 + 方位角均匀」撒 `rayCount` 条**球面**射线（种子由体素坐标哈希，逐帧稳定不闪噪），单步 0.5 × 最小占用体素边长在占用图上步进，命中即截止；**按命中距离加权**（`hit ? t/maxDist : 1.0`）累加可见度，写 `imageStore(voxelAO, ivec3, vec4(ao))`。首版刻意不做法线半球：省掉法线估计（空体素处梯度为 0 会退化），靠距离权重照样让贴面/夹角变暗；对应代价 = 采样侧必须沿表面法线偏半个体素（见 §5.2）。
3. **`voxelAOBlur.compute`**（首版做）— 3×3×3 加权盒式（中心 4 / 面邻 2 / 棱角 1），压掉射线数有限带来的噪点与体素阶梯；`voxelAO → voxelAOBlur` 单向读写成对，避免读写同图的 RAW 竞态（同格式同布局相消时执行器不额外插屏障）。

### 4.1 触发点与执行面

体素化是**一次性任务**，但**执行面改走帧图 compute Pass**（v0.3 修正原「独立命令缓冲 + fence」方案）：

- `VoxelizationPass implements RenderPass`：`addToGraph` 里 `graph.addComputePass(...)`，声明 `addTextureStorageWrite(occupancy)` + 逐网格 `addBufferRead(vertex/index)`；屏障/布局由 RHI 图执行器自动生成（先例 = `ComputeSsboTest`、`TextureTypesTest` 的 storage image 写）
- **结构稳定 + 派发门控**：Pass 每帧恒挂载（structureHash 不抖），回调里 `if (!pendingRebuild) return;`，重建完成即消费标志 → 后面帧零 dispatch
- 逐网格一次 dispatch：每网格一个描述符集（`b0` 顶点 SSBO / `b1` 索引 SSBO / `b2` 占用 storage image / `b3` 参数 UBO），push constants 带 world 矩阵 + 三角形区间 + 步长/偏移 + `indexU16`
- 描述符集在**录制前**（`prepare()`）批量 update，回调内只 bind + dispatch（避免「绑定后改描述符」的 UB）
- 触发时机：① 开关首次打开；② **编辑器命令「工具｜重建体素 AO」**（`tools.voxelAoRebuild` → `JavelinEngine.requestVoxelRebuild()` → 两个 Pass 的 `requestRebuild()`，标志 volatile、可跨线程置位，2026-11-19 已落地）；③ 改占用分辨率；④ 渲染对象数量变化（`renderers().size()` 比对，首版廉价判据；移动/旋转同数量物体不会触发，走 ②）
- 首版 Pass 挂在场景 Pass 尾部（overlay 位），AO 消费在下一帧生效（一帧延迟，可接受）。**v0.6 修正（2026-11-19 落码时改判）**：改挂 **`ShadowPass` 之后、几何 Pass 之前**（`pipeline.addPass(voxelizationPass, "Shadow")`、AO Pass 再插其名后）——挂尾部时同帧的光照读到的是上帧图，重建首帧会采到全零 AO（环境项整屏发黑一帧）；前置后「体素化 → AO → 模糊」在同一帧光照消费之前完成，`Voxelize`→`VoxelAO`→`VoxelAOBlur` 的依赖由占用图「storage 写 / 采样读」声明自动推导

**索引读取的对齐约束**（v0.3 新增）：`MeshUpload` 建索引缓冲时按 4 字节补尾零——compute 把索引缓冲按 `uint` 字读，UINT16 奇数索引的末字会跨出缓冲；补位不改 `indexCount`，绘制路径不受影响。

**网格收集与重建**（v0.4 新增）：`VoxelizationPass` 从 `RenderingSystem.renderers()` 收集全量渲染对象（同网格同世界变换去重，多材质/多子网格只体素化一次），按网格世界 AABB 并集建网格；每次重建**换新图**（旧图无法原地清零，残留占用会造出假体素），旧图/旧描述符集经 `retireResource` 两帧退役。渲染对象数量变化即触发重建（首版口径，动态内容仍待显式命令）。

障碍：占用图在 kernel 内用 `imageAtomicOr` 写，图执行器按 `StorageWrite → Sampled` 声明生成 `GENERAL → SHADER_READ_ONLY` 屏障（批 D 由 AO 内核算起）；`imageAtomicOr` 对 `r32ui` 是 GLSL 保证支持路径。

**模糊图的最终消费面（v0.6 落码补）**：`VoxelAoPass` 之后没有任何 Pass 声明读 `voxelAOBlur`，而它正是 set1 binding8 的采样面——图执行器不知道这次采样，`storage 写（GENERAL）→ 光照采样` 拿不到写后读屏障，也没有布局转换。修法 = 消费方显式 `addTextureRead(rs.voxelAoTextureFor(view.name()))`（HYBRID `LightingPass`、FORWARD `OpaqueForwardPass` / `TransparentForwardPass`；`UnlitForwardPass` 不乘环境项故不声明），声明用的纹理与 set1 指向同一张（判定同 `syncVoxelAo`，无网格即占位白图）——转换本身即屏障。

## 5. 渲染侧消费

### 5.1 描述符与 UBO（**2026-11-19 已落码**）

- `frameStorageLayoutDesc()`（set1）新增 `binding8 = COMBINED_IMAGE_SAMPLER`（`FRAGMENT`），指向 **`voxelAOBlur`（模糊图，不是原图）**；`binding9 = UNIFORM_BUFFER`（`voxelParams`）。现有 binding0~7 不动
- 采样器：`Filter.LINEAR`（三线性）、`MipmapMode.NEAREST`、三轴 `CLAMP_TO_EDGE`、无比较（`R8_UNORM` 支持线性过滤，`VoxelGrid.samplerLinear()` 已备）
- **占位资源**（set1 布局要求 binding 必须有有效描述符，关开关时不能留空）：`voxelAoFallback` 1×1×1 全白 `R8_UNORM` 3D 图 + `voxelParamsFallback` 全零 64B UBO + NEAREST 采样器，惰性创建、常驻
- **重指时机**：`VoxelGrid` 由 `VoxelizationPass` 建好/换新后登记到 `RenderingSystem.setVoxelGrid(...)`，各视口在 `collectFrame` 末尾 `syncVoxelAo` 比对身份后重写 set1 的 binding8/9（身份相等零开销）

### 5.2 注入器（**2026-11-19 已落码**）

`ShaderInjector`（`LIGHTING_BLOCKS`）改动：

- 新增声明：`uniform sampler3D voxelAO;`（binding8）+ `uniform VoxelParamsBlock { vec4 voxelOriginResolution; vec4 voxelVoxelSize; vec4 voxelAoParams; vec4 voxelGridBounds; } voxelGI;`（binding9，std140）
- 新增函数 `float javelinVoxelAO(vec3 worldPos, vec3 worldNormal)`：
  1. `voxelAoParams.w > 0.5` 门控，否则返回 1.0；
  2. **采样点沿法线偏半个体素**：`p = worldPos + worldNormal * 0.5 * min(aoVox)`，其中 `aoVox = voxelVoxelSize.xyz * (占用分辨率 / AO 分辨率)`——AO 求值是球面距离加权、实体体素内部恒接近 0，不偏移会在贴合面上采到实体内侧的值，整块发黑；
  3. 归一化到 `[0,1]³` 后取 `texture(voxelAO, uvw).r`，任意轴越界返回 1.0（网格只盖场景包围盒）；
  4. 强度口径 `1 - (1 - ao) * intensity`（`aoParams.z`，不夹 1——滑条 0~2；强度 0 恒 1.0 = 与关开关视觉一致）。
- L188 改为：`vec3 result = lighting.ambient.rgb * lighting.ambient.a * javelinVoxelAO(worldPos, worldNormal);`
- **`javelinLighting` 签名保持 `(viewNormal, viewPos, worldPos)`**：世界法线由 `normalize(transpose(mat3(cam.view)) * viewNormal)` 在函数内现推（`cam.view` 在注入段已有），调用方（内置 `Lit.shader` / `lighting.frag` / 用户 shader）零改动

**注入器改动 = 所有 `.shader` 产物 + 内置 `lighting.frag` 产物必须重导**（CSM 批次同样的强制项，见 `Engine_Shadow_Quality_Design.md` §9）。不重导的表现是：环境项乘算不生效（旧产物没有该采样），且校验层零 ERROR——排障时先查产物时间戳。

### 5.3 与 TAA / 后处理的关系

体素 AO 是环境项的乘子，发生在光照阶段（HYBRID 的 `LightingPass` / FORWARD 的着色），不依赖 G-Buffer 世界位置，因此：

- TAA 的抖动不影响它（世界坐标未抖动，采样稳定）
- SSAO 与之独立叠加（SSAO 在后处理链乘在本帧颜色上）
- FORWARD 档同样生效（不读 G-Buffer），但需确认 Forward 的注入段走的是同一个 `javelinLighting`

## 6. 设置

新增 `VoxelAoSettings`（`@SettingGroup`，挂 `GraphSetting` 图形页），字段全部走 `@InsField` + `@InsSlider`（用户刚落地的滑条 + 恢复默认按钮自动继承）：

| 字段 | 类型 | 缺省 | 语义 |
|------|------|------|------|
| `enabled` | boolean | false | 总开关（关时不建资源、不派发） |
| `voxelResolution` | int（`@SettingSelect` 64/128/256） | 128 | 占用网格分辨率 |
| `aoResolution` | int（`@SettingSelect` 32/64/128） | 64 | AO 网格分辨率 |
| `rayCount` | int（`@SettingSelect` 8/16/32） | 16 | 半球射线数 |
| `maxDistance` | float（滑条 1~50，步 0.5） | 10.0 | 射线最大距离（世界单位） |
| `intensity` | float（滑条 0~2，步 0.01） | 1.0 | AO 强度（`1 - (1-ao)*intensity` 口径） |

即时生效口径：与现有后处理组一致，`apply()` 推引擎；改分辨率触发重建。

## 7. 批次划分

| 批 | 内容 | 验收 |
|----|------|------|
| A | RHI/core 基建：`MeshUpload` 补 STORAGE（✅已落）、`voxel` 包骨架、3D 资源创建/上传、一次性 command buffer 派发骨架（**set1 binding8/9 与体素采样器未做 → 并入 D**） | 编译通过 + 空跑不崩 |
| B | `voxelize.compute` + 占用网格：**全链落码（2026-11-19）**——`MeshVoxelGeometry`（几何读取面）、`VoxelGrid`（R32_UINT 3D 占用 + 64B 参数 UBO + 全零上传）、`VoxelizationPass`（在途挂载 + 派发门控 + 逐网格描述符集/push）、`JavelinEngine.createRenderPipeline` 挂到管线尾；占用量级与轮廓核对待验收 | 体素数量级合理、与场景轮廓对得上 |
| C | **AO 求值 + 3D 模糊全链落码（2026-11-19）**——`voxelAO.compute`（球面 16 射线 + 0.5 体素步长 + 命中距离加权）、`voxelAOBlur.compute`（3×3×3 加权盒式）、`VoxelGrid` 扩 AO 原图/模糊图（`R8_UNORM`）+ 双采样器、`VoxelAoPass`（两个 compute Pass + 网格/参数变化门控 + 描述符集换绑退役）、引擎挂在体素化之后 | 单独可视化 AO，墙角/桌底明显压暗 |
| D | **落码（2026-11-19）**——`frameStorageLayoutDesc` 加 binding8/9、`RenderingSystem` 占位资源族 + `syncVoxelAo` 按网格身份重指、`ShaderInjector` 新增 `javelinVoxelAO` 并作环境项乘子（`javelinLighting` 签名不变，世界法线现推）、关开关即释放网格与 AO 描述符集；`VoxelAoSettings` 与语言包已落于 B | 实机场景缝隙变暗、开阔面不变；**须先重导 `.shader` 与内置 `lighting.frag`** |
| E | 验收与收口：SSAO 共存、TAA 无抖动、FORWARD 档、性能（重建耗时）、文档与坑记 | 用户实机验收 |

## 8. 验收清单

1. 场景加载 → 重建体素 → 环境光遮蔽效果可见：墙角、桌底、物体接触面变暗，开阔地面基本不变
2. `intensity=0` 与 `enabled=false` 视觉一致（干净关闭）
3. 分辨率三档切换 + 重建不崩、不泄漏（资源替换走 retire 两帧）
4. 与 SSAO 同时开启无冲突、无重复压暗到发黑
5. 相机贴近地面/远离场景边界时采样不出现硬边或全暗（越界返回 1.0）
6. FORWARD 档行为一致（或显式 warn 忽略，按拍板）
7. 重建耗时在编辑器可接受（128³ + 64³ + 16 射线，目标 < 200 ms，5060 8G 实测）
8. `[VVL]` 零 ERROR（描述符可见性、布局转换、原子写格式支持）

## 9. 风险与后置

- **精度**：单张 128³ 覆盖大场景 → 单体素边长可达数米，近处接触阴影粗糙。缓解：与 SSAO 叠加；后续 clipmap（相机附近高精度、远处粗）
- **动态物体**：不重体素化则 AO 停留在旧位置（物体走动后原地仍暗、新位置无遮蔽）。首版接受，编辑器提供手动重建
- **mesh buffer 用途**：补 `STORAGE` 后需确认所有后端驱动对 vertex buffer 同时作 SSBO 的兼容性（Vulkan 规范允许，实测为准）
- **注入器强制重导**：与 CSM 同款风险，文档与提交信息都要写清
- **资源生命周期**：3D 纹理随分辨率变更重建，沿用 `retireResource` 两帧退役，避免 GPU 在飞
- **收集侧不吃相机**（2026-11-19 记录）：`VoxelizationPass` 取 `renderingSystem.renderers()` 全量、无 layerMask 过滤 —— AO 是场景属性，不由单台相机决定。将来落视锥剔除（REND-01）时**不要**把相机剔除 / `camera.cullingMask` 漏进来，否则转动相机就会改变场景遮蔽、出现「墙上的暗角随视角跳变」；要区分就给体素化自己的参与掩码。口径详见 `Engine_Shadow_Quality_Design.md` §4.7-1
- **同格式同布局的 RAW 不插屏障**（2026-11-19 落码时实测代码层结论）：`RenderGraphImpl` 的屏障计划只在**目标布局 ≠ 当前布局**时才插 `pipelineBarrier`，所以「storage 写 → storage 读同一张图」这条链（都停 `GENERAL`）拿不到写后读屏障。规避 = 所有跨 kernel 读一律走**采样读**（`addTextureRead` → 转 `SHADER_READ_ONLY`，转换本身就是屏障）：占用图 → AO 读、AO 原图 → 模糊读、模糊图 → 光照读，三处都是这个形态。将来加 kernel 时不要图省事改成 `addTextureStorageRead`
- **`R8_UNORM` 可作 3D storage image**：本机已由 `TextureTypesTest`（RHI-13 验收）实证（同格式 3D 写入 + 读回逐字节一致），故 AO 图按原设计用单通道
- **AO 首版无方向性**：球面距离加权而非法线半球，因此贴面亮暗靠「命中距离短」体现，采样侧偏半体素是必要补偿（§5.2）；若实机发现贴面过黑/欠黑，第一手调 `maxDistance` 与偏移量，不要先改 kernel
- **开关改变图结构**：`enabled=false` 时两个 AO Pass 不进图，`structureHash` 变化会重建图缓存；开关属低频操作，接受
- 后置：方向性 AO、albedo 反弹、DDGI（P4-05）、mip 链（若 DDGI 采样需要，先补 RHI-14）

## 10. 待拍板

- [[P1]] 体素化路径 — **已拍板 GPU**（2026-11-19）
- [[P2]] 分辨率 — **已拍板 128³ + 64³**（2026-11-19）
- [[P3]] FORWARD 档是否一并支持（读取同一 UBO/纹理即可，成本低）还是首版只对 HYBRID 生效
- [[P4]] 触发时机是否加"场景加载自动重建"（首版仅手动命令更可控，推荐手动 + 设置开关首次打开时重建一次）

## 11. 定位与邻接拍板（2026-11-20）

> 背景：用户问「有 DDGI 和光追后是否就没必要体素 AO」。结论 =**不是没必要，是角色降级**；本批把定位与乘算口径一次定死，避免 DDGI 落地时出现双重遮蔽。

- **[[P5]] 定位（已拍板）**：P4-17 = **DDGI 前置底座 + 无 GI / 低端档兜底**（不再是「仅前置轻档」）。不随 DDGI 落码而撤，理由四条：
  ①DDGI 是**低频**探针辐照度（间距 ≥1~2m + 分帧刷新 + 矩插值），表达不出接触尺度遮蔽；
  ②探针只覆盖相机滚动体积，**网格外回落 `ambient` 常量**，那片区域仍需 AO；
  ③**动态物体滞后**（不重体素化/不重探针时遮蔽停在旧位置），AO 至少是静态一致的世界空间量；
  ④**成本量级**——AO 是重建时算一次、每帧近零；DDGI 是分帧刷新 + 大量射线，光追是每帧每像素射线。低端/无光追硬件档保留 AO 最划算。
  另外体素化/追踪那条通道本就复用给 DDGI（届时在同套体素表示上加 albedo），**AO 图保持 `R8_UNORM` 单通道不动**——不在本批为中间档预留 albedo。
- **[[P6]] 环境项乘算互斥（已拍板，落 DDGI 时执行）**：现在 `javelinLighting` 是 `ambient * javelinVoxelAO(...)` 恒乘。DDGI 辐照度**已含遮挡**，再乘 AO = 双重压暗（缝隙发黑、暗部糊死）。规则：
  | 环境项来源 | 是否乘 AO |
  |---|---|
  | `CONSTANT`（④ 步骤一，现状） | 乘（AO 是唯一遮挡） |
  | `SH9`（④ 步骤二，后置） | 乘（天空光无方向性，AO 有用） |
  | DDGI / 光追接管 | **不乘**，由 GI 自身表达遮挡；仅探针网格外回落 `ambient` 的区域恢复乘 |
- **[[P7]] SSAO 收敛（已拍板，落 DDGI 时执行）**：SSAO（后处理乘整帧颜色）+ 体素 AO + DDGI 三层叠乘必须降为「GI 一层 + 至多一层补强」，否则必然过暗；具体保留哪层按 DDGI 实测决定，优先保留 GI。
- **真正的删除时机**：DDGI 在目标硬件（5060 8G）稳定跑住且性能可接受之后，不是 DDGI 落码当天；光追（P4-06）落地后 AO 退成「无 AS 能力/低画质档」的 fallback（AS 首范围仅静态几何，动态物体仍需无 AS 方案兜底）。
- **中间档否决**：不做 SSGI、不做「体素加 albedo 一次弹射」——用户 2026-11-20 拍板直接等 DDGI（`Engine_Backlog.md` P4-05 备注①）。
