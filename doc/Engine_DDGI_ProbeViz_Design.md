# DDGI 探针可视化设计（Engine_DDGI_ProbeViz_Design）

> v0.3（2026-11-26）｜**实机验收通过（DBG-07 关账）**：用户「没红的了，验收过了」。三处随验修——①atlas 直显「铺满视口」（原 1:1 取像素只铺左上角一小块）；②「扫过没」判据改**辐照度 alpha 精确标志**（深度图 RG16F 无 alpha，「合法 0」与「从没写过」同形 → 贴地探针那几格永远固定红）；③深度显示窗口由 `1/MAX_RAY_DISTANCE` 改 `1/5m`（实测命中距离 0.6~2.8m 按 1/60 归一后只剩 0.01~0.05，内区一片纯黑）。明细见 §7.1。
>
> v0.2（2026-11-24）｜**已按 §2 推荐默认落码**（用户「嗯」= 全默认通过）：P1 要线框、P2 `E/π` 线性 + 深度 `m1/MAX_RAY_DISTANCE` 灰度、P3 菜单四项 + F5、P4 只读 accessor；§8 风险 1 按 (a) 落地（`DebugLineAppState` 统一提交）、风险 3 登记为 **DBG-07**。实机验收仍按用户口径后置（§7 挂账）。
>
> v0.1（2026-11-24）｜**方案件（未落码）**：按「先方案后码」出稿，§2 四点待拍板。用户 2026-11-24 拍板「先做探针可视化、验收靠后」——落码先行，验收口径见 §7 挂账。
>
> 定位：`Engine_Roadmap.md` §4.1 **L3（GI 主体）的验收依赖件**（原表只写了「探针可视化 / DBG 族」，无独立编号）。目的 = 把 DDGI 现在**只能靠日志和画面猜**的失败态变成可看：网格铺哪了、atlas 扫没扫满、方向图长什么样、深度矩合不合理。
>
> 不做：全量 CPU 回读给探针点着色（见 §8）、探针重定位/边界混合等算法改动、`ddgi_trace/update` 两段 compute 的任何改动。

## 1. 现状：三个"看不见"

| 想确认的事 | 现在的唯一证据 | 痛点 |
|---|---|---|
| 网格铺在哪、多大、装了场内没有 | 启动日志一行 `DdgiProbeGrid{...}`（`DdgiProbePass.ensureAtlases`） | 相机一动网格变，日志不复述（`debugLogged` 一场只一次） |
| 探针扫满没有、哪些 tile 是空的 | `completedSweeps()` 只到 1；首圈结束打印前 8 个探针的均值 | 8/4608 采样，看不出"哪些探针永远出界" |
| 方向图对不对（八面体上下球有没有扭、有没有黑区） | 无 | 只能改代码加打印 |

这三件正是 B2/B3 排障时反复踩的（`§12` 三处 `ivec2` 缩窄、`SAMPLED_IMAGE` 类型、探针全在场外）——有可视化能省掉大半轮次。

## 2. 待拍板四点

| 编号 | 决策点 | 结论（2026-11-24 用户「嗯」= 全默认） | 备选（未采纳） |
|---|---|---|---|
| **P1** | 要不要「世界空间线框」视图 | **要**（网格 AABB 12 边 + 探针十字，走现有 `DebugDrawLine`） | 只做 atlas 直显，省掉线框 |
| **P2** | atlas 直显的归一化口径 | **辐照度 `E/π` 直接显示（0~1 线性，>1 截断变洋红）；深度显示 `m1 / MAX_RAY_DISTANCE` 灰度**（v0.3 修订：深度改 `1/5m` 显示窗口，见 §5） | 辐照度做 Reinhard 压缩（`x/(1+x)`） |
| **P3** | 入口形态 | **菜单「工具 \| DDGI 探针视图」四项单选**（关闭 / 网格 / 辐照度 atlas / 深度 atlas），F5 循环 | 独立调试面板 tab（`sidebar` 类） |
| **P4** | 数据通道 | **只读 accessor 直连 pass**（`DdgiProbePass.grid()` / `atlases()`），引擎侧 `JavelinEngine.ddgiProbePass()` | 由 `RenderingSystem` 出一份"DDGI 快照"值对象给编辑器 |

