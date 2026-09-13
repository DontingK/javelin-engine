package com.javelin.core;

import com.javelin.core.appstate.AppStateSubsystem;
import com.javelin.core.assets.AssetLibManager;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.RenderingPipeline;
import com.javelin.core.render.pass.ForwardRenderPass;
import com.javelin.core.scene.SceneManager;
import com.javelin.core.scene.component.Camera;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.subsystem.SubsystemManager;
import com.javelin.core.window.GlfwWindow;
import com.javelin.core.window.Window;
import com.javelin.rhi.RHIFactory;
import com.javelin.rhi.RHIFactory.RHIConfig;
import com.javelin.rhi.RHIFactory.RHIContext;
import com.javelin.rhi.Renderer;
import com.javelin.rhi.api.Device;
import com.javelin.rhi.api.GPUResource;
import com.javelin.rhi.api.SwapChain;
import com.javelin.rhi.desc.SwapChainDesc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;


/**
 * 引擎主类，管理窗口、RHI 初始化和主循环。
 * <p>
 * 使用方式：
 * <pre>
 *   var engine = new JavelinEngine("My Game", 1280, 720);
 *   engine.start();
 * </pre>
 * 子类可重写 {@link #initialize()} 和 {@link #update()} 来扩展引擎行为。
 */
public class JavelinEngine {

    private static final Logger log = LoggerFactory.getLogger(JavelinEngine.class);

    static {
        org.lwjgl.system.Configuration.STACK_SIZE.set(1024);
    }

    protected final JavelinSetting setting;
    protected boolean validation;

    protected Window window;
    protected RHIContext rhiContext;
    protected Device device;
    protected SwapChain swapChain;
    protected Renderer renderer;

    protected AssetManager assetManager;
    protected SubsystemManager subsystemManager;
    protected SceneManager sceneManager;
    protected RenderingPipeline renderingPipeline;

    // 执行队列：用于多线程向主线程提交任务，避免多线程访问 RHI 资源的问题
    private final ConcurrentLinkedQueue<Runnable> executeQueue = new ConcurrentLinkedQueue<>();

    public JavelinEngine(JavelinSetting setting) {
        this.setting = setting;
    }


    /**
     * 启动引擎：初始化 -> 主循环 -> 清理。
     */
    public void start() {
        try {
            initializeCore();
            initialize();
            mainLoop();
        } catch (Exception e) {
            log.error("Engine error", e);
            throw e;
        } finally {
            shutdown();
        }
    }

    /**
     * 请求停止引擎主循环。
     * 统一走窗口关闭逻辑，确保清理流程一致。
     */
    public void stop() {
        if (window != null) {
            window.requestClose();
        }
    }

    /**
     * 提交一个任务到引擎主线程执行队列。
     * <p>
     * 所有涉及 RHI 资源（Device、Buffer、Texture、CommandBuffer 等）的操作
     * 都必须在主线程执行。其他线程通过此方法将任务投递到主线程，
     * 由主循环在帧开始时统一执行，避免多线程竞争问题。
     *
     * @param task 要在主线程执行的任务
     */
    public void submitToExecuteQueue(Runnable task) {
        if (task != null) {
            executeQueue.offer(task);
        }
    }

