# 引擎渲染统计设计（v0.1，2026-09-27 实测验收通过）

> 2026-09-27 用户拍板：获取 GPU 信息 + 存活资源计数（**全部资源类型**）+ draw call / 三角形统计，
> 经 DebugDrawText 呈现为 overlay，**F3 边沿开关、默认显示**，帧统计 reset 由**引擎显式调用**。
> 形态参照 bgfx Statistics（统计 + dbg text 同屏呈现）。

## 1. 拍板记录（2026-09-27）

| 议题 | 拍板 |
|------|------|
| reset 语义 | 引擎显式调 `Device.resetFrameStats()`（RHI 不在帧边界自动清零） |
| 统计范围 | 11 类资源全量计数：texture / buffer / graphicsPipeline / computePipeline / sampler / shaderModule / pipelineLayout / descriptorSetLayout / descriptorSet / fence / semaphore |
| 展示 | 新增 AppState 控制的 DebugText overlay，F3 开关，默认显示 |

## 2. RHI 层（com.javelin.rhi）

### 2.1 GPUInfo（一次性静态信息）

`public final class GPUInfo`，纯值对象（构造器包私有，仅 VulkanDevice 构建时从
`VkPhysicalDeviceProperties` 一次性提取——LLWJGL struct 不出 RHI 内部类）：

| 字段 | 来源 |
|------|------|
| `deviceName` / `vendorId` / `deviceId` | properties 同名字段 |
| `deviceType` | `VK_PHYSICAL_DEVICE_TYPE_*` → DISCRETE / INTEGRATED / VIRTUAL / CPU / OTHER 字符串 |
| `apiVersion` + `apiVersionString()` | LWJGL `VK10.VK_API_VERSION_VARIANT/MAJOR/MINOR/PATCH` 拆位（签名已 MCP 核对，3.4.2 sources） |
| `driverVersion` + `driverVersionString()` | 同式 10.10.12 拆位显示；**厂商自定义格式 caveat 在 javadoc 注明**（NVIDIA 等语义不同） |

`toString()` 给日志一行摘要用。

### 2.2 RenderStats（计数器 + 双缓冲帧快照）

`public final class RenderStats`，两类统计：

**存活资源计数**（只增减不重置，存活 = 创建 − 释放）——上面 11 类，每类一对
`onXxxCreated() / onXxxDisposed()` 包_private 打点：

- **打点位置＝资源类构造器末尾 ++ / `dispose()` 的 disposed guard 之后 --**。
  不打在 `VulkanDevice.createXxx` 入口：`uploadTexture` 的 staging buffer、
  `VulkanFrameArena` 的常驻 buffer、管线内部布局都绕过公共入口直 new 构造器，
  构造器打点才是全量真值；
- guard 后打点防重复 dispose 双减；
- `VulkanDescriptorSet` 由池管理、dispose 原为无限幂等标记——**补 `if (disposed) return` guard**
  以配合计数（对 flag 语义无变化）。

**帧统计**（`drawCalls / triangles / instances / dispatches`）：

- 打点在 `VulkanCommandBuffer.draw / drawIndexed / dispatch`，`device.stats()` 直达；
- **快照双缓冲**：内部 `drawCallsRuntime` 等本帧累计 + `drawCallsLastFrame` 等上一帧快照，
  公开 getter `drawCalls()` 等一律**读快照**；
- 三角形口径：`draw → vertexCount/3`、`drawIndexed → indexCount/3`，各 × instanceCount
  （instances 单列）；topology 按 triangle list 计（当前引擎唯一使用形式）；
- 线程模型：单线程退化（RHI 27.1），普通 long 计数即可。

### 2.3 打点的三处顺带修正（本次调研确认的既有问题）

1. **管线内部临时 ShaderModule 用后即弃**：`VulkanGraphicsPipeline / VulkanComputePipeline`
   的 `createShaderModule(spirv)` new 完只取 handle 就丢——计数只涨不跌，且显式销毁
   全靠 Cleaner 兜底。修正：**管线创建成功后立即 `module.dispose()`**——规范语义是
   vkCreate*Pipelines **返回后**才可销毁模块。⚠ 实跑教训（2026-09-27 首跑闪退，
   Vulkan_Pitfalls 第 17 条）：首版把 dispose 放在创建调用之前，驱动解引用悬空句柄
   直接 EXCEPTION_ACCESS_VIOLATION——销毁时机必须精确到「创建完成之后」。
   这也让 shaderModule 计数能全量纳入而不失真；
2. **`VulkanComputePipeline.dispose` 漏销毁 `pipelineLayoutHandle`**（与 Graphics 版
   不对称，`markDestroyed` + `vkDestroyPipelineLayout` 都缺）——补齐；
3. `VulkanDescriptorSet.dispose` 补重入 guard（见 2.2）。

### 2.4 Device 接口扩展（3 方法，§26.1 清单同步）

```java
GPUInfo gpuInfo();         // 设备/驱动静态信息（构造时提取，只读）
RenderStats renderStats(); // 存活计数 + 上一帧 draw 快照（公开只读 getter）
void resetFrameStats();    // 引擎每帧录制收尾后显式调用：快照 runtime→lastFrame 并清零 runtime
```

最小化/跳帧时 `renderFrame` 返回 false、无新录制——引擎**照样调** resetFrameStats：
runtime 未变 → 快照保持上一实帧值，overlay 显示稳定不闪零。

## 3. 引擎层（javelin-core）

### 3.1 reset 调用点与快照时序