## 3. 三视图（V1/V2/V3）

**V1 · 世界线框**（`DebugDrawLine`，随手 follow 相机位移）

- 网格 AABB 12 条边（`grid.origin` + `extentX/Y/Z`），颜色取现有灯族绿之外的调试青色
- 每个探针一个 3 轴十字（3 条短线，长度 `0.1×spacing`）
- 探针数上限处理：`DebugLineBatch.MAX_LINES = 8192`，一条十字 3 线 ⇒ **最多 2730 探针**。实测 192 探针（140×140 atlas 那档）绰绰有余；4608 探针档按「隔点画」降到 1/2 或「只画中心 Y 层」，并在数值行标注「降采样 1/N」
- 与选中驱动的 drawers 不同：**不受选中影响**，按菜单开关独立显隐（新 AppState 每帧 `clear + 重提交`，与 `DebugLineAppState` 共用同一个 batch）

**V2 · atlas 直显**（全屏 quad，GPU 侧，零 CPU 回读）

照 `GBufferViewPass` 全套形态（覆盖制、尾追加、自建 set0、不依赖 set1/set2）：

- 采样整张 atlas 贴屏，`uv = gl_FragCoord / textureSize(atlas)`
- 画 tile 分隔线：辐照度 tile 10px、深度 tile 18px，按 `tilesPerRow` 与 `(IRRADIANCE_TILE, DEPTH_TILE)` 在 frag 里算 `local = mod(fragPx, tileSize)`，`local == 0` 或 `tileSize-1` 时输出暗灰 → **一眼看出 tile 边界与内区位置**
- 未扫 tile（全零）保持黑，但边框用红色 → 区分"没扫到"与"扫到了但辐照度低"
- 直视方向图：8×8 内区铺开后八面体图案应当连续、上下半球无折痕（B3 那个 `octDecode` 扭歪问题肉眼即可见）

**V3 · 数值行**（`DebugText`，第 11 行）

`RenderStatsAppState` 占 0~9、`GBufferDebugAppState` 占 10，本视图落 **11**：

```
DDGI | grid 8x4x6 @1.5  origin(-5.0,0.4,-1.8) | tiers 140x140/252x252 | sweep 1 | probes/帧 64
```

未就绪（`enabled=false` / `atlases == null` / 无 DDGI pass）→ 该行写 `DDGI | 未就绪（启用探针 GI 或环境光模式未选 DDGI）`，菜单项同时置灰（红线 15：诊断态可见，不静默）。

## 4. 实现形态（落码清单，2026-11-24 已执行）

新增/改动，全部限编辑器侧 + 两个只读 accessor：

| 文件 | 性质 | 要点 |
|---|---|---|
| `editor/debug/DdgiProbeVizAppState.java` | 新增 | 意图静态 `volatile int desiredMode` + `requestMode/getMode`（照 `GBufferDebugAppState`）；引擎帧 `update` 消费意图 → 惰性 `addPass/removePass`；F5 边缘循环切换（F4 已被 G-Buffer 视图占用） |
| `editor/debug/DdgiProbeVizPass.java` | 新增 | 全屏 quad；set0 = `sampler2D atlas` + UBO/push `{int mode, float scale, ivec4 tiling}`；`mode` 2=辐照度 / 3=深度 |
| `resources/builtin-asset-editor/debug/ddgiView.frag` + `.meta` | 新增资产 | 复用 `gbufferview.vert`（顶点只做全屏三角，无需新 vert）；源内**不得出现光照注入标记字面量**（`gbufferview.frag` 注释里那条坑：写在注释里也会被注入器命中） |
| `test/test-project/.javelin/assets/editor/debug/ddgiView.frag` | 工程副本 | 交释放器内容字节比对重导（手改副本不会触发重导） |
| `editor/menu/top/DdgiProbeVizMenuCommand.java` | 新增 | 四项单选 + `checked()` + `enabled()`（DDGI 未就绪置灰） |
| `DdgiProbePass` | 加只读 | `grid()` / `atlases()` / `completedSweeps()`（现已 public）|
| `JavelinEngine` | 加只读 | `ddgiProbePass()`（未挂载返回 null；编辑器据此判就绪） |
| `DebugText` 行 | 改 | 新增 `HINT_ROW = 11`（与 GBuffer 的 10 不冲突） |

