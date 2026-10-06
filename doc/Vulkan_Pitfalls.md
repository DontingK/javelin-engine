# Vulkan / LWJGL 陷阱记录

> 版本：v1.1
> 日期：2026-09-25
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

### 11. `vkAllocateDescriptorSets` 输出参数是 `LongBuffer`/`long[]`，不是 `PointerBuffer`

- **症状**：`vkAllocateDescriptorSets(device, allocInfo, pointerBuffer)` 编译错误：
  找不到合适的方法（`PointerBuffer` 无法转换为 `long[]`/`LongBuffer`）。
- **根因**：VkDescriptorSet 是**非调度句柄**，LWJGL 输出参数用 `LongBuffer`/`long[]`；
  只有**调度句柄**（VkCommandBuffer / VkQueue / VkDevice 等）才用 `PointerBuffer`。
- **修法**：`LongBuffer setBuf = stack.mallocLong(1); vkAllocateDescriptorSets(device, allocInfo, setBuf);`
- **出处**：2026-09-24 描述符池落地首编译报错（签名以编译器错误提示为准）。

---

## 三、Vulkan 规范

### 12. `calloc` 结构体漏设字段：sType / descriptorCount 零初始化 ★静默失败

- **症状 A（sType）**：顶点输入声明（binding/stride/attribute）不生效，**无任何报错**（无校验层时），
  顶点 fetch 到垃圾数据，图形静默不可见。
- **根因 A**：`calloc` 零初始化后 sType = 0，不是合法的
  `VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO`，驱动解析行为未定义。
- **症状 B（descriptorCount，2026-09-24 纹理黑屏根因）**：`VkWriteDescriptorSet` 只设了
  dstSet/dstBinding/descriptorType/pImageInfo，漏 `descriptorCount` → calloc 后为 0 →
  `vkUpdateDescriptorSets` 按 **0 条**写入 = **静默不写**，描述符集保持空，
  shader 采样返回黑色，无任何报错。
- **修法**：`calloc` 结构体凡是有 count 的字段（descriptorCount / stageCount 等）都要显式设置；
  只有 `pXxx(Buffer)` 数组指针 setter 会自动带 count（见第 7 条）。sType 则靠
  同方法内其他结构体都设了、唯独漏一个的对比排查。
- **出处**：A = 2026-09-23 三角形排查中发现的规范违反（非根因，但保留修复）；
  B = 2026-09-24 纹理测试黑屏根因。

### 13. dynamic rendering 必须挂 `VkPipelineRenderingCreateInfo`（2026-09-24 已修）

- `renderPass(VK_NULL_HANDLE)` 走 dynamic rendering 时，必须经 pNext 挂
  `VkPipelineRenderingCreateInfo` 声明 `colorAttachmentCount` / `pColorAttachmentFormats` /
  `depthAttachmentFormat`，否则管线创建信息不完整（修复前靠驱动宽容工作，属规范违反）。
- **修法**：从 `GraphicsPipelineDesc.colorAttachmentFormats/depthAttachmentFormat` 构建该结构体，
  `pipelineInfo.pNext(renderingInfo)`（LWJGL 类型化重载会保留既有 pNext 链）；
  深度模板混合格式（D24S8/D32S8/S8）须把同一格式同时填进 `stencilAttachmentFormat`。
- **出处**：2026-09-23 三角形阶段发现、2026-09-24 修复（VulkanGraphicsPipeline）。

### 14. Y 翻转与绕序（实测结论，2026-09-23）

- Vulkan NDC **Y 向下**、深度 **[0, 1]**（完整约定见 `RHI_Design_Core.md` 161-168 节）；
- **不翻转**时，CCW 资产（Y 向上书写）在 framebuffer（Y 向下）中即 **CW** 绕序
  （frontFace=CW 可见、CCW 被剔）；
- **翻转后**（负高度视口或投影矩阵翻转，二者镜像等价）变为 **CCW** 绕序，
  **须配 `frontFace = CCW`**；
- 引擎最终约定：**投影矩阵翻转 + frontFace=CCW**（用户拍板：翻转放投影矩阵，视口透传）。

### 15. 视口 y 与高度符号必须配对

- `RHIViewport(0, h, w, h)`（y=h 且正高度）把 NDC 映射到 y ∈ [h, 2h]，
  几何整体被推出屏幕裁剪掉，与绕序/剔除无关；
- 不翻转的正确写法是 `(0, 0, w, h)`（此时三角形顶点朝下）；
- Y 翻转写法是 `(0, h, w, -h)`（负高度，此时顶点朝上）。

---

### 16. loadOp 清屏无视 scissor：区域清屏必须 `vkCmdClearAttachments` ★设计级

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

