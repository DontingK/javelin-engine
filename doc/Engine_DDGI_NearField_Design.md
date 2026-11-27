# DDGI 近景窗口（相机跟随体素场）设计（Engine_DDGI_NearField_Design）

> **⛔ 本档已作废（2026-11-27）：随 P4-05 DDGI 整体退役（见 `Engine_DDGI_Design.md` 头部横幅）**——原 v0.3 预期的「待光追 trace 走 AS 求交后作废」由退役直接兑现：体素层与探针层不复存在，本档立论前提消失。纯留档。

> v0.3（2026-11-26）｜**⏸ 本档冻结、不排期**：用户拍板 DDGI 定位 = **有界场景 GI**（关卡内/室内），无界（自由飞行、大世界）GI 交给光追；`DdgiProbePass` 已补「相机离开体素场即冻结网格」口径（`Engine_DDGI_Design.md` §13.9）。滚动窗口那一整套（`copyImage` + slab 增量体素化 + atlas 位移重投影）**待光追 trace 走 AS 求交后整档作废**——届时体素层不复存在，本档的立论前提（体素分辨率随场景变大塌陷）随之消失。恢复条件见 §8 更新。
>
> v0.2（2026-11-23）｜**补 §10 动态物体（角色）**：记录性结论，不动代码。现状 = 角色会被并入"静态集"，世界矩阵进签名 → 移动时签名每帧变、两帧稳定门控恒 false → 体素图**冻结在角色最后一次静止那一刻**（幽灵体素 + 新位置不遮挡），角色停下才整场重建。已确认的正面事实：`ddgi_trace.compute` 的命中点遮挡采主视口 CSM，角色对直射的遮挡**已经是动态的**。缺的只有「基色反弹 / 占据位 / 探针降权」三项，口径定为「动态物体只接收 GI，发射与遮挡交给 rayQuery（P4-06）」。见 §10。
>
> v0.1（2026-11-23）｜**后置方案件 · 低优先级**：本档只出方案，不落码、不排入 `Engine_Roadmap.md` §4 施工序。触发条件见 §8。
>
> 背景：用户拍板「GI 只做近景，范围跟着相机走」。核对现状后确认——**探针块已经是相机跟随的，缺的是体素层**：`DdgiVoxelizer` 至今仍是「整世界一张 N³ 体素图」，场景 AABB 一大，近景 GI 的空间分辨率就跟着塌（§3）。本档补这一层，并把「跟随时引发的全量重扫」一并收掉。
>
> 不做：clipmap 多级探针 / chunk 流式体素库 / 双精度世界坐标 / 第二视口 DDGI —— 全部明确出界（§5）。

## 0. 一句话主张

把 GI 的输入端从「一张全场体素图」改成「一块跟着相机走的滚动窗口体素场」，探针块与窗口**共用同一套 8m chunk 位移事件**：窗口位移走「乒乓拷贝 + 3 片 slab 增量体素化」，探针 atlas 走「位移重投影 + 新进探针全扫」。这样体素分辨率恒定（0.5m 级）、跳格从每 1.5m 一次降到每 8m 一次、远景继续由常量环境项兜底。

## 1. 现状盘点（哪层在跟、哪层不跟）

| 层 | 是否跟随相机 | 实现位置 | 现有数值 |
|---|---|---|---|
| 探针网格（辐照度/深度 atlas 的几何） | ❌ 不跟（2026-11-26 改：`anchoredToField` 锚定场内、只居中于余量；旧 `fittedToField` 整格跟随已退役） | `DdgiProbeGrid.anchoredToField`、`DdgiProbePass.ensureAtlases` | 默认 16×4×16@2m → 30×6×30 m（按场 AABB 收缩，实测 6×3×4）；上限 64×32×64 → 126×62×126 m |
| 体素场（追踪段的求交输入） | ❌ 不跟，固定覆盖**全部**静态集 AABB | `DdgiVoxelField.create`（`DdgiVoxelField.java:89`）、`DdgiVoxelizer.prepare`（`:286`） | 单张 N³ RGBA8，N = 128（默认）/ 256（上限）；`voxelSize = 场边长 / N` |
| 追踪射线长度 | ❌ 常量 | `DdgiProbePass.MAX_RAY_DISTANCE`（`:86`） | 60 m，写进 grid UBO `reservedGrid.y` 与 push constant |
| 探针历史（hysteresis） | ❌ 一跳格就清 | `DdgiProbePass.ensureAtlases`（`:351`）、`dispatchUpdate`（`:592`） | 原点一变 → `sliceCursor=0`、`firstSweepDone=false`、`blend=0` |

