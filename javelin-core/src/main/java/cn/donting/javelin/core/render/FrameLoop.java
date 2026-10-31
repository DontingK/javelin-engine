package cn.donting.javelin.core.render;

import cn.donting.javelin.rhi.CleanerRegistry;
import cn.donting.javelin.rhi.CompiledGraph;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.RenderGraphCache;
import cn.donting.javelin.rhi.SubmitInfo;
import cn.donting.javelin.rhi.SwapChain;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.desc.SwapChainDesc;
import cn.donting.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Function;

/**
 * 帧循环（设计文档第 24.1 / 24.6 节）：FrameState 环形 + 交换链 + 图缓存的收口。
 *
 * 单帧迭代顺序（24.1）：
 * fence 闸门 → acquire → 声明并编译图（缓存命中则复用计划）→ 录制+提交
 * → present → CleanerRegistry 帧推进（17 节两帧规则）→ 环形槽位推进。
 *
 * recreate 自包含（24.6 双路径）：渲染面报告 resize，或 acquire 返回 &lt;0
 * → 交换链重建 + 图缓存失效（23.6-3，backbuffer 句柄变化）+ resize 确认。
 *
 * 线程模型：单线程退化模式（27.1），renderFrame 在同一线程顺序执行全部步骤；
 * 窗口最小化（isVisible=false）时 renderFrame 直接返回 false，调用方仍须 pollEvents。
 */
public final class FrameLoop implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(FrameLoop.class);

    /** acquire 超时（配 imageAvailable 信号量实际不阻塞） */
    private static final long ACQUIRE_TIMEOUT_NANOS = Long.MAX_VALUE;

    private final Device device;
    private final RenderSurface surface;
    private final SwapChainDesc descTemplate;
    private final SwapChain swapChain;
    private final RenderGraphCache graphCache = new RenderGraphCache();
    private final FrameState[] states;
    private final int maxFramesInFlight;

    private int ringIndex;
    private long frameCounter;
    private boolean closed;

    /**
     * @param device             以带 RenderSurface 的 createDevice 创建的设备
     * @param surface            呈现目标（resize / 可见性查询）
     * @param desc               交换链描述模板（尺寸取 surface 当前值，recreate 时刷新）
     * @param maxFramesInFlight  in-flight 帧数（2~3，24.7）
     */
    public FrameLoop(Device device, RenderSurface surface, SwapChainDesc desc, int maxFramesInFlight) {
        if (device == null || surface == null || desc == null) {
            throw new IllegalArgumentException("device / surface / desc must not be null");
        }
        if (maxFramesInFlight < 1) {
            throw new IllegalArgumentException("maxFramesInFlight must be >= 1, got: " + maxFramesInFlight);
        }
        this.device = device;
        this.surface = surface;
        this.descTemplate = desc;
        this.maxFramesInFlight = maxFramesInFlight;
        this.states = new FrameState[maxFramesInFlight];
        for (int i = 0; i < maxFramesInFlight; i++) {
            states[i] = new FrameState(device);
        }
        this.swapChain = device.createSwapChain(desc);
        // 呈现配置读数：presentMode 为实际生效值（vsync→MAILBOX 不支持时静默回退
        // FIFO，回退只有此处可见——Vulkan_Pitfalls #21 观测口）
        log.info("swapchain: images={}, present={}", swapChain.imageCount(), swapChain.presentMode());
        // 交换链已按当前尺寸创建：同步 resize 确认值，
        // 避免 GLFWRenderSurface 初始 ack=-1 导致首帧无意义的 recreate
        surface.acknowledgeResize();
    }

    /**
     * 执行一帧。
     *
     * @param graphFactory 用本帧 acquire 到的 backbuffer 声明渲染图；
     *                     acquire 发生在闸门之后、编译之前，故声明以 backbuffer 为入参
     * @return true = 已提交并呈现；false = 本帧跳过（窗口最小化 / recreate 后仍无法 acquire）
     */
    public boolean renderFrame(Function<Texture, RenderGraphBuilder> graphFactory) {
        checkOpen();
        if (graphFactory == null) {
            throw new IllegalArgumentException("graphFactory must not be null");
        }
        // 最小化：跳过渲染（GLFW framebuffer 尺寸为 0，不能用于重建交换链）
        if (!surface.isVisible()) {
            return false;
        }
        // recreate 主动路径：渲染面报告 resize（24.6）
        if (surface.isResized()) {
            recreateSwapChain();
        }

        FrameState f = states[ringIndex];
        f.fence().await();          // 帧闸门（24.1）
        f.fence().reset();
        f.arena().reset();          // 帧内存池回收（17.3）

        int idx = swapChain.acquireNextImage(ACQUIRE_TIMEOUT_NANOS, f.imageAvailable());
        if (idx < 0) {
            // recreate 被动路径：尺寸过期 / 设备恢复（24.6）
            recreateSwapChain();
            idx = swapChain.acquireNextImage(ACQUIRE_TIMEOUT_NANOS, f.imageAvailable());
            if (idx < 0) {
                return false;
            }
        }

        RenderGraphBuilder graph = graphFactory.apply(swapChain.image(idx));
        CompiledGraph compiled = graphCache.compile(graph);
        graph.execute(f.cmd(), swapChain.image(idx), compiled);

        device.graphicsQueue().submit(f.cmd(), new SubmitInfo(
                List.of(f.imageAvailable()), List.of(f.renderFinished()), f.fence()));
        swapChain.present(idx, f.renderFinished());

        // 延迟销毁队列帧推进（17 节两帧规则）
        CleanerRegistry.onFrameEnd(frameCounter++);
        ringIndex = (ringIndex + 1) % maxFramesInFlight;
        return true;
    }

    /** 当前交换链（查询图像数 / 尺寸 / 声明终点附件用） */
    public SwapChain swapChain() {
        return swapChain;
    }

    /** 单调帧计数（CleanerRegistry 帧推进已消费） */
    public long frameCounter() {
        return frameCounter;
    }

    /** 重建交换链并失效图缓存（23.6-3 / 24.6） */
    private void recreateSwapChain() {
        log.info("Recreating swapchain: {}x{}", surface.width(), surface.height());
        swapChain.recreate(new SwapChainDesc(
                surface.width(), surface.height(),
                descTemplate.presentMode(), descTemplate.colorSpace(),
                descTemplate.minImageCount(), descTemplate.vsync()));
        graphCache.invalidate();
        surface.acknowledgeResize();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        device.waitIdle();
        for (FrameState s : states) {
            s.close();
        }
        swapChain.dispose();
    }

    private void checkOpen() {
        if (closed) {
            throw new IllegalStateException("FrameLoop has been closed");
        }
    }
}