`JavelinEngine.mainLoop` 每帧 `frameLoop.renderFrame(...)` 之后无条件
`device.resetFrameStats()`。时序链（帧 N+1）：

```
earlyUpdate → RenderStatsAppState.update：读 stats 快照（=帧 N 的值）写 DebugText
renderFrame → buildFrameGraph（overlay 装配文本网格）→ graph.execute 录制（draw 打点累加 runtime）
mainLoop 尾 → resetFrameStats：runtime 快照进 lastFrame 并清零
```

图声明（含 overlay 装配）天然先于录制，overlay 读到的只能是上一帧——
快照双缓冲即为此设计（bgfx stats 同款语义），不存在读零问题。

### 3.2 RenderStatsAppState（com.javelin.core.debug）

- `extends AbstractAppState`；F3 边沿（`Input.wasPressed`）切 `visible`，**默认 true**；
- 关闭时逐行 `DebugText.clear(row)` 清干净自己占的行；
- **固定占用 DebugText 行 0~7**（组件规范，其他 DebugText 客户端请避开），
  每帧单行覆写惯用法 `clear(row) + printf(row, ...)`：
  **文本刷新 1 秒节流一轮**（2026-09-27 用户反馈"FPS 跳动太快"）——数字每秒刷
  一次不逐帧闪动，F3 开关不受节流即时响应；计时用未缩放 dt（timeScale 暂停时
  统计照常推进）。

| 行 | 内容 |
|----|------|
| 0 | 标题 "Javelin 统计"（黄前景）+ "[F3 隐藏]"（灰） |
| 1 | GPU 名（白） |
| 2 | API 版本 + 驱动版本（灰） |
| 3 | FPS（**本刷新窗实测**帧数，仅首帧由 Time EMA 兜底）+ 帧计数（亮绿） |
| 4 | DrawCalls + 三角形 + 实例（+ Dispatch，非零才显示） |
| 5 | 纹理 / 缓冲 / 图形管线 / compute 管线 |
| 6 | 采样器 / 描述符集 / 描述符布局 |
| 7 | 着色器模块 / 管线布局 / 栅栏 / 信号量 |

### 3.3 JavelinEngine 接线

- 新增 `protected AppStateSubsystem appStateSubsystem` 字段
  （`registerSubsystem` 注册时留引用——顺带给编辑器子类一个不查表就能拿到的挂载口）；
- 新增 protected 钩子 `registerDefaultAppStates(AppStateManager)`，默认 attach
  `RenderStatsAppState`；`initialize()` 在 `subsystemManager.initAll(this)` 之后调用；
- **DebugTextDemo 覆盖此钩子为空**：该样例自身就在验收 DebugText 行区语义
  （自动行 0/1、色表行区），与 overlay 的行 0~7 必然叠写冲突，关闭以保持样例纯净；
- **其余 demo 与 JavelinEditor（编辑器视口）默认显示统计**。JavelinEditor 原有
  `registerSubsystem` 覆盖（历史动机：编辑器要拿 AppStateSubsystem 引用，故自建
  实例替换）在基类持有 protected 字段 + 注册 DebugDrawTextSubsystem 后与基类
  完全重复，已整个删除（用户指正 2026-09-27）。⚠ 履历中的教训留档：编辑器同名
  私有字段曾遮蔽基类新字段致启动 NPE——**若有子类确需覆盖 `registerSubsystem`，
  必须给基类 `appStateSubsystem` 字段赋值，且不得以同名字段遮蔽**。

## 4. 文件清单

| 层 | 新增 | 修改 |
|----|------|------|
| RHI | `GPUInfo.java`、`RenderStats.java` | `Device`（+3 方法）、`VulkanDevice`（构建/持有/透传）、`VulkanCommandBuffer`（draw 打点）、11 资源类（ctor/dispose 各 1 行）、两处管线临时模块改为创建完成后即销 |
| core | `debug/RenderStatsAppState.java` | `JavelinEngine`（字段/钩子/reset 接线）、`DebugTextDemo`（关 overlay） |
| 文档 | 本文 | `RHI_Design_Core.md` §26.1 Device 清单、`AGENTS.md` 文档索引 |

## 5. API 签名确认记录（IDEA MCP）

- LWJGL 3.4.2 `VK10.VK_API_VERSION_VARIANT/MAJOR/MINOR/PATCH`（static int 版本拆位，
  sources jar 2826~2856 行核对）：MAJOR=`(v>>>22)&0x7F`、MINOR=`(v>>>12)&0x3FF`、PATCH=`v&0xFFF`；
- `VkPhysicalDeviceProperties.deviceNameString() / vendorID() / deviceID() / deviceType()`：
  项目内 `VulkanPhysicalDevice` 已有 `deviceNameString()/deviceType()/limits()` 用例，同族访问器模式一致。

## 6. 验收点

1. 任一 demo（SceneRenderDemo / MultiCameraDemo）或编辑器启动，左上角默认显示 8 行统计；
   GPU 名 / 驱动版本与机器实际一致；
2. F3 关闭后行 0~7 全部消失，再按恢复；数字每秒刷新一轮（1Hz 节流）、不逐帧闪动；
3. drawCalls / 三角形数与场景规模吻合（SceneRenderDemo 已知三角形数可对照）；
   overlay 读到的是**上一帧**快照，帧间不闪零、跳帧不归零；
4. 资源计数稳定应用：资产加载 / 窗口 resize 后数字与实际创建量一致，长跑无漂移
   （管线临时 shader 模块即时销毁后 shaderModule 计数应保持低位稳定）。