结论：**"范围跟相机"只实现了探针这一半**。探针块滑到哪儿，求交用的还是同一张覆盖全场、随世界变粗的体素图。

## 2. 尺度账（为什么必须动体素层）

`voxelSize = 场景AABB最长边 / N`（各轴独立，`DdgiVoxelField.create` 用 `(max-min)/resolution` 逐轴算）：

| 场景 AABB（最长边） | N=128（默认） | N=256（上限） | 128³ 显存 |
|---|---|---|---|
| 10 m（当前测试场景） | 0.078 m | 0.039 m | 8 MB |
| 40 m（小型室外场） | 0.31 m | 0.16 m | 8 MB / 67 MB |
| 200 m | 1.56 m | 0.78 m | 同上 |
| 500 m | 3.9 m | 1.95 m | 同上 |

判据：**体素边长 > 构件厚度的一半时该构件从 GI 里消失**（DDA 只认占据位，薄墙、栏杆、门框、隔断、树冠全变成空洞）。200m 场景在默认档 1.56m 体素，等于只剩大块盒子还能反弹——「近景要准」在这个档位上直接不成立。

另有两笔连带成本：

- **重建粒度**：`StaticSetSignature`（网格实例 + 世界矩阵 1e-4 量化 + 基色）一变，`DdgiVoxelizer` 就整场重体素化（一实例一 dispatch，`DdgiVoxelizer.java:169`）。大世界里任何静态物体移动、任何流式加载，都是整场重来，成本随 N³ 立方增长。
- **显存**：256³ = 64 MB 起步，还没算 atlas（1024 探针时 2.1 MB、131072 探针时 ~275 MB）。

## 3. 方案：滚动窗口体素场

### 3.1 窗口参数

推荐缺省：**W = 64 m 立方（非立方见 §6 未定项），N = 128 ⇒ 体素 0.5 m**，chunk = 8 m（每轴 8 个 chunk，128 体素 = 16 体素/chunk）。

- 显存：128³×4B = **8.0 MB**（与现在同档，但只覆盖近景）
- 0.5 m 体素能吃掉 1 m 以上的构件，门框/隔断/栏杆在 2 个体素内可分辨 ⇒ 近景渗色与阴影边界可接受
- 窗口 64 m 对近景 GI 足够：DDGI 本身是低频数据，远处反弹贡献本来就该交给环境项

相机跨 chunk 判据（**滞回带，不做每格吸附**）：设当前窗口中心 chunk 为 `c0`，相机所在 chunk 为 `cc`；`|cc − c0| > 1` 时整窗位移 1 chunk 并更新 `c0`。等价于相机在中心 chunk 及其 6 邻域内自由移动都不触发位移 → 位移间隔 ≈ 8 m 行走，而现状是每 `spacing`（1.5~2 m）一次。

### 3.2 探针块与窗口对齐

现有 `fitOrigin` 是「锚在场 min、按 spacing 整格跟相机」，位移频次 = spacing。改为**与窗口共享 chunk 吸附**：`origin = floor((cam − E/2) / chunk) * chunk`（新增 `DdgiProbeGrid.chunkAligned`，`DdgiProbeGridTest` 补例：同一 chunk 内原点恒定、跨 chunk 只跳 chunkSize）。

跨度取 **E = W − 2·margin**，`margin = 12 m`（= 1 chunk + 2 spacing 的最低余量），即 E ≈ 40 m：

| 配置 | 三向 | spacing | 探针数 | 跨度 | atlas（辐照度/深度） | 一圈帧数（128/帧） |
|---|---|---|---|---|---|---|
| 推荐 | 24×8×24 | 1.5 m | 4608 | 34.5×10.5×34.5 m | 68×68 tile → 680×680 / 1224×1224 ≈ 8.6 MB | 36 |
| 省档 | 16×6×16 | 2.0 m | 1536 | 30×10×30 m | 40×40 tile → 400×400 / 720×720 ≈ 3.4 MB | 12 |

固定探针数 ⇒ **atlas 版面恒定**，`ensureAtlases` 不再换代重建（只剩位移时的值重投影），把「换代 → 首圈不混合 → 抖动」那条链整根断掉。

