# 引擎调试文本设计（Engine DebugText Design）

> 版本：v0.1（2026-09-27）
> 范围：core 新模块 DebugDrawText——bgfx dbgText 式全屏调试文本，**含汉字**。
> 验收样例：`javelin-core/src/test/java/com/javelin/core/DebugTextDemo.java`。
> 配套：`doc/Engine_Roadmap.md` 二期功能域（路线档补录）；调度契约见 `Subsystem` 接口。

---

## 1. 拍板记录（2026-09-27）

| 决策点 | 拍板 | 理由 |
|--------|------|------|
| 字形方案 | **内嵌静态 16x16 点阵**（用户否 AWT 运行时光栅化——不给 core 引 java.desktop；否 FreeType+TTF） | 调试文本要经典像素风 + 零运行时依赖；动态光栅化的灵活性对调试文本收益不抵依赖 |
| 字库来源 | **javelin-tool `FontRasterizer`；字体默认 = tool resources 打包的思源黑体 Bold（SIL OFL，`front/` 目录），CLI 传参可换**（2026-09-27 二次拍板：推翻初版"TTF 不进仓"，OFL 字体直接入仓打包；选择与授权换字体时由使用者把关） | 零参 CLI 开箱即用，产物可随时复现 |
| 字符覆盖 | **ASCII 32-126 + GB2312 符号区 01-09 + GB2312 一级 3755 + □(U+25A1 回退符)**（符号区为 2026-09-27 用户补拍板——全角标点/制表符是中文调试文本刚需；~158KB，否二级全收） | ，。：「」【】…—等与常用汉字全齐；二级生僻字回退 □ 是有意取舍 |
| 点阵样式 | **1bpp 纯黑白**（否 4bit 灰度抗锯齿） | 经典点阵风 + 体积最小 |
| Shader 来源 | **core 内嵌 .spv** | GLSL 源归 javelin-tool 离线 shaderc 编译，SPIR-V 非源码、不踩"着色器源码不进 core"拍板。被否备选：正式化 installBuiltinShaders（= 恢复已删的内置程序路径）、editor 内置资产（core 依赖 editor 资产释放链，裸引擎没字） |
| 颜色模型 | **attr 一字节：低 4 位前景 + 高 4 位背景，共用 16 色 EGA 调色板** | 超越 bgfx（bg 限黑）更实用，仍是紧凑单字节 API |
| 行距 | **字高 16px + 行间 2px = 行距 18px**（用户拍板 2026-09-27，网格 rows=高/18） | 连续多行调试文本的呼吸感，字形资产与 fs 采样均不变（2px 空隙来自顶点几何） |
| 清屏语义 | **持续到手动 `clear()`**（否每帧自动清） | bgfx 网格模型：写一次一直在、改哪刷哪；动态内容帧覆写即可 |
| API 形态 | **行级追加 + 自动叠行（2026-09-27 用户重定义，推翻初版坐标式 print/printf）**：`printf(row, txt, attr)`=固定行追加在已有文字后，`printf(txt)`=自动叠行写完换下一行；**不做 \n\r 转义换行** | 调试文本 90% 场景是"按行打日志/按行刷新"，坐标式定位是多余心智负担；HUD 动态行 = 每帧 clear+重写 |
| 单行清 | **`clear(row)` 与 `clear()` 双档**（2026-09-27 用户补拍板） | 单行刷新惯用法：每帧 `clear(row)+printf(row,...)` 刷新目标行，同屏其余行持续不动 |
| 门面形态 | **静态门面 `DebugText`，对齐 Input 范本** | bind/unbind 由子系统 init/shutdown 驱动；未初始化抛 `IllegalStateException`（静默会掩盖接线错误）；v0.1 仅主线程 |
| 渲染挂点 | **`Subsystem.appendOverlay` 帧组装级钩子（2026-09-27 二次拍板泛化，推翻初版"引擎持字段直调"）+ `SubsystemManager.appendOverlays` 统一遍历**；closeGpu 钩子配套（shutdownAll 早于 waitIdle，GPU 资源销毁走 `closeGpu()` 统一遍历） | Pass 是逐相机视口进图的，挂 passes() 会随相机数重复绘制；overlay 写最终 backbuffer，**无相机也出字**；泛化后引擎不认识具体子系统，后续 overlay 客户零改动接入 |

## 2. 架构

```
DebugText 静态门面（clear / printf(row,txt,attr) / printf(txt)——唯一使用方式）
        │  写网格（主线程；格子坐标，左上原点）
        ▼
DebugTextGrid（cols=宽/8、rows=高/16；每格 codepoint + attr；双宽排版）
        ▲ 持有/读取
DebugDrawTextSubsystem（AbstractSubsystem：字库纹理 + 采样器 set + 自建 PSO + 双帧槽顶点 buffer）
        ▲ buildFrameGraph 视口循环后追加（唯一动引擎的改动）
DebugTextOverlay → graph.addGraphicsPass("DebugText")：非空格子拼顶点
    → 双帧槽 buffer.update → bind PSO + 字体 set → 一次性 draw（写当前 backbuffer）
```