**挂载时机**：`DdgiProbePass` 由 `JavelinEngine` 直接 `pipeline.addPass`，编辑器拿到的引用可能为空（DDGI 关闭 / 非首视口）——`ddgiProbePass()` 一律返回可空，视图侧判空后走"未就绪"分支。

### 4.1 落码记录（2026-11-24，同一批）

| 文件 | 实际改动 |
|---|---|
| `core/render/ddgi/DdgiProbePass.java` | `MAX_RAY_DISTANCE` 由 private 提为 `public static final`（可视化按它归一化深度，避免两边各写一个 60）；新增只读 `grid()` / `atlases()`（javadoc 明写"调试视图只读，不得改动"） |
| `core/JavelinEngine.java` | 新增 `ddgiProbePass()`（可空；javadoc 标明只读消费口） |
| `editor/debug/DdgiProbeViewPass.java` | 新建：全屏三角 + push `{int mode, float scale}`（8B）+ 自建 set0 单采样器（atlas 换代重建）；顶点复用 `gbufferview.vert` |
| `editor/debug/DdgiProbeVizAppState.java` | 新建：`MODE_OFF/GRID/IRRADIANCE/DEPTH`、静态意图 + F5 循环、`DebugText` 第 11 行状态（网格/atlas 尺寸/首圈/十字 1/N/每帧探针数）、`emitGridLines()` 静态提交、就绪标记供菜单判灰 |
| `editor/debug/DebugLineAppState.java` | `clear()` 后调 `DdgiProbeVizAppState.emitGridLines(engine)`（**风险 1 按 (a)**：clear 单执行者不变） |
| `editor/menu/top/DdgiProbeVizMenuCommand.java` | 新建：四项单选 + `enabled()` 按就绪标记判灰 |
| `editor/menu/EditorMenuSetup.java` | 4 个命令注册 + 「工具」域新增 `tools.ddgiProbeView` 子菜单 |
| `editor/JavelinEditor.java` | `stateManager.attach(new DdgiProbeVizAppState())`（紧随 G-Buffer 视图） |
| `editor/resources/builtin-asset-editor/debug/ddgiView.frag(+.meta)` | 新建：`texelFetch` 1:1 直显 + tile 边框（未扫红/已扫暗灰）+ 溢出洋红；guid `b7c2d94e1f3a5b608c7d2e9f4a1b3c5d` |
| `test/test-project/.javelin/assets/editor/debug/ddgiView.frag(+.meta)` | 工程副本（与原图字节一致，供释放器比对重导） |
| `lang/zh_CN.properties` / `en_US.properties` | 新增 `tools.ddgiProbeView.*`（含 `{}` 顺位插值的状态行）、`ddgiProbeView.assetMissing/assetInvalid/loadFailed` |

**未做（按红线）**：未编译、未跑测（编译交用户）；`ddgiView.frag` 需经导入链编译出 SPIR-V 产物后菜单才可点亮。

## 5. 显示细则（数字口径）

