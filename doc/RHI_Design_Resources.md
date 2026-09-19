# RHI 设计 · 分卷三：资源与绑定

> 总纲：`RHI_Design.md`（含 1 概述 / 2 设计原则 / 11 架构取舍与坑点 + 完整章节索引）
> 本卷包含：第 15 资源描述符与绑定模型 / 16 Shader SPIR-V 加载与 PipelineCache / 17 资源生命周期与内存管理
> 章节编号全局唯一（1~21），跨卷引用按编号查找，完整索引见总纲。

---


## 15. 资源描述符与绑定模型（P0 补齐）

> 目标：让第 10 节的 `setUniform` / `bindTexture` / `bindUniformBuffer` 全部可落地。
> 原则：上层不接触 `VkDescriptorSet`；所有绑定经 `DescriptorSet` 抽象；String 名称只出现在构建期，运行期零字符串操作。
> 对应 Vulkan 概念：Descriptor Set / Descriptor Set Layout / Descriptor Pool / Dynamic Uniform Buffer。

### 15.1 绑定模型选型

|方案|说明|优点|缺点|结论|
|---|---|---|---|---|
|A. 传统 Descriptor Set + 动态 UBO|每帧一个大 UBO，相机/物体矩阵按偏移写入；Descriptor Set 按材质/资源组合复用|实现简单、错误好排查、驱动兼容性最好|绑定粒度粗，切换成本略高|**v0.1 采用**|
|B. Bindless（VK_EXT_descriptor_indexing）|资源进全局索引表，shader 用数组下标访问|绑定开销最低、现代引擎主流（UE5/Unity HDRP 方向）|需要描述符堆管理 + 索引表，调试复杂|**v0.2 预留**，接口已兼容|

设计约束：**两种方案共用同一套上层 API**（`DescriptorSet` + `bindDescriptorSet`），v0.2 切换 Bindless 时只换 RHI 内部实现，不改引擎层代码。

### 15.2 核心类型定义

```java
// 描述符类型（对应 VkDescriptorType）
public enum RHIDescriptorType {
    UNIFORM_BUFFER,      // 只读 Uniform 缓冲
    DYNAMIC_UNIFORM_BUFFER, // 动态偏移 Uniform 缓冲（每物体矩阵）
    STORAGE_BUFFER,      // 可读写存储缓冲
    SAMPLED_TEXTURE,     // 采样纹理
    STORAGE_TEXTURE,     // 可读写纹理（计算/后处理）
    SAMPLER              // 采样器
}

// 单个绑定点声明（对应 VkDescriptorSetLayoutBinding）
public record RHIBinding(
    int set,                 // 描述符集编号（0=帧级，1=物体级，2=材质级）
    int binding,             // 集内绑定号
    RHIDescriptorType type,
    int count,               // 数组数量，Bindless 时为大数
    int stageFlags           // 可访问阶段，如 ShaderStage.VERTEX | ShaderStage.FRAGMENT
) {}

// 描述符集布局：由一组 RHIBinding 构建，绑定到管线创建
public class DescriptorSetLayout {
    public static DescriptorSetLayout create(List<RHIBinding> bindings);
}

// 描述符集实例：运行时填充真实资源
public interface DescriptorSet {
    void bindUniformBuffer(int binding, Buffer buffer, long offset, long range);
    void bindTexture(int binding, Texture tex, Sampler sampler);
    void bindSampler(int binding, Sampler sampler);
    void bindStorageBuffer(int binding, Buffer buffer);
    // 延迟到提交前统一更新，避免回调内频繁 vkUpdateDescriptorSets
    void update();
}
```

`GraphicsPipelineDesc` 增加描述符布局（第 12.6 节创建管线的入口不变）：

```java
public class GraphicsPipelineDesc {
    // ... 原有 shader / inputLayout / 状态字段 ...
    public List<DescriptorSetLayout> descriptorLayouts;  // 新增：管线使用的描述符集布局
    public int pushConstantSize;                             // 新增：Push Constant 总字节数（通常 ≤ 128B）
}
```

### 15.3 三级绑定划分（帧级 / 物体级 / 材质级）

