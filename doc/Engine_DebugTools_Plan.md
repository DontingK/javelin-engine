# 引擎调试工具计划（Engine Debug Tools Plan）

> 版本：v0.3
> 日期：2026-11-10（v0.3：2026-11-12）
> 说明：排障基建专档——两件工具的方案与排期：① Vulkan 校验层消息回调（把校验层报错接进引擎日志，越权/错序类坑当场现形）；② G-Buffer 可视化调试视图（工具菜单/F4 把 G-Buffer 三张贴屏，肉眼比对中间产物）。立项起因 = 批次④-B 验收随验坑 **Pitfalls #35**（set0 阶段可见性 VERTEX-only，LightingPass fs 读 `cam.view` 越权**读零不报错**、全黑排查半日——两件工具任一在位都能把它变成分钟级问题）。
> 原则：**只做排障面、不进正式渲染路径**；两件相互独立、可分批落地。
>
> v0.3 变更（2026-11-12）：**步骤二（dbg-2）落地并验收（2026-11-12 用户实测），归属=编辑器**——G-Buffer 可视化调试视图：`GBufferViewPass`（覆盖制全屏贴图，内置 `builtin-asset-editor/debug/gbufferview.vert/.frag` 走 SpvShader 导入链、不带光照注入标记）+ push 常量 `int mode` 三选一 + 自建 set0 三采样器（不依赖 set1/set2）；切换入口 = **顶部菜单**「工具 | G-Buffer 视图」四项单选（F4 降为快捷循环）；`GBufferDebugAppState`（编辑器装配链挂载、FORWARD 档置灰/提示、Pass 惰性创建）——**core 零改动**（未挂 `registerDefaultAppStates`）。实装落点见 §3.5，验收口径 §3.4 未变。
>
> v0.2 变更（2026-11-12）：**步骤一（dbg-1）落地并验收**（2026-11-12 用户实测：启动正常、`logs/error/error.log` 零 ERROR；「违约现形」能力首跑即实证——五连报据以定位坑 #37/#38）——`JavelinSetting.engineDebug` 设置字段（默认 true；序列化走既有协议、**不新增接口**；命令行 `--debug=true|false` 经 `LaunchArgs` 覆盖）+ 无校验层 **fail-fast** + `VulkanInstance` DebugUtilsMessenger 回调（WARNING/ERROR 路由 logback，`[VVL]` 前缀）+ 编辑器启动链异常弹错误框（红线 15）。实装落点见 §2.5；验收口径 §2.3 未变。

---

## 1. 现状与差距

### 1.1 校验层：开了但听不见

`VulkanInstance`（RHI）现状：`debug=true` 时若本机有 `VK_LAYER_KHRONOS_validation` 即启用该层（`VulkanInstance.java:71`），但**没有注册 DebugUtilsMessenger 回调**——校验层消息默认走 `OutputDebugString`（Windows 系统调试通道），不进 logback，引擎日志里一片安静。等于付了校验层的性能开销，却没收它的输出。

DebugUtilsMessenger 是 Vulkan 官方回调机制（`VK_EXT_debug_utils` 扩展，实例创建时已按 debug 条件请求该扩展）：`vkCreateDebugUtilsMessengerEXT` 注册后，驱动把每条校验消息（严重度 ERROR/WARNING/INFO/VERBOSE × 类型 VALIDATION/PERFORMANCE/GENERAL）回调给应用，应用自行路由。#35 这类「fs 访问 stageFlags 不含 FRAGMENT 的 binding」正是校验层的标准命中项——draw 时报出 set/binding/stage 与涉及管线。

### 1.2 G-Buffer：中间产物看不见

HYBRID 档落地后，不透明着色链 = GBufferPass（写三张 MRT）→ LightingPass（读回计算）。中间产物 albedo/法线/世界位置对用户完全黑箱——出问题时（如 #35：G-Buffer 其实正常、光照结果全黑）只能靠推演分辨断点在哪一环。四期 SSAO/后处理全部消费 G-Buffer，可视化视图是长线排障资产。

### 1.3 已核 API（LWJGL 3.4.2，IDEA MCP 查证）