- 辐照度 atlas：`tilesPerRow = ceil(sqrt(N))`，tile 边长 10（内区 8 + 两侧 1px 边框）；192 探针 ⇒ 14×14 tile ⇒ **140×140**；4608 探针 ⇒ 68×68 ⇒ **680×680**
- 深度 atlas：tile 18（内区 16），同 `tilesPerRow` ⇒ 192 探针 **252×252**、4608 探针 **1224×1224**
- 辐照度存的是 `E = ∫L·cosθ·dω`（更新段已乘 π），消费端除 π —— 直显也除 π，否则整体亮 π 倍，读数和画面亮度对不上
- 深度 tile: `R = E(d)`、`G = E(d²)`；直显 `R` 按 **`DEPTH_DISPLAY_RANGE = 5m`** 归一后 clamp 到 [0,1]（v0.3 修订，原 `1/MAX_RAY_DISTANCE`=1/60：实测命中距离仅 0.6~2.8m，出图落 0.01~0.05，内区整片纯黑看不出「贴地暗 / 空旷亮」的结构）→ 越亮越远，超过 5m 即纯白（朝天无命中记 60m ⇒ 满白，与「远处命中」在视觉上不再区分，属显示窗口取舍）
- 「扫过没」不靠猜数值：读**辐照度图对应 tile 中心的 alpha**（更新段每写一个内区 texel 就置 alpha=1，atlas 建出来是 0）——深度图是 `RG16F` 无 alpha，而「命中距离 0」（探针贴地/埋进几何体，射线一出发就命中）与「从没写过」在数值上完全同形，早期按「四角任一非零」当判据会把这类退化探针永远标成未扫（2026-11-26 实机：固定几格红）。Pass 因此绑两张图：binding0 = 当前展示的 atlas、binding1 = 辐照度 atlas（swept 来源）
- 直显铺满视口：`uv = gl_FragCoord / 视口尺寸`、`px = clamp(ivec2(uv * atlasSize), 0, size-1)`，`texelFetch` 取最近 texel 等效块状放大；push 16B（`int mode, float scale, float vpW, float vpH`）
- tile 边框 1px 的取值保持原样但压暗 50%，避免边框噪声盖住内区图案

## 6. 与既有调试设施的关系

| 设施 | 关系 |
|---|---|
| `DebugDrawLine` / `DebugLineBatch` | V1 复用；与选中驱动 drawers **同 batch、不同提交方**，每帧 `clear` 仍由 `DebugLineAppState` 单执行（见 §8 风险 1，已按 (a) 落地） |
| `DebugText` | V3 复用，行号 11 |
| `Editor_DebugLine_Design.md` | V1 的世界空间线段口径与颜色约定沿用该档（§2/§4） |
| `Engine_DebugTools_Plan.md` §3 | GBuffer 视图为同族先例（dbg-2）；本项登记 **dbg-3**（编号 = Backlog `DBG-07`） |
| `DdgiProbePass` 诊断 SSBO | 保留不动（首圈打印 / `[VoxelAoDebug]` 同款用途），可视化不替代它 |

## 7. 验收口径（2026-11-26 已实机通过）

1. 菜单四项切换即时生效，关闭后 `device` 侧无残留 pass（`removePass` 后可重开）
2. 辐照度 atlas 上能数出 `tilesPerRow × tileRows` 个 tile，tile 边框与内区边界清晰
3. 未扫 tile 边框为红、扫过后转黑 → 能直接看出"这一圈扫到第几个 tile"
4. 世界线框与场景静态几何的空间位置对得上（网格盒覆盖体素场 AABB）
5. DDGI 关闭 / 环境光模式非 DDGI 时：菜单置灰 + 数值行"未就绪"，无异常日志
6. `error.log` 零新 VVL（重点：pass 尾追加的布局转换、atlas 的 `SHADER_READ_ONLY` 声明）

### 7.1 实机验收记录（2026-11-26，用户「没红的了，验收过了」）

