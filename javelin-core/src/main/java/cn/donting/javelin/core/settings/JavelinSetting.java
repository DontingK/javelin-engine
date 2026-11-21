package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * Javelin 引擎设置（唯一设置文件，落 {@code Game/javelinSettings.settings}）。
 *
 * <p>六域：图形（{@link GraphSetting}）/ 窗口（{@link WindowSetting}）/ 图层（{@link LayerSettings}）/
 * 标签（{@link TagSettings}）/ 场景（{@link SceneSetting}）/ 引擎调试开关。声明序即项目设置窗导航序；
 * 各域一律嵌套对象，不再有平铺根字段形态。</p>
 */
@Reflective
public class JavelinSetting implements Serializable {

    /**
     * 图形设置：管线档位 / 背景清屏色 / 后处理 / 环境光遮蔽 / 阴影。
     * 项目设置窗口「图形」页即以本对象为页根。
     */
    private GraphSetting graphSettings = new GraphSetting();

    /**
     * 窗口设置（尺寸 / 标题 / vsync / 帧率上限）；引擎启动期消费，编辑器启动链覆盖 vsync 与
     * maxFps。各页 {@code @Setting} 字段的声明序即项目设置窗导航序（图形在前）。
     */
    private WindowSetting windowSettings = new WindowSetting();

    /**
     * 图层设置：层名清单 + {@code Layer} 注册表投影，改动重启编辑器生效。
     */
    private LayerSettings layerSettings = new LayerSettings();

    /**
     * 标签设置：标签名清单 + {@code Tag} 注册表投影，改动重启编辑器生效。
     */
    private TagSettings tagSettings = new TagSettings();

    /**
     * 场景设置：打包运行时默认启动场景 guid（引擎 loadScene 启动期消费）。
     */
    private SceneSetting sceneSetting = new SceneSetting();

    /**
     * 引擎调试开关（Vulkan 校验层 + DebugUtilsMessenger 回调，Engine_DebugTools_Plan §2）：
     * 默认 false——校验层每次 API 调用都做检查（实测帧率掉 5~20%），日常跑图不需要；
     * 排障/验收时用命令行 {@code --debug=true} 打开（{@link cn.donting.javelin.core.LaunchArgs}
     * 覆盖设置文件，2026-11-21 拍板「debug 需要参数开启」）。启动期消费、运行期不可切；
     * 为 true 且本机无校验层时启动即报错（fail-fast，红线 15）。
     */
    private boolean engineDebug = false;

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

    public LayerSettings getLayerSettings() {
        return layerSettings;
    }

    public void setLayerSettings(LayerSettings layerSettings) {
        if (layerSettings == null) {
            throw new IllegalArgumentException("layerSettings must not be null");
        }
        this.layerSettings = layerSettings;
    }

    public TagSettings getTagSettings() {
        return tagSettings;
    }

    public void setTagSettings(TagSettings tagSettings) {
        if (tagSettings == null) {
            throw new IllegalArgumentException("tagSettings must not be null");
        }
        this.tagSettings = tagSettings;
    }

    public SceneSetting getSceneSetting() {
        return sceneSetting;
    }

    public void setSceneSetting(SceneSetting sceneSetting) {
        if (sceneSetting == null) {
            throw new IllegalArgumentException("sceneSetting must not be null");
        }
        this.sceneSetting = sceneSetting;
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
        output.write("layerSettings", layerSettings);
        output.write("tagSettings", tagSettings);
        output.write("sceneSetting", sceneSetting);
        output.write("engineDebug", engineDebug);
    }

    @Override
    public void read(InputCapsule input) {
        // 各域一律嵌套对象：缺键即字段声明初值（不做平铺根键的兼容读取）
        WindowSetting window = input.readSerializable("windowSettings", WindowSetting.class);
        if (window != null) {
            windowSettings = window;
        }
        GraphSetting graph = input.readSerializable("graphSettings", GraphSetting.class);
        if (graph != null) {
            graphSettings = graph;
        }
        LayerSettings layers = input.readSerializable("layerSettings", LayerSettings.class);
        if (layers != null) {
            layerSettings = layers;
        }
        TagSettings tags = input.readSerializable("tagSettings", TagSettings.class);
        if (tags != null) {
            tagSettings = tags;
        }
        SceneSetting scene = input.readSerializable("sceneSetting", SceneSetting.class);
        if (scene != null) {
            sceneSetting = scene;
        }
        engineDebug = input.readBoolean("engineDebug", true);
    }
}