垂直跨度口径：探针 Y 只铺 10.5 m（≈ 相机上下 ±5 m）。依据是近景 GI 的垂直相关性带宽远小于水平，HDRP APV / 团结引擎的密度也是这么分配的；若以后需要覆盖高层，走 §6 未定项 ③。

### 3.3 位移时的增量体素化

窗口位移 1 chunk（8 m）后，新进入区域 = **3 片 slab**（±X 各一片、±Y、±Z 共 3 个方向各 1 片，实际是 3 个 8m 厚的板，取决于位移方向单轴位移只有 1 片）：

- 内部重叠区：128−16 = 112 体素边长，112³×4B ≈ **5.6 MB** 拷贝
- 新进入 slab：16×128×128 体素 = 262144 体素 ≈ **1.0 MB** 的写入区
- 需要重体素化的三角形：host 侧按实例 AABB 与 slab 相交裁剪（不在 slab 内的实例直接不派发）；保底口径是「一 invocation 一三角形 + slab 早期剔除」，避免裁剪失败时炸 dispatch

两条实现路线，**推荐 A1**：

- **A1 · GPU 内拷（推荐）**：窗口做乒乓两张 `R8G8B8A8_UNORM` 3D 图，位移时对重叠区做 image→image 拷贝，再跑 3 片 slab 的 `voxelize.compute` 写入新图，描述符切到新图。**RHI 缺口：`CommandBuffer` 目前只有 `copyBufferToTexture` / `copyImageToBuffer`（`CommandBuffer.java:122/129`），没有 image→image —— 必须新增 `copyImage(src, dst, srcRegion, dstRegion)`（`vkCmdCopyImage` + 布局转 `TRANSFER_SRC`/`TRANSFER_DST`）。这是本档唯一新增的 RHI 面，约 20 行，与 RHI-14（mip/逐层）无关。**
- **A2 · CPU 重灌（不推荐）**：不做乒乓，每次位移整窗 8 MB 从 host 重传 + 全场景重体素化。省掉 RHI 改动，但把「一次位移」从 5.6 MB 拷贝变成 8 MB 上传 + 全三角重扫，且与「增量」这个初衷相违。

`VoxelParams`（`DdgiVoxelField.java:165`，64B：origin+resolution / voxelSize / gridBounds / reserved）布局不变，位移时只重写参数块；`ddgi_trace.compute` 的 DDA 与 `insideGrid` 完全不用改 —— **追踪段对"窗口"这件事无感，代码零改动**。

### 3.4 探针历史的重投影

现状：网格原点一变，`sliceCursor=0`、`firstSweepDone=false`、update 段 `blend=0`（`DdgiProbePass.java:351`、`:592`）⇒ 整个块重扫一圈（4608 探针 / 128 每帧 = 36 帧），期间画面无历史混合。位移放到 chunk 级后频次已降 4~5 倍，但仍会在每 8m 出一次 0.6 秒的重聚。

重投影规则：位移 `k` 个探针格（`k = chunkSize / spacing`，需整除，不整除则取 `floor` 并把余量折进 margin），新 atlas 的探针 `(x,y,z)` 直接搬旧 atlas 的 `(x−kx, y−ky, z−kz)`：

- 进入边界的两层探针（`x < kx` 等）无历史 ⇒ `hysteresis = 0` 走全新扫描（与首圈规则一致，`firstSweepDone` 只对这两层置 false）
- 搬运用一张 atlas 乒乓（辐照度 ~3.7 MB / 深度 ~6.0 MB）：可以用一支小 compute（`ddgi_shift.comp`，一 invocation 一 texel，直接读旧写新，省掉新增 RHI 命令），也可以复用 A1 的 `copyImage` 做 tile 级 region 拷贝——**推荐 compute 版**，因为 tile 内边框（1px 外框）语义是「内区 + 手工双线性」，按 texel 搬最不容易错位
- 探针 UBO（`grid.originSpacing`）随位移直接重写，消费端 `javelinDDGI`（`ShaderInjector.java:180`）的三线性与 `javelinDDGIInside` 对「网格平移」天然正确

### 3.5 射线距离

`MAX_RAY_DISTANCE` 从 60 定值改为 **`min(60, 0.625·W)` ⇒ 40 m**（W=64）。理由：探针块跨度 34.5 m、窗口 64 m，从块边缘探针出发仍有 ≥ 14 m 余量才出窗口；射线打满 40 m 而窗口内无几何，说明本来就该回落环境项。这条直接销掉 `Engine_DDGI_Design.md` §13 遗留第 ④ 条的一半（自适应仍不按场景 AABB 做，按窗口尺寸做，口径更简单）。

