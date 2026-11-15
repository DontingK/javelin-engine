package cn.donting.javelin.core.settings;

import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.scene.GameScene;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 场景设置：打包运行时的默认启动场景 guid（字段存 guid 串，设置页按资产选择器渲染）。
 *
 * <p>由 {@link JavelinSetting#getSceneSetting()} 持有；引擎 {@code loadScene()} 启动期消费
 * 该 guid 经 {@code AssetManager.loadGuid} 装载启动场景。</p>
 **/
@Reflective
@Setting(label = "projectSettings.category.scene", editorStartup = false)
public class SceneSetting implements Serializable {

    /** 默认启动场景 guid（资产 guid；null = 不装载启动场景） */
    @InsField(label = "projectSettings.defaultScene")
    @SettingAsset(GameScene.class)
    @SettingHint("projectSettings.defaultSceneHint")
    private String defaultSceneGuid;

    public String getDefaultSceneGuid() {
        return defaultSceneGuid;
    }

    public void setDefaultSceneGuid(String defaultSceneGuid) {
        this.defaultSceneGuid = defaultSceneGuid;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("defaultSceneGuid", defaultSceneGuid);
    }

    @Override
    public void read(InputCapsule input) {
        this.defaultSceneGuid = input.readString("defaultSceneGuid", null);
    }
}
