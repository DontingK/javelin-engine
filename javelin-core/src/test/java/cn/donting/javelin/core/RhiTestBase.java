package cn.donting.javelin.core;

import cn.donting.javelin.core.render.FrameLoop;
import cn.donting.javelin.core.window.GLFWWindow;
import cn.donting.javelin.core.window.Window;
import cn.donting.javelin.rhi.Device;
import cn.donting.javelin.rhi.Instance;
import cn.donting.javelin.rhi.PassDef;
import cn.donting.javelin.rhi.RHI;
import cn.donting.javelin.rhi.RHIDevicePrefs;
import cn.donting.javelin.rhi.RenderGraphBuilder;
import cn.donting.javelin.rhi.Texture;
import cn.donting.javelin.rhi.data.RHIClearColor;
import cn.donting.javelin.rhi.data.RHILoadOp;
import cn.donting.javelin.rhi.desc.SwapChainDesc;
import cn.donting.javelin.rhi.render.RenderSurface;
import cn.donting.javelin.tool.shaderc.ShaderStage;
import cn.donting.javelin.tool.shaderc.ShaderCompiler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * RHI 渲染验证测试基类：封装窗口/RHI 初始化、帧循环、逆序销毁的通用骨架。
 *
 * <p>子类只需声明身份与差异点：
 * <ul>
 *   <li>{@link #testName()} / {@link #clearColor()} —— 应用名（实例配置、窗口标题、日志）与清屏色；</li>
 *   <li>{@link #onInit()} —— FrameLoop 就绪后创建测试自建资源（PSO、顶点缓冲等）；</li>
 *   <li>{@link #onPass(PassDef)} —— addColorOutput 之后填充 viewport/scissor/pipeline/绘制回调；</li>
 *   <li>{@link #onDispose()} —— 销毁测试自建资源，在 frameLoop.close() 之后、device.dispose() 之前调用。</li>
 * </ul>
 *
 * <p>运行方式不变：{@code gradlew :javelin-core:runTest -DmainClass=cn.donting.javelin.core.XxxTest}
 */
public abstract class RhiTestBase {

    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected Window window;
    protected Instance instance;
    protected Device device;
    protected FrameLoop frameLoop;

    /** 应用名：用于实例配置、窗口标题、pass 名与日志 */
    protected abstract String testName();

    /** 每帧清屏色 */
    protected abstract RHIClearColor clearColor();

    /** FrameLoop 就绪后调用，创建测试自建资源（PSO/缓冲等） */
    protected void onInit() {
    }

    /** addColorOutput 之后调用，填充 viewport/scissor/pipeline/绘制回调 */
    protected void onPass(PassDef pass) {
    }

    /** 销毁测试自建资源；在 frameLoop.close() 之后、device.dispose() 之前调用 */
    protected void onDispose() {
    }

    public final void run() {
        window = new GLFWWindow(1280, 720, "Javelin RHI - " + testName());
        try {
            window.create();
            window.showWindow();

            RenderSurface surface = window.renderSurface();
            instance = RHI.createInstance(
                    new RHI.RHIInstanceConfig(testName(), 1, true),
                    surface.platform());
            device = instance.createDevice(RHIDevicePrefs.defaults(), surface);

            frameLoop = new FrameLoop(device, surface,
                    SwapChainDesc.defaults(window.width(), window.height()), 2);

            onInit();

            while (!window.closeRequested()) {
                window.pollEvents();
                frameLoop.renderFrame(this::buildGraph);
            }
        } finally {
            if (frameLoop != null) {
                frameLoop.close();
            }
            onDispose();
            if (device != null) {
                device.dispose();
            }
            if (instance != null) {
                instance.dispose();
            }
            window.close();
        }
        log.info("{} finished, frames rendered: {}",
                testName(), frameLoop != null ? frameLoop.frameCounter() : 0);
    }

    /** 编译内嵌 GLSL 源码为 SPIR-V（每次调用创建一个临时 ShaderCompiler） */
    protected byte[] compileShader(String source,
                                   ShaderStage stage,
                                   String fileName) {
        try (ShaderCompiler compiler = new ShaderCompiler()) {
            return compiler.compile(source, stage, fileName);
        }
    }

    /**
     * 编译 test resources 下的着色器资源为 SPIR-V（着色器源码不进 core 主代码，
     * 统一放 {@code src/test/resources/shaders/}，经 {@link TestShaders} 读取 + 编译）。
     *
     * @param resourcePath classpath 路径，如 {@code "shaders/builtin_quad.vert"}
     */
    protected byte[] compileShaderResource(String resourcePath,
                                           ShaderStage stage) {
        return TestShaders.compileResource(resourcePath, stage);
    }

    private RenderGraphBuilder buildGraph(Texture backbuffer) {
        RenderGraphBuilder graph = RHI.beginRenderGraph();
        buildPasses(graph, backbuffer);
        return graph;
    }

    /**
     * 组装本帧的渲染图。默认实现：单个全屏 CLEAR pass（内容在 {@link #onPass} 填充）。
     * 多 Pass 场景（如分视口各自清屏）由子类重写本方法。
     */
    protected void buildPasses(RenderGraphBuilder graph, Texture backbuffer) {
        graph.addGraphicsPass(testName(), pass -> {
            pass.addColorOutput(backbuffer, RHILoadOp.CLEAR, clearColor());
            onPass(pass);
        });
    }
}
