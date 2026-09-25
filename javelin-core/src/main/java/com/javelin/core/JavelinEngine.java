package com.javelin.core;

import com.javelin.core.assets.AssetLibManager;
import com.javelin.core.assets.AssetManager;
import com.javelin.core.render.FrameDescription;
import com.javelin.core.render.FrameLoop;
import com.javelin.core.render.RenderView;
import com.javelin.core.render.RenderingSystem;
import com.javelin.core.render.pipeline.DefaultHybridRenderPipeline;
import com.javelin.core.render.pipeline.PipelineContext;
import com.javelin.core.render.pipeline.RenderPipeline;
import com.javelin.core.scene.Scene;
import com.javelin.core.scene.SceneRenderBridge;
import com.javelin.core.settings.JavelinSetting;
import com.javelin.core.subsystem.SubsystemManager;
import com.javelin.core.window.GLFWWindow;
import com.javelin.core.window.Window;
import com.javelin.rhi.Device;
import com.javelin.rhi.Instance;
import com.javelin.rhi.RHI;
import com.javelin.rhi.RHIDevicePrefs;
import com.javelin.rhi.RenderGraphBuilder;
import com.javelin.rhi.Texture;
import com.javelin.rhi.data.RHIClearColor;
import com.javelin.rhi.data.RHILoadOp;
import com.javelin.rhi.desc.SwapChainDesc;
import com.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * 引擎外壳（Engine_Design.md 2.1；阶段①引擎接线 RHI + 阶段③渲染收口）。
 *
 * <p>生命周期：initialize（窗口 → RHI Instance/Device/FrameLoop → 渲染系统）→
 * onInitialize → mainLoop（pollEvents → Time.update → update → frameLoop.renderFrame）→
 * shutdown 逆序（frameLoop.close（内部 waitIdle）→ renderingSystem.close →
 * device → instance → window，Engine_Implementation_Plan 阶段①）。
 *
 * <p>mainLoop 形态对齐 RHI 27.1 单线程退化：pollEvents → Time.update →
 * scene.update（场景生命周期，若有）→ update → renderFrame；帧描述由图声明回调内组装
 * （acquire 先于图声明，RHI 24.1 落地修正）：graphFactory 收到 backbuffer →
 * SceneRenderBridge.sync（场景→渲染 pull 翻译，若有场景）→ RenderingSystem.collectFrame
 * → 逐视口 renderPipeline.buildGraph。
 */
public class JavelinEngine {

    private static final Logger log = LoggerFactory.getLogger(JavelinEngine.class);

    private final JavelinSetting setting;

    /**
     * 主窗口：start() 时经 createWindow() 工厂创建，帧循环直接驱动（见 Engine_Window_Design.md）
     */
    private Window window;
    private AssetManager assetManager;
    private SubsystemManager subsystemManager;

    // RHI 初始化序列（阶段①：从测试类搬入，工厂可覆盖）
    private Instance instance;
    private Device device;
    private FrameLoop frameLoop;
    private RenderingSystem renderingSystem;
    private RenderPipeline renderPipeline;

    /** 当前场景（setScene 设置；null = 无场景，直注册模式） */
    private Scene scene;
    private final SceneRenderBridge sceneRenderBridge = new SceneRenderBridge();

    private long lastFrameTime;

    public JavelinEngine(JavelinSetting setting) {
        this.setting = setting;
    }


    public void start() {
        try {
            initialize();
            onInitialize();
            mainLoop();
        } finally {
            shutdown();
        }
    }

    private final void initialize() {
        window = createWindow();
        window.create();
        window.showWindow();

        assetManager = createAssetManager();
        subsystemManager = createSubsystemManager();

        // RHI 初始化序列（VkSurfaceKHR 由 createDevice 内部创建并持有，上层不再重复创建）
        RenderSurface surface = window.renderSurface();
        instance = createInstance(surface);
        device = createDevice(surface);
        frameLoop = createFrameLoop(surface);
        renderingSystem = createRenderingSystem();
        renderPipeline = createRenderPipeline();
        log.info("RHI initialized: {}x{}, vsync={}", window.width(), window.height(), setting.isVsync());
    }

    protected void onInitialize() {

    }

    private void mainLoop() {
        Time.init();
        lastFrameTime = System.nanoTime();
        while (!window.closeRequested()) {
            window.pollEvents();
            // 3. 计算 deltaTime
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = currentTime;
            Time.update(deltaTime);
            if (scene != null) {
                scene.update(Time.getDeltaTime());
            }
            update();
            frameLoop.renderFrame(this::buildFrameGraph);
        }
    }

