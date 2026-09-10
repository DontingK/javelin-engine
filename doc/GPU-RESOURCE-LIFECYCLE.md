# GPU 资源自动释放设计文档

> 本文档描述 Javelin Engine 5 的 GPU 资源生命周期管理机制。

---

## 设计目标

1. **自动化**：资源生命周期由 GC 管理，减少手动 `dispose()` 调用
2. **帧安全**：延迟释放，防止当前帧 GPU 仍在使用资源时被销毁
3. **无泄漏**：GC 兜底 + 引擎关闭时强制 flush

---

## 核心组件

### 1. GPUResource 抽象类

**路径**：`javelin-rhi/src/main/java/com/javelin/rhi/api/GPUResource.java`

所有 GPU 资源的基类，内置 `Cleaner` 机制：

```java
public abstract class GPUResource {
    private static final Cleaner cleaner = Cleaner.create();
    private static DelayedResourceDisposer disposer;
    
    protected GPUResource() {
        // 注册 Cleaner：GC 时自动触发延迟释放
        cleaner.register(this, new DisposeAction(this));
    }
    
    public abstract long handle();
    protected abstract void doDispose();  // 子类实现实际释放逻辑
    
    public static void setDisposer(DelayedResourceDisposer disposer) {
        GPUResource.disposer = disposer;
    }
}
```

**关键约束**：
- `dispose()` 可手动调用：加入延迟队列，`framesInFlight` 帧后真正释放（帧安全），重复调用幂等
- 子类实现 `doDispose()` 而非 `dispose()`，真正释放由基类的 `disposeNow()` 统一驱动
- `isDisposed()` 由基类管理

### 2. DelayedResourceDisposer 延迟释放管理器

**路径**：`javelin-rhi/src/main/java/com/javelin/rhi/api/DelayedResourceDisposer.java`

按帧队列管理资源释放：

```java
public class DelayedResourceDisposer {
    private final int delayFrames;  // 通常 = Renderer.framesInFlight
    private final Deque<Deque<GPUResource>> frameQueues;
    
    public void beginFrame() {
        // 每帧推进队列，释放 delayFrames 前标记的资源
        frameQueues.addLast(currentFrameResources);
        Deque<GPUResource> ready = frameQueues.pollFirst();
        for (GPUResource r : ready) r.doDispose();
    }
    
    public void requestDispose(GPUResource resource) {
        // Cleaner 回调触发，加入当前帧队列
        currentFrameResources.add(resource);
    }
    
    public void flush() {
        // 引擎关闭时强制释放所有待释放资源
    }
}
```

### 3. Renderer 集成

延迟释放管理器由 `Renderer` 持有并驱动，引擎主类无需关心：

**Renderer 构造**（延迟帧数自动与 `maxFramesInFlight` 一致）：

```java
public Renderer(Device device, SwapChain swapChain, int maxFramesInFlight) {
    this.resourceDisposer = new DelayedResourceDisposer(maxFramesInFlight);
    GPUResource.setDisposer(resourceDisposer);  // 设置全局 disposer
    // ...
}
```

**每帧推进**（`Renderer.beginFrame()` 内部，fence 等待完成后释放到期资源）：

```java
public boolean beginFrame() {
    device.waitForFences(...);
    // ...
    resourceDisposer.beginFrame();  // 推进延迟队列
    // ...
}
```

**销毁**（`Renderer.dispose()` 内部，GPU 空闲后强制释放所有资源）：

```java
public void dispose() {
    device.waitIdle();
    resourceDisposer.flush();  // 强制释放所有资源
    // ...
}
```

---

## 使用方式

### 创建资源

```java
// 直接创建，无需包装
Buffer buffer = device.createBuffer(desc);
Texture texture = device.createTexture(desc);
GraphicsPipeline pipeline = device.createGraphicsPipeline(desc);
```

### 引擎侧引用

```java
// RenderCommand 直接持有资源引用
public class RenderCommand {
    public GraphicsPipeline pipeline;  // 直接引用
    public Buffer vertexBuffer;        // 直接引用
    public Buffer indexBuffer;         // 直接引用
    public DescriptorSet[] descriptorSets;
}
```

### 资源释放流程

```
1. buffer 不再被任何对象引用
2. GC 回收 buffer 对象
3. Cleaner 回调 → DisposeAction.run()
4. requestDispose(buffer) → 加入当前帧队列
5. N 帧后（delayFrames）→ beginFrame() 中真正释放
6. buffer.doDispose() → vkDestroyBuffer / vkFreeMemory
```

---

## 与 engine4 的对比

| | engine4 | engine5 |
|--|---------|---------|
| 资源句柄 | `int handle` | `long handle`（原生 Vulkan handle） |
| 释放方式 | 显式 `dispose()`（立即释放） | `dispose()` 手动/GC 触发，均延迟释放 |
| 包装类 | `GpuHandle<T>`（Cleaner） | 无，直接引用 `GPUResource` |
| 帧安全 | 无 | `DelayedResourceDisposer` 延迟 `framesInFlight` 帧 |
| 生命周期 | 手动管理 | GC 驱动 + 手动 `dispose()`，引擎关闭 flush |

---

## 注意事项

1. **Device / Instance / Queue 同样继承 `GPUResource`**：虽然是长生命周期单例，销毁时机由引擎关闭流程控制，GC 仅作兜底
2. **循环引用**：避免 `RenderCommand` 与资源之间形成循环引用，否则 GC 无法回收
3. **显式释放场景**：引擎关闭、SwapChain 重建等特殊场景仍可直接调用 `dispose()`（基类已做防护）
4. **线程安全**：`requestDispose()` 在 Cleaner 线程调用，但 `DelayedResourceDisposer` 的队列操作在引擎主线程（`beginFrame()`），需确保无竞争

---

## 相关文件

| 文件 | 说明 |
|------|------|
| `javelin-rhi/src/main/java/com/javelin/rhi/api/GPUResource.java` | 抽象基类 |
| `javelin-rhi/src/main/java/com/javelin/rhi/api/DelayedResourceDisposer.java` | 延迟释放管理器 |
| `javelin-rhi/src/main/java/com/javelin/rhi/Renderer.java` | 持有并驱动延迟释放 |
| `javelin-core/src/main/java/com/javelin/core/render/RenderCommand.java` | 直接使用资源引用示例 |
