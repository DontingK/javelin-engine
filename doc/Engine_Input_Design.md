# 引擎输入设计（Engine Input Design）

> 版本：v0.1（2026-09-26）
> 范围：二期①输入子系统——键盘/鼠标状态采集 + Action 映射（GLFW 事件源）。
> 验收样例：`javelin-core/src/test/java/com/javelin/core/InputCameraDemo.java`（输入驱动相机）。
> 配套：`doc/Engine_Roadmap.md` §2.1；调度契约见 `Subsystem` 接口（subsystem 包）。

---

## 1. 拍板记录（2026-09-26）

| 决策点 | 拍板 | 理由 |
|--------|------|------|
| KeyCode 形态 | **纯语义枚举，零平台关联**（二次拍板 2026-09-26：不携带 GLFW 键码，`fromGlfw/fromAwt` 翻译也不在枚举上——映射由各 Window 实现自己持有：`GLFWKeyMapping` / `AwtKeyMapping`） | KeyCode 与实现解耦，换窗口平台不动 core 枚举；首次拍板只到"core 不暴露 GLFW 常量"，二次升级为完全解耦 |
| 查询门面 | **静态门面 `Input`**（对齐 `Time` 先例） | 单引擎实例假设与 Time 一致；组件代码 `Input.isDown(KeyCode.W)` 最短 |
| Action 层形态 | **三次收束终态（2026-09-26）：ActionMap 概念移除，bind 与动作名查询合流 `Input`——唯一使用方式** | 演进：薄 ActionMap（独立类+查询）→ 绑定源补全 → 查询归门面 → 类整个移除；全局单表无注册生命周期、无多表名冲突，同名重绑即覆盖（rebinding 口子）；上下文动作集（载具/步行两套）后置，届时再议表分组 |
| ESC 关窗便利逻辑 | **移除** | 调试便利非产品行为；接管后由应用层自行决定 ESC 语义 |
| 输入源（编辑器） | **CanvasSurface 视口**（用户拍板 2026-09-26） | 键鼠事件均来自游戏视口区域，坐标即视口局部坐标（拾取直接可用） |
| 动作绑定源 | **键 + 鼠标键混绑（`InputCode` 标记接口）；轴两类源——键组正负与连续源（scroll/mouseDeltaX/mouseDeltaY 直通）** | "Fire 绑左键"是动作语义，不该散在游戏代码里读原始层；连续源覆盖视角/缩放类增量消费 |

## 2. 架构：三层，采集与状态分离

```
GLFWWindow 原生回调（平台键码）
        │  翻译：KeyCode/MouseButton/InputAction（InputSink 契约）
        ▼
InputSubsystem（InputSink + Subsystem 双重角色）
        │  回写入队：持续态 + 边沿双缓冲写入（pollEvents 时段）
        │  earlyUpdate：帧边界翻转，发布本帧快照
        ▼
Input 静态门面（唯一使用方式：原始查询 + 动作 bind/bindAxis/unbind + 动作名查询）
```

### 2.1 类清单（`com.javelin.core.input`）

| 类/接口 | 职责 |
|---------|------|
| `KeyCode` | 引擎键枚举（**纯语义**，无平台码关联；常用键子集 + UNKNOWN 兜底） |
| `MouseButton` | 鼠标键枚举（纯语义：LEFT/RIGHT/MIDDLE + 扩展键） |
| `InputAction` | PRESS/RELEASE/REPEAT（纯语义，实现侧翻译） |
| `InputSink` | **事件出口契约**：Window 实现 → 引擎状态层的唯一通道（onKey/onMouseButton/onCursorMove/onScroll/onFocusChanged）；mods 位掩码为引擎契约（SHIFT=0x1/CTRL=0x2/ALT=0x4/SUPER=0x8） |
| `InputSubsystem` | 状态机：回调写入 + earlyUpdate 帧翻转；`AbstractSubsystem` 实现 + `InputSink` 实现 |
| `Input` | 静态门面骨架：子系统 init 绑定 / shutdown 解绑；未初始化调用抛异常 |
| `Input`（使用面） | **唯一门面**：原始查询（物理键/鼠标/光标/滚轮）+ 动作配置（`bind`/`bindAxis`/`unbind`，全局单表同名覆盖）+ 动作名查询（持续态动词与原始层同名——`isDown(String)` 对 `isDown(KeyCode)`，拍板 2026-09-27；`isOn` 暂并存为同义词）+ `wasPressed`/`wasReleased`/`axis`；查询未绑定名抛异常 |
| `Binding` / `Axis` | 绑定表内部类型（包私有评估器 / 链式配置对象）——经 Input 间接使用，不出包（Axis 的链式 setter 公开） |