|级别|内容|载体|更新频率|典型 set 号|
|---|---|---|---|---|
|帧级|相机 view/proj、时间、光源列表|帧级动态 UBO（每帧一个，如 64KB）|每帧 1 次|set 0|
|物体级|模型矩阵、骨骼数据|同一 UBO 内的动态偏移（`dynamicOffsets` 切换）|每物体 1 次偏移|set 1|
|材质级|贴图、采样器、材质参数|每材质一个 Descriptor Set，跨帧缓存|材质加载时 1 次|set 2|

- set 0 / set 1 共享同一块帧级 UBO，物体切换只改 dynamic offset，**不重新绑定缓冲**；
- set 2 材质描述符集跨帧复用，引擎侧 `MaterialCache` 持有，禁止每帧重建；
- 描述符池按帧环形复用，防止每帧创建 `VkDescriptorSet` 导致池耗尽。

### 15.4 setUniform 的落地路径（String → UBO 偏移）

`pass.setUniform("Camera.viewProj", float[])` 的完整路径：

1. **构建期编译**：RHI 在 Pass 首次构建时把 name 解析为 UBO 偏移（name → offset 映射），缓存进 `PassUniformLayout`；
2. **写入**：编译后写入帧级 UBO 对应偏移（构建阶段完成，回调内不再写 uniform）；
3. **回调期零字符串**：录制命令时只做 `bindDescriptorSet + dynamicOffset`。

```java
// RHI 内部（伪代码）：name -> offset 一次解析，后续全部走 int 句柄
public final class PassUniformLayout {
    private final Map<String, Integer> nameToOffset = new HashMap<>();
    private final ByteBuffer frameUboData; // 帧级 UBO 内容缓冲

    public int offsetOf(String name, int size, int alignment) {
        return nameToOffset.computeIfAbsent(name, n -> allocate(name, size, alignment));
    }
    public int allocate(String name, int size, int alignment) { /* 对齐分配 */ }
}
```

> 工程要求：回调内出现字符串 uniform 调用一律抛异常（Debug 模式），防止每帧哈希查找退化。

### 15.5 典型使用示例（帧级 UBO + 物体动态偏移）

```java
// ===== 构建期 =====
DescriptorSetLayout frameLayout = DescriptorSetLayout.create(List.of(
    new RHIBinding(0, 0, RHIDescriptorType.DYNAMIC_UNIFORM_BUFFER, 1, STAGE_VERTEX)
));
GraphicsPipelineDesc desc = new GraphicsPipelineDesc();
desc.descriptorLayouts = List.of(frameLayout, objLayout, materialLayout);
GraphicsPipeline pipeline = rhi.createGraphicsPipeline(desc);

// ===== 每帧 =====
Buffer frameUbo = rhi.createUniformBuffer(64 * 1024);
int camOffset = frameUbo.writeAligned(0, camA.viewProj);          // 帧级：相机矩阵
DescriptorSet frameSet = rhi.createDescriptorSet(frameLayout);
frameSet.bindUniformBuffer(0, frameUbo, 0, 64 * 1024);
frameSet.update();

// ===== 物体绘制（回调内） =====
pass.setRenderCallback(cmd -> {
    for(RenderObject obj : scene.getOpaqueObjects()) {
        int objOffset = frameUbo.writeAligned(camOffset, obj.modelMatrix); // 物体级动态偏移
        cmd.bindDescriptorSet(0, frameSet, 1, new int[]{objOffset});
        cmd.bindPipeline(obj.material.pipeline);  // v0.2 已统一：显式绑定 PSO
        cmd.bindVertexBuffer(obj.vertexBuffer, 0);
        cmd.drawIndexed(obj.indexCount, 1);
    }
});
```

### 15.6 运行环境基线（Vulkan 版本）

|项|要求|说明|
|---|---|---|
|Vulkan 最低版本|**1.3**|Dynamic Rendering（清屏机制）、Synchronization2（屏障）为核心功能，不再依赖扩展|
|LWJGL|3.3+（含 Vulkan 1.3 绑定）|Java 侧唯一依赖，禁止手写 JNI|
|可选扩展|`VK_EXT_descriptor_indexing`|v0.2 Bindless 前置，v0.1 不启用|
|调试扩展|`VK_EXT_debug_utils`|Debug 构建必须启用（对接第 13 节调试设计）|
|最大 Push Constant|128 字节（各厂商上限）|超过一律走 UBO，不依赖 Push Constant 做大参数块|