    /**
     * 提交一个任务到引擎主线程执行队列，并等待其执行完成。
     * <p>
     * 注意：此方法会阻塞调用线程，直到任务在主线程执行完毕。
     * 切勿在主线程调用此方法，否则会导致死锁。
     *
     * @param task 要在主线程执行的任务
     */
    public void submitToExecuteQueueAndWait(Runnable task) {
        if (task == null) return;

        var latch = new CountDownLatch(1);
        executeQueue.offer(() -> {
            try {
                task.run();
            } finally {
                latch.countDown();
            }
        });

        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrupted while waiting for execute queue task", e);
        }
    }

    /**
     * 执行队列中所有待处理的任务。
     */
    private void flushExecuteQueue() {
        Runnable task;
        while ((task = executeQueue.poll()) != null) {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Execute queue task failed", e);
            }
        }
    }

    /**
     * 初始化核心子系统（窗口、RHI、渲染器）。
     */
    private void initializeCore() {
        log.info("Initializing Javelin Engine: {} ({}x{})", setting.getTitle(), setting.getWidth(), setting.getHeight());

        assetManager = createAssetManager();
        subsystemManager = new SubsystemManager();
        registerSubsystem(subsystemManager);
        subsystemManager.initAll(this);
        sceneManager = new SceneManager();
        // 创建窗口
        window = createWindow();
        window.create();
        window.show();

        // 初始化 RHI
        RHIConfig config = new RHIConfig(setting.getTitle(), validation, 2, true);
        rhiContext = RHIFactory.initialize(config, window);
        device = rhiContext.device();

        // 创建交换链
        SwapChainDesc swapChainDesc = new SwapChainDesc(setting.getWidth(), setting.getHeight());
        swapChain = device.createSwapChain(window, swapChainDesc);

        // 创建渲染器
        renderer = new Renderer(device, swapChain, 2);

        // 创建渲染管线并添加前向渲染 Pass
        renderingPipeline = new RenderingPipeline();
        renderingPipeline.addPass(new ForwardRenderPass());
        log.info("Engine initialized successfully");
    }

    /**
     * 子类重写：在核心初始化完成后执行自定义初始化。
     */
    protected void initialize() {
        // 子类可重写
    }

    /**
     * 子类重写：每帧更新逻辑。
     */
    protected void update() {
        // 子类可重写
    }

    protected void registerSubsystem(SubsystemManager subsystemManager) {
        AppStateSubsystem appStateSubsystem = new AppStateSubsystem();
        subsystemManager.register(appStateSubsystem);
    }

    /**
     * 子类重写：每帧渲染逻辑。
     * <p>
     * 默认实现收集场景中的所有相机进行多相机渲染。子类可重写以自定义渲染流程。
     */
    protected void render() {
        if (sceneManager == null || renderingPipeline == null) {
            return;
        }

        // 收集场景中的相机和可渲染对象
        List<Camera> cameras = new ArrayList<>();
        sceneManager.collectCameras(cameras);

        // 没有相机则跳过渲染
        if (cameras.isEmpty()) {
            return;
        }

        List<com.javelin.core.render.Renderable> renderables = new ArrayList<>();
        sceneManager.collectRenderables(renderables);

        // 多相机渲染（每个相机使用自己的视口配置）
        int windowWidth = window != null ? window.width() : setting.getWidth();
        int windowHeight = window != null ? window.height() : setting.getHeight();

        // 获取当前帧的交换链图像视图
        com.javelin.rhi.api.Texture currentImage = renderer.currentImage();
        com.javelin.rhi.api.TextureView swapChainImageView = null;
        if (currentImage != null) {
            swapChainImageView = currentImage.createView(new com.javelin.rhi.desc.TextureViewDesc(
                    com.javelin.rhi.api.TextureView.TextureViewType.TEXTURE_2D,
                    currentImage.format(),
                    new com.javelin.rhi.desc.TextureViewDesc.TextureSubresourceRange(
                            com.javelin.rhi.desc.TextureViewDesc.AspectFlags.COLOR,
                            0, 1, 0, 1
                    )
            ));
        }

        renderingPipeline.render(renderables, cameras,
                                  Time.getDeltaTime(), windowWidth, windowHeight,
                                  renderer.commandBuffer(), swapChainImageView);
    }

    /**
     * 主循环。
     */
    private void mainLoop() {
        log.info("Entering main loop");
        Time.init();
        long lastFrameTime = System.nanoTime();
        while (!window.shouldClose()) {
            window.pollEvent();
            // 3. 计算 deltaTime
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = currentTime;

            // 更新全局时间系统
            Time.update(deltaTime);

            // 先执行队列中的所有任务（确保 RHI 操作在主线程完成）
            flushExecuteQueue();

            // 子系统早期更新（物理步进、动画采样、输入预处理）
            subsystemManager.earlyUpdate(deltaTime);

            // 更新场景（组件 onUpdate 与帧末销毁处理）
            if (sceneManager != null) {
                sceneManager.update(deltaTime);
            }

            update();

            // 子系统正常更新（AI、游戏逻辑）与晚期更新（物理同步、相机跟随）
            subsystemManager.update(deltaTime);
            subsystemManager.lateUpdate(deltaTime);

            // 开始帧
            if (!renderer.beginFrame()) {
                // 交换链需要重建，跳过当前帧
                continue;
            }

            // 渲染：preRender → 渲染 → render → postRender
            subsystemManager.preRender();
            render();
            subsystemManager.render();
            subsystemManager.postRender();

            // 结束帧并呈现
            renderer.endFrame();
        }

        log.info("Exiting main loop");
    }

    protected Window createWindow() {
        return new GlfwWindow(setting.getWidth(), setting.getHeight(), setting.getTitle());
    }

    protected AssetManager createAssetManager() {
        return new AssetLibManager(new File(Env.getRunDir(), "Library"));
    }

    /**
     * 清理所有资源。
     */
    private void shutdown() {
        log.info("Shutting down engine");

        // 确保队列中剩余任务执行完毕
        flushExecuteQueue();

        // 先销毁场景（组件可能持有 GPU 资源引用）
        if (sceneManager != null) {
            sceneManager.shutdown();
            sceneManager = null;
        }

        // 子系统关闭同样需要在 GPU 资源销毁之前（子系统可能持有 GPU 对象）
        if (subsystemManager != null) {
            subsystemManager.shutdownAll();
            subsystemManager = null;
        }

        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }

        if (swapChain != null) {
            swapChain.dispose();
            swapChain = null;
        }

        if (rhiContext != null) {
            rhiContext.dispose();
            rhiContext = null;
        }

        // 真正销毁所有待延迟资源（帧资源 → 交换链 → 设备 → 实例 的顺序已按入队先后保证）
        GPUResource.flushDisposals();

        if (window != null) {
            window.enginClose();
            window = null;
        }

        log.info("Engine shutdown complete");
    }

    // ========== 访问器 ==========

    public Window window() {
        return window;
    }

    public Device device() {
        return device;
    }

    public SwapChain swapChain() {
        return swapChain;
    }

    public Renderer renderer() {
        return renderer;
    }

    public SceneManager sceneManager() {
        return sceneManager;
    }

    public boolean isRunning() {
        return window != null && !window.shouldClose();
    }

}