子系统本身不出现在 update 钩子里（无逐帧逻辑，网格是被动的）；注册在
AppStateSubsystem 之后（写发生在用户代码 update 时段、消费在渲染时，
注册序只影响 early/lateUpdate 调度，DebugText 均不使用）。

### 2.1 类清单

core `com.javelin.core.debug`（**预留空包，正好落位**）：

| 类 | 职责 |
|----|------|
| `DebugText` | 静态门面：`clear()`（行游标/自动行归 0）/ `clear(row)`（单行清+游标归位，自动行指针不动）/ `printf(row, txt, attr)`（固定行追加）/ `printf(txt)`（自动叠行，前景默认白）/ `printf(txt, attr)`（叠行带色，2026-09-27 拍板补）；未初始化抛 `IllegalStateException`；v0.1 仅主线程 |
| `DebugTextColor` | 16 色索引常量（VGA 序：`BLACK..WHITE`，10=`LIGHT_GREEN`）+ `attr(fg, bg)` 组合 |
| `DebugTextGrid` | 网格：尺寸按 framebuffer；resize 重建并**清空**；print 布字布色（双宽分类）、越界裁、缺字回退 □ |
| `DebugFont16` | 字库 bin 加载：1bpp 解包成 R8、codepoint → 字形位图/图集坐标 |
| `DebugDrawTextSubsystem` | 子系统：init 建全部 GPU 资源并绑定门面 / shutdown 解绑放 CPU 侧；**GPU 资源销毁走 `Subsystem.closeGpu()` 钩子**（引擎经 SubsystemManager.closeGpuAll 在 frameLoop.close()（waitIdle）之后统一遍历）；overlay 贡献=覆写 `appendOverlay(graph, backbuffer, frameIndex)`（`DebugTextOverlay` 独立小类已并入子系统） |

javelin-tool：

| 类 | 职责 |
|----|------|
| `FontRasterizer` | 用户提供 TTF 路径 → 16px 阈值二值化 1bpp → debug_font16.bin |

资源（core resources / tool 各归其位）：

- `debug/debug_font16.bin`（成品提交仓库；生成器代码备将来重生成，不进构建依赖链）
- `debug/debugtext.vert.spv` / `debug/debugtext.frag.spv`（tool 内 GLSL 源离线编译，成品提交）

引擎侧改动（JavelinEngine 两处）：

- `registerSubsystem`：追加 `DebugDrawTextSubsystem`（AppStateSubsystem 之后）；
- `buildFrameGraph`：视口循环结束后追加 overlay 段（子系统启用才进图）。

## 3. 渲染设计

### 3.1 挂点与图结构

- 挂点 = `Subsystem.appendOverlay` 帧组装级钩子，`JavelinEngine.buildFrameGraph` 末尾经
  `SubsystemManager.appendOverlays` 统一遍历（禁用子系统管理器过滤，未就绪子系统自检）；
- overlay **总在视口循环之后**：`addColorOutput(backbuffer, LOAD)` 叠加、全屏
  viewport、无深度附件——与无相机 ClearOnly 兜底同一插入层，只是提为"总在最后"；
- **无相机纯清屏场景也出字**（无场景时正是最需要调试信息的时候）；
- 多相机：overlay 在所有相机视口合成完之后全屏画**一次**，不随相机数重复；
- 子系统启用/禁用翻转图结构哈希**一次**，之后稳定；网格空 = 回调零 draw，
  图结构不变（缓存零扰动）。

### 3.2 PSO（自建，不走 pipelineFor）

- set 布局仅一个 combined image sampler（字库纹理）；**不绑 UBO1 / set2 /
  无 push constants**——顶点直接给 NDC 坐标（`GraphicsPipelineDesc` 支持自定
  set 布局，材质 PSO 的"共用 set2"约束不适用）；
- color attachment 格式 = swapchain image(0) 格式（pipelineFor 先例）；无深度；
- 固定态：no ZTest / no ZWrite / no Blend / no Cull——颜色混合由 fs 按覆盖度完成；
- fs：`coverage = texture(font, uv).r; out = bg*(1-cov) + fg*cov`，nearest 点采样。

### 3.3 顶点与动态 buffer

- 顶点格式：`pos vec2(NDC) + uv vec2 + fg RGBA8 + bg RGBA8` = 24B；
  每格 6 顶点 TRIANGLE_LIST（CPU 侧查调色板打包 fg/bg）；
- 顶点 buffer 按最大格数一次分配（VERTEX + hostVisibleCoherent）；
  **双帧槽交替写**（UBO1 先例——RHI `Buffer.update` 无 fence 防护是已知短板）；
- CPU 侧预分配 ByteBuffer 复用（热路径列表复用规矩）；
- 字库纹理：R8_UNORM **一次上传**（bin 里 1bpp，加载时展开每像素一字节，
  ~1MB VRAM），`SamplerDesc.nearest()`；