### 15.7 工程坑点

1. **动态 UBO 对齐**：偏移必须满足 `minUniformBufferOffsetAlignment`（常见 256B），用 `writeAligned` 统一对齐，禁止手写偏移。
2. **布局一致**：管线创建的 `descriptorLayouts` 必须与运行期绑定的 Descriptor Set 布局一致，否则 `vkCreatePipeline` 失败——RHI 创建管线时做布局比对并抛异常（Debug 模式）。
3. **描述符池**：帧级描述符池环形复用（如 3 帧 × 每帧 512 set），帧延迟销毁，防止池增长失控。
4. **String uniform 禁令**：`setUniform` 只允许构建期；回调内字符串调用 Debug 模式直接抛异常。
5. **Bindless 迁移点**：`DescriptorSet` 接口已抽象，v0.2 只需把内部实现换成描述符堆 + 索引表，shader 侧改为数组下标访问。


### 15.8 描述符池管理（v0.19）

> **决策**：描述符池按「set 布局类型 × 帧数」预算环形复用；更新集中在帧首（录制期不写描述符）；set1 动态 UBO 用动态 offset 绑定，避免每物体建 set；接口隔离在 DescriptorSet 后，Bindless 迁移只换池层。

**池预算与环形复用**

- 池按 set 布局类型分组：每布局一个 VkDescriptorPool（set0 camera / set1 obj / set2 material）；
- 预算 = 布局容量 × maxFramesInFlight × 每帧使用量。示例：set0 2 × 512、set1 2 × 4096、set2 2 × 1024；
- 帧索引版本化：帧 N 用池内第 [N × 预算, (N+1) × 预算) 段，复用前确保该段所在帧 fence 已完成（24.5 闸门）；
- 防失控：池满则新建池，旧池延迟销毁；Debug 模式记录告警（使用率 > 80% 即提示调预算）。

**每帧更新时序（固定三步）**

1. **构建期（CPU）**：setUniform / bindTexture 只记录意图（String → UBO 偏移映射，15.4），不碰 GPU；
2. **帧首（录制前）**：FrameArena 写入 UBO 数据（writeAligned，25.4）——每帧变化数据全部在这一步落内存；
3. **录制期**：Pass 回调内只 bindDescriptorSet（含动态 offset），**禁止回调内写描述符 / 更新 UBO**（15.7-4 禁令的落实）；
4. set2（材质）变化不频繁：材质创建 / 修改时更新一次，多帧复用（对应 20.5 材质实例模型）。

**动态 offset 绑定**

- set1（obj）用动态 UBO：`bindDescriptorSet(1, objSet, 1, new int[]{objOffset})`——同一 set 不同 offset 切换物体，避免每物体建 set；
- set0（camera）每相机一个 offset（第 19 节多相机）；
- 上限：maxDescriptorSetUniformBuffersDynamic；动态 offset 必须满足 minUniformBufferOffsetAlignment（常见 256B，15.7-1）。

**Bindless 预留（15.7-5）**

- 池层全部封装在 DescriptorSet 接口后；将来换描述符堆 + 索引表时，只替换池层，set 布局与 shader 访问方式不变（shader 改数组下标）。

**Debug 审计**

- 池分配 / 回收计数；每帧 set 使用率；泄漏检测（存活 set 数 > 预算 → 抛异常）。

---



---


---

## 16. Shader SPIR-V 加载与 PipelineCache（P1 补齐）

> 目标：GraphicsPipeline 创建是重量级操作（编译 + 优化可达几十~几百 ms），必须做到「创建一次、缓存复用、异步预热、支持热重载」。
> 对应 Vulkan 概念：VkShaderModule、VkPipelineCache、SPIR-V 离线编译。

### 16.1 总体流程

```Plain Text
GLSL 源码（引擎资源）
   ↓ 离线编译（glslc / shaderc，构建期或资源打包期）
SPIR-V 字节码（.spv 文件）
   ↓ 运行时加载（LWJGL 读字节 → VkShaderModule）
GraphicsPipeline 创建（desc + descriptorLayouts + PipelineCache）
   ↓ 缓存
PipelineCache（内存 LRU + 磁盘持久化）
```

### 16.2 SPIR-V 加载