### 17. 着色器模块生命周期：销毁必须晚于「管线创建完成」★启动闪退

- **症状**：渲染统计打点改造后引擎启动闪退——`EXCEPTION_ACCESS_VIOLATION (0xc0000005)`
  原生层崩溃，无 Java 侧异常；日志停在 `InputSubsystem initialized` 之后
  （即 DebugDrawTextSubsystem.init 建首个管线处）。Driver 无校验层拦截，直接崩
  在 `vkCreateGraphicsPipelines` 内部解引用已销毁的模块句柄。
- **根因**：管线内部临时 `VulkanShaderModule` 被"new 完立即 dispose"——dispose 落在
  `vkCreateGraphicsPipelines` **消费句柄之前**，模块先行 `vkDestroyShaderModule`。
  规范语义是「**管线创建完成后**模块才可销毁」（vkCreate*Pipelines 返回即消费完
  SPIR-V）——「创建时消费」≠「参数填完就能销」。
- **修法**：模块引用在 try(MemoryStack) 块外用局部变量留存，
  **`vkCreateGraphicsPipelines` 成功返回之后**再 `dispose()`（Graphics/Compute 两处同修）。
- **教训**：生命周期结论的措辞必须精确到调用时序——「创建完成后可销」与
  「传入创建参数后即可销」在原生层是生与死的差别；此类改动必须对照调用链核对。
- **出处**：2026-09-27 渲染统计打点首跑闪退（SceneRenderDemo，用户回报；
  `Engine_RenderStats_Design.md` §2.3 修正记录）。

---

## 四、GLFW / 窗口

### 18. `glfwCreateWindow` 返回的是 GLFW 内部结构体指针，不是 HWND ★

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

### 19. 校验层缺失降级

- debug 模式请求 `VK_LAYER_KHRONOS_validation` 而本机未装 Vulkan SDK 时，
  `VulkanInstance` 先 `vkEnumerateInstanceLayerProperties` 存在性检查，缺失则 warn 降级继续
  （不再硬失败）；装了 SDK 的机器自动启用。

---

## 五、RHI 命令序约束

### 20. `bindDescriptorSet` 之前必须先 `bindPipeline`（RHI 布局句柄缓存约束）★

- **症状**：运行期 `IllegalStateException: No pipeline bound before bindDescriptorSet`
  （`VulkanCommandBuffer.bindDescriptorSet`）——Pass 回调里第一行就绑描述符集（如 set0 帧级 UBO）时触发；
- **根因**：javelin-rhi 的管线布局句柄在 `bindPipeline` 时缓存（`VulkanCommandBuffer`），
  `bindDescriptorSet` 靠它填 `vkCmdBindDescriptorSets` 的 layout 参数；没绑过 PSO 就没有布局可用。
  注意这是 **RHI 实现层的约束**，不是 Vulkan 规范本身（Vulkan 只要求 layout 兼容）；
- **修法**：Pass 回调入口先 `bindPipeline` 再绑描述符集。Pass 声明期不定 PSO、
  PSO 按物体顶点布局在回调内切换（RHI 12.5-1 模式）时：空绘制列表直接 return
  （Pass 仍声明，loadOp 清屏不受影响）；非空用首个物体 PSO 建立布局——
  同一 Pass 内各 PSO 的 set 布局一致时，后续切 PSO 不影响已绑描述符；
- **出处**：2026-09-25 MultiCameraDemo 首跑，已修（DefaultHybridRenderPipeline 两 Pass 回调）。

---

## 五·五、呈现与帧节律

### 21. FIFO 锁帧下移动顿挫——帧率数据全干净 ★

- **症状**：InputCameraDemo 按住 W 移动有肉眼顿挫；每秒性能日志
  `180 fps | worst 7.8 ms | avg 5.6 ms`——平均恰好 1/180s（显示器刷新率锁帧），
  worst « 2×avg（无任何一帧错过 vsync 周期）。**不掉帧、无 GC 尖刺、渲染本身 <6ms**，
  帧率维度全部排除；
- **根因**：FIFO 显示节拍严格固定（5.56ms/格），主循环 dt 却带 ±2ms 级
  线程唤醒抖动；位移按 dt 积分 → 帧间步进忽大忽小（worst/avg ≈ 40% 差异）——
  节拍没断但步伐不匀，肉眼即顿挫；叠加 vsync 1-2 帧输入→显示延迟加重手感。
  **帧率高 ≠ 节律好，worst/avg 比值是顿挫的观测指标**；