- 回调：`VkDebugUtilsMessengerCallbackEXT extends Callback`（匿名子类覆写 `int invoke(int severity, int types, long pCallbackData, long pUserData)`；`pCallbackData` 是裸指针，经 `VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData)` 物化读 `message()` / `pMessageIdName()`）；GLFW 回调先例 = `GLFWWindow`（回调对象存字段、dispose 时 free——生命周期同法）；
- 创建：`EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(VkInstance, VkDebugUtilsMessengerCreateInfoEXT, null, long[])`；销毁 `vkDestroyDebugUtilsMessengerEXT`；实例创建链里已有先例：`VulkanInstance` 构造器尾 `new VkInstance(handle, createInfo)` 持引用防 GC——**Callback 对象同样必须存字段强引用**（LWJGL 回调靠 native 地址存活，被 GC 即悬垂崩溃）；
- 严重度常量：`VK_DEBUG_UTILS_MESSAGE_SEVERITY_{VERBOSE,INFO,WARNING,ERROR}_BIT_EXT`；类型常量 `VK_DEBUG_UTILS_MESSAGE_TYPE_{GENERAL,VALIDATION,PERFORMANCE}_BIT_EXT`（`EXTDebugUtils.java` 已 static import 进 `VulkanInstance`）。

---

## 2. 步骤一：引擎 debug 开关 + 校验层回调（先行）

### 2.1 引擎 debug 开关（`JavelinSetting.engineDebug`）

**口径（2026-11-10 用户拍板）**：

- `JavelinSetting` 新增 `boolean engineDebug`，**默认 `true`**（开发者默认要听见校验层）；
- **启动期消费、运行期不可切**：`JavelinEngine.createInstanceConfig()` 读设置传入 `RHI.RHIInstanceConfig.debug`（现状硬编码 `true` → 改读设置）。校验层/messenger 在实例创建时装载，运行期换装没有干净时机（实例级对象、全设备共享），不做热切；
- **fail-fast：debug=true 且本机无校验层 → 直接报错不启动**（用户拍板，推翻现有静默降级）：`VulkanInstance` 检不到 `VK_LAYER_KHRONOS_validation` 时抛 `IllegalStateException`（消息两条出路 = 装 Vulkan SDK / 启动加 `--debug=false`），由上层启动链弹给用户——红线 15（错误显性化）与 Pitfalls #19（静默降级掩问题）的双收口。`debug=false` 时不请求校验层也不请求 debug_utils 扩展，照常启动；
- 序列化：`JavelinSetting.engineDebug` 为原生 `boolean`（默认 true），`output.write` / `input.readBoolean` 走既有协议，**不新增任何序列化接口**；缺键按协议既定行为读为 `false`（2026-11-12 拍板；原「`containsKey` 缺键保默认」与「包装类 + `Objects.equals`」两方案均作废）；
- **命令行覆盖**（2026-11-12 用户指令）：各 `main(String[] args)` 首行 `LaunchArgs.parse(args)` 装入启动参数，`JavelinEngine.initialize()` 在实例创建前 `setting.setEngineDebug(LaunchArgs.getBoolean("debug", setting.isEngineDebug()))`——`--debug=true|false` 优先于设置文件；参数持有类落 `javelin-core`（`cn.donting.javelin.core.LaunchArgs`），编辑器 / 引擎 Demo / `GameMain` / `AotGameMain` 四入口同法；
- 消费方：`RHI.RHIInstanceConfig.debug`（已有字段，语义不变——现状引擎恒传 true，此后由设置决定）。

### 2.2 messenger 落点与消息路由

- **落点 `VulkanInstance`**：构造器在校验层启用分支内，紧接实例创建后 `vkCreateDebugUtilsMessengerEXT`。CreateInfo severity = WARNING|ERROR（VERBOSE/INFO 噪声大，不收；后续要开再加常量）；messageType = VALIDATION|GENERAL|PERFORMANCE 全收；回调实例与 messenger handle 存实例字段，`dispose()` 里先 `vkDestroyDebugUtilsMessengerEXT` 再毁实例（messenger 生命周期必须短于实例）；
- **回调内路由**（该线程为驱动回调线程，**只做轻量事**——格式化字符串 + slf4j 调用，禁止任何 Vulkan 调用/锁/对象分配大动作）：
  - severity ERROR → `log.error`；
  - severity WARNING → `log.warn`；
  - 回调返回 VK_FALSE（不中断调用方，官方建议）；
  - 日志格式：`[VVL] {messageIdName} {message}`——前缀供日志检索过滤；
- **Verbose 分支留口**：severity 常量集中一处定义，后续排查细粒度问题（对象命名、生命周期追踪）时加 VERBOSE 只改一行。

### 2.3 端到端自验口径