- 每帧成本模型与 bgfx 相同：全网格扫描 + 非空格子拼顶点一次 draw；
  1080p 满格 ~9000 格 ≈ 5.4 万顶点，无压力。

### 3.4 双宽排版（CJK 网格模型）

- String 按 codepoint 遍历，**wcwidth 简单分类**：CJK 区间 → 全角 16x16 占
  **2 格**，其余 → 半角 8x16 占 1 格——经典 CJK 终端双宽模型，行距 18px
  （字高 16 + 行间 2）统一；
- **游标模型**：每行一个追加游标（`rowCursors`，下一写入列）+ 自动行指针
  （`autoRow`，clear 归 0）；`printf(row,...)` 接在该行已有文字后；
  `printf(txt)` 写当前自动行后指针 +1；
- **控制字符（<0x20，含 \n\r\t）跳过不写不进位**——用户拍板不做转义换行，
  换行只走两个 printf 变体；
- 无 wrap，越出右缘裁剪、游标贴住末列（继续追加空转直到 clear）；表外/缺字回退 □。

## 4. 字库资产管线

- `FontRasterizer`（tool）：字体（默认 = tool resources 打包的思源黑体 Bold，可传参更换）→
  16px 阈值二值化 → 1bpp：半角 ASCII 8x16（95 枚）+ 全角 16x16（GB2312 符号区 01-09 +
  一级 3755 + □ 回退符）；
- bin 产物：头部（"JDTF" magic/版本/字形数，大端）+ 升序 codepoint 索引表 +
  每字形 32 字节 1bpp 位图（16 行 × 2 字节，半角画左 8 列）——加载侧 `DebugFont16`
  二分查找定位、解 1bpp 展开 R8；
- ~158KB 二进制提交进 core resources，core 运行时自包含、不依赖编辑器资产。

## 5. 边界与已知取舍

- **v0.1 仅主线程**——编辑器侧要打日志时按 Input 的 EDT 入队先例补；
- resize 网格原位重建：内容与游标按行列交集保留、裁边不消失（2026-10-17 拍板，
   改自 v0.1 清空语义——打印方多按节流刷新（RenderStats 1Hz），清空后无人立刻重填，
   文本 resize 后消失最长一个刷新窗；bgfx reset 清空不再对标）；
- **不参与相机/视口/剔除**——永远全屏最后画，蒙 depth 永远不挡；
- 静态字库覆盖有限是**有意取舍**（用户拍板）：调试要的不是任意 Unicode
  排版，是确定、零依赖的经典点阵；
- 行距/字号固定不可调（ROW_SPACING 提常量，改值重跑即可；真动态字号后置）。

## 6. 后置项

- 编辑器侧 EDT 打日志入口（入队桥接）；
- 动态字形图集（若将来要任意 Unicode：AWT 光栅化 + `Texture.update(region)`
  增量上传——本次被否方案存档，届时可复活）；
- 字号可调 / `clear` 带区域清除（bgfx dbgTextClear 有 rect 变体）；
- dbgTextImage 等值物（小图上屏）；
- 4bit 灰度抗锯齿档（同 bin 格式改 4bpp 即可，纹理换 R8 直存）。

## 7. 验收样例

`gradlew :javelin-core:runTest -DmainClass=com.javelin.core.DebugTextDemo`
（JavelinEngineTestBase + loadScene→null 范本，**无相机纯清屏场景**）：

- 无相机也出字（挂点验证——ClearOnly 分支外 overlay 独立生效）；
- 行 0 两段 `printf(0,...)` 同行追加、行 1 每帧重写（HUD 惯用法 = 每帧 clear+重写）；
- **行 2 每帧 `clear(row)+printf(row,...)` 单行刷新**，其余行写一次持续不动（单行刷新与持续语义同屏）；
- 无参 `printf` 连写两行走两行（自动叠行 + 游标 clear 归 0）；
- 全 16 色前景/背景对照（每行"背景色块 + 前景色样"两次追加，验证 attr 两个半字节）；
- SPACE 冻结：clear 后画面立空、不再重写（验证"持续到 clear"手动清语义）；
- 窗口 resize：网格重建、文本清空、无越界访问；
- [perf] 观测：有字/无字两态对比，每帧固定成本应不可见。

## 8. 落地顺序

1. ✅ javelin-tool `FontRasterizer` + `DebugTextAssets` CLI（零参默认思源黑体 Bold；4532 字形 bin 实跑验收，2026-09-27）；
2. ✅ tool 内 GLSL → shaderc 编 `.spv`（vs 1296B / fs 952B 实跑验收）；
3. ✅ core.debug 六类落位（门面/网格/颜色/字库/子系统——overlay 并入子系统 appendOverlay）；
4. ✅ JavelinEngine 三处接线（注册 + buildFrameGraph overlay 段 + shutdown 的 GPU close 钩子）；
5. ✅ `DebugTextDemo` 验收样例落地（编译与实跑验证交给用户）；
6. ~~设计档~~——**✅ 本文档（v0.1，2026-09-27）**。

---

> （注：部分内容可能由 AI 生成）