- **修法/验证**：A/B 隔离——`setting.setVsync(false)`（IMMEDIATE）后
  `[perf] ~10000 fps | avg 0.1 ms`，移动顺滑，实锤 FIFO/DWM 呈现节奏；
  引擎侧候选（待做，按性价比）：① present mode 接 MAILBOX
  （`SwapChainDesc.PresentMode` 枚举已有值，`JavelinEngine.createSwapChainDesc`
  只做 vsync→FIFO/IMMEDIATE 二值映射，需加配置位 + swapchain 图像数 2→3）；
  ② vsync 锁帧下 dt 平滑/固定步长（模拟步进与真实 dt 解耦）；③ 独占全屏；
- **观测工具**：InputCameraDemo.update() 的 per-second `[perf]` 日志
  （fps / worst 单帧 / avg），读法——avg ≈ 1/刷新率 = FIFO 宽帧；
  worst « 2×avg = 节律问题而非掉帧；
- **出处**：2026-09-27 InputCameraDemo 移动卡顿排查，用户 A/B 确认；排障链：
  per-second 观测先排帧率（数据干净）→ 单变量 A/B（vsync off 顺滑）→ 定位呈现节律。

### 22. frontFace 绕序推理漏算第二次镜像：投影 Y 翻转 + Vulkan 窗口 y 向下相互抵消 ★推理链级

- **症状**：编辑器 plane（XZ 地面）始终只从下方可见；反转顶点索引后现象不变
  （产物 hexdump 实证确为新索引）。两次观察互相矛盾——任何依赖绕序的剔除
  不可能对两种绕序给出同一可见侧，矛盾本身即推理链有漏。
- **根因**：推理链只数了一次镜像——「投影 m11 取负（GL NDC → Vulkan NDC）后
  CCW 资产呈 CW，故 frontFace 应取 CW」。漏算：Vulkan 窗口映射
  y_w=(y_ndc+1)/2·H 且 y 轴**向下**，相对 GL 的 y-up 放置又是一次垂直镜像——
  **两次镜像抵消，整条管线与 OpenGL 等效**：frontFace=CCW + Cull Back ⇒
  可见面 = 几何法向侧（RenderingSystem 实传 CCW 一直正确；FrontFace.java
  误注「引擎约定 CW」即此漏算，2026-10-04 已更正）。
- **修法/验证**：资产按 CCW 授权（法向侧可见；MultiCameraDemo quad 同口径
  自证——0,1,2 绕序几何法向 +Z、从 +Z 可见）。附带坑：源反转后产物 stale
  不重导（import 只补缺失产物、不检测源变更）会让人误判「索引翻转无效」——
  改 .smesh 源后必须删 Library 产物强制重导。
- **出处**：2026-10-04 编辑器 plane 朝向全天排查（CreatePlaneMenuCommand 落地
  日起）；两次矛盾观察点破推理链，VulkanCommandBuffer 正高度视口 + 投影翻转
  双证据定案。

### 23. `MemoryStack` 上的 struct 被实例字段长期持有：栈帧弹出后读全是覆写垃圾 ★生命周期级

- **症状**：设备能力/属性**后期**读取结果离谱——如 `supportedColorSampleCounts()`
  恒返回 `[COUNT_1]`（MSAA 档位被误判降级 OFF），桌面 GPU 明显不可能；而 GPUInfo
  （构造时早读）显示完全正常，极具迷惑性。
- **根因**：`VulkanPhysicalDevice.evaluate` 里 `VkPhysicalDeviceProperties.malloc(stack)` /
  `VkPhysicalDeviceFeatures.malloc(stack)` **栈上分配**，引用却被 `VulkanPhysicalDevice`
  实例字段带出方法。`select` 栈帧弹出后，该内存区被后续 `stackPush`（设备创建/交换链
  创建）复用覆写：构造期早读（GPUInfo 快照）侥幸踩在覆写前；初始化后期读
  （limits、`features.samplerAnisotropy()`——后者一直是静默垃圾数据）读到的全是覆写后的
  内容（本次实测为全零）。
- **修法**：跨栈帧长期持有的 struct 一律**无栈参堆分配**（`calloc()`/`malloc()`，
  生命周期随 Java 引用走 GC）；`MemoryStack` 只给「帧内即用即弃」的数据。排查指纹：
  同一来源某处读数恒为零/恒默认、另一处早读却正确 = 栈帧生命周期嫌疑。
- **出处**：2026-10-06 MSAA 抗锯齿首验收——日志 `antialiasing 降档: 设备
  color=[COUNT_1] depth=[COUNT_1]` 实锤（同日修复：props/feats 改无栈参 `calloc()`
  堆分配；全库排查确认其余 `Vk*` 实例字段均为 handle 包装器无自有内存）。

---

## 六、排障方法论（沉淀）

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
