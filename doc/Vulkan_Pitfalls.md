# Vulkan / LWJGL 陷阱记录

> 版本：v1.0
> 日期：2026-09-23
> 范围：Javelin Engine 5 RHI 实现过程中实踩的 Vulkan 规范、LWJGL 绑定、GLFW 窗口、Java NIO 陷阱，
> 以及沉淀下来的 GPU 排障方法论。每条含：症状 → 根因 → 修法 → 出处。
> 实现进度与排障过程详见 `RHI_Implementation_Progress.md`。

---

## 一、Java NIO / 内存

### 1. `ByteBuffer.allocateDirect()` 默认大端，GPU 期望小端 ★最阴险

- **症状**：顶点/索引数据上传"成功"（无报错），GPU 读到的却是垃圾值——三角形退化、花屏、
  采样乱码，一切"画不出来"类症状皆可能。回读显存可见一堆非规格化小数
  （如 `2.68E-43`、`4.6006E-41`——这是 float 字节整体错位的特征指纹）。
- **根因**：Java NIO 的 ByteBuffer 默认 **BIG_ENDIAN**；x86/GPU 数据是 **小端**。
  `putFloat(-0.5f)` 写出 `BF 00 00 00`，GPU 按小端解读成 `0x000000BF`（非规格化数 ≈ 2.68E-43）。
- **修法**：所有喂给 `UploadData` / 显存的上传缓冲，必须显式
  `.order(ByteOrder.LITTLE_ENDIAN)` 后再写数据。回读诊断同理按小端解读。
- **出处**：2026-09-23 三角形首跑不可见的真正根因（排查四步记录见进度文档"阶段⑥ 第二步"）。

### 2. `mapped.put(data)` 会消耗源 buffer 的 position

- **症状**：同一个 `UploadData`/`ByteBuffer` 第二次使用时长度为 0，写入静默变空操作。
- **修法**：`update()` 入口先取 `int bytes = data.remaining()` 备用，别在写入后再读 `remaining()`。

---

## 二、LWJGL 绑定

### 3. `VkWin32SurfaceCreateInfoKHR.validate()` 对 hinstance/hwnd 均做非空检查

- **症状**：NPE @ `Checks.check`（创建 surface 时）。
- **根因**：LWJGL 3.4 生成代码要求 `hinstance`/`hwnd` 均非 0；Vulkan 规范里 hinstance 本可选。
- **修法**：`hinstance(WinBase.GetModuleHandle(null, (CharSequence) null))`
  （lwjgl core 的 `org.lwjgl.system.windows.WinBase`；两个重载使 `null, null` 有歧义，必须转型）。
- **出处**：2026-09-22 清屏排障第二障。

### 4. LWJGL core 的 `User32` 是部分绑定

- `IsIconic` / `IsWindowVisible` 签名：`(long hwnd) → boolean`；
- **没有** `GetClientRect` / `GetWindowThreadProcessId` / `IsWindow`，需要时得自己写 JNA/FFM 绑定；
- Win32 常量：`GWL_STYLE = -16`、`WS_VISIBLE = 0x10000000`。

### 5. VkResult 错误码：-6 与 -7 别看混

- `-6` = `VK_ERROR_LAYER_NOT_PRESENT`（**层**缺失，如未装 Vulkan SDK 校验层）；
- `-7` = `VK_ERROR_EXTENSION_NOT_PRESENT`（**扩展**缺失）。

### 6. `vkCmd*` 系列第一个参数是 `VkCommandBuffer` 对象，不是 `long`

- 需 `new VkCommandBuffer(address, device)` 构造（`vkBeginCommandBuffer`/`vkEndCommandBuffer`/所有 `vkCmd*`）。

### 7. `VkSubmitInfo.pCommandBuffers` 是 `PointerBuffer` 不是 `LongBuffer`

- 用 `stack.mallocPointer(1)` + `.put(0, address)`；
- `signalSemaphoreCount(int)` 等 count setter **不存在**，由 `pXxx.remaining()` 自动推断——
  LWJGL 的 `pXxx(Buffer)` setter 一律自动带 count（设备队列创建依赖此行为，可佐证），无需手动设。