## 4. 与现有链路的关系（改哪些、不改哪些）

| 组件 | 改动 |
|---|---|
| `DdgiVoxelField` | 从「一次性 create + 首帧全零上传」改为「窗口对象 + 乒乓两张 + `shift()` 改写 origin/voxelSize」；`zeros()` 只在两图初建时跑 |
| `DdgiVoxelizer` | 分支：首次建窗 = 全量体素化；之后按 chunk 位移做 slab 增量；`StaticSetSignature` 改为「窗口内静态集签名」，签名只影响窗口内内容的正确性，不再触发整场重建 |
| `DdgiProbeGrid` | 新增 `chunkAligned(...)`（与窗口同源吸附）；`fittedToField` 保留给「非跟随模式」 |
| `DdgiProbePass` | `ensureAtlases` 去换代分支（版面恒定）；新增位移事件处理（窗口位移 → 重投影 + 两层探针重扫）；`reservedGrid.y` 写 40 m |
| `ddgi_trace.compute` | **零改动**（DDA 只认 `field` 参数块） |
| `ddgi_update.compute` | **零改动**（atlas 索引语义不变，重投影在宿主/独立 compute 里做） |
| `ShaderInjector.DDGI_BLOCKS` | **零改动**（消费端只看 origin/spacing/counts） |
| `CommandBuffer` / `VulkanCommandBuffer` | 新增 `copyImage`（A1 用）；A1 之外只有 compute 版重投影则可不加 |

## 5. 明确不做

1. **clipmap / 多级探针**（内层 1.5m + 外层 8m 跨层混合）——多级要改消费端采样链与 atlas 组织，工作量是窗口方案的 3~5 倍，且与「只做近景」的定位相悖。
2. **chunk 级体素库 / 流式淘汰（LRU）**——窗口已经是有界的，不需要库。
3. **双精度世界坐标 / floating origin**——窗口内坐标量级小，`float` 够用；这条留给「大世界渲染」独立立项。
4. **第二视口 DDGI**——现状只绑 `cameras().get(0)`，仍是既有遗留。
5. **天空盒 / SH9 作为未命中项**——仍回落常量 `ambient`（`Engine_DDGI_Design.md` §13 遗留第 ② 条），本档不接管。

## 6. 风险与未定项

1. **位移 pop**：窗口挪动的那一帧，新进入 slab 的体素化与探针重扫都不是瞬时的（1 MB 体素 + 2 层探针 ≈ 2~4 帧）。三选一：(a) 提前一 chunk 预取（相机到边界前 8m 就起位移）；(b) 接受 2~4 帧的近景 GI 缺失（有 hysteresis 与法线偏置掩盖）；(c) 位移期间对窗口边缘做 1~2s 的强度渐隐。**推荐 (a)**，代价是多一张「预取即将进入的 slab」。
2. **静态集语义**：引擎至今没有 `isStatic` 标志，静态集 = 「不透明 + `castShadow`」近似（`Engine_DDGI_Design.md` §11）。窗口化后这个近似的影响从「全场幽灵几何」缩小到「窗口内幽灵几何」，但 BUG-13 的动静分离门控仍需单独收口。
3. **垂直覆盖率**：探针 Y 只有 10.5 m。高空俯视/高层场景会看到「头顶一片环境色」。可选：窗口改非立方 W=(64, 32, 64)，`voxelSize` 逐轴独立（`DdgiVoxelField.create` 已支持），探针改 24×16×24@1.5 = 9216 探针（atlas ≈ 17 MB，一圈 72 帧）——**需拍板是否接受这笔成本**。
4. **非均匀 spacing**（Y 向粗化）会让 `DdgiProbeGrid` 的 `spacing` 从 `float` 变 `vec3`，连带消费端 `local = (p − lo) / sp` 与 `hi` 计算全改。当前 `spacing` 是标量，**本档不碰**。
5. **拷贝带宽与屏障**：位移帧要做一次 5.6 MB image→image + 一次 slab 写入，VVL 检查点 = `VUID-vkCmdCopyImage-*` 的区域边界、以及 TRANSFER→STORAGE→SHADER_READ 三段布局转换（旧版 atlas 的 00344/09600 是同型错误，必须逐段声明）。
6. **`DdgiAtlases.matches(grid)` 的替换判据**：版面恒等后恒为 true，需改成「窗口位移时也能复用」，否则会退化成每 8m 换代。

