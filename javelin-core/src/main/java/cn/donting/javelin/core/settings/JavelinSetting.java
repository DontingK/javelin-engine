package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.math.Color;
import cn.donting.javelin.core.render.pipeline.PipelineProfile;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * Javelin 引擎设置（持久化单点，落 {@code Game/javelinSettings.settings}）。
 *
 * <p>三域：窗口（{@link WindowSetting}）/ 图形（{@link GraphSetting}，项目设置「图形」页根）/
 * 引擎调试（{@code engineDebug}）。旧档把各域字段平铺在根上，read 时按「缺嵌套键即旧档」
 * 判据一次性迁入；新文件只写三域对象。</p>
 */
@Reflective
public class JavelinSetting implements Serializable {

    /**
     * 图形设置（2026-11-14 从本类根字段迁入 {@link GraphSetting}）：管线档位 / 背景清屏色 /
     * 后处理 / 环境光遮蔽 / 阴影。项目设置窗口「图形」页即以本对象为页根。
     */
    private GraphSetting graphSettings = new GraphSetting();

    /**
     * 窗口设置（尺寸 / 标题 / vsync / 帧率上限，2026-11-14 从本类根字段迁入
     * {@link WindowSetting}）；引擎启动期消费，编辑器启动链覆盖 vsync 与 maxFps。
     *
     * <p>两页 {@code @Setting} 字段的声明序即项目设置窗导航序（图形在前）。</p>
     */
    private WindowSetting windowSettings = new WindowSetting();

    /**
     * 引擎调试开关（Vulkan 校验层 + DebugUtilsMessenger 回调，Engine_DebugTools_Plan §2）：
     * 默认 true——开发者默认要听见校验层。启动期消费、运行期不可切；为 true 且本机无
     * 校验层时启动即报错（fail-fast，红线 15），关掉本开关可免校验层启动。
     */
    private boolean engineDebug = true;

    public WindowSetting getWindowSettings() {
        return windowSettings;
    }

    public void setWindowSettings(WindowSetting windowSettings) {
        if (windowSettings == null) {
            throw new IllegalArgumentException("windowSettings must not be null");
        }
        this.windowSettings = windowSettings;
    }

    public GraphSetting getGraphSettings() {
        return graphSettings;
    }

    public void setGraphSettings(GraphSetting graphSettings) {
        if (graphSettings == null) {
            throw new IllegalArgumentException("graphSettings must not be null");
        }
        this.graphSettings = graphSettings;
    }

    public boolean isEngineDebug() {
        return engineDebug;
    }

    public void setEngineDebug(boolean engineDebug) {
        this.engineDebug = engineDebug;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("windowSettings", windowSettings);
        output.write("graphSettings", graphSettings);
        output.write("engineDebug", engineDebug);
    }

    @Override
    public void read(InputCapsule input) {
        // 窗口（2026-11-14 迁入 WindowSetting）：缺 windowSettings 键即旧档 → 迁根键
        WindowSetting window = input.readSerializable("windowSettings", WindowSetting.class);
        if (window != null) {
            windowSettings = window;
        } else {
            windowSettings.setWidth(input.readInt("width", 1080));
            windowSettings.setHeight(input.readInt("height", 720));
            windowSettings.setTitle(input.readString("title", "Javelin Engine 5"));
            windowSettings.setVsync(input.readBoolean("vsync", true));
            windowSettings.setMaxFps(input.readInt("maxFps", 144));
        }
        // 图形（2026-11-14 迁入 GraphSetting）：缺 graphSettings 键即旧档 → 迁根上的四项
        GraphSetting graph = input.readSerializable("graphSettings", GraphSetting.class);
        if (graph != null) {
            graphSettings = graph;
        } else {
            graphSettings.setRenderPipeline(input.readEnum("renderPipeline", PipelineProfile.class,
                    graphSettings.getRenderPipeline()));
            Color bg = input.readSerializable("backgroundColor", Color.class);
            if (bg != null) {
                graphSettings.setBackgroundColor(bg);
            }
            PostProcessSettings pps = input.readSerializable("postProcessSettings",
                    PostProcessSettings.class);
            if (pps != null) {
                graphSettings.setPostProcessSettings(pps);
                // SSAO 独立成组后的补迁：旧文件把开关与参数挂在后处理下
                graphSettings.adoptLegacySsao();
            }
            ShadowSettings shadow = input.readSerializable("shadowSettings", ShadowSettings.class);
            if (shadow != null) {
                graphSettings.setShadowSettings(shadow);
            }
        }
        engineDebug = input.readBoolean("engineDebug", true);
        // 最早形态的三效果开关（2026-11-14 从本类顶层收拢进各设置对象）：旧文件把它们写在
        // 根上，"true" 表示当时开启；新文件不再写这些键（post-move 文件读到 false 无副作用），
        // 故只迁 true，实现「旧工程开关不丢」。
        if (input.readBoolean("postProcessing", false)) {
            graphSettings.getPostProcessSettings().setTonemapEnabled(true);
        }
        if (input.readBoolean("bloom", false)) {
            graphSettings.getPostProcessSettings().setBloomEnabled(true);
        }
        if (input.readBoolean("ssao", false)) {
            graphSettings.getSsaoSettings().setEnabled(true);
        }
    }
}