- debug=true 无 SDK 环境（CI/干净机）：启动即抛错、错误消息含两条出路——fail-fast 生效；
- debug=true 有 SDK：正常启动，日志无虚假 VVL 洗屏（正常路径应零 WARNING/ERROR；有即顺手排障）；故意造一个违约（如临时把 set0 stageFlags 改回 VERTEX）→ draw 帧 VVL ERROR 现形，日志可见 set/binding/stage——**#35 复现即验收**；
- debug=false：不请求层/扩展，正常启动，零 VVL 日志；
- 设置往返：engineDebug 两种值落盘重启保持。

### 2.4 边界

- 校验层开销（每次 API 调用校验，帧率掉 5~20%）属 debug 开关语义内，用户关 `engineDebug` 即恢复——不自动调档；
- 编辑器「首选项」UI 不在本步（设置文件手改即可用；首选项面板接入随下次 i18n/设置批次走，避免本步混 UI）。

### 2.5 实装落点（2026-11-12）

| 落点 | 改动 |
|------|------|
| `JavelinSetting` | 新增 `boolean engineDebug = true`（`isEngineDebug()`/`setEngineDebug(boolean)`）+ `write`/`read`（`input.readBoolean`，缺键按协议= false，不改接口） |
| `LaunchArgs`（新增，javelin-core） | 启动参数单点持有：`parse(String[] args)`（`--k=v` 取值、无值 `--k`=`true`、其余忽略）+ `get`/`getBoolean`/`has`；解析结果 volatile 整体替换（main 与引擎线程可见） |
| `JavelinEngine.initialize` | 实例创建前 `setting.setEngineDebug(LaunchArgs.getBoolean("debug", setting.isEngineDebug()))`（命令行优先于设置文件） |
| 四入口 main | `JavelinEngine` / `GameMain` / `AotGameMain` / `JavelinEditor` 的 `main(String[] args)` 首行 `LaunchArgs.parse(args)` |
| `JavelinEngine.createInstanceConfig` | `RHIInstanceConfig.debug` 由恒 `true` 改读 `setting.isEngineDebug()` |
| `VulkanInstance` | ① 无 `VK_LAYER_KHRONOS_validation` 即抛 `IllegalStateException`（消息含「安装 Vulkan SDK / 设 engineDebug=false」两条出路）；② 实例创建后注册 messenger（severity WARNING\|ERROR、type VALIDATION\|GENERAL\|PERFORMANCE），回调对象与 handle 存字段；③ `dispose` 先销毁 messenger、后毁实例、最后 `Callback.free()` |
| `ProjectLoadingFrame` | 启动链 `catch` 由只打日志改弹错误框（红线 15），确认后 `System.exit(1)`——加载窗已 dispose，无其他可见窗口可回退 |

> 注：`RhiTestBase` 仍以 `debug=true` 建实例（测试显式要校验），无 SDK 的机器上测试会按 fail-fast 报错——与 §2.3「debug=true 无 SDK 即抛错」口径一致。

---

## 3. 步骤二：G-Buffer 可视化调试视图

### 3.1 形态

- **切换入口 = 顶部菜单**「工具 | G-Buffer 视图」四项单选（关闭 / 基色 / 世界法线 / 世界位置，`checked()` 勾选）；另保留 **F4 边沿循环**作快捷键（F3 = 渲染统计文本，邻位顺理）。**默认关闭**（调试视图不常驻）。菜单在 EDT 写意图、AppState 引擎帧消费（Gizmo 意图镜像同口径）；三个视图项仅 HYBRID 档可用（置灰），「关闭」恒可用；
- **形态 = 覆盖制全屏贴图**：按 `gl_FragCoord` 绝对坐标把目标 G-Buffer 附件 1:1 贴回本视口（与 `LightingPass` 同款映射，不做居中留白——单视口满屏等价、分屏各贴各自区域）。法线视图着色 = `normal * 0.5 + 0.5`（[-1,1]→[0,1] 可见域）；世界位置 = **带符号线性重映射** `p / (2·RANGE) + 0.5`（0=中灰、+xyz 变亮 / -xyz 变暗、±RANGE 外饱和——`RANGE` 常量默认 10）——原「xyz 直传」在 UNORM 缓冲里会把负值钳成 0（负坐标全黑看不见，2026-11-12 实机修正）；albedo 原样；
- **覆盖制着色器**（SkyboxPass/LightingPass 同族先例）：`builtin-asset-editor/debug/gbufferview.vert/.frag` 走 SpvShader 导入链（**不带光照注入标记**——只采样三选一，无光照注入；vertex = 全屏三角形，LightingPass 同款）；uniform 通道 = push constants 传 `int mode`（三态选一，**不依赖 set1/set2**，自建 set0 三采样器）；描述符集按「视口名 + 三张纹理身份」缓存、附件换代重建。