```java
public class ShaderLoader {
    // 从 classpath / 文件系统加载 .spv 字节码
    public static byte[] loadSpv(String path) { /* ... */ }

    // 创建 VkShaderModule（内部实现；上层不接触 Vk* 句柄）
    public ShaderModule createShaderModule(byte[] spirv) {
        // 校验：字节头 0x07230203（SPIR-V magic number）
        // 失败抛 RHIException，带文件名与错误信息
    }
}
```

- 离线编译是硬约束：**运行时禁止调 glslang 编译**，只加载已编译字节码，启动更快、无编译工具依赖；
- shader 与管线的关联：`GraphicsPipelineDesc` 中 `vertexShader` / `fragmentShader` 为 `ShaderModule`；
- 校验失败处理：Debug 模式打印 SPIR-V 反汇编（可选 spv-dis），直接定位问题。

### 16.3 Shader 策略：0 变体 + uniform 分支（决策 v0.7）

> 决策：**不采用变体系统**（太复杂，见第 12.7 节扩展思路）。一个 Shader 一个程序，差异全部运行时解决：

1. **贴图有无** → 占位贴图：法线贴默认 (128,128,255)，metalRough 贴默认值；shader 用 uniform 分支（`if (material.useNormalMap)`，GPU 一致分支开销极小）；
2. **功能开关**（阴影采样、AO、细节贴图）→ uniform 分支，值来自材质 UBO；
3. **渲染阶段差异** → Shader 多 Pass（第 20 节），不是变体：GBuffer / ShadowCaster / Transparent 是不同 Pass，天然分开。

- 缓存 key = `GraphicsPipelineDesc 哈希 + descriptorLayouts 哈希`（不含变体维度），幂等查找；
- 代价：指令略多、寄存器略高（通常 <5% 性能差），可接受；
- 未来若 profile 出真实瓶颈（如 AlphaTest 指令流分叉、GBuffer 输出格式差异），再按第 12.7 节思路引入**手动变体**，只开 2~4 个关键组合，不搞自动 multi_compile。

### 16.4 PipelineCache 设计

```java
public class GraphicsPipelineCache {
    // 线程安全：加载期预热 + 运行期查找
    public GraphicsPipeline getOrCreate(GraphicsPipelineDesc desc);

    // 磁盘持久化：VkPipelineCache 二进制序列化
    public void saveToDisk(Path path);
    public void loadFromDisk(Path path);   // 首次启动秒开

    // 异步预编译：后台线程批量创建常用管线
    public void prewarm(List<GraphicsPipelineDesc> descs);

    // 热重载（仅 Debug）：shader 文件 mtime 变化时重建
    public void reloadIfChanged(String shaderPath);
}
```

要点：

1. **内存 LRU**：容量上限（如 512 条），超限淘汰最久未用管线（引擎侧仍持引用则走延迟销毁，见第 17 节）；
2. **磁盘缓存**：`vkCreatePipelineCache` 时传入上次保存的二进制，PSO 编译时间从"几百 ms"降到"几 ms"；在退出 / 存档点保存；
3. **异步预编译**：加载界面后台线程预热 GBuffer / 光照 / 后处理常用 PSO，避免进入场景时卡顿；
4. **热重载**：Debug 模式监听 shader 源文件 mtime，变化时重建对应 GraphicsPipeline 并替换缓存（用于迭代材质 / 光照调试）。

### 16.5 创建管线失败校验（Debug）

管线创建失败必须给出可行动错误（而非黑屏）：

- shader 接口不匹配（VS 输出 vs FS 输入、missing entry point）
- 附件格式与 shader 输出不匹配
- descriptor layout 与 shader 反射不一致（见 15.7 第 2 条）

Debug 模式默认开启 `VK_EXT_validation` + 上述比对，错误信息含管线名与 shader 路径。

### 16.6 工程坑点

1. SPIR-V magic number 校验必做，防止加载损坏 / 错误版本字节码；
2. PipelineCache 二进制**不能跨驱动 / 跨平台混用**，按 `(GPU 名称, 驱动版本)` 分文件保存；
3. 不采用变体系统（第 16.3 节）；若未来引入手动变体，需控制组合数：仅实例化用到的组合 + 懒创建，不做全量预编译（见第 12.7 节）；
4. 热重载只允许 Debug：Release 构建禁用文件监听，避免运行时 IO 与不确定行为；
5. 所有管线创建集中在加载阶段 + 缓存查找，**运行期路径不允许 createPipeline 同步调用**。