## 7. 施工批次（仅当选做时参考，不排期）

| 批 | 内容 | 验收口径 |
|---|---|---|
| N1 体素窗口（不含探针改造） | `DdgiVoxelField` 乒乓窗口 + `copyImage`（或包一层）+ `DdgiVoxelizer` slab 增量 + 位移事件 | 日志「DDGI 体素窗口就绪 W=64 N=128 voxel=(0.50,0.50,0.50)」；跨 chunk 时「窗口位移 +8m：拷贝 5.6MB / slab 3 片 / nx 实例 / xx ms」；`error.log` 零 VVL；`tools.voxelDebug` 读回单切片确认位移后几何不错位 |
| N2 探针 chunk 吸附 | `DdgiProbeGrid.chunkAligned` + `ensureAtlases` 去换代 + `MAX_RAY_DISTANCE=40` | 同一 chunk 内 `grid` 哈希不变；行走 8m 的 `grid origin` 变化次数从 4~6 降至 1；实机走位无 1.5m 周期的亮度脉动 |
| N3 位移重投影 | `ddgi_shift.compute`（texel 级搬 tile）+ 边界两层 `hysteresis=0` | 位移前后同一世界点辐照度差 < 5%（诊断 SSBO 两点读回）；位移后 2 帧内新边界探针非零 |
| N4 收口 | §6 未定项拍板结论落档；沙盘与旧档同步 | `Engine_DDGI_Design.md` 加「近景窗口」交叉引用；`Engine_Features.md` §6 相应行标注 |

## 8. 触发条件（什么时候从后置转正）

满足任一即捞起本档：

1. 出现实际关卡/场景 AABB 最长边 > 40 m（当前测试场景 10 米级，用不上）
2. 实机验收时确认「近景 GI 里薄构件塌陷」复现（判据 = 体素边长 > 构件厚度/2）
3. 走位时 1.5 m 周期的亮度脉动成为可感知问题（现状 1024 探针按 16 帧重扫，小场景尚可）
4. 需要室外关卡或载具/飞行相机

## 9. 落档位置与关联

- 本档：`doc/Engine_DDGI_NearField_Design.md`（**不登记 `Engine_Roadmap.md` §4**）
- 关联：`doc/Engine_DDGI_Design.md`（v0.5，主档；本档是其 §13 遗留 ④ 的延伸）、`doc/Engine_Backlog.md`（P4-05 备注）、`javelin-core/.../render/ddgi/*`、`javelin-rhi/.../CommandBuffer.java`
- 若拍板开做，再按 N1~N3 进 Roadmap 并同步 `AGENTS.md` 索引

## 10. 动态物体（角色）：现状 bug 与「只接收」口径

> 状态：**记录性结论（2026-11-23），不动代码**。本节只固定事实与口径，避免以后做角色/载具时踩同一个坑。

### 10.1 现状：角色会让 GI 半冻结（bug 级）

| 环节 | 事实 | 位置 |
|---|---|---|
| 集合过滤 | 只判 `isTransparent() \|\| !isCastShadow()` —— 不透明且投影的角色**会被并进"静态集"** | `DdgiVoxelizer.collect()` `:230` |
| 签名 | 每个实例的**世界矩阵**（1e-4 量化）+ 网格 + 基色一起拌进 64 位摘要 ⇒ 角色一动签名每帧都变 | `StaticSetSignature` |
| 门控 | `stableTwoFrames` 要求连续两帧同签名；角色连续移动时恒 false ⇒ **提前返回旧 field 与旧 entries，体素图不更新** | `DdgiVoxelizer.ensureReady()` `:181-190` |

直接后果三条：

1. **冻结**：角色移动期间 GI 用的还是它最后一次："停下两帧"那一刻的几何快照，新位置既不遮挡也不反弹
2. **幽灵**：旧位置留着角色的体素占位 ⇒ 原来的位置出现幽灵暗角/渗色（BUG-13 的"幽灵暗角"同型，只是动因从"数量门控"变成"签名门控"）
3. **停一次炸一次**：角色停住两帧就触发**整场**重体素化；场景越大、N 越高，这次卡顿越明显

注意这条与大世界无关：**小场景只要放了会动的角色就会撞上**。

### 10.2 已经正确的部分：直射遮挡是动态的

