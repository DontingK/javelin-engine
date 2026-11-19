package cn.donting.javelin.core;

import cn.donting.javelin.core.appstate.AppStateManager;
import cn.donting.javelin.core.appstate.AppStateSubsystem;
import cn.donting.javelin.core.assets.AssetLibManager;
import cn.donting.javelin.core.assets.AssetManager;
import cn.donting.javelin.core.debug.DebugDrawTextSubsystem;
import cn.donting.javelin.core.debug.RenderStatsAppState;
import cn.donting.javelin.core.render.picking.PickSystem;
import cn.donting.javelin.core.input.Input;
import cn.donting.javelin.core.input.InputSubsystem;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.physics.Ray;
import cn.donting.javelin.core.physics.query.Physics;
import cn.donting.javelin.core.render.FrameDescription;
import cn.donting.javelin.core.render.FrameLoop;
import cn.donting.javelin.core.render.RenderView;
import cn.donting.javelin.core.render.RenderingSystem;
import cn.donting.javelin.core.render.material.DefaultMaterial;
import cn.donting.javelin.core.render.pipeline.DefaultHybridRenderPipeline;
import cn.donting.javelin.core.render.pipeline.ForwardRenderPipeline;
import cn.donting.javelin.core.render.pipeline.PipelineContext;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.render.pipeline.PostProcessSetup;
import cn.donting.javelin.core.render.pipeline.RenderPipeline;
import cn.donting.javelin.core.render.voxel.VoxelAoPass;
import cn.donting.javelin.core.render.voxel.VoxelizationPass;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.scene.LayerAndTagManger;
import cn.donting.javelin.core.scene.Scene;
import cn.donting.javelin.core.scene.SceneRenderBridge;
import cn.donting.javelin.core.scene.component.Camera;
import cn.donting.javelin.core.settings.GraphSetting;
import cn.donting.javelin.core.settings.JavelinSetting;
import cn.donting.javelin.core.settings.PostProcessSettings;
import cn.donting.javelin.core.settings.SsaoSettings;
import cn.donting.javelin.core.settings.WindowSetting;
import cn.donting.javelin.core.subsystem.SubsystemManager;
import cn.donting.javelin.core.window.GLFWWindow;
import cn.donting.javelin.core.window.Window;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.Instance;
import cn.donting.javelin.rhi.RHI;
import cn.donting.javelin.rhi.RHIDevicePrefs;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.SwapChainDesc;
import cn.donting.javelin.rhi.render.RenderSurface;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.concurrent.ConcurrentLinkedQueue;

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

    /** 当前引擎实例（构造赋值、shutdown 摘除；JavelinEditor.getInstance 同款先例——相机射线等静态取用口） */
    private static JavelinEngine engineInstance;

    private final JavelinSetting setting;

    /**
     * 主窗口：start() 时经 createWindow() 工厂创建，帧循环直接驱动（见 Engine_Window_Design.md）
     */
    protected Window window;
    protected AssetManager assetManager;
    protected SubsystemManager subsystemManager;
    /** AppState 子系统引用（registerSubsystem 注册时留存：默认挂载口与子类挂载便捷入口） */
    protected AppStateSubsystem appStateSubsystem;

    // RHI 初始化序列（阶段①：从测试类搬入，工厂可覆盖）
    private Instance instance;
    private Device device;
    private FrameLoop frameLoop;
    /**
     * 渲染表面（渲染目标）——initialize 时取自窗口；尺寸=渲染目标像素
     *（运行时=窗口帧缓冲、编辑器=画布物理像素）。Camera 射线换算（screenToRay 简式）真值源。
     */
    private RenderSurface renderSurface;
    protected RenderingSystem renderingSystem;
    protected RenderPipeline renderPipeline;
    /** 后处理节点装配（组合根，createRenderPipeline 建；管线只承载链与展开） */
    private PostProcessSetup postProcessSetup;
    /** FORWARD 档忽略 SSAO 的 warn 一次性标记（applyRenderSettings 高频调用，不刷日志） */
    private boolean ssaoForwardWarned;
    /** FORWARD 档忽略 TAA 的 warn 一次性标记（同上口径） */
    private boolean taaForwardWarned;
    /** GPU ID-buffer 拾取系统（可选服务，消费方构造 PickSystem 时注册；mainLoop 帧末调度） */
    private PickSystem pickSystem;
    /** 体素化 Pass（四期 P4-17；设置入口在图形页「体素化环境光遮蔽」组） */
    private VoxelizationPass voxelizationPass;
    /** 体素 AO 求值 Pass（占用图 → AO 图，批 C；恒挂载在体素化之后） */
    private VoxelAoPass voxelAoPass;

    /** 当前场景（setScene 设置；null = 无场景，直注册模式） */
    protected Scene scene;
    private final SceneRenderBridge sceneRenderBridge = new SceneRenderBridge();

    /** 主线程待执行任务（submitTask 提交，mainLoop 帧首消费） */
    private final ConcurrentLinkedQueue<Runnable> pendingTasks = new ConcurrentLinkedQueue<>();

    private long lastFrameTime;

    /** 限帧末段精自旋余量（1.5ms）：Windows Thread.sleep 毫秒级精度，高刷限帧不兜底会帧期过冲 */
    private static final long LIMITER_SPIN_MARGIN_NANOS = 1_500_000L;

    /** 限帧锚点（上一帧限帧结束时刻；帧超时不追帧，无债务累积） */
    private long limiterAnchorNanos;

    public JavelinEngine(JavelinSetting setting) {
        this.setting = setting;
        // Layer/Tag 注册表投影（启动一次；设置窗改动经 LayerAndTagManger 增量刷新）
        LayerAndTagManger.applyLayers(setting.getLayerSettings());
        LayerAndTagManger.applyTags(setting.getTagSettings(), null);
        engineInstance = this;
    }

    /**
     * 当前引擎实例（构造时赋值、shutdown 摘除；未创建/已关停返回 null）。
     * 无状态取用口（相机射线尺寸、渲染表面查询等）经此免手动穿参。
     */
    public static JavelinEngine getInstance() {
        return engineInstance;
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
        // 启动参数覆盖引擎设置（命令行优先于设置文件）：--debug=true|false → engineDebug，
        // 在实例创建前落地（Engine_DebugTools_Plan §2.1：校验层/messenger 装载于实例创建时）
        setting.setEngineDebug(LaunchArgs.getBoolean("debug", setting.isEngineDebug()));
        window = createWindow();
        window.create();
        window.showWindow();

        assetManager = createAssetManager();
        // 默认 shader 门面注入（坏 shader 渲染回退判据，拍板 2026-10-27）
        DefaultMaterial.init(assetManager);
        subsystemManager = createSubsystemManager();
        registerSubsystem(subsystemManager);

        // RHI 初始化序列（VkSurfaceKHR 由 createDevice 内部创建并持有，上层不再重复创建）
        renderSurface = window.renderSurface();
        instance = createInstance(renderSurface);
        device = createDevice(renderSurface);
        frameLoop = createFrameLoop(renderSurface);
        renderingSystem = createRenderingSystem();
        renderPipeline = createRenderPipeline();
        // 图形设置单点下发（背景清屏色 / 后处理参数 / 链成员；FORWARD 档忽略 SSAO 并 warn 一次）：
        // 管线与后处理组合根都已就位，此处一次完成初始下发
        applyRenderSettings();
        log.info("RHI initialized: {}x{}, vsync={}, maxFps={}",
                window.width(), window.height(),
                setting.getWindowSettings().isVsync(), setting.getWindowSettings().getMaxFps());

        subsystemManager.initAll(this);

        // 引擎级默认 AppState（渲染统计 overlay）：initAll 后状态管理器已就绪，
        // 首帧 earlyUpdate 处理挂卸队列即生效
        registerDefaultAppStates(appStateSubsystem.getStateManager());


        Scene scene = loadScene();
        setScene(scene);
    }

    protected void registerSubsystem(SubsystemManager subsystemManager){
        // 注册序 = earlyUpdate 调度序：Input 先于 AppState，
        // 输入边沿翻转（帧边界发布）先完成，后继消费方读到一致快照
        subsystemManager.register(new InputSubsystem());
        appStateSubsystem = new AppStateSubsystem();
        subsystemManager.register(appStateSubsystem);
        // DebugText：渲染贡献走 SubSystem appendOverlay 帧组装级钩子，注册序无调度影响
        subsystemManager.register(new DebugDrawTextSubsystem());
    }

    /**
     * 默认挂载的引擎级 AppState（渲染统计 overlay 等）：initialize 在 initAll 之后调用，
     * 挂卸队列下一帧边界生效。子类可覆盖摘除/追加（DebugTextDemo 覆盖为空以独占行区）。
     */
    protected void registerDefaultAppStates(AppStateManager stateManager) {
        stateManager.attach(new RenderStatsAppState());
    }

    protected void onInitialize() {

    }

    private void mainLoop() {
        Time.init();
        lastFrameTime = System.nanoTime();
        limiterAnchorNanos = lastFrameTime;
        while (!window.closeRequested()) {
            window.pollEvents();
            // 异步任务帧首消费（主线程）：任意线程 submitTask 提交，帧边界串行执行，
            // 早于 Time 计时——任务耗时计入本帧 dt
            runPendingTasks();
            // 3. 计算 deltaTime
            long currentTime = System.nanoTime();
            float deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0f;
            lastFrameTime = currentTime;
            Time.update(deltaTime);
            subsystemManager.earlyUpdate(Time.getDeltaTime());
            if (scene != null) {
                scene.update(Time.getDeltaTime());
            }
            subsystemManager.lateUpdate(Time.getDeltaTime());
            update();
            frameLoop.renderFrame(this::buildFrameGraph);
            // 拾取读回调度（GPU ID-buffer 拾取，可选服务）：本帧拾取附件已提交，
            // 收割上一批读回 + 投递本帧点击/悬停像素拷贝（Engine_Picking_Design.md）
            if (pickSystem != null) {
                pickSystem.frameEnd(frameLoop.frameCounter());
            }
            // 录制收尾：本帧统计快照归档并清零，下帧 overlay 经 renderStats() 读到本帧值；
            // 跳帧时 runtime 未变，重复快照保持上一实帧显示、不闪零（§3.1 时序）
            device.resetFrameStats();
            limitFrameRate();
        }
    }

    /**
     * 异步提交任务到引擎主线程（帧边界执行：mainLoop 每帧 pollEvents 后、子系统
     * 场景更新前消费，任意线程可调）。跨线程的场景/资源读写作任务经此收敛到主线程
     * 串行化；需要结果的调用方自行组合 CompletableFuture 等待。
     *
     * @param task 任务体（null 忽略）
     */
    public void submitTask(Runnable task) {
        if (task != null) {
            pendingTasks.add(task);
        }
    }

    /** 帧首清空任务队列（异常隔离：单任务失败记日志不拖垮帧循环） */
    private void runPendingTasks() {
        Runnable task;
        while ((task = pendingTasks.poll()) != null) {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("异步任务执行出错", t);
            }
        }
    }

    /**
     * 引擎帧率限制（mainLoop 帧末，{@code setting.maxFps}，0 = 不限制）：按上一帧
     * 限帧结束时刻 + 1/maxFps 为目标等待。粗睡 + 末段 {@link Thread#onSpinWait()}
     * 精自旋——Windows Thread.sleep 毫秒级精度，高刷限帧不兜底会帧期过冲；锚点取
     * 等待结束时刻，帧超时不追帧（无债务累积、节奏自愈）。vsync(MAILBOX) 下建议
     * maxFps = 刷新率 × ~1.1：限得恰好等于刷新率会偶发「vblank 无新帧→旧帧重复
     * 显示」（FIFO 同病灶，Vulkan_Pitfalls #21）。
     */
    private void limitFrameRate() {
        int maxFps = setting.getWindowSettings().getMaxFps();
        if (maxFps <= 0) {
            limiterAnchorNanos = System.nanoTime();   // 免限帧也保鲜锚点（运行期改设置首帧即生效）
            return;
        }
        long target = limiterAnchorNanos + 1_000_000_000L / maxFps;
        long remaining = target - System.nanoTime();
        if (remaining > 0) {
            long sleepNanos = remaining - LIMITER_SPIN_MARGIN_NANOS;
            if (sleepNanos >= 1_000_000L) {   // 不足 1ms 直接进自旋（Windows 计时粒度）
                try {
                    Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    limiterAnchorNanos = System.nanoTime();
                    return;
                }
            }
            while (System.nanoTime() < target) {
                Thread.onSpinWait();
            }
        }
        limiterAnchorNanos = System.nanoTime();
    }

    /** 图声明回调：acquire 之后调用，backbuffer 为入参（RHI 24.1） */
    private RenderGraphBuilder buildFrameGraph(Texture backbuffer) {
        RenderGraphBuilder graph = RHI.beginRenderGraph();
        if (renderingSystem == null || renderPipeline == null) {
            // 无渲染系统（默认 JavelinEngine.main）：纯清屏，阶段①最小画面
            graph.addGraphicsPass("ClearOnly", pass ->
                    pass.addColorOutput(backbuffer, RHILoadOp.CLEAR,
                            clearColor()));
        } else {
            if (scene != null) {
                sceneRenderBridge.sync(scene, renderingSystem, backbuffer);
            }
            FrameDescription desc = renderingSystem.collectFrame(frameLoop.frameCounter(), backbuffer);
            // 相机级抗锯齿：链成员按本帧相机状态同步（任一相机启用 TAA 才挂 TAA + 锐化）
            syncCameraAntialiasing(desc);
            // HDR 生效（后处理链非空）时场景色写本视口 lightingRT，链尾 Tonemap 写显示终点
            boolean hdr = renderPipeline.usesHdrSceneTarget();
            // 场景色格式随 HDR 切换（材质 PSO 颜色附件须为 RGBA16F——格式变化即失效 PSO 缓存）
            renderingSystem.setHdrSceneColor(hdr);
            for (RenderView view : desc.views()) {
                renderPipeline.buildGraph(graph, new PipelineContext(
                        renderingSystem, view, desc, backbuffer,
                        renderingSystem.depthFor(view.name()),
                        hdr ? renderingSystem.lightingRtFor(view.name()) : null));
            }
            if (desc.views().isEmpty()) {
                graph.addGraphicsPass("ClearOnly", pass ->
                        pass.addColorOutput(backbuffer, RHILoadOp.CLEAR,
                                clearColor()));
            }
        }
        // 帧组装级 overlay 钩子统一遍历（DebugText 等首客户）：总在视口合成之后、
        // 写最终 backbuffer、无相机也出字（Engine_DebugText_Design.md §3.1）
        subsystemManager.appendOverlays(graph, backbuffer, frameLoop.frameCounter());
        return graph;
    }

    /** 设置的默认背景颜色 → RHI 清屏值（三处清屏统一来源：无渲染系统/无相机兜底/管线注入） */
    private RHIClearColor clearColor() {
        Color bg = setting.getGraphSettings().getBackgroundColor();
        return new RHIClearColor(bg.r, bg.g, bg.b, bg.a);
    }

    /**
     * 图形设置单点下发：把 {@code setting.graphSettings} 的当前值推给运行中的渲染链——后处理
     * 参数、背景清屏色双 sink、效果链成员按开关标志增删（FORWARD 档忽略 SSAO 并 warn 一次）。
     *
     * <p>抗锯齿（TAA + 配套锐化）是相机级设置，不在本方法内：链成员由
     * {@link #syncCameraAntialiasing} 每帧按相机状态同步。</p>
     *
     * <p>启动期在 {@link #initialize()} 调一次完成初始下发；运行期改过设置字段（设置面板的
     * 反射写 / 工具菜单开关）后调用本方法即生效。阴影不在此列——渲染系统持 live 实例、每帧
     * 打包进 UBO，改即生效。</p>
     */
    public void applyRenderSettings() {
        GraphSetting graph = setting.getGraphSettings();
        PostProcessSettings pps = graph.getPostProcessSettings();
        if (postProcessSetup != null) {
            postProcessSetup.setBloomParams(pps.getBloomThreshold(), pps.getBloomKnee(),
                    pps.getBloomIntensity());
            postProcessSetup.setTonemapKnee(pps.getTonemapKnee());
            SsaoSettings ssao = graph.getSsaoSettings();
            postProcessSetup.setSsaoParams(ssao.getRadius(), ssao.getBias(), ssao.getIntensity());
        }
        pushClearColor();
        if (postProcessSetup == null || renderPipeline == null) {
            return;
        }
        boolean forward = renderPipeline.profile() == PipelineProfile.FORWARD;
        applyPostProcessNode(PostProcessSetup.TONEMAP, pps.isTonemapEnabled());
        applyPostProcessNode(PostProcessSetup.BLOOM, pps.isBloomEnabled());
        boolean ssaoEnabled = graph.getSsaoSettings().isEnabled();
        if (ssaoEnabled && forward && !ssaoForwardWarned) {
            log.warn("SSAO 需要 G-Buffer，FORWARD 档不支持——忽略本次启用（HYBRID 档生效）");
            ssaoForwardWarned = true;
        }
        applyPostProcessNode(PostProcessSetup.SSAO, ssaoEnabled && !forward);
        // 抗锯齿（TAA + 配套锐化）是相机级设置：链成员按本帧相机状态每帧同步，
        // 见 buildFrameGraph → syncCameraAntialiasing（此处不处理）
    }

    /**
     * 相机级抗锯齿的链成员同步（每帧 collectFrame 之后调）：任一相机启用 TAA → 挂 TAA 与
     * 配套锐化节点，否则摘掉（摘掉后不再创建历史 RT、不再抖动）。
     *
     * <p>逐视口的实际行为（关=直拷）由 TaaPass / SharpenPass 按相机设置判定，本方法只决定
     * 节点在不在链上（链成员影响 HDR 切换与惰性创建）。</p>
     */
    private void syncCameraAntialiasing(FrameDescription desc) {
        if (postProcessSetup == null || renderPipeline == null || renderingSystem == null) {
            return;
        }
        boolean anyTaa = false;
        for (RenderView view : desc.views()) {
            if (renderingSystem.taaViewEnabled(view.name())) {
                anyTaa = true;
                break;
            }
        }
        boolean forward = renderPipeline.profile() == PipelineProfile.FORWARD;
        if (anyTaa && forward && !taaForwardWarned) {
            log.warn("TAA 需要 G-Buffer 世界位置，FORWARD 档不支持——忽略相机抗锯齿（HYBRID 档生效）");
            taaForwardWarned = true;
        }
        boolean taaActive = anyTaa && !forward;
        applyPostProcessNode(PostProcessSetup.TAA, taaActive);
        // 锐化是 TAA 的配套（Unity 口径：抗锯齿关闭即不锐化），与 TAA 同生同灭
        applyPostProcessNode(PostProcessSetup.SHARPEN, taaActive);
        // 铁律：TAA 不在链上必须停抖（否则白白亚像素抖动、静态画面也会变糊）
        renderingSystem.setTaaJitterEnabled(taaActive);
    }

    /**
     * 链成员按目标状态增删：状态一致即跳过——{@code PostProcessSetup.setEnabled} 会惰性创建
     * 节点（关掉的节点也创建 = 白装载着色器），故先查链（{@code isEnabled} 不触发创建）。
     */
    private void applyPostProcessNode(String id, boolean enabled) {
        if (postProcessSetup.isEnabled(renderPipeline.postProcess(), id) == enabled) {
            return;
        }
        postProcessSetup.setEnabled(renderPipeline.postProcess(), id, enabled);
    }

    /** 背景清屏色下发（管线清屏 sink + 后处理组合根；两者缺一即跳过对应者） */
    private void pushClearColor() {
        RHIClearColor clear = clearColor();
        if (renderPipeline != null) {
            renderPipeline.setClearColor(clear);
        }
        if (postProcessSetup != null) {
            postProcessSetup.setClearColor(clear);
        }
    }

    /** shutdown 逆序：子系统 → frameLoop（含 waitIdle）→ 渲染系统 → device → instance → window */
    private void shutdown() {
        if (subsystemManager != null) {
            subsystemManager.shutdownAll();
        }
        if (frameLoop != null) {
            frameLoop.close();
        }
        if (renderingSystem != null) {
            renderingSystem.close();
        }
        if (pickSystem != null) {
            pickSystem.close();   // waitIdle 之后（frameLoop.close 已做）：摘 Pass + PSO + 读回环
        }
        // 子系统 GPU 资源统一销毁（shutdownAll 早于 waitIdle 不能碰 GPU，
        // closeGpu 钩子在此安全区统一遍历——RenderingSystem.close() 同层）
        if (subsystemManager != null) {
            subsystemManager.closeGpuAll();
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
        // L1 物理查询门面解绑（场景随引擎关停不再可查）
        Physics.setScene(null);
        // 引擎静态实例摘除（关停后 getInstance() 返回 null）
        if (engineInstance == this) {
            engineInstance = null;
        }
        Time.reset();
    }

    public JavelinSetting setting() {
        return setting;
    }

    /** 注册拾取系统（PickSystem 构造时自调）：mainLoop 帧末调度 + shutdown 关闭 */
    public void setPickSystem(PickSystem pickSystem) {
        this.pickSystem = pickSystem;
    }


    /**
     * 窗口工厂：子类可覆盖以替换窗口实现
     */
    protected Window createWindow() {
        WindowSetting windowSetting = setting.getWindowSettings();
        return new GLFWWindow(windowSetting.getWidth(), windowSetting.getHeight(),
                windowSetting.getTitle());
    }

    protected AssetManager createAssetManager() {
        return new AssetLibManager(new File("./Library"));
    }

    protected SubsystemManager createSubsystemManager() {
        return new SubsystemManager();
    }

    protected RHI.RHIInstanceConfig createInstanceConfig() {
        // 校验层/messenger 开关（Engine_DebugTools_Plan §2.1）：启动期消费 setting.engineDebug，
        // 运行期不可切（实例级装载无热切时机）
        return new RHI.RHIInstanceConfig(setting.getWindowSettings().getTitle(), 1,
                setting.isEngineDebug());
    }

    protected RHIDevicePrefs createDevicePrefs() {
        return RHIDevicePrefs.defaults();
    }

    /** vsync → MAILBOX（2026-10-06 拍板：FIFO+DWM 窗口合成「踩线/错过」双稳见
     *  Vulkan_Pitfalls #21，MAILBOX 替换语义免疫；生产端不限速由 maxFps 限帧兜底；
     *  表面不支持时 VulkanSwapChain 自动回退 FIFO）；关闭 vsync → IMMEDIATE
     *  （Engine_Implementation_Plan 开放项落定） */
    protected SwapChainDesc createSwapChainDesc() {
        boolean vsync = setting.getWindowSettings().isVsync();
        SwapChainDesc.PresentMode presentMode = vsync
                ? SwapChainDesc.PresentMode.MAILBOX
                : SwapChainDesc.PresentMode.IMMEDIATE;
        return new SwapChainDesc(window.width(), window.height(),
                presentMode, SwapChainDesc.ColorSpace.SRGB, 0, vsync);
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
        return new RenderingSystem(device(), frameLoop(), 2);
    }

    protected RenderPipeline createRenderPipeline() {
        if (renderingSystem == null) {
            return null;
        }
        // 启动期按设置选实现类（2026-11-13 拍板：管线不再承载运行期切档——编辑器固定混合，
        // 前向管线只留给打包运行时与 demo/测试）
        RenderPipeline pipeline = setting.getGraphSettings().getRenderPipeline() == PipelineProfile.FORWARD
                ? new ForwardRenderPipeline(device(), assetManager)
                : new DefaultHybridRenderPipeline(device(), assetManager);
        // 后处理节点装配（组合根持有；管线只承载链与展开——2026-11-14 解耦）
        postProcessSetup = new PostProcessSetup(device(), getAssetManager());
        // 阴影参数（Engine_Shadow_Quality_Design §3.2）：渲染系统持 live 实例，每帧打包进 UBO
        renderingSystem.setShadowSettings(setting.getGraphSettings().getShadowSettings());
        // 体素化 AO（Engine_VoxelAO_Design §4.1）：插在 ShadowPass 之后、几何 Pass 之前——
        // 占用图 → AO 图 → 模糊图必须在同一帧光照消费之前算完（挂尾部则光照读到的是
        // 上帧图，重建首帧会采到全零 AO = 环境项整屏发黑）；开关关闭时不进图
        renderingSystem.setVoxelAoSettings(setting.getGraphSettings().getVoxelAoSettings());
        voxelizationPass = new VoxelizationPass(device(), renderingSystem, assetManager);
        pipeline.addPass(voxelizationPass, "Shadow");
        // 体素 AO 求值（批 C）：必须排在体素化之后（占用图 → AO 图 → 模糊图），
        // 依赖由占用图的「storage 写 / 采样读」声明自动推导
        voxelAoPass = new VoxelAoPass(device(), renderingSystem, voxelizationPass, assetManager);
        pipeline.addPass(voxelAoPass, voxelizationPass.name());
        return pipeline;
    }

    protected Scene loadScene(){
        String sceneGuid = setting.getSceneSetting().getDefaultSceneGuid();
        return assetManager.loadGuid(sceneGuid, GameScene.class);
    }

    /**
     * 设置当前场景：旧场景注册的渲染侧包装全部注销，新场景下一帧起经
     * SceneRenderBridge pull 进渲染系统。重复设同一引用为幂等空操作。
     * 同时绑定 L1 物理查询门面（{@code Physics.raycast} 查当前场景，
     * Engine_Collision_Design.md §10）。
     */
    public void setScene(Scene scene) {
        if (this.scene == scene) {
            return;
        }
        if (this.scene != null && renderingSystem != null) {
            sceneRenderBridge.clear(renderingSystem);
        }
        this.scene = scene;
        Physics.setScene(scene);
        if (renderingSystem != null) {
            // 切场景 = 历史语义断裂（旧场景的历史色对新场景是错的），TAA 从当前帧重新累积
            renderingSystem.invalidateTaaAll();
        }
    }

    /** 当前场景（start() 调用前/未设置时为 null） */
    public Scene scene() {
        return scene;
    }

    /**
     * 从鼠标当前位置发射世界射线（坐标取 {@link Input} 鼠标像素坐标，尺寸自取渲染表面
     * ——同 {@link Camera#screenToRay(float, float, Ray)}，左上原点）。等价 Unity
     * {@code Camera.main.ScreenPointToRay(Input.mousePosition)} 组合的本引擎形态。
     *
     * <p>坐标空间与渲染表面同像素（运行时=窗口帧缓冲、编辑器=画布物理像素——
     * 编辑器画布逻辑坐标在 dpi≠1 时须先换算，编辑器内部路径继续用显式尺寸重载）。</p>
     *
     * @param camera 目标相机（通常 {@code scene().getMainCamera()}）
     * @param out    输出射线（复用实例零分配；direction 归一化——与 maxDistance/distance
     *               的世界距离口径一致）
     * @return out
     * @throws IllegalStateException 相机 null（场景无主相机）/ 引擎未启动（无渲染表面尺寸）
     */
    public Ray mouseRay(Camera camera, Ray out) {
        if (camera == null) {
            throw new IllegalStateException("mouseRay 相机为 null：场景无主相机（Scene.getMainCamera）或未传入");
        }
        return camera.screenToRay(Input.mouseX(), Input.mouseY(), out);
    }

    /**
     * 主窗口；start() 调用前为 null
     */
    public Window window() {
        return window;
    }

    /**
     * 渲染表面（渲染目标）——尺寸=渲染目标像素（运行时=窗口帧缓冲、编辑器=画布物理像素）；
     * {@code Camera.screenToRay(x, y, out)} 简式的尺寸真值源。start() 前为 null。
     */
    public RenderSurface renderSurface() {
        return renderSurface;
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

    /** 后处理节点装配（组合根，持有节点实例 + 按 id 增删链；编辑器工具菜单开关经此） */
    public PostProcessSetup postProcessSetup() {
        return postProcessSetup;
    }

    public AssetManager getAssetManager() {
        return assetManager;
    }

    /**
     * 请求重建体素 AO（编辑器「工具｜重建体素 AO」命令入口）：只立标志，实际
     * 重新收集/派发在下一次图构建时完成（见 {@code VoxelizationPass.ensureReady}）。
     *
     * <p>开关关闭时是合法的空操作（占位资源照常采样，无遮蔽）。可从任意线程调用——
     * 标志位 volatile，标志本身无跨帧副作用。</p>
     */
    public void requestVoxelRebuild() {
        if (voxelizationPass == null || voxelAoPass == null) {
            log.warn("体素 AO 重建请求被忽略：渲染管线未建（未完成启动）");
            return;
        }
        if (setting.getGraphSettings().getVoxelAoSettings() == null
                || !setting.getGraphSettings().getVoxelAoSettings().isEnabled()) {
            log.info("体素 AO 重建请求被忽略：总开关关闭（开启后首帧自动重建）");
            return;
        }
        voxelizationPass.requestRebuild();
        voxelAoPass.requestRebuild();
        log.info("体素 AO 重建已排队（下一次图构建执行）");
    }

    protected void update() {

    }

    public static void main(String[] args) {
        LaunchArgs.parse(args);
        JavelinSetting setting = new JavelinSetting();
        JavelinEngine javelinEngine = new JavelinEngine(setting);
        javelinEngine.start();
    }

}