window 侧改动：`Window` 新增 `attachInputSink(InputSink)` default 空方法；
`GLFWWindow` 在 `create()` 统一注册 key/mouseButton/cursorPos/scroll/focus 五个
GLFW 回调，翻译成引擎枚举后投递（sink 为 null 时零开销跳过）——
**平台键码 → 引擎枚举的映射由实现私有持有**：GLFW 侧 `GLFWKeyMapping`
（core.window 包私有），AWT 侧 `AwtKeyMapping`（editor，见 §5）；
`MainFrame`（编辑器 Swing/CEF 窗口）同接口接入——**输入源 = CanvasSurface 视口**。

## 3. 帧语义与时序（本设计的地基）

mainLoop 每帧（JavelinEngine 接线，输入子系统零侵入）：

```
window.pollEvents()          ← 回调在本时段触发（主线程，无并发问题）
  → Time.update
  → subsystemManager.earlyUpdate   ← InputSubsystem：帧边界翻转，发布本帧快照
  → scene.update                   ← 组件 onUpdate 读 Input（本帧一致值）
  → subsystemManager.lateUpdate
  → update() → renderFrame
```

关键推导：**pollEvents 先于 earlyUpdate**，因此回调写入的就是"本帧"事件；
earlyUpdate 发布后，同帧所有消费方（scene.update / AppState / update）读到同一份快照。

### 3.1 边沿双缓冲（替代事件队列）

`justPressed/justReleased` 各两块缓冲，write/read 交替：

- 回调（pollEvents 时段）写 **write 侧**：按压沿/释放沿置位 + 持续态即时维护；
- earlyUpdate 交换 write/read：回调刚写完的变成本帧 **read 侧**（发布）；
  上帧 read 侧转新 write 侧并清空，承接下帧 pollEvents 事件。

相比"事件队列 + drain"：无队列分配、无逐条分发，且天然保证边沿**恰一帧有效**
（等价 Unity `GetKeyDown` 语义）。press+release 落在同一帧时两个边沿同时为真（持续态以 release 为准）。

### 3.2 增量/滚轮：累计窗口即帧窗口

鼠标增量、滚轮在回调里**累计**（500Hz+ 设备一帧多次事件），
earlyUpdate 发布累计值并清零——累计窗口 = [上帧 earlyUpdate, 本帧 pollEvents 末]，
恰为本帧事件总量。光标位置存最后事件值（窗口逻辑坐标，左上原点）。

### 3.3 时序不变量（消费方约定）

- isDown/isMouseDown：帧内恒定；
- wasPressed/wasReleased：本帧边沿，下一帧必须清零（双缓冲保证）；
- lateUpdate 之后到下帧 earlyUpdate 之间读到的是**同一帧**快照（无半帧翻转）。

## 4. 边界与已知取舍

- **REPEAT 不进边沿**：按住不放不重复触发 wasPressed（等价 GetKeyDown）；
  文本输入/IME 消费 repeat 后置；
- **失焦清态**：GLFW 不保证失焦补发 RELEASE——`onFocusChanged(false)` 清空
  全部持续按下态与 write 侧边沿，防"卡键"（read 侧保留本帧已发布值）；
- **UI 消费遮罩**：`Input.setConsumedByUI(true)` 全查询复位（v0.1 口子）——
  编辑器 UI 焦点接管游戏输入时使用；完整"事件分发/消费"模型后置；
- **未绑定查询抛异常**：Input 在子系统未注册/引擎未启动/已关闭时抛
  `IllegalStateException`——静默返回 false 会掩盖接线错误；
- **单 GLFW 窗口**：v0.1 游戏路径只服务主窗口；编辑器路径见 §5；
- **PRESS 归一化**：`onKey/onMouseButton` 对"PRESS 且已按住"按键重复跳过边沿——
  GLFW 显式 REPEAT，AWT 无 REPEAT 事件、按住会重发 KEY_PRESSED，
  归一化后两端边沿语义一致"恰一次"（press→release→press 同帧快速连按不受影响，
  RELEASE 已把持续态清位）。

## 5. 编辑器输入路径（Swing/CEF，输入源 = Canvas 视口）

编辑器 mainLoop 跑在 **Engine Main 线程**（ThreadUtil.run，见 ProjectLoadingFrame），
AWT 事件在 **EDT**——不能直写 InputSubsystem 状态，走"EDT 入队 → 引擎线程泵"：