---


---

## 17. 资源生命周期与内存管理（P1 补齐）

> 目标：RHI 资源不能交给 Java GC 释放 GPU 资源；采用 **Java 对象持有句柄 + Cleaner 兜底销毁 + 帧延迟销毁**，不使用引用计数。
> 对应 Vulkan 概念：VkDeviceMemory、VkBuffer/VkImage、vkDestroy*、vkFreeMemory。

### 17.1 资源所有权模型（Cleaner 方案）

- RHI 资源 = **Java 对象**（`Texture` / `Buffer` / `GraphicsPipeline`），对象内部持有 `long handle`；
- 句柄是 RHI 内部 `ResourceTable` 的索引：handle → impl（VkBuffer/VkImage/VkPipeline...）；
- **不使用引用计数**：生命周期由 Java 对象可达性决定。对象不可达 → `Cleaner` 回调触发 → 资源句柄进入**延迟销毁队列**；
- 引擎层持有对象引用即"资源存活"，丢弃引用即"希望销毁"（由 Cleaner 兜底）；
- 显式 `close()` 可选：长生命周期 / 大显存资源建议主动调用，销毁时机确定（立即入延迟队列）。

```java
public abstract class RHIResource implements AutoCloseable {
    final long handle;                     // 句柄：ResourceTable 索引
    final Cleaner.Cleanable cleanable;

    RHIResource(RHI rhi, long handle) {
        this.handle = handle;
        // 关键：Cleaner 回调捕获的是 DestroyTask（rhi + handle 原始值），
        // 绝不能捕获 this / 闭包引用对象自身，否则对象永远可达、Cleaner 永不触发。
        this.cleanable = rhi.cleaner.register(this, new DestroyTask(rhi, handle));
    }

    /** 显式确定性销毁（推荐大显存 / 长生命周期资源）：立即入延迟销毁队列 */
    @Override public void close() { cleanable.clean(); }
}

// Cleaner 回调载体：只持有 rhi 与 handle，不引用 RHIResource 实例
final class DestroyTask implements Runnable {
    final RHI rhi; final long handle;
    DestroyTask(RHI rhi, long handle) { this.rhi = rhi; this.handle = handle; }
    public void run() { rhi.enqueueDestroy(handle); }   // 线程安全入队，见 17.2
}
```

> 设计边界（Cleaner 职责范围，用户决策）：
> - **Cleaner 只负责上层引擎需要并持有的资源对象**：`Texture`（纹理）、`ShaderModule`（着色器）、`Buffer`（缓冲）等引擎侧资源——对象不可达 → Cleaner 触发 → 入延迟销毁队列；
> - **管线（GraphicsPipeline）** 同样是引擎侧资源，纳入 Cleaner 模型：PipelineCache（第 16 节）作为持有者，`getOrCreate` 持强引用，LRU 淘汰时移除引用，无外部引用时由 Cleaner 兜底回收；引擎需要确定性销毁时显式 `close()`；
> - **RHI 内部资源**（RenderGraph 临时 RT、Frame Arena、描述符池、CommandBuffer）由 RHI 确定性管理（图缓存复用 + 帧调度），**不经过 Cleaner**——避免每帧临时对象触发 GC + Cleaner 风暴。

### 17.2 Cleaner 触发 → 延迟销毁（两帧规则）

GPU 异步执行：当前帧提交的命令可能在第 N+1 / N+2 帧才执行完。**Cleaner 触发 ≠ 可以立即 vkDestroy**：

- Cleaner 可能在任意线程、任意时刻触发（GC 时机不可控），回调内**禁止直接调 Vulkan**；
- 回调只做一件事：`enqueueDestroy(handle)`（线程安全入队）；
- RHI 提交线程每帧 `process()`：检查 `frameIndex + 2 <= 当前帧`（或资源最后一次使用的 fence 已信号）→ 真正 `vkDestroy`。