### 8. `vkCreateGraphicsPipelines` / `vkCreateComputePipelines` 需要 `Buffer`

- 单个对象也要 `VkGraphicsPipelineCreateInfo.calloc(1, stack)` 创建单元素 Buffer。

### 9. 常量类归属

- `VK_API_VERSION_1_3` 在 `VK13` 类（`import static VK13.VK_API_VERSION_1_3`）；
- `VK_SAMPLER_ADDRESS_MODE_MIRROR_CLAMP_TO_EDGE` 在 `VK12` 类。

### 10. `Fence.wait()` 覆盖 `Object.wait()`

- RHI 的 `Fence.wait()` 与 `Object.wait()` 冲突，本项目决策改名 `await()`。

---

## 三、Vulkan 规范

### 11. `VkPipelineVertexInputStateCreateInfo` 漏 `sType$Default()` ★静默失败

- **症状**：顶点输入声明（binding/stride/attribute）不生效，**无任何报错**（无校验层时），
  顶点 fetch 到垃圾数据，图形静默不可见。
- **根因**：`calloc` 零初始化后 sType = 0，不是合法的
  `VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO`，驱动解析行为未定义。
- **修法**：所有 `Vk*CreateInfo` 组装后逐一检查 `sType$Default()`——
  同方法里其他结构体都设了、唯独漏一个是本坑的高发形态。
- **出处**：2026-09-23 三角形排查中发现的规范违反（非根因，但保留修复）。

### 12. dynamic rendering 必须挂 `VkPipelineRenderingCreateInfo`（遗留未修）

- `renderPass(VK_NULL_HANDLE)` 走 dynamic rendering 时，必须经 pNext 挂
  `VkPipelineRenderingCreateInfo` 声明 `colorAttachmentCount` / `pColorAttachmentFormats` /
  `depthAttachmentFormat`，否则管线创建信息不完整（当前实现靠驱动宽容工作，属规范违反）。
- **状态**：已记录待办，修复时机待拍板。

### 13. Y 翻转与绕序（实测结论，2026-09-23）

- Vulkan NDC **Y 向下**、深度 **[0, 1]**（完整约定见 `RHI_Design_Core.md` 161-168 节）；
- **不翻转**时，CCW 资产（Y 向上书写）在 framebuffer（Y 向下）中即 **CW** 绕序
  （frontFace=CW 可见、CCW 被剔）；
- **翻转后**（负高度视口或投影矩阵翻转，二者镜像等价）变为 **CCW** 绕序，
  **须配 `frontFace = CCW`**；
- 引擎最终约定：**投影矩阵翻转 + frontFace=CCW**（用户拍板：翻转放投影矩阵，视口透传）。

### 14. 视口 y 与高度符号必须配对

- `RHIViewport(0, h, w, h)`（y=h 且正高度）把 NDC 映射到 y ∈ [h, 2h]，
  几何整体被推出屏幕裁剪掉，与绕序/剔除无关；
- 不翻转的正确写法是 `(0, 0, w, h)`（此时三角形顶点朝下）；
- Y 翻转写法是 `(0, h, w, -h)`（负高度，此时顶点朝上）。

---

### 15. loadOp 清屏无视 scissor：区域清屏必须 `vkCmdClearAttachments` ★设计级

- **症状**：多视口/分屏场景给每个 pass 设不同 `setScissor` + `CLEAR` loadOp，
  预期各区域不同清屏色，实际只有最后声明的颜色全屏生效（前一个区域被后一个清掉）；
- **根因**：Vulkan 规定 render pass loadOp 的 CLEAR **忽略 scissor，始终清整个附件**。
  scissor 只影响光栅化阶段的绘制，不影响附件加载/清屏；