### 3.2 挂点与驱动

- **归属 = 编辑器**（javelin-editor `editor.debug`，2026-11-12 拍板从 core 迁入）：`GBufferDebugAppState extends AbsEditorAppState`，`JavelinEditor.onInitialize` 随编辑器装配链挂载；切换走工具菜单命令 `GBufferViewMenuCommand`（`JavelinEditor.getInstance()` 取管线判档位）；
- **驱动**：菜单 `execute()` 只写静态意图 `GBufferDebugAppState.requestMode(mode)`；`update` 在引擎帧消费——F4 边沿循环、意图变化才 `pipeline.addPass/removePass` + `setMode`（Gizmo 网格开关 = AppState 挂/卸 Pass 先例）；Pass **惰性创建**（不激活不装载着色器），创建/装载失败捕获 → 日志 + 行提示 + 回退意图（不把帧循环带崩）；
- **Pass 挂点 = 末尾置顶**（`addPass(pass)` 尾追加，两档同步——GizmoPass/DebugLinePass 同口径）：目标视口 = **编辑器相机视口**（`targetView` 过滤，GAME 视口不进图），读该视口 G-Buffer 三张（`rs.gbufferAlbedoFor/NormalFor/WorldPosFor(视口名)`，RenderingSystem 既有公开口），HYBRID 档专用视图——**FORWARD 档下按 F4 弹日志 + DebugText 行提示「仅 HYBRID 档可用」**（管线档位 `RenderPipeline.profile()` 可查；运行期 HYBRID 切 FORWARD 自动收起）；
- 图依赖：`addTextureRead` 三张（读声明即建边，G-Buffer 必已写完）；写终点 = backbuffer（覆盖制 LOAD）。

### 3.3 边界与不做

- 只看**目标视口（编辑器相机）** G-Buffer——多视口分屏各看各的属过度设计，需要时再加；
- 不做像素取值放大镜/直方图（那是 RenderDoc 的领域；本视图只答「中间产物肉眼对不对」）；
- shadowMap 可视化（另一张高价值中间产物）**不在本步**——深度比较采样器采样与普通采样不同（需专 shader 分支），独立小批次后置，档内留位。

### 3.4 自验口径

- HYBRID 档：F4 循环四态——albedo 与画面基色一致 / 法线朝向直觉对（+Y 上表面偏绿）/ 世界位置平滑渐变无断裂 / 关闭恢复；
- #35 复盘对照：模拟「G-Buffer 正常但光照输出黑」时，F4 albedo 视图正常 → 断点定位在 LightingPass，不再需要推演；
- FORWARD 档：F4 无画面 + 提示行；F3 统计不受影响。

### 3.5 实装落点（2026-11-12；归属=编辑器，当日从 core 迁入）

