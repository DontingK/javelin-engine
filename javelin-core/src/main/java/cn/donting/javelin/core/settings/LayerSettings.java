package cn.donting.javelin.core.settings;


import cn.donting.javelin.ast.annotation.Reflective;
import cn.donting.javelin.core.inspector.InsField;
import cn.donting.javelin.core.scene.Layer;
import cn.donting.javelin.core.serialize.InputCapsule;
import cn.donting.javelin.core.serialize.OutputCapsule;
import cn.donting.javelin.core.serialize.Serializable;

/**
 * 图层设置（项目设置「图层」页根）：索引 0~30 的层名，空串=未命名槽位。
 *
 * <p>由 {@link JavelinSetting#getLayerSettings()} 持有；设置窗改动经
 * {@code LayerAndTagManger.applyLayers} 即时投影到 {@link Layer} 注册表（索引即层号，
 * 只改名字不换实例）。</p>
 **/
@Reflective
@Setting(label = "projectSettings.category.layers")
public class LayerSettings implements Serializable {

    /** 层名（索引=层号；长度即槽位数，改动长度会移位层号，故面板按定长编辑） */
    @InsField(label = "projectSettings.layers")
    @SettingHint("projectSettings.layersHint")
    private String[] layerNames = new String[Layer.COUNT];

    public String[] getLayerNames() {
        return layerNames;
    }

    public void setLayerNames(String[] layerNames) {
        if (layerNames == null) {
            throw new IllegalArgumentException("layerNames must not be null");
        }
        this.layerNames = layerNames;
    }

    @Override
    public void write(OutputCapsule output) {
        output.write("layerNames", layerNames);
    }

    @Override
    public void read(InputCapsule input) {
        String[] names = input.readStringArray("layerNames");
        if (names != null) {
            layerNames = names;
        }
    }
}