`ddgi_trace.compute` 的命中点直射项不看法线点积就完事，而是走 `cascadeShadowFactor(hitPos)`——采样**主视口** CSM 阴影图（逐级联试投影、单 tap）。CSM 每帧重画且包含动态物体，所以：

- 角色脚下的地面，在探针入射辐射里**会正确变暗**（角色挡太阳这件事已经生效）
- 角色自身的接触暗部由 CSM + 现有 SSAO 兜住，不依赖体素层

这条是本节最重要的判断依据：**"角色没被 GI 照顾"在视觉上远没有想象中严重**。

### 10.3 真正的缺口只有三件

1. 角色的基色不参与反弹（人形角色颜色渗到墙上的那点贡献缺失）
2. 角色不写体素占据位 ⇒ 探针 visibility 的深度矩（`javelinProbeVisibility` 的 Chebyshev 上界）不含角色 ⇒ **角色背后的探针不降权**（薄墙光渗的同类问题，但角色通常站着不动、面积小）
3. 探针到命中点的路径（可见性）不受角色影响

### 10.4 为什么不该把角色塞进体素场（时序账）

探针是**低频 + 时域累积**的数据：一圈扫描 4608 探针 / 每帧 128 = **36 帧 ≈ 0.6 s（60fps）**，再加 `hysteresis = 0.85` 的历史权重。而人形角色 1.5 m/s 走 0.6 s 就位移 0.9 m，**超过体素边长（0.5 m）**。

所以"把角色体素化进去"会同时付两笔账：每帧重体素化（成本）+ 探针历史被污染后的拖影（画质）。这与 RTXGI / HDRP APV / Lumen 的共同口径一致：**动态物体默认不进辐照度场，只做接收方**，它自身那份间接贡献交给屏幕空间或光追补。

### 10.5 三条路线（按成本排）

| 路线 | 做法 | 代价 | 判定 |
|---|---|---|---|
| **A · 只接收（推荐）** | 角色不进体素场，只被 GI 照亮；遮挡交给 CSM（已生效）+ SSAO | 角色颜色不反弹、角色后探针不降权 | 一个字段 + 一行过滤，**不依赖窗口方案，可独立先做** |
| **B · 动态代理体素** | 角色用 1~2 个 AABB / 16³ 局部粗代理写进第二张"动态场"，trace 时静态/动态双场遍历 | trace 成本翻倍、滞后仍在 0.6 s 量级、代理占位怎么清要另设计 | 中等收益、中等成本，排在 A 之后 |
| **C · 交给 rayQuery** | P4-06 光追后探针射线直接命中角色三角形，逐帧真实求交、无体素滞后 | 等 AS/BDA 编译链（未开工） | **角色 GI 的正解在 ⑦，不在 ⑥**——这也是 roadmap 把光追排在 DDGI 之后的原因之一 |

### 10.6 A 的落地口径（只记口径，不落码）

- `RenderObject` 新增 `giStatic`（**缺省 false = 参与 GI**，保持现有场景导入零改动）；角色/载具/会动的场景物体由组件把它置 true
- `DdgiVoxelizer.collect()` 过滤改「不透明 + `castShadow` + `giStatic`」；`albedoOf` / 签名逻辑不变
- 编辑器 MeshRenderer 面板加「参与 GI 静态集」勾选（`@InsField`），与 `castShadow` 同级
- `ensureReady()` 在签名不稳时补一条**节流 WARN**（「体素集在动，沿用旧图 N 帧」，每秒最多一条）——现在完全静默，形象地说"墙为什么没变暗"根本查不到
- 验收：①角色连续行走 60 s，日志零"整场重建"、零新 VVL；②原位置无幽灵暗角（对比截图）；③角色脚下有 CSM 影且亮度与关 GI 档无跳变；④停下两帧后不触发重重建

### 10.7 与窗口方案（§3）的交互

- 窗口化后**仍必须做 §10.6 的过滤**：否则「窗口按 chunk 位移」与「角色逐帧改签名」两套门控会互相打架，窗口永远停在"签名不稳定"分支上
- 若以后做路线 B，代理体素应作为**窗口层内的动态 tile 槽**（与 §3.3 的 slab 增量共用一套场/参数块），不要另建第三张 3D 图，否则 trace 段要多一次绑定与多一次遍历

### 10.8 本节不做

蒙皮/骨骼动画（引擎目前无 `Skeleton` / `Animator` 类，角色今天只能是刚体平移）｜GI 里的角色自阴影（交 CSM）｜角色之间的颜色互弹（交 rayQuery 或 SSGI）