    /** 图声明回调：acquire 之后调用，backbuffer 为入参（RHI 24.1） */
    private RenderGraphBuilder buildFrameGraph(Texture backbuffer) {
        if (renderingSystem == null || renderPipeline == null) {
            // 无渲染系统（默认 JavelinEngine.main）：纯清屏，阶段①最小画面
            RenderGraphBuilder graph = RHI.beginRenderGraph();
            graph.addGraphicsPass("ClearOnly", pass ->
                    pass.addColorOutput(backbuffer, RHILoadOp.CLEAR,
                            new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f)));
            return graph;
        }
        if (scene != null) {
            sceneRenderBridge.sync(scene, renderingSystem, backbuffer);
        }
        FrameDescription desc = renderingSystem.collectFrame(frameLoop.frameCounter(), backbuffer);
        RenderGraphBuilder graph = RHI.beginRenderGraph();
        for (RenderView view : desc.views()) {
            renderPipeline.buildGraph(graph, new PipelineContext(
                    renderingSystem, view, desc, backbuffer,
                    renderingSystem.depthFor(view.name())));
        }
        if (desc.views().isEmpty()) {
            graph.addGraphicsPass("ClearOnly", pass ->
                    pass.addColorOutput(backbuffer, RHILoadOp.CLEAR,
                            new RHIClearColor(0.05f, 0.05f, 0.08f, 1.0f)));
        }
        return graph;
    }

    /** shutdown 逆序（阶段①）：frameLoop（含 waitIdle）→ 渲染系统 → device → instance → window */
    private void shutdown() {
        if (frameLoop != null) {
            frameLoop.close();
        }
        if (renderingSystem != null) {
            renderingSystem.close();
        }
        if (device != null) {
            device.dispose();
        }
        if (instance != null) {
            instance.dispose();
        }
        if (window != null) {
            window.close();
        }
        Time.reset();
    }

    public JavelinSetting setting() {
        return setting;
    }


    /**
     * 窗口工厂：子类可覆盖以替换窗口实现
     */
    protected Window createWindow() {
        return new GLFWWindow(setting.getWidth(), setting.getHeight(), setting.getTitle());
    }

    protected AssetManager createAssetManager() {
        return new AssetLibManager(new File("./Library"));
    }

    protected SubsystemManager createSubsystemManager() {
        return new SubsystemManager();
    }

    protected RHI.RHIInstanceConfig createInstanceConfig() {
        return new RHI.RHIInstanceConfig(setting.getTitle(), 1, true);
    }

    protected RHIDevicePrefs createDevicePrefs() {
        return RHIDevicePrefs.defaults();
    }

    /** vsync → FIFO（必支持）；关闭 vsync → IMMEDIATE（Engine_Implementation_Plan 开放项落定） */
    protected SwapChainDesc createSwapChainDesc() {
        SwapChainDesc.PresentMode presentMode = setting.isVsync()
                ? SwapChainDesc.PresentMode.FIFO
                : SwapChainDesc.PresentMode.IMMEDIATE;
        return new SwapChainDesc(window.width(), window.height(),
                presentMode, SwapChainDesc.ColorSpace.SRGB, 0, setting.isVsync());
    }

    protected Instance createInstance(RenderSurface surface) {
        return RHI.createInstance(createInstanceConfig(), surface.platform());
    }

    protected Device createDevice(RenderSurface surface) {
        return instance.createDevice(createDevicePrefs(), surface);
    }

    protected FrameLoop createFrameLoop(RenderSurface surface) {
        return new FrameLoop(device, surface, createSwapChainDesc(), 2);
    }

    /**
     * 渲染系统工厂：默认 null（纯清屏）；游戏/演示子类覆盖创建并注册相机与渲染对象。
     */
    protected RenderingSystem createRenderingSystem() {
        return new RenderingSystem(device(), frameLoop());
    }

    protected RenderPipeline createRenderPipeline() {
        return renderingSystem != null ? new DefaultHybridRenderPipeline() : null;
    }

    /**
     * 设置当前场景：旧场景注册的渲染侧包装全部注销，新场景下一帧起经
     * SceneRenderBridge pull 进渲染系统。重复设同一引用为幂等空操作。
     */
    public void setScene(Scene scene) {
        if (this.scene == scene) {
            return;
        }
        if (this.scene != null && renderingSystem != null) {
            sceneRenderBridge.clear(renderingSystem);
        }
        this.scene = scene;
    }

    /** 当前场景（start() 调用前/未设置时为 null） */
    public Scene scene() {
        return scene;
    }

    /**
     * 主窗口；start() 调用前为 null
     */
    public Window window() {
        return window;
    }

    public Instance instance() {
        return instance;
    }

    public Device device() {
        return device;
    }

    public FrameLoop frameLoop() {
        return frameLoop;
    }

    public RenderingSystem renderingSystem() {
        return renderingSystem;
    }

    public RenderPipeline renderPipeline() {
        return renderPipeline;
    }

    protected void update() {

    }

    public static void main(String[] args) {
        JavelinSetting setting = new JavelinSetting();
        JavelinEngine javelinEngine = new JavelinEngine(setting);
        javelinEngine.start();
    }

}