| 落点 | 改动 |
|------|------|
| `GBufferViewPass`（新增，javelin-editor `editor.debug`） | 覆盖制全屏大三角形（`LightingPass` 同款 NDC 顶点 + 1x 无深度附件 PSO）；`addToGraph` 按 `mode` 门控（0=不进图）+ **`targetView` 过滤**（只编辑器相机视口，GAME 视口不进图）；读本视口 G-Buffer 三张（`gbufferAlbedoFor/NormalFor/WorldPosFor` + `addTextureRead` 建边）+ 终点 `colorTarget()` LOAD；render 回调绑 PSO + 自建 set0（三采样器）+ push `int mode` + `draw(3,1)`；描述符集按「view 名 + 三张纹理身份」缓存、附件换代重建；着色器缺失/坏产物经 `EditorLang` 报错（红线 15） |
| `GBufferViewMenuCommand`（新增，javelin-editor `menu.top`） | 四个实例（关闭/基色/世界法线/世界位置），单选勾选（`checked()` 比对意图模式）；`execute()` 只写意图 `requestMode(mode)`（Gizmo 意图镜像口径）；三个视图项 `enabled()` 仅 HYBRID 档可用、「关闭」恒可用 |
| `gbufferview.vert/.frag`（新增，`javelin-editor/.../builtin-asset-editor/debug/`） | vs = 全屏三角形 NDC（零声明）；fs = `layout(set=0, binding=0/1/2) uniform sampler2D` 三张 G-Buffer + `layout(push_constant){ int mode; }`——按 `gl_FragCoord / textureSize` 1:1 取回本像素；albedo 原样 / 法线 `×0.5+0.5` / 世界位置**带符号重映射**（`p/(2·RANGE)+0.5`，负值不再钳黑）；**源内（含注释）不得出现光照注入标记字面量**（`source.contains` 判定——注释里写也会误触注入撞名，2026-11-12 实锤）；伴生 `.meta`（assetType=SpvShader）；Gradle 索引任务自动收 `.vert/.frag` |
| `GBufferDebugAppState`（新增，javelin-editor `editor.debug`） | `extends AbsEditorAppState`；静态 volatile 意图 `requestMode/getMode`（菜单 EDT 写、帧内消费）；`update`：F4 边沿循环 + 意图变化才挂/卸 Pass + `setMode`；FORWARD 档按 F4 不切换（日志 + DebugText 行 10 提示「仅 HYBRID 档可用」）、运行期切到 FORWARD 自动收起；Pass 惰性创建 + 创建/装载失败捕获（日志 + 行提示 + 回退意图）；onDetach 摘 Pass |
| `JavelinEditor.onInitialize` | 编辑器场景就绪分支内 `attach(new GBufferDebugAppState())`（与 DebugLine/Pick 同链） |
| `EditorMenuSetup` / 语言包 | 注册 4 条命令 + 「工具」域 `.submenu("tools.gbufferView", ...)` 四项；zh_CN / en_US 各补 `tools.gbufferView*` 与 `gbufferView.assetMissing/assetInvalid` 键 |

> 实装取舍：显示沿用 `LightingPass` 的 **`gl_FragCoord` 绝对坐标 1:1 映射**（不另做居中缩放/留白——单视口满屏时两者等价，分屏时各视口贴各自区域最直观）；只作用于编辑器相机视口（多视口各看各的属过度设计，§3.3）。
>
> 归属沿革：v0.2 时点本步骤原拟落 core（`registerDefaultAppStates` 同族）；2026-11-12 用户拍板**归编辑器**——切换入口随之改为**顶部菜单**（F4 降为快捷键），Pass/AppState/内置 shader 全迁 javelin-editor，core 零改动（`JavelinEngine.registerDefaultAppStates` 未加此状态）。

---

## 4. 排期与批次划分

| 批次 | 内容 | 依赖 | 量级预估 |
|------|------|------|---------|
| **dbg-1** ✅ 已验收（2026-11-12 用户实测） | 步骤一：`engineDebug` 设置 + fail-fast + messenger 回调路由（2.1~2.4 全部） | 无（先行） | 小（RHI `VulkanInstance` 主体 + `JavelinSetting` 两字段 + `JavelinEngine` 一行接线；改动集中、无渲染路径风险） |
| **dbg-2** ✅ 已验收（2026-11-12 用户实测；归属编辑器） | 步骤二：G-Buffer 可视化（3.1~3.4 全部；实装落点 §3.5） | dbg-1 无硬依赖，可并行；建议在后（dbg-1 验收后顺手） | 中（新 AppState + 覆盖 Pass + 内置 shader 资产三件 + meta + 单测/手验清单） |
| 后置挂账 | shadowMap 可视化（3.3）；VERBOSE 严重度；编辑器首选项 UI（2.4）；SPIR-V 静态自检（无 SDK 环境的引擎侧校验，立项另议） | dbg-1/2 | — |

两批各自独立验收（dbg-1 = 2.3 清单、dbg-2 = 3.4 清单），验收口径单点落 `Engine_Features.md`；批次索引进 `Engine_Roadmap_Progress.md`（支撑类散件节）。

## 5. 拍板记录

| 日期 | 事项 | 拍板 |
|------|------|------|
| 2026-11-10 | 立项两件（校验层回调 / GBuffer 可视化），SPIR-V 自检后置 | 用户 |
| 2026-11-10 | `engineDebug` 默认 true（开发者默认听见校验层） | 用户 |
| 2026-11-10 | debug=true 且无校验层 → **报错不启动**（推翻静默降级，红线 15 / Pitfalls #19 收口） | 用户 |
| 2026-11-10 | 开关 = 启动期消费、运行期不切（校验层实例级装载无热切时机） | 用户（「需要一个启动时引擎 debug 开关」） |
| 2026-11-12 | dbg-1 落地待验收（实装落点 §2.5） | 实施 |