| 步骤 | 反馈 | 处置 |
|---|---|---|
| 辐照度 atlas | 「铺满视口，只有左上角有灰色边框，里面有红色、蓝色、有梯度」 | **与 DBG-03 同型 bug**：`uv = gl_FragCoord / textureSize(atlas)` 1:1 取像素，atlas 仅 60~150px 而视口上千像素 → 只铺左上角一块。改 push 加视口矩形 + `uv = gl_FragCoord / viewport`、`texelFetch` 最近邻放大 |
| 格数对不上 | 「只有 6×6」（按早期日志以为该是 15×15） | 非 bug：格数 = `ceil(√N)`，当时网格为 `3x4x3 probes=36` ⇒ 6 列 × 6 行正好 36 格 |
| 深度 atlas 红框 | 「看起来正常，但是深度有红框」→ 「红的格是固定」 | 判据缺陷：深度 `RG16F` 无 alpha，把「数值 0」当「没写过」。固定那几格 = 最低层贴地探针（originY≈0.003、射线一出发就命中⇒平均距离≈0），值合法但被判永不扫过。改 swept 判据 = 辐照度图对应 tile 中心 alpha（Pass 加 binding1），与数值无关 |
| 深度全黑 | 早期按 `1/MAX_RAY_DISTANCE` 归一 | 改 `DEPTH_DISPLAY_RANGE = 5m` 窗口 |
| 复验 | 「没红的了」 | **验收通过**，DBG-07 关账 |

**遗留**：贴地/埋进几何体的退化探针仍在网格里（只影响该探针的数据质量，不影响视图判读）——探针合法性分类是 `Engine_DDGI_Design.md` 的既有遗留项；`MAX_RAY_DISTANCE`(60m) 与 5m 显示窗口的取舍见 §5。

## 8. 风险与未定项

1. **`DebugDrawLine.clear()` 的单执行者问题**——**已按 (a) 落地**：`DebugLineAppState.update` 每帧 `clear` 后先调 `emitGridLines`，再提交选中 drawers；网格线只看自己的开关，不受选中影响。（备选 (b)「DebugLineBatch 分帧段」未采用。）
2. **探针点数量上限**：4608 探针档必须降采样（§3 V1），降采样规则要写进数值行，否则"少了几个探针"会被当成 bug。**已落地**：`CROSS_BUDGET = 2000` 十字（≈6000 线，留一半给 drawers），步长整数抽取，状态行显示 `十字 1/N`。
3. **dbg 编号**——**已拍为 DBG-07** 并登记 `Engine_Backlog.md` §2（与 DBG-03 同族但对象不同）。
4. **HDR 直显的曝光**：辐照度量级随场景光照变化，`E/π` 在 0~1 之外就截断；若实际场景 E 常在 2~5，直显会全屏洋红。缓解 = P2 备选（Reinhard）或用 `scale` push 手动调。**验收时未触发**（当前场景 `E/π` 落在 0~1 内，个别格洋红即真实溢出），保留为按需手段。
5. **不做**：CPU 全量读回给每个探针点上色（`TextureReadbackRing` 已具备，但 4608 探针 × 8×8 每帧平均 = 每帧几十 ms，且会与 TAA 抢带宽）；做到时按需触发 + 1Hz 限频再说，不进本档。

## 9. 落档与关联

- 本档：`doc/Engine_DDGI_ProbeViz_Design.md`（v0.1 方案件 → v0.2 落码 → v0.3 验收通过）
- 关联：`doc/Engine_DDGI_Design.md`（§13 诊断口径）、`doc/Editor_DebugLine_Design.md`、`doc/Engine_DebugTools_Plan.md` §3、`doc/Engine_Roadmap.md` §4.1 L3 行（探针可视化）、`doc/Engine_Backlog.md` DBG 族
- 落码后同步：`Engine_DDGI_Design.md` 加一节交叉引用、`Engine_Roadmap_Progress.md` 补批次行、`AGENTS.md` 文档索引
