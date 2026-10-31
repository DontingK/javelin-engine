# 引擎窗口设计

> 版本：v0.1
> 日期：2026-09-21
> 模块：javelin-core（`cn.donting.javelin.core.window`）
> 上游依赖：javelin-rhi 的 `RenderSurface` / `Platform`（RHI 设计文档 23.2 / 23.3）

---

## 1. 目标与非目标

**目标**：为阶段⑥（RHI 实现计划）的窗口呈现链路提供引擎侧窗口抽象——窗口创建/显示、每帧事件轮询、resize 感知（swapchain recreate 触发）、关闭请求，并通过 `RenderSurface` 桥接给 RHI。

**非目标**（本次不做）：

- 键鼠输入回调——另设 Input 子系统（本接口刻意保持最小呈现集）
- 多窗口管理——当前单主窗口语义
- Swing Canvas / 编辑器嵌入——接口形状为其预留（见第 3 节分离设计），实现留待编辑器阶段

## 2. 背景

v2 重构后工程内无任何窗口代码（历史 `GLFWSurface` / `VulkanInitTest` 已删除），
RHI 侧仅有 `RenderSurface` 消费契约（接口，无实现）。
javelin-core 已具备 Subsystem 体系（`SubsystemManager` / `Subsystem`）与 lwjgl-glfw 依赖。

## 3. 核心设计：Window 与 RenderSurface 分离

```
cn.donting.javelin.core.window              cn.donting.javelin.rhi.render
┌──────────────────────┐             ┌─────────────────────┐
│ Window（接口）        │             │ RenderSurface（接口） │
│  showWindow          │             │  nativeHandle()     │
│  closeRequested      │ renderSurface() platform()         │
│  width/height        │────────────▶│  width/height       │
│  isResized/ack       │             │  isResized/ack      │
│  isVisible/close     │             │  isVisible          │
└─────────┬────────────┘             └──────────▲──────────┘
          │                                     │
   ┌──────┴───────┐                      ┌──────┴───────┐
   │ GLFWWindow   │ 持有并创建           │ GLFWRender-  │ 适配器（包私有）
   │ （GLFW 实现）│◄─────────────────────│ Surface      │
   └──────────────┘                      └──────────────┘

归属：Window 是 JavelinEngine 的属性（应用引导时注入），不是子系统。
```

**为什么分离**（RHI 设计文档 23.2）：渲染表面不一定是窗口——GLFW 下包窗口、
Swing 下包 Canvas。若 `Window extends RenderSurface`，Swing 场景需伪造 Window。
分离后：`Window` 是引擎侧窗口抽象，`renderSurface()` 返回呈现面；
无窗口的呈现场景（Canvas）可直接实现 `RenderSurface`，与 Window 无关。
控制反转方向不变：上层实现、RHI 消费，RHI 只认 nativeHandle + platform。

## 4. 接口清单

| 类 | 职责 |
|----|------|
| `Window` | 引擎侧窗口抽象（最小呈现集），见源码 javadoc |
| `GLFWWindow` | GLFW 实现；GLFW init/terminate 引用计数；先隐藏创建再显示 |
| `GLFWRenderSurface` | 包私有适配器，把窗口状态翻译成 RHI 契约 |

## 4.1 归属：JavelinEngine 的属性，不是子系统

用户拍板（2026-09-21）：窗口归引擎主类持有——`JavelinEngine.start()` 内经
`createWindow()` 工厂创建（protected，子类可覆盖），`window()` 访问。
生命周期由 start() 编排：`create() → showWindow() → mainLoop() → close()`（finally）。
理由：

- 窗口不是"跨节点的全局服务"（Subsystem 的定位），是应用级资源，
  尺寸/标题等配置来自 `JavelinSetting`
- 呈现链路与窗口强耦合：acquire/present/resize/close 全在帧循环内，不存在独立的
  "窗口更新阶段"交给子系统调度
- 包一层 Subsystem 只是给 showWindow/pollEvents/close 加间接层

**后续演进**：待 SubsystemManager 驱动的正式主循环落地、且 Input 子系统需要窗口句柄时，
可能以"窗口属主 + 状态分发"的身份回归；形状届时再定。

## 5. 关键设计点

### 5.1 GLFW 全局引用计数

`glfwInit()`/`glfwTerminate()` 是进程级全局调用。`GLFWWindow` 以静态引用计数管理：
首个窗口 showWindow 时 init + 安装错误回调，最后一个 close 时 terminate。
多窗口共存安全，与 23.4 单窗口现状兼容。

### 5.2 resize 检测：轮询而非回调

`GLFWRenderSurface.isResized()` 每次查询时轮询 `glfwGetFramebufferSize`，
与上次 `acknowledgeResize()` 确认值比较。取舍：

- **选轮询**：与 `RenderSurface` 查询模型一致；渲染循环每帧本来就要问一次，
  不引入回调状态机；无跨线程问题
- **弃回调**：`glfwSetFramebufferSizeCallback` 需在主线程安装、状态需同步给渲染侧

初始确认值为 -1 → 首次 `isResized()` 必为 true，恰好驱动 swapchain 首次创建。

### 5.3 主线程约束

所有 GLFW 调用（创建/轮询/销毁）必须在主线程执行。
帧循环中 `pollEvents` 的前提：引擎主循环跑在主线程
（当前为单线程退化模式，RHI 设计文档 27.1 允许）。

### 5.4 平台检测

`GLFWRenderSurface.platform()` 经 LWJGL `org.lwjgl.system.Platform.get()` 映射到
RHI `Platform`。简化：LINUX / FREEBSD 一律映射 X11，Wayland 区分留待 Linux 适配。

### 5.5 ESC 关闭

ESC 释放 = 请求关闭（`glfwSetWindowShouldClose`），属窗口生命周期便利，
不是输入系统——正式输入走后续 Input 子系统。

## 6. 与阶段⑥帧循环的衔接

```
启动（JavelinEngine.start()）：
  window = createWindow()   // 工厂，protected 可覆盖
  window.create()           // 隐藏创建
  window.showWindow()

每帧（mainLoop，窗口从 engine.window() 获取）：
  window.pollEvents()              // 保证 resize/close 状态最新
  渲染侧：surface.isResized() ? recreate swapchain + invalidate 图缓存 + acknowledge
  acquire → 录制图 → present
  window.closeRequested() → 退出循环

退出：finally 中 window.close()
```

swapchain recreate 与图缓存失效的咬合见 RHI 设计文档 18.5 / 23.6-3。

## 7. 已知限制

| 限制 | 说明 | 后续 |
|------|------|------|
| 单主窗口 | 无多窗口/多 RenderSurface 管理 | 编辑器阶段 |
| 无输入 | 键鼠回调未定义 | Input 子系统 |
| Linux 平台 | 固定 X11 | Linux 适配 |
| DPI 变更 | 轮询模型可感知尺寸变化，但交换链重建时机由渲染侧决定 | 阶段⑥ |