```java
class DeferredDestroyQueue {
    final ConcurrentLinkedQueue<DestroyEntry> queue = new ConcurrentLinkedQueue<>();

    // 可由 Cleaner 线程调用：线程安全入队
    void enqueue(long handle, long frameIndex) {
        queue.add(new DestroyEntry(handle, frameIndex));
    }
    // 仅 RHI 提交线程调用：每帧处理
    void process(long currentFrame) {
        DestroyEntry e;
        while ((e = queue.peek()) != null && e.frameIndex + 2 <= currentFrame) {
            queue.poll();
            vkDestroy(e.handle);   // 内部实现：vkDestroyBuffer/Image/Pipeline...
        }
    }
}
```

### 17.3 帧内内存分配（Frame Arena）

每帧都会产生大量临时数据（动态 UBO、每帧 uniform、瞬时缓冲），用**环形 Arena** 避免逐对象分配：

- 按「帧数 % 3」轮换三块 Arena，上一帧使用的内存等 GPU 完成后再复用；
- 只支持顺序分配（bump allocator），帧末整体重置；
- 动态 UBO（15.5 的 `frameUbo`）就是这个 Arena 上的一个区域；
- **Arena 内存不是 Java 对象**：由 RHI 帧调度确定性归还，不经 Cleaner、不参与引用计数。

```java
class FrameArena {
    // 3 帧环形：CPU 写 frame f，GPU 在 f-1 / f-2 使用
    final ByteBuffer[] buffers = new ByteBuffer[3];
    final int capacity = 64 * 1024 * 1024;  // 每块 64MB（可配）

    long allocate(int size, int alignment) { /* bump + 对齐 */ }
    void reset(int frameIndex) { buffers[frameIndex % 3].clear(); }
}
```

- 超限策略：超过 Arena 容量的对象走独立分配 + 延迟销毁；
- Debug 模式可统计每帧分配字节数，反推容量配置。

### 17.4 临时 RT 内存别名（与 RenderGraph 衔接）

- RenderGraph 生命周期分析：临时 RT 的「最后使用 Pass」之后即可释放，供后续临时 RT 复用显存；
- 规则：只有生命周期互不重叠的 RT 才能别名到同一块 `VkImage`；
- 与第 13 节调试约束联动：**Debug 关闭别名**（`setDebugDisableAliasing(true)`），Release 开启。

### 17.5 存活资源审计（Debug）

Cleaner 方案没有引用计数，"泄漏"表现为**对象被长期持有但不再使用**，审计策略：

- `ResourceTable` 记录每个 handle 的**创建堆栈**（见第 13 节）与**最后一次使用帧号**；
- Debug 每 N 帧（如 300 帧）扫描一次：`lastUseFrame` 距今超过阈值（如 600 帧）的资源，打印警告 + 创建堆栈，提示可能是"忘了 close / 忘了丢弃引用"；
- 应用退出：dump 所有仍存活的资源对象列表（数量、类型、创建堆栈），人工核对；
- 单元测试断言：`rhi.getLiveResourceCount() == 0`（要求测试路径显式 `close()` 全部资源，验证延迟队列清空）。

### 17.6 工程坑点

1. **Cleaner 自引用陷阱**：`register(obj, () -> rhi.enqueueDestroy(this))` 中闭包捕获 `this` → 对象永远可达，Cleaner 永不触发。回调必须用只持有 `rhi + handle` 的独立任务对象；
2. **Cleaner 线程不能碰 Vulkan**：回调只入队，真正的 `vkDestroy` 由提交线程在 `process()` 完成，避免跨线程 / GPU 在用资源；
3. **GC 时机不可控**：靠 Cleaner 兜底的资源销毁延迟不定。大显存纹理、管线等长生命周期资源**必须显式 `close()`**；Cleaner 只作为"防止忘记释放"的兜底，不作为性能关键路径；
4. **临时资源风暴**：每帧创建的大量临时对象会放大 GC + Cleaner 开销，所以临时数据一律走 Frame Arena / 图缓存（RHI 内部确定性管理），不暴露为 Cleaner 对象；
5. 不使用 `finalize()`（JDK 9 起废弃、不可控），Cleaner 是唯一兜底通道；
6. 句柄复用：handle 从 ResourceTable 移除前必须检查状态（`DESTROYING` 中禁止复用），防止 ABA 问题。

---


---

---

> （注：部分内容可能由 AI 生成）