```
Canvas 视口（CanvasSurface）AWT 监听（EDT）
    │  翻译入队：AwtKeyMapping.keyCode（VK_→KeyCode）/ mouseButton / modifiers
    │           （闭包捕获值不引用事件对象）
    ▼ ConcurrentLinkedQueue<Runnable>
MainFrame.pollEvents()（引擎线程，mainLoop 驱动）
    │  泵空队列 → sink.on*（等价 glfwPollEvents 语义，时序对齐 GLFW 路径）
    ▼
InputSubsystem（单线程消费，后续与 GLFW 路径完全一致）
```

要点：
- **输入源 = CanvasSurface**：鼠标坐标即视口局部坐标（拾取直接可用）；
  键盘事件须 canvas 持有焦点——点击视口 `requestFocusInWindow()` 抢占
  （视口输入惯例），点击 CEF UI 焦点移走、游戏输入自然静默（无须 consumedByUI）；
- **canvas 失焦 → onFocusChanged(false) 清态**（点 UI 区域防"卡键"）；
- **VK_ 与 GLFW 键码错位**：仅字母/数字/部分标点（ASCII 段）重合，
  导航/F 键/小键盘逐项翻译（`AwtKeyMapping`，连号区间数组直落 + switch 逐项）；
  AWT 不分左右修饰键（统一落 LEFT_*）；方括号/小键盘回转不映射落 UNKNOWN；
- **位掩码契约不变**：mods 统一引擎位风格（SHIFT=0x1/CTRL=0x2/ALT=0x4/SUPER=0x8），
  AWT `getModifiersEx()` 掩码经 `AwtKeyMapping.modifiers` 翻译。

## 6. 后置项

- 文本输入（char 回调 + IME）、剪贴板；
- 鼠标锁/游标模式（编辑器视口飞行相机需要 raw motion 时做）；
- 事件订阅 API（UI 要的是"事件流"不是"帧快照"，届时补 listener 通道）；
- ~~`KeyCode.fromAwt`（编辑器 Swing 窗口接入）~~——**✅ 编辑器已接入**（映射落
  editor 侧 `AwtKeyMapping`，见 §5；二次拍板后 KeyCode 上不设反查）；
- 方括号、小键盘回车的 AWT 映射（AWT 物理键位不可靠，待真机验证再定）；
- 屏幕点 → 世界射线换算：鼠标窗口坐标 → NDC 在二期③ Ray 内做（依赖
  viewportRect/DPI 信息，不属输入层）;
- 手柄/触摸。

## 7. 验收样例（双 Demo 对照：动作层 / 原始层）

`gradlew :javelin-core:runTest -DmainClass=com.javelin.core.InputCameraDemo`
（动作层样例：移动/视角/缩放走 bind + 按名查询）

- WASD/方向键移动（Input.bind 布尔动作 + 键组轴）、QE 升降、滚轮调速度（scroll 连续源轴）、
  Shift 疾行（原始层 isDown(KeyCode) 直查，不走动作表）；
- F 键打印光标窗口坐标（原始层 wasPressed 边沿 + mouseX/Y 位置通道）；
- 按住右键拖拽转视角：右键走混绑动作（MouseButton 入绑），光标增量走
  mouseDeltaX/Y 连续源轴（`rotationYXZ` 摆 yaw/pitch，拖右看右、拖下看下，pitch 限 ±89°）；
- SPACE 边沿翻转中央四边形旋转方向（每按一次触发一次，按住不放不重复）；
- 失焦再聚焦：按住的键不卡键；
- ESC 不再关窗（系统关闭按钮关窗）。

`gradlew :javelin-core:runTest -DmainClass=com.javelin.core.RawInputDemo`
（**原始层对照样例**：与上例同场景同行为，但**全程零 bind / 零按名查询**——
isDown/isMouseDown/wasPressed/mouseDelta/scrollDelta/mouseXY 直查；
SPACE 此处为边沿翻转移动方向。两个 Demo 的 [perf] 对比即动作表解析开销的
A/B 量测，预期差异不可见（每次按名查询仅多一次 HashMap 解析 + 布尔探针）。

双 Demo 行为一致 = 门面两层语义等价性验收；[perf] 数量级一致 = 动作层无性能税。
两个 Demo 共用 `JavelinEngineTestBase` 的每秒性能观测（`perfLogEnabled` 开关）。

---

> （注：部分内容可能由 AI 生成）