- **修法**：分区域清屏用 `vkCmdClearAttachments`（rect 在命令里显式给，同样不受管线
  scissor 影响），且必须在渲染通道实例内调用。dynamic rendering
  （`vkCmdBeginRendering`）开启的也是渲染通道实例，该命令合法；
  底层为 LOAD loadOp 保住矩形外内容 + 通道开始后按附件索引批量清；
- **注意**：rect 用 framebuffer 坐标（左上原点）且须在 renderArea 内；
  `VkClearAttachment.colorAttachment` 是 pColorAttachments 的索引，不是 attachment 序号；
  整附件清屏仍优先 CLEAR loadOp（零额外命令）；
- **出处**：Vulkan 规范 VkRenderingAttachmentInfo/loadOp 语义；javelin-engine4
  `VkRenderBackend.clearViewAttachments` 同款做法（多 View 各自区域清屏，已生产验证）；
  engine5 阶段⑥ RHI 已封装：`PassDef.addColorOutput(4 参重载, clearRegion)` +
  `CommandBuffer.clearColor`，验收测试 ViewportQuadTest。

---

## 四、GLFW / 窗口

### 16. `glfwCreateWindow` 返回的是 GLFW 内部结构体指针，不是 HWND ★

- **症状**：手写 `vkCreateWin32SurfaceKHR` 后，`vkGetPhysicalDeviceSurfaceCapabilitiesKHR`
  报 `VK_ERROR_SURFACE_LOST_KHR`（-1000000000）；但 surface 创建、formats/present 查询
  对野指针 HWND 全部"成功"（该驱动不校验），极具误导性。
- **根因**：`RenderSurface.nativeHandle()` 语义错位——设计写"GLFW window 指针"，
  RHI 却当 HWND 用。（旧引擎走 `glfwCreateWindowSurface`，GLFW 内部自己换 HWND，所以从没踩过。）
- **修法**：`GLFWRenderSurface.nativeHandle()` 在 WINDOWS 下经
  `GLFWNativeWin32.glfwGetWin32Window(glfwHandle)` 翻译为真 HWND（core 侧做，RHI 不依赖 GLFW）。
- **排障误导留念**：曾误判为"窗口 show 后闪现即被销毁"——GWL_STYLE=0、IsWindowVisible=false
  等 win32 探测拿 GLFW 结构体指针自然全 false；实际是句柄根本不是 HWND。
  `glfwGetWindowAttrib(handle, GLFW_VISIBLE = 0x20004)` 可核对 GLFW 侧可见性。
- **出处**：2026-09-22 清屏排障第三障。

### 17. 校验层缺失降级

- debug 模式请求 `VK_LAYER_KHRONOS_validation` 而本机未装 Vulkan SDK 时，
  `VulkanInstance` 先 `vkEnumerateInstanceLayerProperties` 存在性检查，缺失则 warn 降级继续
  （不再硬失败）；装了 SDK 的机器自动启用。

---

## 五、排障方法论（沉淀）

1. **隔离实验，一次只改一个变量**：本轮三角形排查的收敛路径——
   `cull=none`（排除剔除）→ `vb=shader` 顶点写死（排除顶点缓冲/输入装配）→
   正/负高度视口（排除翻转）→ 回读显存（定位字节错位）。每轮实验只回答一个是非题。
2. **数据级 bug 静态审查查不出**：代码全链路"看起来都对"时，上回读——
   hostVisible 内存在写入后重新 map 打印，一锤定音。
3. **诊断开关默认值写死在源码**：`JavaExec` 子 JVM 不继承命令行 `-D`
   （需 build.gradle 显式 `systemProperty` 转发），IDE 直接跑 Main 时环境变量也不可靠；
   排查期最稳的方式是默认值直接写在源码里，重编译即切换。
4. **核对配置生效看日志**：每轮实验先确认日志 config 行/时间戳是新的，再解读现象——
   旧日志当新日志贴出会浪费一整轮。
5. **本机无 javap/java 可用时**（gradlew toolchain JDK 不在常规路径），
   别指望反编译 LWJGL 验证签名——改从调用方行为佐证（如"设备队列创建依赖 pXxx 自动带 count"）。
